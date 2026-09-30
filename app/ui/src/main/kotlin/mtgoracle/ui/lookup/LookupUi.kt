package mtgoracle.ui.lookup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import mtgoracle.core.deck.DeckRevision
import mtgoracle.ui.kit.CardFace

/**
 * What the library screen needs from the command line's owner (the app's
 * LookupCommands): the output, what a line or a click does, and a card to
 * show in the zoom pane. The state lives here, so it outlives screen changes:
 * back from a game, the scrollback and the history are still there.
 */
class LookupUi(
    val output: OutputLog,
    val suggest: (String) -> String?,
    val submit: (String) -> Unit,
    val open: (OutputLink) -> Unit,
    /** A card by name for the zoom pane; null when there is no such card. */
    val face: (String) -> CardFace?,
    /** What a half-typed line means, for the hint under it; null for nothing to say. Cheap: no database. */
    val preview: (String) -> Preview? = { null },
    /** How many cards a search line finds; null when it is not a search. Hits the database: called off the UI thread. */
    val count: (String) -> Int? = { null },
    /** A change to the open deck (the workspace's buttons, keys and menus). */
    val edit: (EditAction) -> Unit = {},
) {
    /** A change to the library (a new deck, a rename, an import...): set by the app, which asks and writes. */
    var intent: (mtgoracle.ui.library.LibraryIntent) -> Unit = {}
    /** A question open over the screen (a name, a choice, a confirmation); it has the keyboard while it is. */
    var ask by mutableStateOf<Ask?>(null)
    /** What the zoom pane shows while a question offers cards (a printing under the mouse); over the screen's own. */
    var hoverFace by mutableStateOf<CardFace?>(null)
    /** Why the last change was refused; null once something else happens. */
    var refusal by mutableStateOf<Refusal?>(null)
    var deckTab by mutableStateOf(DeckTab.DECK)
    /** The open deck's revisions, newest first. */
    var history by mutableStateOf<List<DeckRevision>>(emptyList())
    /** Considering-list card -> the rule that would stop it in the deck (the `!`). */
    var flags by mutableStateOf<Map<String, String>>(emptyMap())
    /** The open deck's points list (lower-cased name -> points) and budget; empty and null outside a points format. */
    var points by mutableStateOf<Map<String, Int>>(emptyMap())
    var pointsBudget by mutableStateOf<Int?>(null)

    fun pointsOf(card: String): Int? = points[card.lowercase()]

    val command = CommandLineState()
    /** The middle pane shows the output rather than the deck. */
    var showOutput by mutableStateOf(false)
    /** `> ` at the root, `UW Draw Go> ` after `cd` into it. */
    var prompt by mutableStateOf("> ")
    /** Search results as a grid of cards (T switches to lines). */
    var grid by mutableStateOf(true)
    /** The row of the newest search page the arrow keys have selected; null before the first press. */
    var selected by mutableStateOf<Int?>(null)

    /** Moves the selection by [by] rows of the newest page, stopping at its ends; returns the card now selected. */
    fun moveSelection(by: Int): String? {
        val rows = output.latestSearch?.page?.rows.orEmpty()
        if (rows.isEmpty()) return null
        val at = ((selected ?: if (by > 0) -1 else rows.size) + by).coerceIn(0, rows.lastIndex)
        selected = at
        return rows[at].name
    }

    /** The selected card of the newest page, if any. */
    val selectedCard: String? get() = selected?.let { output.latestSearch?.page?.rows?.getOrNull(it)?.name }
}

/** The hint under the command line: the query read back, a command's usage, or what is wrong. */
data class Preview(val text: String, val error: Boolean = false, val isSearch: Boolean = false)
