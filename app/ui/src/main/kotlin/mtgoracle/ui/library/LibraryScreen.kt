package mtgoracle.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import mtgoracle.core.deck.Deck
import mtgoracle.core.deck.DeckCard
import mtgoracle.core.deck.DeckSummary
import mtgoracle.core.deck.Section
import mtgoracle.ui.board.SIDE_COLS
import mtgoracle.ui.kit.BoxPane
import mtgoracle.ui.kit.CardFace
import mtgoracle.ui.kit.CardFrame
import mtgoracle.ui.kit.CardMode
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.GridText
import mtgoracle.ui.kit.StatusLine
import mtgoracle.ui.kit.ZoomPane
import mtgoracle.ui.kit.cellWidth
import mtgoracle.ui.kit.cells
import mtgoracle.ui.kit.clickTarget
import mtgoracle.ui.kit.face
import mtgoracle.ui.kit.fit
import mtgoracle.ui.lookup.CommandLine
import mtgoracle.ui.lookup.LookupUi
import mtgoracle.ui.lookup.OutputLink
import mtgoracle.ui.lookup.OutputPane
import mtgoracle.ui.theme.LocalCells
import mtgoracle.ui.theme.Palette
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.utf16CodePoint
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput

const val DECK_LIST_COLS = 40

/** The order the text-mode deck view groups by, front face's type deciding. */
private val TYPE_ORDER = listOf("Creature", "Planeswalker", "Battle", "Instant", "Sorcery", "Artifact", "Enchantment", "Land")

fun primaryType(card: DeckCard): String {
    val front = card.info?.typeLine?.split(" // ")?.first().orEmpty()
    return TYPE_ORDER.firstOrNull { front.contains(it) } ?: "Other"
}

