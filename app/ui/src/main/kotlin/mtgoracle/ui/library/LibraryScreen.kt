package mtgoracle.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
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
import mtgoracle.ui.kit.Toolbar
import mtgoracle.ui.kit.CardFace
import mtgoracle.ui.kit.CardMode
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.GridText
import mtgoracle.ui.kit.StatusLine
import mtgoracle.ui.kit.PaneEdge
import mtgoracle.ui.kit.ZoomPane
import mtgoracle.ui.kit.region
import mtgoracle.ui.kit.cellWidth
import mtgoracle.ui.kit.clickTarget
import mtgoracle.ui.kit.fit
import mtgoracle.ui.lookup.AskBar
import mtgoracle.ui.lookup.CommandLine
import mtgoracle.core.library.PackageScope
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

/** How soon a second click on the same deck counts as a double-click, as Windows' default is. */
private const val DOUBLE_CLICK_MILLIS = 500L

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
    onAchievements: () -> Unit = {},
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
    /** The deck list's and the zoom pane's widths as last kept (drag their edges, Ctrl+(Shift+)←/→). */
    columns: SideColumns = SideColumns(DECK_LIST_COLS, SIDE_COLS),
    onColumnsChange: (SideColumns) -> Unit = {},
    /** Cards Forge's AI won't play or Forge lacks: flagged red beside them. */
    aiFlags: Map<String, mtgoracle.core.deck.AiFlag> = emptyMap(),
) {
    var kept by remember { mutableStateOf(columns) }
    // The window's width in cells as last laid out: read by the keys, so not state (it is written while composing).
    val total = remember { intArrayOf(Int.MAX_VALUE) }
    fun keep(next: SideColumns) { kept = next.clampedTo(total[0]); onColumnsChange(kept) }
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
    // A second click on the same deck soon after the first opens it. Timed by hand: a double-tap detector
    // makes every single click wait out the double-click window, and the selection lags behind the mouse.
    val lastDeckClick = remember { longArrayOf(-1, 0) }
    val onClick: (ClickTarget) -> Unit = { t ->
        val name = (t as? ClickTarget.Control)?.name.orEmpty()
        when {
            name.startsWith("deck:") -> {
                val id = name.removePrefix("deck:").toInt()
                val now = System.currentTimeMillis()
                val again = lastDeckClick[0] == id.toLong() && now - lastDeckClick[1] < DOUBLE_CLICK_MILLIS
                lastDeckClick[0] = id.toLong(); lastDeckClick[1] = now
                showDeck(id)
                if (again) { lastDeckClick[0] = -1; onEdit() }
            }
            name == "play" -> onPlay()
            name == "mode" -> onToggleMode()
            name == "prefetch" -> onPrefetch()
            name == "achievements" -> onAchievements()
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
        Modifier.fillMaxSize().background(Palette.surface).focusRequester(focus).focusable().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (lookup?.let { routeKey(e, it, commandFocus) } ?: KeyRoute.TO_SCREEN) {
                KeyRoute.HANDLED -> return@onPreviewKeyEvent true
                KeyRoute.TO_LINE -> return@onPreviewKeyEvent false
                KeyRoute.TO_SCREEN -> {}
            }
            kept.stepped(e)?.let { keep(it); return@onPreviewKeyEvent true }
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
        total[0] = cols
        val drag = rememberColumnDrag(kept, cols, ::keep)
        val left = drag.live.left
        val side = drag.live.right
        Column(Modifier.fillMaxSize()) {
            Toolbar(listOf(
                "play" to "Play", "new-deck" to "New deck", "new-folder" to "New folder", "import" to "Import",
                "mode" to if (mode == CardMode.ART) "Text" else "Art", "prefetch" to "Fetch images", "sync" to "Sync", "achievements" to "Achievements",
            ), onClick)
            Row(Modifier.weight(1f).fillMaxWidth().endsTyping(lookup, focus)) {
                Box(Modifier.cellWidth(left).fillMaxHeight()) {
                    BoxPane("decks", Modifier.fillMaxSize().region("library-decks")) {
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
                                GridText(fit(folderName + tag + if (inFolder.isEmpty()) "  (empty)" else "", left - 2),
                                    Modifier.clickTarget(ClickTarget.Control("folder:${folderId ?: "none"}"), {}).onRightClick { at -> folderId?.let { menu = "folder:$it" to at } },
                                    color = Palette.dim, bold = true)
                                inFolder.forEach { d ->
                                    val selected = d.id == selectedId
                                    GridText(
                                        fit("  ${d.name}".padEnd(left - 8) + "%4d".format(d.cardCount), left - 2),
                                        Modifier.clickTarget(ClickTarget.Control("deck:${d.id}"), onClick).onRightClick { at -> showDeck(d.id); menu = "deck:${d.id}" to at },
                                        color = if (selected) Palette.background else Palette.foreground,
                                        background = if (selected) Palette.foreground else Color.Unspecified,
                                    )
                                }
                            }
                        }
                    }
                    // Its right border is the handle.
                    PaneEdge(drag.leftEdge, "left-edge", Modifier.align(Alignment.CenterEnd))
                }
                val middle = cols - left - side
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
                                else DeckView(deck, keyFor, mode, middle - 2, onHover = { zoom = it }, points = pointsOf, aiFlags = aiFlags,
                                    onAiFlag = { card -> lookup?.intent?.invoke(LibraryIntent.AiSubstitute(deck.id, card)) })
                            }
                        }
                    }
                }
                Box(Modifier.cellWidth(side).fillMaxHeight().region("library-zoom")) {
                    ZoomPane(lookup?.hoverFace ?: zoom, side, imageRows = 20, textMode = mode == CardMode.TEXT, modifier = Modifier.fillMaxWidth())
                    PaneEdge(drag.rightEdge, "right-edge", Modifier.align(Alignment.CenterStart))
                }
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
            val hints = if (lookup?.command?.focused == true) TYPING_HINTS
            else listOfNotNull(":" to "command", ("Tab" to "deck/output").takeIf { lookup != null }, "↑↓" to "deck", "Enter / double-click" to "open", "P" to "play", "T" to "text/art", "I" to "fetch images", "Q" to "quit")
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
            "export as a package (.mtgoracle)" to { intent(LibraryIntent.ExportPackage(PackageScope.Deck(id))) },
            "export the whole library as a package" to { intent(LibraryIntent.ExportPackage(PackageScope.Library)) },
            "delete..." to { intent(LibraryIntent.DeleteDeck(id)) },
        ) else listOf(
            "new deck here..." to { intent(LibraryIntent.NewDeck(id)) },
            "import a deck here..." to { intent(LibraryIntent.Import(id)) },
            "default format..." to { intent(LibraryIntent.FolderFormat(id)) },
            "export the folder as a package (.mtgoracle)" to { intent(LibraryIntent.ExportPackage(PackageScope.Folder(id))) },
            "export the whole library as a package" to { intent(LibraryIntent.ExportPackage(PackageScope.Library)) },
            "delete folder..." to { intent(LibraryIntent.DeleteFolder(id)) },
        )
        val title = if (what.startsWith("deck:")) decks.firstOrNull { it.id == id }?.name.orEmpty() else folders.firstOrNull { it.id == id }?.name.orEmpty()
        ContextMenu(title, items, at) { menu = null }
    }
    LaunchedEffect(Unit) { startFocus(lookup, focus, commandFocus) }
}

/** The status line's hints while the command line has the keyboard. */
internal val TYPING_HINTS = listOf("Enter" to "run", "↑↓" to "history", "Tab" to "autofill", "PgUp/PgDn" to "scroll", "Esc" to "leave")

