package mtgoracle.ui.kit

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import mtgoracle.core.art.ArtKind
import mtgoracle.ui.theme.Palette

/**
 * The card under the cursor, as MTGO shows it: the full card image — whole,
 * scaled to fit, copyright and artist lines intact — and its text beneath:
 * name and cost, type, rules text, power/toughness, in that order. Without
 * an image (text mode, or not fetched yet) the text alone, from the top.
 */
@Composable
fun ZoomPane(face: CardFace?, cols: Int, imageRows: Int, textMode: Boolean, modifier: Modifier = Modifier) {
    // A fixed height: the image area, then name, type, text and stats; without an image the text has the image's rows too.
    val textRows = 6
    BoxPane("card", modifier.cellHeight(imageRows + textRows + 3 + 2)) {
        val inner = cols - 2
        Column {
            if (face == null) { GridText(fit("hover a card to see it here", inner), color = Palette.dim); return@Column }
            val bitmap = if (textMode) null else rememberArt(face.imageKey, ArtKind.FULL)
            if (bitmap != null) Box(Modifier.cells(inner, imageRows)) {
                Image(bitmap, contentDescription = face.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().cells(inner, imageRows))
            }
            GridText(fit("${face.name}  ${face.manaCost}", inner), bold = true)
            GridText(fit(face.typeLine, inner))
            cutText(face.text, inner, if (bitmap != null) textRows else imageRows + textRows).forEach { GridText(it, color = Palette.dim) }
            if (face.stats.isNotEmpty()) GridText(fit(face.stats, inner))
        }
    }
}

/** [text] wrapped to [width] in at most [rows] lines, the last ending in `…` when some is left out. */
internal fun cutText(text: String, width: Int, rows: Int): List<String> {
    val lines = wrap(text, width)
    if (lines.size <= rows || rows <= 0) return lines.take(maxOf(0, rows)).map { fit(it, width) }
    // fit cuts a full last line to make room for the mark; a shorter one just takes it.
    return lines.take(rows - 1).map { fit(it, width) } + fit(lines[rows - 1].trimEnd() + "…", width)
}

/** The bottom line: key hints, then a message (the active yield, a warning). */
@Composable
fun StatusLine(hints: List<Pair<String, String>>, message: String?, cols: Int, modifier: Modifier = Modifier, warning: String? = null) {
    val all = (hints + mtgoracle.ui.theme.LocalGlobalHints.current).map { (key, what) -> "$key $what" }
    if (warning != null) {
        // A warning takes the line over, in the accent: something decided for you, and you should know.
        GridText(fit(" ! $warning", cols), modifier = modifier.statusPanel(), color = Palette.accent, bold = true)
        return
    }
    // The message has its room first: the board's hints run to ~170 cells and hid the active yield.
    val right = fit(message.orEmpty(), minOf(message.orEmpty().length, maxOf(0, cols - 4))).trimEnd()
    val hints = wholeHints(all, maxOf(0, cols - right.length - 3))
    val gap = maxOf(1, cols - hints.length - right.length - 2)
    GridText(fit(" $hints" + " ".repeat(gap) + right, cols), modifier = modifier.statusPanel(), color = Palette.surfaceText)
}

/** As many of [hints] as fit [room], whole, the last ones dropped: half a hint ("Ctrl+=/- si…") says nothing. */
internal fun wholeHints(hints: List<String>, room: Int): String {
    var shown = hints
    while (shown.isNotEmpty() && shown.joinToString("  ").length > room) shown = shown.dropLast(1)
    return shown.joinToString("  ")
}
