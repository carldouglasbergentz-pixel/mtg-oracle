package mtgoracle.forge

import forge.card.MagicColor
import forge.game.GameView
import forge.game.card.CardView
import forge.game.player.PlayerView
import forge.game.zone.ZoneType
import mtgoracle.model.BoardState
import mtgoracle.model.CardState
import mtgoracle.model.PlayerState
import mtgoracle.model.StackEntry

/**
 * GameView (live, mutable, shared with the engine thread) -> BoardState
 * (immutable, ours). The only place the UI's picture of the game is made.
 *
 * Forge mutates its views on the game thread while this reads them on the
 * EDT — Forge's own desktop GUI lives with the same race. Collections are
 * copied through `threadSafeIterable`; a snapshot that still trips over a
 * concurrent change throws, and the caller simply takes the next one.
 */
internal class Snapshots(private val cardViews: MutableMap<Int, CardView>, private val playerViews: MutableMap<Int, PlayerView>) {

    fun build(
        game: GameView,
        seatPlayerIds: Set<Int>,
        mayView: (CardView) -> Boolean,
        recentLog: List<String>,
        gameOver: Boolean,
    ): BoardState {
        val players = game.players.threadSafeIterable().map { player(it, it.id in seatPlayerIds, mayView) }
        val stack = game.stack?.threadSafeIterable()?.map { item ->
            StackEntry(
                id = item.id,
                text = item.text.orEmpty(),
                sourceName = item.sourceCard?.let { cardName(it, mayView) } ?: "?",
                controllerName = item.activatingPlayer?.name ?: "?",
            )
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
            recentLog = recentLog,
            gameOver = over,
            result = if (over) resultLine(game) else null,
        )
    }

    private fun resultLine(game: GameView): String {
        val outcome = game.outcome ?: return "game over"
        return if (outcome.isDraw) "draw" else "${game.winningPlayerName ?: "?"} won"
    }

    private fun player(p: PlayerView, isSeat: Boolean, mayView: (CardView) -> Boolean): PlayerState {
        playerViews[p.id] = p
        val handViews = cards(p, ZoneType.Hand)
        val handVisible = handViews.all(mayView)
        return PlayerState(
            id = p.id,
            name = p.name,
            life = p.life,
            isAi = p.isAI,
            isSeat = isSeat,
            hasPriority = p.hasPriority,
            hasLost = p.hasLost,
            handCount = handViews.size,
            hand = if (handVisible) handViews.map { card(it) } else null,
            libraryCount = p.getZoneSize(ZoneType.Library),
            battlefield = cards(p, ZoneType.Battlefield).map { card(it) },
            graveyard = cards(p, ZoneType.Graveyard).map { card(it) },
            exile = cards(p, ZoneType.Exile).map { card(it) },
            manaPool = manaPool(p),
        )
    }

    private fun cards(p: PlayerView, zone: ZoneType): List<CardView> =
        p.getCards(zone)?.threadSafeIterable()?.toList().orEmpty()

    fun card(cv: CardView): CardState {
        cardViews[cv.id] = cv
        val state = cv.currentState
        val creature = state.isCreature
        return CardState(
            id = cv.id,
            name = if (cv.isFaceDown) "Face-down card" else state.name,
            manaCost = state.manaCost?.toString().orEmpty(),
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
            text = state.oracleText.orEmpty(),
        )
    }

    private fun cardName(cv: CardView, mayView: (CardView) -> Boolean): String =
        if (mayView(cv)) cv.currentState.name else "hidden"

    private fun manaPool(p: PlayerView): String = POOL_COLOURS
        .map { (letter, colour) -> letter to p.getMana(colour) }
        .filter { it.second > 0 }
        .joinToString(" ") { "${it.first}${it.second}" }

    private companion object {
        val POOL_COLOURS = listOf(
            "W" to MagicColor.WHITE, "U" to MagicColor.BLUE, "B" to MagicColor.BLACK,
            "R" to MagicColor.RED, "G" to MagicColor.GREEN, "C" to MagicColor.COLORLESS,
        )
    }
}
