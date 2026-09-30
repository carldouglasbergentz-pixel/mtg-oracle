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
import mtgoracle.core.analysis.DeckInsight
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
import mtgoracle.ui.lookup.AskBar
import mtgoracle.ui.lookup.CommandLine
import mtgoracle.ui.lookup.DeckTab
import mtgoracle.ui.lookup.EditAction
import mtgoracle.core.deck.DeckSection
import mtgoracle.ui.kit.FitText
import mtgoracle.ui.kit.clickTarget
import androidx.compose.ui.input.key.utf16CodePoint
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
 * Editing, by mouse first: a result's `[+] [sb] [?]` (or + S C on the
 * selected one), a deck row's `-` / `+`, a right-click menu on any card, the
 * Deck / Considering / History tabs (1 2 3). Tab moves the arrow keys
 * between the results and the deck; there `+` `-` and Delete edit the row.
 * A refused change says why, and `[ add anyway ]` (F) pushes it through.
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
    /** The analysis block, fixed above the search: every edit's effect in view. */
    insight: DeckInsight? = null,
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
    // Which list the arrow keys walk: the results, or the deck pane's rows.
    var inDeck by remember { mutableStateOf(false) }
    var deckRow by remember { mutableStateOf<Int?>(null) }
    val rows = deck?.let { deckRows(it, lookup.deckTab) }.orEmpty()
    fun selectRow(by: Int) {
        if (rows.isEmpty()) return
        val at = ((deckRow ?: if (by > 0) -1 else rows.size) + by).coerceIn(0, rows.lastIndex)
        deckRow = at
        zoom = rows[at].card.let { c -> lookup.face(c.name) ?: zoom }
    }
    fun rowEdit(make: (DeckRow) -> EditAction?) { deckRow?.let { rows.getOrNull(it) }?.let(make)?.let(lookup.edit) }
    fun resultEdit(section: DeckSection) { lookup.selectedCard?.let { lookup.edit(EditAction.Add(it, section)) } }
    val onClick: (ClickTarget) -> Unit = { t ->
        when ((t as? ClickTarget.Control)?.name) {
            "library" -> onLeave()
            "play" -> onPlay()
            "results" -> onToggleResults()
            "deck-mode" -> onToggleDeckMode()
            "force" -> lookup.edit(EditAction.Force)
            "import" -> deck?.let { lookup.intent(LibraryIntent.ImportInto(it.id)) }
            "export" -> deck?.let { lookup.intent(LibraryIntent.Export(it.id)) }
            "format" -> deck?.let { lookup.intent(LibraryIntent.DeckFormat(it.id)) }
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
            val plus = e.key == Key.Plus || e.key == Key.NumPadAdd || e.key == Key.Equals || e.utf16CodePoint == '+'.code
            val minus = e.key == Key.Minus || e.key == Key.NumPadSubtract || e.utf16CodePoint == '-'.code
            when {
                e.key == Key.Escape -> onLeave()
                e.key == Key.Tab -> inDeck = !inDeck
                e.key == Key.One -> lookup.deckTab = DeckTab.DECK
                e.key == Key.Two -> lookup.deckTab = DeckTab.CONSIDERING
                e.key == Key.Three -> lookup.deckTab = DeckTab.HISTORY
                e.key == Key.F && lookup.refusal?.forceable == true -> lookup.edit(EditAction.Force)
                inDeck && e.key == Key.DirectionUp -> selectRow(-1)
                inDeck && e.key == Key.DirectionDown -> selectRow(+1)
                inDeck && plus -> rowEdit { r -> if (r.section == DeckSection.CONSIDERING) EditAction.Move(r.card.name, r.section, DeckSection.MAIN) else EditAction.Add(r.card.name, r.section) }
                inDeck && minus -> rowEdit { r -> EditAction.Remove(r.card.name, r.section) }
                inDeck && e.key == Key.Delete -> rowEdit { r -> EditAction.Remove(r.card.name, r.section, all = true) }
                inDeck && (e.key == Key.Enter || e.key == Key.NumPadEnter) -> deckRow?.let { rows.getOrNull(it) }?.let { lookup.open(OutputLink.Card(it.card.name)) }
                !inDeck && plus -> resultEdit(DeckSection.MAIN)
                !inDeck && e.key == Key.S -> resultEdit(DeckSection.SIDEBOARD)
                !inDeck && e.key == Key.C -> resultEdit(DeckSection.CONSIDERING)
                !inDeck && e.key == Key.DirectionLeft -> select(-1)
                !inDeck && e.key == Key.DirectionRight -> select(+1)
                !inDeck && e.key == Key.DirectionUp -> select(-step)
                !inDeck && e.key == Key.DirectionDown -> select(+step)
                e.key == Key.Enter || e.key == Key.NumPadEnter -> lookup.selectedCard?.let { lookup.open(OutputLink.Card(it)) } ?: return@onPreviewKeyEvent false
                else -> when (e.key) {
                Key.T -> if (e.isShiftPressed) onToggleDeckMode() else onToggleResults()
                Key.PageUp -> page(-1)
                Key.PageDown -> page(+1)
                Key.P -> onPlay()
                Key.Q -> onQuit()
                else -> return@onPreviewKeyEvent false
                }
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
                val spent = deck?.cards?.filter { !it.isSideboard }?.sumOf { c -> (lookup.pointsOf(c.name) ?: 0) * c.quantity }
                val right = deck?.let { d ->
                    listOfNotNull(d.format?.let(mtgoracle.core.lookup.Formats::shortName), "${d.mainCount} cards", lookup.pointsBudget?.let { b -> "$spent/$b pts" + if ((spent ?: 0) > b) "!" else "" }).joinToString(" · ")
                }
                BoxPane(deck?.name ?: "deck", Modifier.cellWidth(left).fillMaxHeight().region("workspace-deck"), right = right) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        if (deck == null) GridText("This deck is gone.", color = Palette.dim)
                        else EditableDeck(
                            deck, lookup.deckTab, deckMode, left - 2, selected = deckRow.takeIf { inDeck }, keyFor = keyFor,
                            points = lookup::pointsOf, flags = lookup.flags, history = lookup.history,
                            extraMenu = { row ->
                                if (row.section == DeckSection.CONSIDERING) emptyList()
                                else listOf("choose printing..." to { lookup.intent(LibraryIntent.ChoosePrinting(deck.id, row.card.name, row.section)) })
                            },
                            onTab = { lookup.deckTab = it; deckRow = null }, onEdit = lookup.edit,
                            onOpen = { lookup.open(OutputLink.Card(it)) }, onHover = { zoom = it },
                        )
                    }
                }
                val title = "search" + if (filters.isEmpty()) " · the whole pool" else " · ${filters.joinToString("  ")}"
                Column(Modifier.weight(1f).fillMaxHeight()) {
                AnalysisPane(insight, middle, onOpen = lookup.open, Modifier.fillMaxWidth())
                BoxPane(title, Modifier.fillMaxWidth().weight(1f).region("workspace-search"), right = if (lookup.grid) "T: lines" else "T: grid") {
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
                            grid = lookup.grid, selected = lookup.selected.takeIf { !inDeck }, faceOf = lookup.face,
                            actions = true, points = lookup::pointsOf,
                        )
                    }
                }
                }
                ZoomPane(lookup.hoverFace ?: zoom, SIDE_COLS, imageRows = 20, textMode = false, modifier = Modifier.cellWidth(SIDE_COLS).fillMaxHeight())
            }
            lookup.ask?.let { ask -> AskBar(ask) { lookup.ask = null; lookup.hoverFace = null; focus.requestFocus() } }
            lookup.refusal?.let { r ->
                Row(Modifier.fillMaxWidth()) {
                    GridText(" ✗ ", color = Palette.tapped, bold = true)
                    FitText(r.text, Modifier.weight(1f), color = Palette.tapped)
                    if (r.forceable) GridText(" [ add anyway (F) ] ", Modifier.clickTarget(ClickTarget.Control("force"), onClick), color = Palette.background, background = Palette.tapped)
                }
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
                "library" to "[ Library ]", "play" to "[ Play ]", "import" to "[ Import ]", "export" to "[ Export ]", "format" to "[ Format ]",
                "results" to if (lookup.grid) "[ Results as lines ]" else "[ Results as grid ]",
                "deck-mode" to if (deckMode == CardMode.TEXT) "[ Deck as frames ]" else "[ Deck as lines ]",
            ), onClick)
            val hints = if (lookup.command.focused) TYPING_HINTS
            else if (inDeck) listOf("↑↓" to "card", "+ -" to "copies", "Del" to "remove", "Enter" to "open", "Tab" to "results", "1 2 3" to "tabs", "right-click" to "menu", "Esc" to "library")
            else listOf(":" to "search", "←→↑↓" to "select", "+ S C" to "deck / side / consider", "Tab" to "deck", "T" to "grid/lines", "1 2 3" to "tabs", "P" to "play", "Esc" to "library")
            StatusLine(hints, notice, cols)
        }
    }
    LaunchedEffect(Unit) { startFocus(lookup, focus, commandFocus) }
}
