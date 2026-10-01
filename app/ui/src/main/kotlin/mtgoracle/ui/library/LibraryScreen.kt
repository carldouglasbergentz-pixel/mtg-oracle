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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.launch
import mtgoracle.core.analysis.DeckInsight
import mtgoracle.core.deck.Deck
import mtgoracle.core.deck.DeckCard
import mtgoracle.core.deck.DeckSummary
import mtgoracle.ui.board.SIDE_COLS
import mtgoracle.ui.kit.BoxPane
import mtgoracle.ui.kit.CardFace
import mtgoracle.ui.kit.CardMode
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.GridText
import mtgoracle.ui.kit.StatusLine
import mtgoracle.ui.kit.ZoomPane
import mtgoracle.ui.kit.cellWidth
import mtgoracle.ui.kit.clickTarget
import mtgoracle.ui.kit.fit
import mtgoracle.ui.lookup.AskBar
import mtgoracle.ui.lookup.CommandLine
import mtgoracle.ui.kit.ContextMenu
import mtgoracle.ui.kit.onRightClick
import mtgoracle.core.deck.Folder
import mtgoracle.core.lookup.Formats
import androidx.compose.ui.geometry.Offset
import mtgoracle.ui.lookup.KeyRoute
import mtgoracle.ui.lookup.LookupUi
import mtgoracle.ui.lookup.OutputLink
import mtgoracle.ui.lookup.OutputPane
import mtgoracle.ui.lookup.endsTyping
import mtgoracle.ui.lookup.routeKey
import mtgoracle.ui.lookup.startFocus
import mtgoracle.ui.theme.LocalCells
import mtgoracle.ui.theme.Palette

const val DECK_LIST_COLS = 40

/**
 * Folders and decks on the left, the selected deck in the middle (art frames
 * or text lines, T toggles), the zoom pane on the right, and the command line
 * at the bottom. Enter opens the deck to edit (the deck workspace), P plays
 * it. A command shows its output in the middle instead of the deck; Tab
 * switches back. Decks are read-only here: the TUI edits them.
 *
 * The screen's own keys (arrows, T, Q...) only act while the command line
 * does not have the keyboard ([routeKey]), so typing `quit` never quits on its `q`.
 */
