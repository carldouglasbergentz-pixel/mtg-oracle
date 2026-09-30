package mtgoracle.ui.lookup

import mtgoracle.core.deck.DeckSection

/**
 * What a click, a key or a command asks of the open deck. Values, as links
 * are: the card is a name, never text to parse. The app's DeckEditing
 * carries them out through the deck engine (data/DeckWriter).
 */
sealed interface EditAction {
    /** [quantity] more copies into [section]: main, sideboard, or the considering list. */
    data class Add(val card: String, val section: DeckSection, val quantity: Int = 1) : EditAction
    /** [quantity] copies out of [section], or every copy there with [all]. */
    data class Remove(val card: String, val section: DeckSection, val all: Boolean = false, val quantity: Int = 1) : EditAction
    data class Move(val card: String, val from: DeckSection, val to: DeckSection) : EditAction
    data class Promote(val card: String) : EditAction
    data class Demote(val card: String) : EditAction
    /** The last refused action again, past the rule that stopped it ("add anyway"). */
    data object Force : EditAction
}

/** Why the last change was refused, and whether [EditAction.Force] may push it through. */
data class Refusal(val text: String, val forceable: Boolean)

/** The deck pane's tabs in the workspace. */
enum class DeckTab(val label: String) { DECK("Deck"), CONSIDERING("Considering"), HISTORY("History") }
