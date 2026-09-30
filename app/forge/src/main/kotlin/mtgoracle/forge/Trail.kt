package mtgoracle.forge

import com.google.common.eventbus.Subscribe
import forge.game.Game
import forge.game.card.CardView
import forge.game.event.GameEventCardChangeZone
import forge.game.event.GameEventCardDamaged
import forge.game.event.GameEventPlayerLivesChanged
import forge.game.event.GameEventPlayerPriority
import forge.game.event.GameEventManaPool
import forge.game.event.EventValueChangeType
import forge.card.mana.ManaAtom
import forge.game.event.GameEventSpellAbilityCast
import forge.game.phase.PhaseType
import forge.game.player.PlayerView
import forge.game.zone.ZoneType
import mtgoracle.core.model.TrailEntry

/**
 * What just happened, as a line a player can take in at a glance — the
 * things that don't use the stack, or resolve before you notice: a land
 * drop, a fetchland going to the graveyard and the land it found, a life
 * payment, a card drawn.
 *
 * Built from Forge's game events on the game thread, under the seat's own
 * visibility rule ([named]): a card is named only if this seat may see it
 * where it went or where it came from was public. Otherwise it is "a card",
 * and its id is never kept.
 *
 * The actor — whose doing it was — is what the board filters on: the
 * controller of what is resolving, else the player with priority (who pays
 * costs and plays lands), else the active player (turn-based actions:
 * combat damage, the draw).
 */
