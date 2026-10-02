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
 * scaled to fit, copyright and artist lines intact — and its text beneath.
 * Without an image (text mode, or not fetched yet) the text alone.
 */
@Composable
fun ZoomPane(face: CardFace?, cols: Int, imageRows: Int, textMode: Boolean, modifier: Modifier = Modifier) {
    // A fixed height: image area, then name, type, text and stats in reserved rows.
    val textRows = 6
    BoxPane("card", modifier.cellHeight(imageRows + textRows + 3 + 2)) {
        val inner = cols - 2
        Column {
            val bitmap = if (textMode || face == null) null else rememberArt(face.imageKey, ArtKind.FULL)
            Box(Modifier.cells(inner, imageRows)) {
                when {
                    face == null -> GridText(fit("hover a card to see it here", inner), color = Palette.dim)
                    bitmap != null -> Image(bitmap, contentDescription = face.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().cells(inner, imageRows))
                    else -> Column { wrap(face.text, inner).take(imageRows).forEach { GridText(fit(it, inner), color = Palette.dim) } }
                }
            }
            GridText(fit(face?.let { "${it.name}  ${it.manaCost}" }.orEmpty(), inner), bold = true)
            GridText(fit(face?.typeLine.orEmpty(), inner))
            Box(Modifier.cells(inner, textRows)) {
                Column { if (bitmap != null && face != null) wrap(face.text, inner).take(textRows).forEach { GridText(fit(it, inner), color = Palette.dim) } }
            }
            GridText(fit(face?.stats.orEmpty(), inner))
        }
    }
}

/** The bottom line: key hints, then a message (the active yield, a warning). */
@Composable
fun StatusLine(hints: List<Pair<String, String>>, message: String?, cols: Int, modifier: Modifier = Modifier, warning: String? = null) {
    val left = (hints + mtgoracle.ui.theme.LocalGlobalHints.current).joinToString("  ") { (key, what) -> "$key $what" }
    if (warning != null) {
        // A warning takes the line over, in the accent: something decided for you, and you should know.
        GridText(fit(" ! $warning", cols), modifier = modifier.statusPanel(), color = Palette.accent, bold = true)
        return
    }
    // The message has its room first and the hints are cut: the board's hints run to ~170 cells and hid the active yield.
    val right = fit(message.orEmpty(), minOf(message.orEmpty().length, maxOf(0, cols - 4))).trimEnd()
    val hints = fit(left, maxOf(0, cols - right.length - 3)).trimEnd()
    val gap = maxOf(1, cols - hints.length - right.length - 2)
    GridText(fit(" $hints" + " ".repeat(gap) + right, cols), modifier = modifier.statusPanel(), color = Palette.surfaceText)
}
