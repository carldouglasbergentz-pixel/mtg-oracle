package mtgoracle.forge

import forge.card.mana.ManaAtom
import forge.game.GameView
import forge.game.card.CardView
import forge.game.player.PlayerView
import forge.game.zone.ZoneType
import mtgoracle.core.model.BoardRef
import mtgoracle.core.model.BoardState
import mtgoracle.core.model.StackKind
import mtgoracle.core.model.TrailEntry
import mtgoracle.core.model.CardState
import mtgoracle.core.model.CombatLine
import mtgoracle.core.model.PlayerState
import mtgoracle.core.model.StackEntry

/**
 * GameView (live, mutable, shared with the engine thread) -> BoardState
 * (immutable, ours). The only place the UI's picture of the game is made —
 * and so the one place hidden information is kept out of it: every card
 * passes [mayView] (Forge's own per-viewer rule), and one that fails becomes a
 * back with a stand-in id. The UI cannot leak what it never receives.
 *
 * Forge mutates its views on the game thread while this reads them on the
 * EDT — Forge's own desktop GUI lives with the same race. The seat snapshots
 * again whenever a prompt appears, when the engine is parked and still. Collections are
 * copied through `threadSafeIterable`; a snapshot that still trips over a
 * concurrent change throws, and the caller simply takes the next one.
 */
internal class Snapshots(private val cardViews: MutableMap<Int, CardView>, private val playerViews: MutableMap<Int, PlayerView>) {

    fun build(
        game: GameView,
        seatPlayerIds: Set<Int>,
        mayView: (CardView) -> Boolean,
        /** For a face-down card: may this seat see what it really is (Forge's canFaceDownBeShownToAny)? */
        mayPeek: (CardView) -> Boolean,
        recentLog: List<String>,
        gameOver: Boolean,
        trail: List<TrailEntry> = emptyList(),
        decisionSeq: Long = 0,
    ): BoardState {
        // Cards this seat may not see get stand-in ids: nothing ties a back to the card behind it.
        var nextStand = 0
        val stand = { --nextStand }
        val players = game.players.threadSafeIterable().map { player(it, it.id in seatPlayerIds, mayView, mayPeek, stand) }
        val stack = game.stack?.threadSafeIterable()?.map { item ->
            val source = item.sourceCard
            val seen = source == null || mayView(source) || abilityNamesItsSource(item.isAbility || item.isTrigger, source.isFaceDown)
            val unseen = if (source?.isFaceDown == true) "face-down" else "hidden"
            // Targets by name only when this seat may see them; "face-down" only for one that is.
            val cardTargets = item.targetCards?.threadSafeIterable()?.toList().orEmpty()
            val playerTargets = item.targetPlayers?.threadSafeIterable()?.toList().orEmpty()
            // An ability reads as its own text ("{T}, Pay 1 life, Sacrifice Polluted Delta: Search…"); the
            // stack's description is the resolution sentence. A spell's description names its targets: keep it.
            val live = game.game?.stack?.firstOrNull { it.id == item.id }?.spellAbility
            val text = when {
                !seen -> "a $unseen spell"
                live != null && !live.isSpell -> live.toString()
                // "Lightning Bolt - Lightning Bolt deals 3 damage to Savannah Lions.": the frame already names it.
                else -> FORGE_IDS.replace(item.text.orEmpty(), "").removePrefix("${source?.currentState?.name} - ")
            }
            StackEntry(
                id = item.id,
                text = FORGE_IDS.replace(text, ""),
                sourceName = if (seen) source?.currentState?.name ?: "?" else unseen,
                controllerName = item.activatingPlayer?.name ?: "?",
                sourceCardId = source?.id?.takeIf { seen },
                imageKey = source?.takeIf { seen }?.currentState?.imageKey,
                controllerId = item.activatingPlayer?.id ?: -1,
                kind = when { item.isTrigger -> StackKind.TRIGGERED; item.isAbility -> StackKind.ACTIVATED; else -> StackKind.SPELL },
                targetNames = cardTargets.map {
                    when {
                        it.isFaceDown -> "a face-down card"
                        mayView(it) -> it.currentState.name
                        else -> "a hidden card"
                    }
                } + playerTargets.map { it.name },
                targets = cardTargets.filter { mayView(it) }.map { BoardRef.Card(it.id) } + playerTargets.map { BoardRef.Player(it.id) },
                source = source?.takeIf { seen && (!it.isFaceDown || mayPeek(it)) }?.let { card(it) },
            )
        }.orEmpty()
        // The live Combat, not the view: Forge refreshes its CombatView only between steps,
        // so blocks being declared wouldn't show. Read when the engine is parked on a prompt.
        val live = game.game?.phaseHandler?.combat
        val combat = if (live != null) {
            live.attackers.map { a -> CombatLine(a.id, live.getDefenderByAttacker(a)?.toString() ?: "?", live.getBlockers(a).map { it.id }) }
        } else game.combat?.let { view ->
            view.attackers.map { attacker ->
                CombatLine(attacker.id, view.getDefender(attacker)?.toString() ?: "?", view.getBlockers(attacker)?.map { it.id }.orEmpty())
            }
        }.orEmpty()
        val over = gameOver || game.isGameOver
        return BoardState(
            turn = game.turn,
            phase = game.phase?.nameForUi ?: "-",
            phaseKey = game.phase?.name ?: "-",
            activePlayerId = game.playerTurn?.id ?: -1,
            activePlayerName = game.playerTurn?.name ?: "-",
            players = players,
            stack = stack,
            combat = combat,
            recentLog = recentLog,
            gameOver = over,
            result = if (over) resultLine(game) else null,
            trail = trail,
            decisionSeq = decisionSeq,
        )
    }

