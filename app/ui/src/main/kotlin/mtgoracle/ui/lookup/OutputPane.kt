package mtgoracle.ui.lookup

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import mtgoracle.ui.kit.CardFace
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.GridText
import mtgoracle.ui.kit.clickTarget
import mtgoracle.ui.theme.GlyphGuard
import mtgoracle.ui.theme.LocalCells
import mtgoracle.ui.theme.Palette

/**
 * The scrollback, rendered for the width it has. A new command scrolls its
 * echo to the top, so a long answer (a card profile) reads from its start.
 * [onOpen] runs a clicked link; [onHover] hears which link the mouse is on
 * (the zoom pane shows a hovered card). With [grid], the newest search page
 * is drawn as cards ([SearchGrid]); older pages stay lines, so the
 * scrollback never holds hundreds of images.
 */
@Composable
fun OutputPane(
    log: OutputLog,
    listState: LazyListState,
    onOpen: (OutputLink) -> Unit,
    onHover: (OutputLink) -> Unit,
    modifier: Modifier = Modifier,
    grid: Boolean = false,
    selected: Int? = null,
    faceOf: (String) -> CardFace? = { null },
    /** The deck workspace: results carry `+ sb ?` and pointed cards their points. */
    actions: Boolean = false,
    points: (String) -> Int? = { null },
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val cols = LocalCells.current.cols(constraints.maxWidth.toFloat())
        SideEffect { log.width = cols }
        LaunchedEffect(log.entries.size) {
            val latest = log.entries.indexOfLast { it.isEcho }
            if (latest >= 0) listState.scrollToItem(latest)
        }
        LazyColumn(state = listState) {
            items(log.entries, key = { it.id }) { entry ->
                val lines = remember(entry.id, cols) { entry.rendering.lines(cols) }
                val page = entry.page
                if (grid && page != null && page.rows.isNotEmpty() && entry.id == log.latestSearch?.id) {
                    SearchGrid(page, lines, entry.id * 100_000, selected, faceOf, onOpen, onHover, actions, points)
                } else Column {
                    lines.forEachIndexed { i, line ->
                        // The selected row of the newest page, when it is drawn as lines: the arrows move it.
                        val row = i - 1
                        val chosen = page != null && entry.id == log.latestSearch?.id && row == selected
                        OutputLineView(if (chosen) line.copy(tone = Tone.ECHO) else line, entry.id * 100_000 + i * 100, onOpen, onHover)
                    }
                }
            }
        }
    }
}

private fun toneColor(tone: Tone): Color = when (tone) {
    Tone.PLAIN, Tone.BOLD -> Palette.foreground
    Tone.DIM -> Palette.dim
    Tone.ECHO -> Palette.accent
    Tone.ERROR -> Palette.tapped
}

private fun toneBold(tone: Tone) = tone == Tone.BOLD || tone == Tone.ERROR || tone == Tone.ECHO

/** One line: plain text between its links. [at] makes each link's click target unique on screen. */
@Composable
internal fun OutputLineView(line: OutLine, at: Long, onOpen: (OutputLink) -> Unit, onHover: (OutputLink) -> Unit) {
    val color = toneColor(line.tone)
    val bold = toneBold(line.tone)
    if (line.spans.isEmpty()) {
        GridText(line.text.ifEmpty { " " }, color = color, bold = bold)
        return
    }
    Row {
        var column = 0
        line.spans.sortedBy { it.start }.forEachIndexed { i, span ->
            if (span.start > column) GridText(line.text.substring(column, span.start), color = color, bold = bold)
            LinkText(line.text.substring(span.start, span.end), ClickTarget.Link(span.link, at + i), color, bold, onOpen, onHover)
            column = span.end
        }
        if (column < line.text.length) GridText(line.text.substring(column), color = color, bold = bold)
    }
}

/** A link: the line's own colour, underlined while the mouse is on it, a hand for a pointer. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun LinkText(text: String, target: ClickTarget.Link, color: Color, bold: Boolean, onOpen: (OutputLink) -> Unit, onHover: (OutputLink) -> Unit) {
    var hovered by remember { mutableStateOf(false) }
    val style = SpanStyle(color = color, fontWeight = if (bold) FontWeight.Bold else null, textDecoration = if (hovered) TextDecoration.Underline else null)
    GridText(
        AnnotatedString(GlyphGuard.safe(text), style),
        Modifier
            .clickTarget(target, { onOpen(target.link) }, { hovered = true; onHover(target.link) })
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .pointerHoverIcon(PointerIcon.Hand),
    )
}
