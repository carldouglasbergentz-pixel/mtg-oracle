package mtgoracle.ui.board

import androidx.compose.ui.geometry.Rect

/**
 * The viewer's own arrangement of the board — where they dragged the stack
 * box, whether they folded it — which the app persists. The board's
 * geometry itself is fixed; nothing here moves a card.
 */
data class BoardLayout(
    /** Top-left of the stack box in cells from the window's corner; null: anchored at the midline. */
    val stackCol: Int? = null,
    val stackRow: Int? = null,
    /** Folded to a one-line `stack: N` bar (S). */
    val stackCollapsed: Boolean = false,
    /** Each player's zone column, in cells (drag its edge, Ctrl+Shift+←/→). */
    val zoneCols: Int = ZONE_COLS,
    /** The right column — zoom, stack list, log — in cells (drag its edge, Ctrl+←/→). */
    val sideCols: Int = SIDE_COLS,
    /** Tapped cards turned a quarter, as on a real table (R); off, they only shift a cell and take the tapped tone. */
    val rotateTapped: Boolean = true,
)

/** [pos] kept inside a window of [total] cells for something [size] cells long. */
fun clampCells(pos: Int, size: Int, total: Int): Int = pos.coerceIn(0, maxOf(0, total - size))

/** The narrowest each pane may be: the stop ladder's two columns; the zoom pane's card; the table itself. */
const val MIN_ZONE_COLS = ZONE_COLS
const val MAX_ZONE_COLS = 60
const val MIN_SIDE_COLS = 32
const val MIN_FIELD_COLS = 60

/**
 * The pane widths a window of [totalCols] can hold: each within its own
 * bounds, and the table keeps [MIN_FIELD_COLS] — the side column gives way
 * first, since the table is what you play on. Applied on every layout, so a
 * shrinking window re-clamps without anyone asking.
 */
fun BoardLayout.clampedTo(totalCols: Int): BoardLayout {
    val zone = zoneCols.coerceIn(MIN_ZONE_COLS, MAX_ZONE_COLS)
    val side = sideCols.coerceIn(MIN_SIDE_COLS, maxOf(MIN_SIDE_COLS, totalCols - zone - MIN_FIELD_COLS))
    val zoneFit = zone.coerceAtMost(maxOf(MIN_ZONE_COLS, totalCols - side - MIN_FIELD_COLS))
    return copy(zoneCols = zoneFit, sideCols = side)
}

/** Where the stack box goes for the current prompt. */
enum class StackPlacement { AS_SET, ABOVE_MIDLINE, BELOW_MIDLINE, FOLDED }

/**
 * The stack box never hides something you must click: where the viewer put
 * it, unless that covers one of [legal] (the prompt's cards); then the other
 * side of the midline, then the near side; failing both, folded to its bar
 * on the midline, which covers no card.
 */
fun placeStackBox(wanted: Rect, above: Rect, below: Rect, midlineY: Float, legal: List<Rect>): StackPlacement {
    fun clear(r: Rect) = legal.none { it.overlaps(r) }
    if (clear(wanted)) return StackPlacement.AS_SET
    val order = if (wanted.center.y <= midlineY) listOf(StackPlacement.BELOW_MIDLINE to below, StackPlacement.ABOVE_MIDLINE to above)
        else listOf(StackPlacement.ABOVE_MIDLINE to above, StackPlacement.BELOW_MIDLINE to below)
    return order.firstOrNull { clear(it.second) }?.first ?: StackPlacement.FOLDED
}