@Composable
fun LibraryScreen(
    decks: List<DeckSummary>,
    selectedId: Int?,
    deck: Deck?,
    keyFor: (DeckCard) -> String?,
    mode: CardMode,
    notice: String?,
    onSelect: (Int) -> Unit,
    onPlay: () -> Unit,
    onToggleMode: () -> Unit,
    onPrefetch: () -> Unit,
    onQuit: () -> Unit,
    lookup: LookupUi? = null,
    onEdit: () -> Unit = {},
    /** Every folder, the empty ones too: a folder just made has no deck yet. */
    folders: List<Folder> = emptyList(),
    /** The analysis block above the selected deck; null while it is being read. */
    insight: DeckInsight? = null,
    /** The selected deck's points per card (a points format), for `Mana Drain (3)`; null for none. */
    pointsOf: (String) -> Int? = { null },
    /** The selected deck's colour identity when it has a commander (`{U}{W}`), and its points spent of its budget (`9/10 pts`). */
    badges: List<String> = emptyList(),
) {
    var menu by remember { mutableStateOf<Pair<String, Offset>?>(null) }
    val selectedFolder = decks.firstOrNull { it.id == selectedId }?.folderId
    var zoom by remember { mutableStateOf<CardFace?>(null) }
    val focus = remember { FocusRequester() }
    val commandFocus = remember { FocusRequester() }
    val outputScroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    fun showDeck(id: Int) {
        lookup?.showOutput = false
        onSelect(id)
    }
    fun page(by: Int) {
        if (lookup?.showOutput != true) return
        scope.launch { outputScroll.scrollBy(by * outputScroll.layoutInfo.viewportSize.height * 0.9f) }
    }
    val onClick: (ClickTarget) -> Unit = { t ->
        val name = (t as? ClickTarget.Control)?.name.orEmpty()
        when {
            name.startsWith("deck:") -> showDeck(name.removePrefix("deck:").toInt())
            name == "edit" -> onEdit()
            name == "play" -> onPlay()
            name == "mode" -> onToggleMode()
            name == "prefetch" -> onPrefetch()
            name == "new-deck" -> lookup?.intent?.invoke(LibraryIntent.NewDeck(selectedFolder, askFolder = true))
            name == "new-folder" -> lookup?.intent?.invoke(LibraryIntent.NewFolder)
            name == "import" -> lookup?.intent?.invoke(LibraryIntent.Import(selectedFolder, askFolder = true))
            name == "sync" -> lookup?.submit?.invoke("sync")
        }
    }
    fun move(by: Int) {
        if (decks.isEmpty()) return
        val at = decks.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
        showDeck(decks[(at + by).coerceIn(0, decks.lastIndex)].id)
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Palette.background).focusRequester(focus).focusable().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (lookup?.let { routeKey(e, it, commandFocus) } ?: KeyRoute.TO_SCREEN) {
                KeyRoute.HANDLED -> return@onPreviewKeyEvent true
                KeyRoute.TO_LINE -> return@onPreviewKeyEvent false
                KeyRoute.TO_SCREEN -> {}
            }
            when (e.key) {
                Key.Tab -> if (lookup != null) lookup.showOutput = !lookup.showOutput else return@onPreviewKeyEvent false
                Key.PageUp -> page(-1)
                Key.PageDown -> page(+1)
                Key.DirectionUp -> move(-1)
                Key.DirectionDown -> move(+1)
                Key.Enter, Key.NumPadEnter -> onEdit()
                Key.P -> onPlay()
                Key.T -> onToggleMode()
                Key.I -> onPrefetch()
                Key.Q -> onQuit()
                else -> return@onPreviewKeyEvent false
            }
            true
        },
    ) {
        val cols = LocalCells.current.cols(constraints.maxWidth.toFloat())
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f).fillMaxWidth().endsTyping(lookup, focus)) {
                BoxPane("decks", Modifier.cellWidth(DECK_LIST_COLS).fillMaxHeight()) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        // Every folder, empty or not, then the decks outside them. A right-click opens a menu.
                        // The folders listed, and any a deck names that the list lacks: no deck may go missing.
                        val known = folders.map { it.id as Int? to it.name }
                        val unlisted = decks.filter { d -> d.folderId != null && known.none { it.first == d.folderId } }
                            .map { it.folderId to (it.folderName ?: "folder #${it.folderId}") }.distinct()
                        val groups = (known + unlisted).sortedBy { it.second.lowercase() } + (null to "(no folder)")
                        for ((folderId, folderName) in groups) {
                            val inFolder = decks.filter { it.folderId == folderId }
                            if (folderId == null && inFolder.isEmpty()) continue
                            // A folder with a default format carries its tag: `Duel Commander [DC]`.
                            val tag = folders.firstOrNull { it.id == folderId }?.format?.takeIf { it.isNotBlank() }?.let { " [${Formats.shortName(it)}]" }.orEmpty()
                            GridText(fit(folderName + tag + if (inFolder.isEmpty()) "  (empty)" else "", DECK_LIST_COLS - 2),
                                Modifier.clickTarget(ClickTarget.Control("folder:${folderId ?: "none"}"), {}).onRightClick { at -> folderId?.let { menu = "folder:$it" to at } },
                                color = Palette.dim, bold = true)
                            inFolder.forEach { d ->
                                val selected = d.id == selectedId
                                GridText(
                                    fit("  ${d.name}".padEnd(DECK_LIST_COLS - 8) + "%4d".format(d.cardCount), DECK_LIST_COLS - 2),
                                    Modifier.clickTarget(ClickTarget.Control("deck:${d.id}"), onClick).onRightClick { at -> showDeck(d.id); menu = "deck:${d.id}" to at },
                                    color = if (selected) Palette.background else Palette.foreground,
                                    background = if (selected) Palette.foreground else Color.Unspecified,
                                )
                            }
                        }
                    }
                }
                val middle = cols - DECK_LIST_COLS - SIDE_COLS
                val right = deck?.let { d ->
                    (listOfNotNull(d.format?.let(Formats::shortName)) + badges + listOfNotNull("${d.mainCount} cards", d.substitutions.takeIf { it.isNotEmpty() }?.let { "AI copy: ${it.size} substitutions" })).joinToString(" · ")
                }
                if (lookup?.showOutput == true) {
                    BoxPane("output", Modifier.weight(1f).fillMaxHeight(), right = "Tab: ${deck?.name ?: "deck"}") {
                        OutputPane(lookup.output, outputScroll, onOpen = lookup.open,
                            onHover = { link -> (link as? OutputLink.Card)?.let { lookup.face(it.name) }?.let { zoom = it } })
                    }
                } else {
                    // The analysis stays put above the deck, which scrolls under it.
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        if (deck != null) AnalysisPane(insight, middle, onOpen = { lookup?.open?.invoke(it) }, Modifier.fillMaxWidth())
                        BoxPane(deck?.name ?: "no deck selected", Modifier.fillMaxWidth().weight(1f), right = right) {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                if (deck == null) GridText("Pick a deck on the left.", color = Palette.dim)
                                else DeckView(deck, keyFor, mode, middle - 2, onHover = { zoom = it }, points = pointsOf)
                            }
                        }
                    }
                }
                ZoomPane(lookup?.hoverFace ?: zoom, SIDE_COLS, imageRows = 20, textMode = mode == CardMode.TEXT, modifier = Modifier.cellWidth(SIDE_COLS).fillMaxHeight())
            }
            lookup?.ask?.let { ask -> AskBar(ask) { lookup.ask = null; lookup.hoverFace = null; focus.requestFocus() } }
            if (lookup != null) {
                CommandLine(
                    lookup.command, lookup.prompt, lookup.suggest,
                    onSubmit = { lookup.submit(it) },
                    onLeave = { focus.requestFocus() },
                    onPage = ::page,
                    focus = commandFocus,
                    preview = lookup.preview,
                    count = lookup.count,
                )
            }
            Buttons(listOf(
                "edit" to "[ Edit ]", "play" to "[ Play ]", "new-deck" to "[ New deck ]", "new-folder" to "[ New folder ]", "import" to "[ Import ]",
                "mode" to if (mode == CardMode.ART) "[ Text ]" else "[ Art ]", "prefetch" to "[ Fetch images ]", "sync" to "[ Sync ]",
            ), onClick)
            val hints = if (lookup?.command?.focused == true) TYPING_HINTS
            else listOfNotNull(":" to "command", ("Tab" to "deck/output").takeIf { lookup != null }, "↑↓" to "deck", "Enter" to "edit", "P" to "play", "T" to "text/art", "I" to "fetch images", "Q" to "quit")
            StatusLine(hints, notice, cols)
        }
    }
    menu?.let { (what, at) ->
        val intent = lookup?.intent ?: return@let
        val id = what.substringAfter(':').toInt()
        val items: List<Pair<String, () -> Unit>> = if (what.startsWith("deck:")) listOf(
            "open to edit" to { onSelect(id); onEdit() },
            "play" to { onSelect(id); onPlay() },
            "rename..." to { intent(LibraryIntent.RenameDeck(id)) },
            "move to folder..." to { intent(LibraryIntent.MoveDeck(id)) },
            "format..." to { intent(LibraryIntent.DeckFormat(id)) },
            "export to the clipboard" to { intent(LibraryIntent.Export(id)) },
            "delete..." to { intent(LibraryIntent.DeleteDeck(id)) },
        ) else listOf(
            "new deck here..." to { intent(LibraryIntent.NewDeck(id)) },
            "import a deck here..." to { intent(LibraryIntent.Import(id)) },
            "default format..." to { intent(LibraryIntent.FolderFormat(id)) },
            "delete folder..." to { intent(LibraryIntent.DeleteFolder(id)) },
        )
        val title = if (what.startsWith("deck:")) decks.firstOrNull { it.id == id }?.name.orEmpty() else folders.firstOrNull { it.id == id }?.name.orEmpty()
        ContextMenu(title, items, at) { menu = null }
    }
    LaunchedEffect(Unit) { startFocus(lookup, focus, commandFocus) }
}

/** The status line's hints while the command line has the keyboard. */
internal val TYPING_HINTS = listOf("Enter" to "run", "↑↓" to "history", "Tab" to "autofill", "PgUp/PgDn" to "scroll", "Esc" to "leave")

/** A row of `[ Label ]` controls. */
@Composable
internal fun Buttons(buttons: List<Pair<String, String>>, onClick: (ClickTarget) -> Unit) {
    Row {
        buttons.forEach { (name, label) ->
            GridText(label, Modifier.clickTarget(ClickTarget.Control(name), onClick), color = Palette.background, background = Palette.foreground)
            GridText("  ")
        }
    }
}