internal class Trail(
    private val named: (CardView) -> Boolean,
    private val onChange: () -> Unit,
    private val keep: Int = 30,
) {
    private val entries = ArrayDeque<TrailEntry>()
    private var seq = 0L
    @Volatile private var game: Game? = null

    /** Each game of a match starts its own trail (seq keeps counting, so "since your last decision" still holds). */
    fun attach(game: Game) {
        if (this.game === game) return
        this.game = game
        synchronized(this) { entries.clear(); pending.clear() }
        started = false
        game.subscribeToEvents(this)
    }

    @Synchronized fun snapshot(): List<TrailEntry> = entries.map(::resolve)
    @Synchronized fun lastSeq(): Long = seq

    /**
     * Nothing before the first priority is play: the opening hands, the
     * mulligans, a staged board being put in place.
     */
    @Volatile private var started = false

    @Subscribe
    fun onPriority(event: GameEventPlayerPriority) { started = true }

    @Subscribe
    fun onCast(event: GameEventSpellAbilityCast) {
        val item = event.si() ?: return
        val source = item.sourceCard
        val name = source?.takeIf { named(it) || abilityNamesItsSource(item.isAbility || item.isTrigger, it.isFaceDown) }?.currentState?.name
        val text = when {
            item.isTrigger -> "${name ?: "a hidden card"} triggered"
            item.isAbility -> "activated ${name ?: "a hidden card"}"
            else -> "cast ${name ?: if (source?.isFaceDown == true) "a face-down spell" else "a hidden card"}"
        }
        add(item.activatingPlayer?.id ?: actor(), text, source?.takeIf { name != null })
    }

    @Subscribe
    fun onZone(event: GameEventCardChangeZone) {
        val card = event.card() ?: return
        val from = event.from()?.zoneType()
        val to = event.to()?.zoneType() ?: return
        // The stack has its own events (cast, resolve); what a spell becomes is the next zone change.
        if (from == ZoneType.Stack || to == ZoneType.Stack) return
        // What the event tells now, kept for a phrase that may be finished later.
        val land = card.currentState?.isLand == true
        val wasResolving = resolving()
        val enteredFaceDown = card.isFaceDown
        fun phrase(what: String) = when {
            from == ZoneType.Library && to == ZoneType.Hand -> "drew $what"
            from == ZoneType.Library && to == ZoneType.Battlefield -> "fetched $what"
            from == ZoneType.Hand && to == ZoneType.Battlefield && land && !wasResolving -> "played $what"
            from == ZoneType.Hand && to == ZoneType.Graveyard -> "discarded $what"
            from == ZoneType.Library && to == ZoneType.Graveyard -> "milled $what"
            to == ZoneType.Battlefield && enteredFaceDown -> "a face-down card entered"
            from == ZoneType.Battlefield || from == null -> "$what → ${zone(to)}"
            else -> "$what ${zone(from)} → ${zone(to)}"
        }
        // A draw is its owner's; anything else is whoever made it happen.
        val actor = if (from == ZoneType.Library && to == ZoneType.Hand) card.controller?.id ?: actor() else actor()
        if (from !in PUBLIC && to in PUBLIC) {
            // Out of a hidden zone into a public one: at this event Forge has not yet turned a
            // hideaway card face down (the trail named one), nor made a face-up one viewable.
            // Named when the trail is read, from the card as it is by then.
            addPending(actor, card.id, ::phrase)
            return
        }
        val name = card.currentState?.name?.takeIf { (!card.isFaceDown && from in PUBLIC) || named(card) }
        val text = phrase(name ?: "a card")
        add(actor, text, card.takeIf { name != null }, mergeDraws = text == "drew a card")
    }

    /** Entries whose card is named when the trail is read (see [onZone]), by seq. */
    private val pending = HashMap<Long, Pair<Int, (String) -> String>>()

    @Synchronized
    private fun addPending(actor: Int, cardId: Int, phrase: (String) -> String) {
        val added = add(actor, phrase("a card"), null) ?: return
        pending[added] = cardId to phrase
        pending.keys.retainAll(entries.map { it.seq }.toSet())
    }

    /** A pending entry, named now if this seat may see the card where it is; "a face-down card" if it lies face down. */
    private fun resolve(entry: TrailEntry): TrailEntry {
        val (cardId, phrase) = pending[entry.seq] ?: return entry
        val card = runCatching { game?.findById(cardId)?.view }.getOrNull()
        val name = card?.takeIf(named)?.currentState?.name
        val what = name ?: if (card?.isFaceDown == true) "a face-down card" else "a card"
        return entry.copy(text = phrase(what), cardIds = if (name != null) setOf(cardId) else emptySet())
    }

    @Subscribe
    fun onLife(event: GameEventPlayerLivesChanged) {
        val player = event.player() ?: return
        val actor = actor()
        val whose = if (player.id == actor) "" else "${short(player)} "
        add(actor, "${whose}life ${event.oldLives()}→${event.newLives()}", null)
    }

    /** Each player's pool as last seen, to tell what an event added. */
    private val pools = mutableMapOf<Int, Map<Char, Int>>()

    /**
     * Mana added by something resolving — a delayed trigger, a ritual — is
     * worth a line ("Mana Drain: +{C}{C}{C}{C}"); lands tapped for a payment are not.
     */
    @Subscribe
    fun onManaPool(event: GameEventManaPool) {
        val view = event.player() ?: return
        val player = game?.players?.firstOrNull { it.id == view.id } ?: return
        val now = POOL.mapValues { (_, atom) -> player.manaPool.getAmountOfColor(atom.toByte()) }
        val before = synchronized(this) { pools.put(view.id, now) }.orEmpty()
        if (event.mode() != EventValueChangeType.Added || !resolving()) return
        val gained = now.mapNotNull { (c, n) -> (n - (before[c] ?: 0)).takeIf { it > 0 }?.let { c to it } }
        if (gained.isEmpty()) return
        val source = game?.stack?.peek()?.sourceCard?.takeIf { named(it.view) }?.name
        val mana = gained.joinToString("") { (c, n) -> if (n <= 3) "{$c}".repeat(n) else "{$c}×$n" }
        add(actor(), (source?.let { "$it: " } ?: "") + "+$mana", null)
    }

    @Subscribe
    fun onDamage(event: GameEventCardDamaged) {
        val card = event.card() ?: return
        if (!named(card)) return
        add(actor(), "${event.amount()} damage to ${card.currentState.name}", card)
    }

    private fun resolving(): Boolean = game?.stack?.isResolving == true

    private fun actor(): Int {
        val g = game ?: return -1
        if (g.stack.isResolving) g.stack.peek()?.activatingPlayer?.let { return it.id }
        val phase = g.phaseHandler.phase
        if (phase == PhaseType.COMBAT_DAMAGE || phase == PhaseType.COMBAT_FIRST_STRIKE_DAMAGE) g.phaseHandler.playerTurn?.let { return it.id }
        return (g.phaseHandler.priorityPlayer ?: g.phaseHandler.playerTurn)?.id ?: -1
    }

    /** The new entry's seq, or null before play has started. */
    @Synchronized
    private fun add(actor: Int, text: String, card: CardView?, mergeDraws: Boolean = false): Long? {
        if (!started) return null
        val last = entries.lastOrNull()
        if (mergeDraws && last != null && last.actorId == actor && DRAWS.matches(last.text)) {
            val n = DRAWS.find(last.text)!!.groupValues[1].ifEmpty { "1" }.toInt() + 1
            entries[entries.lastIndex] = last.copy(seq = ++seq, text = "drew $n cards")
        } else {
            entries.addLast(TrailEntry(++seq, actor, text, card?.let { setOf(it.id) }.orEmpty()))
            while (entries.size > keep) entries.removeFirst()
        }
        onChange()
        return seq
    }

    private companion object {
        val PUBLIC = setOf(ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile, ZoneType.Command, ZoneType.Stack)
        val DRAWS = Regex("drew (?:a card|(\\d+) cards)")
        /** Mana *atoms* (the pool counts in these): colourless is its own bit here, unlike MagicColor's 0. */
        val POOL = linkedMapOf('W' to ManaAtom.WHITE, 'U' to ManaAtom.BLUE, 'B' to ManaAtom.BLACK, 'R' to ManaAtom.RED, 'G' to ManaAtom.GREEN, 'C' to ManaAtom.COLORLESS)

        fun zone(z: ZoneType?) = z?.name?.lowercase() ?: "?"
        /** "AI (Rakdos Midrange (AI))" -> "AI": the board names players the same way. */
        fun short(p: PlayerView) = p.name.substringBefore(" (")
    }
}