/**
 * Folders and decks on the left, the selected deck in the middle (art frames
 * or text lines, T toggles), the zoom pane on the right, and the command line
 * at the bottom. A command shows its output in the middle instead of the
 * deck; Tab switches back. Decks are read-only here: the TUI edits them.
 *
 * The screen's own keys (arrows, T, Q...) only act while the command line
 * does not have the keyboard, so typing `quit` never quits on its `q`.
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
) {
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
            name == "play" -> onPlay()
            name == "mode" -> onToggleMode()
            name == "prefetch" -> onPrefetch()
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
            if (e.isCtrlPressed && e.key == Key.L && lookup != null) { lookup.output.clear(); return@onPreviewKeyEvent true }
            // Seen here before the command line: while it has the keyboard, every other key is its.
            if (lookup?.command?.focused == true) return@onPreviewKeyEvent false
            if (lookup != null && (e.utf16CodePoint == ':'.code || (e.isCtrlPressed && e.key == Key.K))) {
                lookup.command.openedBy(if (e.isCtrlPressed) null else ':')
                commandFocus.requestFocus()
                return@onPreviewKeyEvent true
            }
            when (e.key) {
                Key.Tab -> if (lookup != null) lookup.showOutput = !lookup.showOutput else return@onPreviewKeyEvent false
                Key.PageUp -> page(-1)
                Key.PageDown -> page(+1)
                Key.DirectionUp -> move(-1)
                Key.DirectionDown -> move(+1)
                Key.Enter, Key.P -> onPlay()
                Key.T -> onToggleMode()
                Key.I -> onPrefetch()
                Key.Q -> onQuit()
                else -> return@onPreviewKeyEvent false
            }
            true
        },
    ) {
        val cols = LocalCells.current.cols(constraints.maxWidth.toFloat())
        val currentLookup by rememberUpdatedState(lookup)
        Column(Modifier.fillMaxSize()) {
            // A press anywhere in the panes ends typing, as clicking outside a text field does,
            // so T and the arrows are the screen's again. Seen first (Initial), never consumed.
            Row(Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        if (e.type == PointerEventType.Press && currentLookup?.command?.focused == true) focus.requestFocus()
                    }
                }
            }) {
                BoxPane("decks", Modifier.cellWidth(DECK_LIST_COLS).fillMaxHeight()) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        var folder: String? = "\u0000"
                        decks.forEach { d ->
                            if (d.folderName != folder) {
                                folder = d.folderName
                                GridText(fit(d.folderName ?: "(no folder)", DECK_LIST_COLS - 2), color = Palette.dim, bold = true)
                            }
                            val selected = d.id == selectedId
                            GridText(
                                fit("  ${d.name}".padEnd(DECK_LIST_COLS - 8) + "%4d".format(d.cardCount), DECK_LIST_COLS - 2),
                                Modifier.clickTarget(ClickTarget.Control("deck:${d.id}"), onClick),
                                color = if (selected) Palette.background else Palette.foreground,
                                background = if (selected) Palette.foreground else androidx.compose.ui.graphics.Color.Unspecified,
                            )
                        }
                    }
                }
                val middle = cols - DECK_LIST_COLS - SIDE_COLS
                val right = deck?.let { d ->
                    listOfNotNull(d.format, "${d.mainCount} cards", d.substitutions.takeIf { it.isNotEmpty() }?.let { "AI copy: ${it.size} substitutions" }).joinToString(" · ")
                }
                if (lookup?.showOutput == true) {
                    BoxPane("output", Modifier.weight(1f).fillMaxHeight(), right = "Tab: ${deck?.name ?: "deck"}") {
                        OutputPane(lookup.output, outputScroll, onOpen = lookup.open,
                            onHover = { link -> (link as? OutputLink.Card)?.let { lookup.face(it.name) }?.let { zoom = it } })
                    }
                } else {
                    BoxPane(deck?.name ?: "no deck selected", Modifier.weight(1f).fillMaxHeight(), right = right) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            if (deck == null) GridText("Pick a deck on the left.", color = Palette.dim)
                            else DeckView(deck, keyFor, mode, middle - 2, onHover = { zoom = it })
                        }
                    }
                }
                ZoomPane(zoom, SIDE_COLS, imageRows = 20, textMode = mode == CardMode.TEXT, modifier = Modifier.cellWidth(SIDE_COLS).fillMaxHeight())
            }
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
            Row {
                listOf("play" to "[ Play ]", "mode" to if (mode == CardMode.ART) "[ Text ]" else "[ Art ]", "prefetch" to "[ Fetch images ]").forEach { (name, label) ->
                    GridText(label, Modifier.clickTarget(ClickTarget.Control(name), onClick), color = Palette.background, background = Palette.foreground)
                    GridText("  ")
                }
            }
            val hints = if (lookup?.command?.focused == true) listOf("Enter" to "run", "↑↓" to "history", "Tab" to "autofill", "PgUp/PgDn" to "scroll", "Esc" to "leave")
            else listOfNotNull(":" to "command", ("Tab" to "deck/output").takeIf { lookup != null }, "↑↓" to "deck", "Enter" to "play", "T" to "text/art", "I" to "fetch images", "Q" to "quit")
            StatusLine(hints, notice, cols)
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeckView(deck: Deck, keyFor: (DeckCard) -> String?, mode: CardMode, cols: Int, onHover: (CardFace) -> Unit) {
    val gap = with(LocalDensity.current) { LocalCells.current.width.toDp() }
    for (section in Section.entries) {
        val cards = deck.cards.filter { it.section == section }
        if (cards.isEmpty()) continue
        val groups = cards.groupBy { if (section == Section.MAIN) primaryType(it) else section.name.lowercase().replaceFirstChar { c -> c.uppercase() } }
        val order = (listOf("Commander", "Sideboard") + TYPE_ORDER + "Other")
        for ((group, groupCards) in groups.entries.sortedBy { order.indexOf(it.key) }) {
            GridText(fit("─ $group (${groupCards.sumOf { it.quantity }})", cols), color = Palette.dim, bold = true)
            if (mode == CardMode.TEXT) {
                groupCards.forEachIndexed { i, card ->
                    val face = card.face(keyFor(card))
                    val printing = card.setCode?.let { " (${it.uppercase()}) ${card.collectorNumber.orEmpty()}" }.orEmpty()
                    GridText(fit("%2d %-32s %-10s %s".format(card.quantity, card.name, face.manaCost, face.typeLine) + printing, cols),
                        Modifier.clickTarget(ClickTarget.Control("card:${section}:$group:$i"), {}) { onHover(face) })
                }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    groupCards.forEachIndexed { i, card ->
                        val face = card.face(keyFor(card))
                        CardFrame(face, mode, target = ClickTarget.Control("card:${section}:$group:$i"), onHover = { onHover(face) })
                    }
                }
            }
        }
    }
}
