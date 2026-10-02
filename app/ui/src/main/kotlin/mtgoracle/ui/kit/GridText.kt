package mtgoracle.ui.kit

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import mtgoracle.ui.theme.GlyphGuard
import mtgoracle.ui.theme.LocalCells
import mtgoracle.ui.theme.Palette
import mtgoracle.ui.theme.gridStyle

/** [text] as exactly [width] cells: guarded, flattened, padded or cut with `…`. */
fun fit(text: String, width: Int): String {
    val flat = GlyphGuard.safe(text.replace('\n', ' '))
    return when {
        width <= 0 -> ""
        flat.length <= width -> flat.padEnd(width)
        else -> flat.take(width - 1) + "…"
    }
}

/** Word-wrapped to [width] cells; a word longer than a line (a file path) is broken across lines, not cut. */
fun wrap(text: String, width: Int): List<String> {
    if (width <= 0) return emptyList()
    return text.split('\n').flatMap { paragraph ->
        val safe = GlyphGuard.safe(paragraph)
        if (safe.length <= width) listOf(safe) else buildList {
            var line = StringBuilder()
            for (word in safe.split(' ').flatMap { it.chunked(width).ifEmpty { listOf("") } }) {
                if (line.isNotEmpty() && line.length + 1 + word.length > width) { add(line.toString()); line = StringBuilder() }
                if (line.isNotEmpty()) line.append(' ')
                line.append(word)
            }
            if (line.isNotEmpty()) add(line.toString())
        }
    }
}

/**
 * [wrap], keeping [text]'s leading spaces on every line and indenting the
 * lines after the first by [hang] more, so `  [x] watch: …` continues under
 * `watch` and not under the box.
 */
fun wrapHanging(text: String, width: Int, hang: Int = 0): List<String> {
    val lead = " ".repeat(text.takeWhile { it == ' ' }.length)
    // Guarded before it is wrapped, so the first line is a prefix of it: a glyph the guard
    // replaced made `removePrefix` miss and the first line come out twice.
    val body = GlyphGuard.safe(text.trimStart())
    val first = wrap(body, maxOf(1, width - lead.length)).firstOrNull() ?: return emptyList()
    val rest = body.drop(first.length).trimStart()
    if (rest.isEmpty()) return listOf(lead + first)
    val indent = lead + " ".repeat(hang)
    return listOf(lead + first) + wrap(rest, maxOf(1, width - indent.length)).map { indent + it }
}

/** The cells [constraints] give across, or null where the width is unbounded (a sideways scroll). */
private fun boundedCols(maxWidth: Int, hasBoundedWidth: Boolean, cells: mtgoracle.ui.theme.Cells): Int? =
    if (hasBoundedWidth) cells.cols(maxWidth.toFloat()) else null

/**
 * One line cut with `…` only where the space it is given really ends. The
 * width is measured at layout, never a constant: a screen that assumed 110
 * columns cut its lines with half a 1920-wide window empty beside them.
 */
@Composable
fun FitText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Palette.foreground,
    background: Color = Color.Unspecified,
    bold: Boolean = false,
) {
    BoxWithConstraints(modifier) {
        val cols = boundedCols(constraints.maxWidth, constraints.hasBoundedWidth, LocalCells.current)
        GridText(if (cols == null) text else fit(text, cols), color = color, background = background, bold = bold)
    }
}

/**
 * Instructions, word-wrapped to the measured width rather than cut: a help
 * line that loses its end loses what the key does. [hang] indents the
 * continuation lines (see [wrapHanging]).
 */
@Composable
fun WrapText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Palette.foreground,
    bold: Boolean = false,
    hang: Int = 0,
) {
    BoxWithConstraints(modifier) {
        val cols = boundedCols(constraints.maxWidth, constraints.hasBoundedWidth, LocalCells.current)
        Column {
            if (cols == null) GridText(text, color = color, bold = bold)
            else wrapHanging(text, cols, hang).forEach { GridText(fit(it, cols), color = color, bold = bold) }
        }
    }
}

/** A size in grid cells. */
@Composable
fun Modifier.cells(cols: Int, rows: Int): Modifier {
    val cells = LocalCells.current
    val density = LocalDensity.current
    return with(density) { this@cells.size((cols * cells.width).toDp(), (rows * cells.height).toDp()) }
}

/** A width in grid cells; the height is left to the layout. */
@Composable
fun Modifier.cellWidth(cols: Int): Modifier {
    val cells = LocalCells.current
    return with(LocalDensity.current) { this@cellWidth.width((cols * cells.width).toDp()) }
}

/** A height in grid rows; the width is left to the layout. */
@Composable
fun Modifier.cellHeight(rows: Int): Modifier {
    val cells = LocalCells.current
    return with(LocalDensity.current) { this@cellHeight.height((rows * cells.height).toDp()) }
}

/** One line of grid text; everything drawn on the grid goes through here or [fit]. */
@Composable
fun GridText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Palette.foreground,
    background: Color = Color.Unspecified,
    bold: Boolean = false,
) {
    RecordText(text)
    BasicText(
        text = GlyphGuard.safe(text),
        modifier = modifier,
        style = gridStyle.copy(color = color, background = background, fontWeight = if (bold) FontWeight.Bold else null),
        softWrap = false,
        maxLines = 1,
        overflow = TextOverflow.Clip,
    )
}

@Composable
fun GridText(text: AnnotatedString, modifier: Modifier = Modifier) {
    RecordText(text.text)
    BasicText(text = text, modifier = modifier, style = gridStyle, softWrap = false, maxLines = 1, overflow = TextOverflow.Clip)
}


/**
 * Everything drawn as text right now, when a test asks for it (the offscreen
 * driver provides one): how the hidden-information tests prove a name was
 * never on screen. Null, and free, in the window.
 */
class TextRecorder {
    private val live = java.util.concurrent.ConcurrentHashMap<Any, String>()
    internal fun put(key: Any, text: String) { live[key] = text }
    internal fun remove(key: Any) { live.remove(key) }
    fun all(): String = live.values.joinToString(separator = "\n")
}

val LocalTextRecorder = androidx.compose.runtime.staticCompositionLocalOf<TextRecorder?> { null }

@Composable
fun RecordText(text: String) {
    val recorder = LocalTextRecorder.current ?: return
    val key = androidx.compose.runtime.remember { Any() }
    androidx.compose.runtime.SideEffect { recorder.put(key, text) }
    androidx.compose.runtime.DisposableEffect(recorder, key) { onDispose { recorder.remove(key) } }
}
