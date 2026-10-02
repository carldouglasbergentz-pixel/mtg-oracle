package mtgoracle.forge

import com.google.common.eventbus.Subscribe
import forge.game.Game
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
 * it fizzled; one removed without resolving was countered by what is
 * resolving then (a counterspell stays on top of the stack while it does).
 * Everything named was on the stack, and so public, but a face-down spell
 * is named only under the seat's own rule ([named]).
 */
internal class Countered(
    private val named: (CardView) -> Boolean,
    private val onCountered: (Report) -> Unit,
) {
    /** [controllerId] is whose the item was; [actorId] whose doing it was (the counterspell's caster, else the same). */
    data class Report(val controllerId: Int, val actorId: Int, val what: String, val by: String?, val fizzled: Boolean, val card: CardView?) {
        val text: String get() = when {
            fizzled -> "$what fizzled: its targets were gone"
            by != null -> "$by countered $what"
            else -> "$what left the stack without resolving"
        }
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
        val (by, actor) = counteredBy(id) ?: (null to item.controllerId)
        onCountered(Report(item.controllerId, actor, item.what, by, fizzled = false, item.card))
    }

    /** What is resolving now, unless it is the item itself: the counterspell, and who cast it. */
    private fun counteredBy(id: Int): Pair<String?, Int>? = runCatching {
        val top = game?.stack?.takeIf { it.isResolving }?.peek() ?: return null
        if (top.spellAbility.view.id == id) return null
        val name = top.sourceCard?.view?.takeIf { named(it) }?.currentState?.name
        name to (top.activatingPlayer?.id ?: return null)
    }.getOrNull()
}
