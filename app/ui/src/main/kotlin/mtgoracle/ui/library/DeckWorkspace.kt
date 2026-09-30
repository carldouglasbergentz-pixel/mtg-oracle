package mtgoracle.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.launch
import mtgoracle.core.deck.Deck
import mtgoracle.core.deck.DeckCard
import mtgoracle.ui.board.SIDE_COLS
import mtgoracle.ui.kit.BoxPane
import mtgoracle.ui.kit.CardFace
import mtgoracle.ui.kit.CardMode
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.FrameSize
import mtgoracle.ui.kit.GridText
import mtgoracle.ui.kit.StatusLine
import mtgoracle.ui.kit.WrapText
import mtgoracle.ui.kit.ZoomPane
import mtgoracle.ui.kit.cellWidth
import mtgoracle.ui.kit.region
import mtgoracle.ui.lookup.CommandLine
import mtgoracle.ui.lookup.KeyRoute
import mtgoracle.ui.lookup.LookupUi
import mtgoracle.ui.lookup.OutputLink
import mtgoracle.ui.lookup.OutputPane
import mtgoracle.ui.lookup.endsTyping
import mtgoracle.ui.lookup.gridColumns
import mtgoracle.ui.lookup.routeKey
import mtgoracle.ui.lookup.startFocus
import mtgoracle.ui.theme.LocalCells
import mtgoracle.ui.theme.Palette

/** The deck pane's width: one line per card in text mode, three frames across in art mode. */
private fun deckCols(mode: CardMode) = if (mode == CardMode.TEXT) 58 else 3 * (FrameSize.cols(CardMode.ART) + 1) + 1

/**
 * One deck opened to work on, as Moxfield opens one: the deck on the left,
 * search in the middle, the zoom pane on the right. Search follows the deck
 * (its commander's colours and its format, named in the middle pane's
 * title); results are a grid of cards, or lines (T). The arrow keys select
 * a result (zoomed), Enter opens it. Esc goes back to the library.
 *
 * Read-only for now: adding and removing from the results is step 4.
 */
@Composable
fun DeckWorkspace(
    deck: Deck?,
    /** The deck's search filters as the search announces them (`ci<=UW  f:canlander`); empty for none. */
    filters: List<String>,
    keyFor: (DeckCard) -> String?,
    /** Lines or frames in the deck pane (Shift+T). */
    deckMode: CardMode,
    lookup: LookupUi,
    notice: String?,
    onLeave: () -> Unit,
    onToggleResults: () -> Unit,
    onToggleDeckMode: () -> Unit,
    onPlay: () -> Unit,
    onQuit: () -> Unit,
) {
    var zoom by remember { mutableStateOf<CardFace?>(null) }
    val focus = remember { FocusRequester() }
    val commandFocus = remember { FocusRequester() }
    val outputScroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Cards per grid row, as laid out now: read by the arrow keys, so not state (it is written while composing).
    val perRow = remember { intArrayOf(1) }
    fun page(by: Int) {
        scope.launch { outputScroll.scrollBy(by * outputScroll.layoutInfo.viewportSize.height * 0.9f) }
    }
    fun select(by: Int) {
        lookup.moveSelection(by)?.let { name -> lookup.face(name)?.let { zoom = it } }
    }
    val onClick: (ClickTarget) -> Unit = { t ->
        when ((t as? ClickTarget.Control)?.name) {
            "library" -> onLeave()
            "play" -> onPlay()
            "results" -> onToggleResults()
            "deck-mode" -> onToggleDeckMode()
        }
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Palette.background).focusRequester(focus).focusable().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (routeKey(e, lookup, commandFocus)) {
                KeyRoute.HANDLED -> return@onPreviewKeyEvent true
                KeyRoute.TO_LINE -> return@onPreviewKeyEvent false
                KeyRoute.TO_SCREEN -> {}
            }
            val step = if (lookup.grid) perRow[0] else 1
            when (e.key) {
                Key.Escape -> onLeave()
                Key.DirectionLeft -> select(-1)
                Key.DirectionRight -> select(+1)
                Key.DirectionUp -> select(-step)
                Key.DirectionDown -> select(+step)
                Key.Enter, Key.NumPadEnter -> lookup.selectedCard?.let { lookup.open(OutputLink.Card(it)) } ?: return@onPreviewKeyEvent false
                Key.T -> if (e.isShiftPressed) onToggleDeckMode() else onToggleResults()
                Key.PageUp -> page(-1)
                Key.PageDown -> page(+1)
                Key.P -> onPlay()
                Key.Q -> onQuit()
                else -> return@onPreviewKeyEvent false
            }
            true
        },
    ) {
        val cols = LocalCells.current.cols(constraints.maxWidth.toFloat())
        val left = deckCols(deckMode)
        val middle = cols - left - SIDE_COLS
        perRow[0] = gridColumns(middle - 2)
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f).fillMaxWidth().endsTyping(lookup, focus)) {
                val right = deck?.let { d -> listOfNotNull(d.format, "${d.mainCount} cards").joinToString(" · ") }
                BoxPane(deck?.name ?: "deck", Modifier.cellWidth(left).fillMaxHeight().region("workspace-deck"), right = right) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        if (deck == null) GridText("This deck is gone.", color = Palette.dim)
                        else DeckView(deck, keyFor, deckMode, left - 2, onHover = { zoom = it })
                    }
                }
                val title = "search" + if (filters.isEmpty()) " · the whole pool" else " · ${filters.joinToString("  ")}"
                BoxPane(title, Modifier.weight(1f).fillMaxHeight().region("workspace-search"), right = if (lookup.grid) "T: lines" else "T: grid") {
                    if (lookup.output.entries.isEmpty()) {
                        Column(Modifier.fillMaxWidth()) {
                            WrapText("Type what you are looking for (press : first): a name (bolt), a type (goblin), " +
                                "or the search language (t:instant c:u mv<=2, otag:removal). The line under it says what it means " +
                                "and how many cards it finds; `help search` has the whole language.", color = Palette.dim)
                        }
                    } else {
                        OutputPane(
                            lookup.output, outputScroll, onOpen = lookup.open,
                            onHover = { link -> (link as? OutputLink.Card)?.let { lookup.face(it.name) }?.let { zoom = it } },
                            grid = lookup.grid, selected = lookup.selected, faceOf = lookup.face,
                        )
                    }
                }
                ZoomPane(zoom, SIDE_COLS, imageRows = 20, textMode = false, modifier = Modifier.cellWidth(SIDE_COLS).fillMaxHeight())
            }
            CommandLine(
                lookup.command, lookup.prompt, lookup.suggest,
                onSubmit = { lookup.submit(it) },
                onLeave = { focus.requestFocus() },
                onPage = ::page,
                focus = commandFocus,
                preview = lookup.preview,
                count = lookup.count,
            )
            Buttons(listOf(
                "library" to "[ Library ]", "play" to "[ Play ]",
                "results" to if (lookup.grid) "[ Results as lines ]" else "[ Results as grid ]",
                "deck-mode" to if (deckMode == CardMode.TEXT) "[ Deck as frames ]" else "[ Deck as lines ]",
            ), onClick)
            val hints = if (lookup.command.focused) TYPING_HINTS
            else listOf(":" to "search / command", "←→↑↓" to "select", "Enter" to "open", "T" to "grid/lines", "Shift+T" to "deck view", "P" to "play", "Esc" to "library")
            StatusLine(hints, notice, cols)
        }
    }
    LaunchedEffect(Unit) { startFocus(lookup, focus, commandFocus) }
}
