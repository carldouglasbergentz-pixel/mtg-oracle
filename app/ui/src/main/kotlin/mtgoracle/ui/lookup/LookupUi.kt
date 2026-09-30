package mtgoracle.ui.lookup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
) {
    val command = CommandLineState()
    /** The middle pane shows the output rather than the deck. */
    var showOutput by mutableStateOf(false)
    /** `> ` at the root, `UW Draw Go> ` after `cd` into it. */
    var prompt by mutableStateOf("> ")
}

/** The hint under the command line: the query read back, a command's usage, or what is wrong. */
data class Preview(val text: String, val error: Boolean = false, val isSearch: Boolean = false)
