package mtgoracle.ui.board

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.drop
import mtgoracle.core.model.LogKind
import mtgoracle.core.model.LogLine
import mtgoracle.ui.kit.BoxPane
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.GridText
import mtgoracle.ui.kit.RuleLine
import mtgoracle.ui.kit.cells
import mtgoracle.ui.kit.clickTarget
import mtgoracle.ui.theme.GlyphGuard
import mtgoracle.ui.theme.LocalCells
import mtgoracle.ui.theme.Palette

/** A wrapped row of a log line: its text, and where its cards stand in it (index into the line's cards). */
internal data class LogRow(val text: String, val spans: List<RowSpan>)
internal data class RowSpan(val start: Int, val end: Int, val card: Int)

/** Lines after a line's first start this far in, under its text. */
private const val HANG = 2

/**
 * [line] wrapped to [width] at spaces, its continuation rows indented, each
 * card's place carried into the rows it falls in (a name the wrap splits is
 * in both).
 */
internal fun logRows(line: LogLine, width: Int): List<LogRow> {
    val text = line.text
    val rows = mutableListOf<LogRow>()
    var pos = 0
    while (pos < text.length || rows.isEmpty()) {
        val indent = if (rows.isEmpty()) 0 else HANG
        val room = maxOf(1, width - indent)
        // Forge writes some lines as several (a block per attacker): each starts a row, since a row draws one line.
        val newline = text.indexOf('\n', pos).takeIf { it >= 0 && it - pos <= room }
        val cut = when {
            newline != null -> newline
            text.length - pos <= room -> text.length
            else -> text.lastIndexOf(' ', pos + room).takeIf { it > pos } ?: (pos + room)
        }
        val spans = line.cards.mapIndexedNotNull { i, c ->
            val start = maxOf(c.start, pos)
            val end = minOf(c.end, cut)
            if (start < end) RowSpan(start - pos + indent, end - pos + indent, i) else null
        }
        rows += LogRow(" ".repeat(indent) + text.substring(pos, cut), spans)
        pos = cut
        while (pos < text.length && (text[pos] == ' ' || text[pos] == '\n')) pos++
    }
    return rows
}

/** A line's colour by what it tells: steps and mana recede, what was taken or revealed stands out, damage and life lost in the tapped tone. */
private fun colorOf(kind: LogKind): Color = when (kind) {
    LogKind.PHASE, LogKind.MANA, LogKind.DRAW, LogKind.OTHER -> Palette.dim
    LogKind.CAST, LogKind.RESOLVE, LogKind.LAND, LogKind.COMBAT, LogKind.LIFE_GAINED -> Palette.foreground
    LogKind.REVEAL, LogKind.DISCARD, LogKind.COUNTERED, LogKind.ZONE, LogKind.OUTCOME, LogKind.TURN -> Palette.accent
    LogKind.DAMAGE, LogKind.LIFE_LOST -> Palette.tapped
}

/**
 * The match's play-by-play, whole, with a scrollbar; without the steps of
 * each turn unless [steps]. It follows the newest
 * line while it is scrolled to the bottom; scrolled up, it stays where it was
 * read. A card named in a line is bold, and hovering it puts it in the zoom
 * pane ([onHover] with a [ClickTarget.LogCard]).
 */
@Composable
internal fun LogPane(log: List<LogLine>, cols: Int, onHover: (ClickTarget?) -> Unit, modifier: Modifier, steps: Boolean = true) {
    // One column for the scrollbar, beside the border's two.
    val inner = maxOf(8, cols - 3)
    val state = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(state) {
        // A scroll the reader ended at the bottom follows again; one that ended above stays. Not the first
        // value: before the first scroll down the list is at its top, and reading that stopped the following.
        snapshotFlow { state.isScrollInProgress }.drop(1).collect { scrolling -> if (!scrolling) follow = !state.canScrollForward }
    }
    val shown = remember(log, steps) { if (steps) log else log.filter { it.kind != LogKind.PHASE } }
    LaunchedEffect(shown.size) { if (follow && shown.isNotEmpty()) state.scrollToItem(shown.lastIndex) }
    BoxPane("log", modifier, right = if (steps) null else "events only") {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                items(shown, key = { it.seq }) { line ->
                    if (line.kind == LogKind.TURN) RuleLine(inner, label = line.text, color = Palette.accent, bold = true)
                    else remember(line, inner) { logRows(line, inner) }.forEach { row -> LogRowView(line, row, onHover) }
                }
            }
            val cells = LocalCells.current
            val thickness = with(LocalDensity.current) { (cells.width * 0.6f).toDp() }
            VerticalScrollbar(
                rememberScrollbarAdapter(state),
                Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                style = ScrollbarStyle(minimalHeight = 16.dp, thickness = thickness, shape = RectangleShape, hoverDurationMillis = 150,
                    unhoverColor = Palette.dim.copy(alpha = 0.5f), hoverColor = Palette.accent),
            )
        }
    }
}

/**
 * One row as one text (so a test reads it whole), its cards bold, with a
 * hover target laid over each card. Glyphs are guarded piece by piece: a
 * stand-in may be longer than what it stands for, and the targets follow.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun LogRowView(line: LogLine, row: LogRow, onHover: (ClickTarget?) -> Unit) {
    val base = colorOf(line.kind)
    // A name stands out of its line: full colour on a dim line, bold always.
    val nameColor = if (base == Palette.dim) Palette.foreground else base
    var hovered by remember { mutableIntStateOf(-1) }
    val pieces = buildList {
        var at = 0
        row.spans.sortedBy { it.start }.forEachIndexed { i, span ->
            if (span.start > at) add(GlyphGuard.safe(row.text.substring(at, span.start)) to -1)
            add(GlyphGuard.safe(row.text.substring(span.start, span.end)) to i)
            at = span.end
        }
        if (at < row.text.length || isEmpty()) add(GlyphGuard.safe(row.text.substring(at)) to -1)
    }
    val text: AnnotatedString = buildAnnotatedString {
        pieces.forEach { (piece, span) ->
            if (span < 0) withStyle(SpanStyle(color = base)) { append(piece) }
            else withStyle(SpanStyle(color = nameColor, fontWeight = FontWeight.Bold, textDecoration = if (span == hovered) TextDecoration.Underline else null)) { append(piece) }
        }
    }
    val cells = LocalCells.current
    Box {
        GridText(text)
        var column = 0
        pieces.forEach { (piece, span) ->
            if (span >= 0) {
                val card = row.spans.sortedBy { it.start }[span]
                val target = ClickTarget.LogCard(line.seq, card.card, card.start)
                // Read now: the offset is placed after the loop has moved [column] on to the row's end.
                val x = column
                Box(
                    Modifier.offset { IntOffset((x * cells.width).toInt(), 0) }.cells(piece.length, 1)
                        .clickTarget(target, {}, { hovered = span; onHover(target) }, mark = false) // over the text: the underline marks it
                        .onPointerEvent(PointerEventType.Exit) { hovered = -1 }
                        .pointerHoverIcon(PointerIcon.Hand),
                )
            }
            column += piece.length
        }
    }
}
