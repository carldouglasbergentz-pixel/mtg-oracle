package mtgoracle.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import mtgoracle.ui.board.PANE_STEP
import mtgoracle.ui.kit.PaneDrag
import mtgoracle.ui.theme.LocalCells
import kotlin.math.roundToInt

/** The widths in cells of a screen's left and right columns; the middle takes what is left. */
data class SideColumns(val left: Int, val right: Int) {
    /**
     * Each within its bounds, and the middle keeps [MIN_MIDDLE_COLS]: the right
     * column gives way first, as on the board. Applied on every layout, so a
     * shrinking window re-clamps without anyone asking.
     */
    fun clampedTo(total: Int): SideColumns {
        val l = left.coerceIn(MIN_LEFT_COLS, MAX_SIDE_COLS)
        val r = right.coerceIn(MIN_RIGHT_COLS, maxOf(MIN_RIGHT_COLS, minOf(MAX_SIDE_COLS, total - l - MIN_MIDDLE_COLS)))
        return SideColumns(l.coerceAtMost(maxOf(MIN_LEFT_COLS, total - r - MIN_MIDDLE_COLS)), r)
    }

    /** Ctrl+←/→ moves the right column's edge, Ctrl+Shift+←/→ the left column's, [PANE_STEP] cells a press; null for any other key. */
    fun stepped(e: KeyEvent): SideColumns? {
        if (!e.isCtrlPressed || (e.key != Key.DirectionLeft && e.key != Key.DirectionRight)) return null
        val step = if (e.key == Key.DirectionRight) PANE_STEP else -PANE_STEP
        return if (e.isShiftPressed) copy(left = left + step) else copy(right = right - step)
    }
}

/** The narrowest a list or deck column may be; the zoom pane's card; the middle's room to work. */
const val MIN_LEFT_COLS = 24
const val MIN_RIGHT_COLS = 32
const val MAX_SIDE_COLS = 120
const val MIN_MIDDLE_COLS = 40

/** The widths shown while an edge is dragged, and the two edges' drags. */
class ColumnDrag(val live: SideColumns, val leftEdge: PaneDrag, val rightEdge: PaneDrag)

/**
 * [kept] as dragged: the edges move by whole cells while the mouse moves, and
 * [onKeep] gets the new widths only when the drag ends (a write per cell
 * crossed would read as a hang).
 */
@Composable
fun rememberColumnDrag(kept: SideColumns, total: Int, onKeep: (SideColumns) -> Unit): ColumnDrag {
    val cell = LocalCells.current.width
    var leftPx by remember { mutableStateOf(0f) }
    var rightPx by remember { mutableStateOf(0f) }
    val live = SideColumns(kept.left + (leftPx / cell).roundToInt(), kept.right - (rightPx / cell).roundToInt()).clampedTo(total)
    return ColumnDrag(
        live,
        PaneDrag({ leftPx += it }) { leftPx = 0f; onKeep(live) },
        PaneDrag({ rightPx += it }) { rightPx = 0f; onKeep(live) },
    )
}
