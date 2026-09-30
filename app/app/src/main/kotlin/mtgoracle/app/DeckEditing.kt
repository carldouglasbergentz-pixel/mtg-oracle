package mtgoracle.app

import mtgoracle.core.deck.DeckRefusal
import mtgoracle.core.deck.DeckSection
import mtgoracle.data.DeckWriter
import mtgoracle.data.Lookup
import mtgoracle.forge.Log
import mtgoracle.ui.lookup.EditAction
import mtgoracle.ui.lookup.LookupUi
import mtgoracle.ui.lookup.Refusal

/**
 * Carries out the workspace's edits on the open deck through the deck
 * engine, then refreshes what the screen shows of it. A refused change
 * leaves its reason on the screen and is kept, so `add anyway` can push the
 * same change through the rule that stopped it.
 */
class DeckEditing(
    private val writer: DeckWriter,
    private val lookup: Lookup,
    private val ui: LookupUi,
    /** The deck open in the workspace, or null. */
    private val openDeck: () -> Int?,
    /** Re-reads deck [id] after a change: its rows, and its scope (a new commander is a new identity). */
    private val reload: (Int) -> Unit,
    /** One line on the status bar: what the change did. */
    private val say: (String) -> Unit,
) {
    private var refused: EditAction? = null

    /** Returns what happened: the status line's text, or the refusal. */
    fun perform(action: EditAction, forced: Boolean = false): String {
        val deckId = openDeck() ?: return "open a deck first (Enter on it in the library, or `cd <deck>`)".also(say)
        val (todo, force) = if (action == EditAction.Force) (refused ?: return "nothing to push through") to true else action to forced
        val result = try {
            val done = apply(deckId, todo, force) + if (force) " (forced)" else ""
            refused = null
            ui.refusal = null
            say(done)
            done
        } catch (e: DeckRefusal) {
            refused = todo.takeIf { e.forceable }
            ui.refusal = Refusal(e.message ?: e.kind.name, e.forceable)
            "refused: ${e.message}"
        } catch (e: Exception) {
            Log.error("deck edit failed", e)
            ui.refusal = Refusal("${e::class.simpleName}: ${e.message}", forceable = false)
            "failed: ${e.message}"
        }
        refresh(deckId)
        return result
    }

    /** The change itself; returns what the status bar says. */
    private fun apply(deckId: Int, action: EditAction, force: Boolean): String = when (action) {
        is EditAction.Add -> when (action.section) {
            DeckSection.CONSIDERING -> "considering +${action.quantity} ${writer.consider(deckId, action.card, action.quantity)}"
            DeckSection.SIDEBOARD -> "sideboard +${action.quantity} ${writer.add(deckId, action.card, action.quantity, sideboard = true, force = force)}"
            DeckSection.COMMANDER -> writer.promote(deckId, action.card, force = force).let { "${it.card}: ${it.action}" }
            DeckSection.MAIN -> "+${action.quantity} ${writer.add(deckId, action.card, action.quantity, force = force)}"
        }
        is EditAction.Remove -> writer.remove(deckId, action.card, if (action.all) null else action.quantity, action.section)
            .let { (card, removed, left) -> "-$removed $card (${action.section.key}; $left left)" }
        is EditAction.Move -> "${writer.move(deckId, action.card, action.from, action.to, 1, force)}: ${action.from.key} -> ${action.to.key}"
        is EditAction.Promote -> writer.promote(deckId, action.card, force = force).let { "${it.card}: ${it.action}" + (it.formatSet?.let { f -> "; format set to $f" } ?: "") }
        is EditAction.Demote -> writer.promote(deckId, action.card, unset = true).let { "${it.card}: ${it.action}" }
        EditAction.Force -> error("Force is resolved before apply")
    }

    /** `undo`: the newest revision reverted (undo of undo is redo). */
    fun undo(): String {
        val deckId = openDeck() ?: return "open a deck first"
        return try {
            val rev = writer.undo(deckId)
            ui.refusal = null
            "undone: ${rev.note}"
        } catch (e: DeckRefusal) {
            e.message ?: e.kind.name
        }.also { refresh(deckId) }
    }

    /** Everything the workspace shows of deck [deckId], read again. */
    fun refresh(deckId: Int) {
        reload(deckId)
        ui.history = writer.history(deckId, limit = 200)
        val scope = lookup.deckScope(deckId)
        val format = scope?.format
        ui.points = lookup.points(format?.key?.takeIf { format.pointsBudget != null })
        ui.pointsBudget = format?.pointsBudget
        // The considering list's `!`: what would stop each card going into the deck.
        ui.flags = lookup.deckConsidering(deckId).mapNotNull { name -> writer.wouldRefuse(deckId, name)?.let { name to (it.message ?: it.kind.name) } }.toMap()
    }
}
