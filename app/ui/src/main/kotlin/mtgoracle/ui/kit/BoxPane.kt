package mtgoracle.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import kotlin.math.roundToInt
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import mtgoracle.ui.theme.Cells
import mtgoracle.ui.theme.LocalCells
import mtgoracle.ui.theme.Palette
import mtgoracle.ui.theme.gridStyle

/** Line sets for box borders: the single frame, and the double one that marks a legal pick. */
enum class Border(val tl: Char, val h: Char, val tr: Char, val v: Char, val bl: Char, val br: Char) {
    SINGLE('┌', '─', '┐', '│', '└', '┘'),
    DOUBLE('╔', '═', '╗', '║', '╚', '╝'),
    /** A tapped card: dashed edges (with the frame shifted a cell, as if turned). */
    DASHED('┌', '┄', '┐', '┆', '└', '┘'),
}

/**
 * The whole cells in [px], with a pixel's grace: a pane of 30 cells of 7.32
 * is laid out in 219 pixels, not 219.6, and counted strictly it lost a
 * column inside (the stop ladder's right column was clipped: `1st strik`).
 */
internal fun wholeCells(px: Int, cell: Float): Int = wholeCells(px.toFloat(), cell)
internal fun wholeCells(px: Float, cell: Float): Int = ((px + 1f) / cell).toInt()

/**
 * Snaps this element's size down to whole cells, so borders drawn in
 * characters meet exactly at the corners.
 */
@Composable
fun Modifier.snapToCells(): Modifier {
    val cells = LocalCells.current
    return layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        // Fixed constraints (fillMax...) can't be snapped; the border then ends on the last whole cell.
        val width = maxOf(constraints.minWidth, (wholeCells(placeable.width, cells.width) * cells.width).toInt())
        val height = maxOf(constraints.minHeight, (wholeCells(placeable.height, cells.height) * cells.height).toInt())
        layout(minOf(width, placeable.width), minOf(height, placeable.height)) { placeable.place(0, 0) }
    }
}

/**
 * The top edge: `┌─ Title ── badge ┐`. The title gets every cell the badge
 * doesn't need — corners, the `─ ` lead, one space after it, the badge and
 * its space — so a narrow land frame still reads `┌─ Mountain ×3 ┐`.
 */
private fun titleOf(title: String?, cols: Int, right: String?): String? =
    title?.let { fit(it, maxOf(0, cols - 2 - 2 - 1 - (right?.let { r -> r.length + 1 } ?: 1))).trimEnd() }

/**
 * The badge leaves the title half the edge, or what it needs of half, as the drawn looks do: a long badge hid
 * a deck's name. A badge of parts (`EDH · 99 + commander · {B}{G}`) that must shrink drops whole parts from its end.
 */
internal fun badgeOf(right: String?, cols: Int, title: String?): String? = right?.let {
    val room = maxOf(0, cols - 6)
    var parts = it.split(" · ")
    // Its first part whole, though, where the edge has room: a frame's `(3) AI!`.
    val width = minOf(room, maxOf(room - minOf(title?.length ?: 0, room / 2), parts.first().length))
    while (parts.size > 1 && parts.joinToString(" · ").length > width) parts = parts.dropLast(1)
    fit(parts.joinToString(" · "), width).trimEnd()
}

private fun topEdge(border: Border, cols: Int, title: String?, right: String?): String = buildString {
    val badge = badgeOf(right, cols, title)
    val head = titleOf(title, cols, badge)?.let { "${border.h} $it " }.orEmpty()
    // The badge stands off the line as the title does (`── badge ┐`) where a line is left between them;
    // a frame the title fills keeps it tight (`Mountain ×3 ┐`), the title's own space before it.
    val spaced = badge?.let { " $it " }
    val tail = when {
        spaced == null -> ""
        cols - 2 - head.length - spaced.length >= 1 -> spaced
        else -> "$badge "
    }
    append(border.tl)
    append(head)
    append(border.h.toString().repeat(maxOf(0, cols - 2 - head.length - tail.length)))
    append(tail)
    append(border.tr)
}