    private fun resultLine(game: GameView): String {
        val outcome = game.outcome ?: return "game over"
        return if (outcome.isDraw) "draw" else "${game.winningPlayerName ?: "?"} won"
    }

    private fun player(p: PlayerView, isSeat: Boolean, mayView: (CardView) -> Boolean, mayPeek: (CardView) -> Boolean, stand: () -> Int): PlayerState {
        playerViews[p.id] = p
        val hand = cards(p, ZoneType.Hand)
        // "May view" is about the card as it is now; a face-down card is seen face-down unless we may peek.
        val sees = { cv: CardView -> mayView(cv) && (!cv.isFaceDown || mayPeek(cv)) }
        return PlayerState(
            id = p.id,
            name = p.name,
            life = p.life,
            isAi = p.isAI,
            isSeat = isSeat,
            hasPriority = p.hasPriority,
            hasLost = p.hasLost,
            handCount = hand.size,
            hand = hand.map { if (sees(it)) card(it) else CardState.back(stand()) },
            libraryCount = p.getZoneSize(ZoneType.Library),
            battlefield = cards(p, ZoneType.Battlefield).map { if (it.isFaceDown && !mayPeek(it)) faceDown(it) else card(it) },
            // A back says face-down only when the card is (face-down is public; what it is, is not).
            graveyard = cards(p, ZoneType.Graveyard).map { if (sees(it)) card(it) else CardState.back(stand(), it.isFaceDown) },
            exile = cards(p, ZoneType.Exile).map { if (sees(it)) card(it) else CardState.back(stand(), it.isFaceDown) },
            command = cards(p, ZoneType.Command).map { if (sees(it)) card(it) else CardState.back(stand(), it.isFaceDown) },
            manaPool = manaPool(p),
            poison = p.counters?.entrySet()?.firstOrNull { it.element.name.equals("poison", ignoreCase = true) }?.count ?: 0,
        )
    }

    private fun cards(p: PlayerView, zone: ZoneType): List<CardView> =
        p.getCards(zone)?.threadSafeIterable()?.toList().orEmpty()

    /** A card this seat may see. Face-down ones reach here only when it may peek (its own morphs), and say what they are. */
    fun card(cv: CardView): CardState {
        cardViews[cv.id] = cv
        val state = cv.currentState
        val creature = state.isCreature
        val real = if (cv.isFaceDown) cv.alternateState?.name?.takeIf { it.isNotBlank() } else null
        return CardState(
            id = cv.id,
            name = if (cv.isFaceDown) "Face-down" + (real?.let { ": $it" } ?: "") else state.name,
            // Forge prints a missing cost (lands) as "no cost"; that is no cost at all.
            manaCost = if (cv.isFaceDown) "" else state.manaCost?.takeUnless { it.isNoCost }?.toString().orEmpty(),
            typeLine = state.type?.toString().orEmpty(),
            power = if (creature) state.power else null,
            toughness = if (creature) state.toughness else null,
            loyalty = state.loyalty?.takeIf { state.isPlaneswalker },
            isLand = state.isLand,
            isCreature = creature,
            tapped = cv.isTapped,
            summoningSick = cv.isSick,
            attacking = cv.isAttacking,
            blocking = cv.isBlocking,
            damage = cv.damage,
            isToken = cv.isToken,
            attachedToId = cv.attachedTo?.id,
            text = if (cv.isFaceDown) "" else state.oracleText.orEmpty(),
            imageKey = if (cv.isFaceDown) null else state.imageKey,
            counters = counters(cv),
            faceDown = cv.isFaceDown,
        )
    }

    /** An opponent's face-down permanent: only what the table sees — a 2/2 with no name. */
    private fun faceDown(cv: CardView): CardState {
        cardViews[cv.id] = cv // it can be targeted, so it must be clickable
        val state = cv.currentState
        return CardState(
            id = cv.id, name = "Face-down", manaCost = "", typeLine = if (state.isCreature) "Creature" else "",
            power = if (state.isCreature) state.power else null, toughness = if (state.isCreature) state.toughness else null,
            loyalty = null, isLand = false, isCreature = state.isCreature, tapped = cv.isTapped, summoningSick = cv.isSick,
            attacking = cv.isAttacking, blocking = cv.isBlocking, damage = cv.damage, isToken = false,
            attachedToId = cv.attachedTo?.id, text = "", imageKey = null, counters = counters(cv), faceDown = true,
        )
    }

    private fun counters(cv: CardView): String =
        cv.counters?.entrySet()?.joinToString(", ") { "${it.element.name} x${it.count}" }.orEmpty()

    private fun manaPool(p: PlayerView): String = POOL_COLOURS
        .map { (letter, colour) -> letter to p.getMana(colour) }
        .filter { it.second > 0 }
        .joinToString(" ") { "${it.first}${it.second}" }

    private companion object {
        /** Forge suffixes card names with their id in text ("Polluted Delta (144)"); the table shows names. */
        val FORGE_IDS = Regex(" \\(\\d+\\)")
        /**
         * PlayerView keys the pool by mana *atom*. MagicColor.COLORLESS is 0,
         * not colourless's atom, and read with it colourless mana never showed.
         */
        val POOL_COLOURS = listOf(
            "W" to ManaAtom.WHITE, "U" to ManaAtom.BLUE, "B" to ManaAtom.BLACK,
            "R" to ManaAtom.RED, "G" to ManaAtom.GREEN, "C" to ManaAtom.COLORLESS,
        ).map { (letter, atom) -> letter to atom.toByte() }
    }
}
