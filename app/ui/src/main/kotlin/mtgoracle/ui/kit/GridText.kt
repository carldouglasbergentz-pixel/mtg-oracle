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

/**
 * [text] as exactly [width] cells: guarded, flattened, padded or cut with `…`.
 * A cut drops what it leaves dangling (a space, ` —`, ` ·`), so `Creature —…`
 * reads `Creature…` and `Bilbo, Thief …` reads `Bilbo, Thief…`.
 */
fun fit(text: String, width: Int): String {
    val flat = GlyphGuard.safe(text.replace('\n', ' '))
    return when {
        width <= 0 -> ""
        flat.length <= width -> flat.padEnd(width)
        else -> (flat.take(width - 1).trimEnd(*DANGLING).ifEmpty { flat.take(width - 1) } + "…").padEnd(width)
    }
}

/** What a cut must not end on: the space, dash or separator that pointed at the words cut off. */
private val DANGLING = charArrayOf(' ', '—', '–', '-', '·', ',', ':', '/', '|')

/**
 * Word-wrapped to [width] cells; a word longer than a line (a file path) is broken across lines, not cut.
 * A ` · ` between parts is dropped where the line breaks: the break sets them apart, and a line
 * ending on a lone `·` read as cut.
 */
fun wrap(text: String, width: Int): List<String> {
    if (width <= 0) return emptyList()
    return text.split('\n').flatMap { paragraph ->
        val safe = GlyphGuard.safe(paragraph)
        if (safe.length <= width) listOf(safe) else buildList {
            var line = StringBuilder()
            val words = safe.split(' ').flatMap { it.chunked(width).ifEmpty { listOf("") } }
            for ((i, word) in words.withIndex()) {
                if (word == SEPARATOR && (line.isEmpty() || line.length + 3 + words.getOrNull(i + 1).orEmpty().length > width)) {
                    if (line.isNotEmpty()) { add(line.toString()); line = StringBuilder() }
                    continue
                }
                if (line.isNotEmpty() && line.length + 1 + word.length > width) { add(line.toString()); line = StringBuilder() }
                if (line.isNotEmpty()) line.append(' ')
                line.append(word)
            }
            if (line.isNotEmpty()) add(line.toString())
        }
    }
}

/** What the parts of a hint line are set apart with: `Enter: keep · Esc: back`. */
const val SEPARATOR = "·"

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
    // A separator the break fell on goes with it, as in [wrap].
    val rest = body.drop(first.length).trimStart().removePrefix(SEPARATOR).trimStart()
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
    // In the order the texts were first drawn, so a question wrapped over lines reads in sequence (StagedGame.flatText).
    private val live = LinkedHashMap<Any, String>()
    internal fun put(key: Any, text: String) { synchronized(live) { live[key] = text } }
    internal fun remove(key: Any) { synchronized(live) { live.remove(key) } }
    fun all(): String = synchronized(live) { live.values.joinToString(separator = "\n") }
}

val LocalTextRecorder = androidx.compose.runtime.staticCompositionLocalOf<TextRecorder?> { null }

@Composable
fun RecordText(text: String) {
    val recorder = LocalTextRecorder.current ?: return
    val key = androidx.compose.runtime.remember { Any() }
    androidx.compose.runtime.SideEffect { recorder.put(key, text) }
    androidx.compose.runtime.DisposableEffect(recorder, key) { onDispose { recorder.remove(key) } }
}
