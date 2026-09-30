package mtgoracle.ui.library

import mtgoracle.core.deck.DeckSection

/**
 * What a button or a menu in the library or the workspace asks for, before
 * anything is asked or written: the app's LibraryActions turns each into a
 * question (a name, a choice, a confirmation) and then the write.
 */
sealed interface LibraryIntent {
    data class NewDeck(val folderId: Int?) : LibraryIntent
    data object NewFolder : LibraryIntent
    data class RenameDeck(val deckId: Int) : LibraryIntent
    data class MoveDeck(val deckId: Int) : LibraryIntent
    data class DeckFormat(val deckId: Int) : LibraryIntent
    data class DeleteDeck(val deckId: Int) : LibraryIntent
    data class FolderFormat(val folderId: Int) : LibraryIntent
    data class DeleteFolder(val folderId: Int) : LibraryIntent
    /** A new deck from the list on the clipboard, in [folderId]. */
    data class Import(val folderId: Int?) : LibraryIntent
    /** The list on the clipboard into deck [deckId]: added to it, or replacing it (after a preview). */
    data class ImportInto(val deckId: Int) : LibraryIntent
    data class Export(val deckId: Int) : LibraryIntent
    data class ChoosePrinting(val deckId: Int, val card: String, val section: DeckSection) : LibraryIntent
}
