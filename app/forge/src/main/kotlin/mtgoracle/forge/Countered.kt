package mtgoracle.forge

import com.google.common.eventbus.Subscribe
import forge.game.Game
import forge.game.ability.ApiType
import forge.game.card.CardView
import forge.game.event.GameEventSpellAbilityCast
import forge.game.event.GameEventSpellRemovedFromStack
import forge.game.event.GameEventSpellResolved

/**
 * Spells and abilities that leave the stack without resolving: countered
 * (Spell Snare on Consult the Star Charts, Tale's End on a trigger) or
 * fizzled (every target gone). Forge's log has it as one line among many
 * ("Resolve Stack: Spell Snare - Counter …"), easy to miss with triggers
 * going on; [onCountered] makes it a line of its own.
 *
 * An item is followed from its cast. One that resolves is reported only if
 * it fizzled; one removed without resolving was taken by what is resolving
 * then (a counterspell stays on top of the stack while it does). Forge says
 * "removed" for a counter and a bounce alike, so the remover's own effect
 * decides the word: Counterspell (and Remand) counter, Bilbo's Gambit only
 * returns a spell to its owner's hand.
 * Everything named was on the stack, and so public, but a face-down spell
 * is named only under the seat's own rule ([named]).
 */
internal class Countered(
    private val named: (CardView) -> Boolean,
    private val onCountered: (Report) -> Unit,
) {
    /**
     * [controllerId] is whose the item was; [actorId] whose doing it was (the counterspell's caster, else the same).
     * [countered] is false when [by] moved it rather than countered it, to Forge's zone [destination] (`Hand`, `Library`, `Exile`).
     */
    data class Report(
        val controllerId: Int, val actorId: Int, val what: String, val by: String?, val fizzled: Boolean, val card: CardView?,
        val countered: Boolean = true, val destination: String? = null,
    ) {
        val text: String get() = when {
            fizzled -> "$what fizzled: its targets were gone"
            by != null && countered -> "$by countered $what"
            by != null -> when (destination) {
                "Hand" -> "$by returned $what to its owner's hand"
                "Library" -> "$by put $what into its owner's library"
                "Exile" -> "$by exiled $what"
                else -> "$by removed $what from the stack"
            }
            else -> "$what left the stack without resolving"
        }
        /** The game log's word for it. */
        val kind: String get() = when { fizzled -> "Fizzled"; countered -> "Countered"; else -> "Removed" }
    }

    @Volatile private var game: Game? = null
    /** Items on the stack by their view id: what to call them, and whose they are. */
    private val open = HashMap<Int, Item>()

    private data class Item(val what: String, val controllerId: Int, val card: CardView?)

    fun attach(game: Game) {
        if (this.game === game) return
        this.game = game
        synchronized(this) { open.clear() }
        game.subscribeToEvents(this)
    }

    @Subscribe
    fun onCast(event: GameEventSpellAbilityCast) {
        val sa = event.sa() ?: return
        val source = event.si()?.sourceCard ?: sa.hostCard
        val name = source?.takeIf { named(it) }?.currentState?.name
        val ability = event.si()?.let { it.isAbility || it.isTrigger } == true
        val what = when {
            name == null -> if (ability) "a hidden card's ability" else "a hidden spell"
            ability -> "$name's ability"
            else -> name
        }
        val controller = event.si()?.activatingPlayer?.id ?: -1
        synchronized(this) { open[sa.id] = Item(what, controller, source?.takeIf { name != null }) }
    }

    @Subscribe
    fun onResolved(event: GameEventSpellResolved) {
        val item = synchronized(this) { open.remove(event.spell()?.id ?: return) } ?: return
        if (event.hasFizzled()) onCountered(Report(item.controllerId, item.controllerId, item.what, null, fizzled = true, item.card))
    }

    @Subscribe
    fun onRemoved(event: GameEventSpellRemovedFromStack) {
        val id = event.sa()?.id ?: return
        val item = synchronized(this) { open.remove(id) } ?: return
        val remover = removedBy(id)
        onCountered(Report(item.controllerId, remover?.actorId ?: item.controllerId, item.what, remover?.name, fizzled = false, item.card,
            countered = remover?.counters ?: true, destination = remover?.destination))
    }

    private data class Remover(val name: String?, val actorId: Int, val counters: Boolean, val destination: String?)

    /** What is resolving now, unless it is the item itself: the counterspell (or the bounce), who cast it, and what its effect does. */
    private fun removedBy(id: Int): Remover? = runCatching {
        val top = game?.stack?.takeIf { it.isResolving }?.peek() ?: return null
        if (top.spellAbility.view.id == id) return null
        val name = top.sourceCard?.view?.takeIf { named(it) }?.currentState?.name
        val effects = generateSequence(top.spellAbility) { it.subAbility }.toList()
        Remover(
            name, top.activatingPlayer?.id ?: return null,
            counters = effects.any { it.api == ApiType.Counter },
            destination = effects.firstOrNull { it.api == ApiType.ChangeZone }?.getParam("Destination"),
        )
    }.getOrNull()
}