@Composable
fun Modifier.boxBorder(title: String? = null, right: String? = null, border: Border = Border.SINGLE, color: Color = Palette.dim, titleColor: Color = Palette.foreground): Modifier {
    val cells = LocalCells.current
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val style: TextStyle = gridStyle.copy(color = color)
    val titleStyle = gridStyle.copy(color = titleColor, fontWeight = FontWeight.Bold)
    title?.let { RecordText(it) }
    right?.let { RecordText(it) }
    Palette.chrome?.let { return drawnBorder(it, title, right, titleBar(border, color)) }
    return drawWithCache {
        val cols = wholeCells(size.width, cells.width)
        val rows = wholeCells(size.height, cells.height)
        val top = measurer.measure(topEdge(border, cols, title, right), style)
        // The title is drawn again over the edge in its own (bold) style.
        val titleLayout = titleOf(title, cols, badgeOf(right, cols, title))?.let { measurer.measure(it, titleStyle) }
        val bottom = measurer.measure(border.bl + border.h.toString().repeat(maxOf(0, cols - 2)) + border.br, style)
        val side = measurer.measure(border.v.toString(), style)
        onDrawWithContent {
            drawContent()
            if (cols < 2 || rows < 2) return@onDrawWithContent
            drawText(top, topLeft = Offset.Zero)
            titleLayout?.let { drawText(it, topLeft = Offset(3 * cells.width, 0f)) }
            for (r in 1 until rows - 1) {
                drawText(side, topLeft = Offset(0f, r * cells.height))
                drawText(side, topLeft = Offset((cols - 1) * cells.width, r * cells.height))
            }
            drawText(bottom, topLeft = Offset(0f, (rows - 1) * cells.height))
        }
    }
}

/**
 * The title bar a drawn chrome gives what the character border says with its
 * lines and colour: a double line or the accent has the user's attention, a
 * dashed line or the tapped tone is a tapped card (or a crash).
 */
private fun titleBar(border: Border, color: Color): TitleBar = when {
    border == Border.DASHED || color == Palette.tapped -> TitleBar.TAPPED
    border == Border.DOUBLE || color == Palette.accent -> TitleBar.ACTIVE
    else -> TitleBar.INACTIVE
}

/** A titled pane: a character border one cell wide (or a drawn chrome in the same cells), content inside it. */
@Composable
fun BoxPane(
    title: String?,
    modifier: Modifier = Modifier,
    right: String? = null,
    border: Border = Border.SINGLE,
    borderColor: Color = Palette.dim,
    content: @Composable () -> Unit,
) {
    val cells = LocalCells.current
    Box(modifier.snapToCells().chromeShape().background(Palette.background).boxBorder(title, right, border, borderColor)) {
        // Clipped: a line wider than the pane (a wide table) stops at the border instead of running into the next pane.
        Box(Modifier.insideBorder(cells).clipToBounds()) { content() }
    }
}

/**
 * The content's room: the whole cells between the border's rows and columns.
 * The border sits on the last WHOLE cell, so a pane with a fraction of a cell
 * left over (fillMaxHeight rarely lands on a whole row) gave its content that
 * fraction too, and the last line was drawn under the bottom edge.
 */
private fun Modifier.insideBorder(cells: Cells): Modifier = layout { measurable, constraints ->
    fun room(max: Int, cell: Float) = if (max == Constraints.Infinity) Constraints.Infinity else maxOf(0, ((wholeCells(max, cell) - 2) * cell).roundToInt())
    val maxWidth = room(constraints.maxWidth, cells.width)
    val maxHeight = room(constraints.maxHeight, cells.height)
    // No minimum: what fills the pane asks for the maximum, and the pane keeps its own size either way.
    val inner = Constraints(maxWidth = maxWidth, maxHeight = maxHeight)
    val placeable = measurable.measure(inner)
    val x = cells.width.roundToInt()
    val y = cells.height.roundToInt()
    layout(constraints.constrainWidth(placeable.width + 2 * x), constraints.constrainHeight(placeable.height + 2 * y)) { placeable.place(x, y) }
}
