package mtgoracle.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import mtgoracle.core.model.BoardState
import mtgoracle.core.model.PhaseStops
import mtgoracle.ui.kit.Border
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.boxBorder
import mtgoracle.ui.kit.cells
import mtgoracle.ui.kit.chromeShape
import mtgoracle.ui.kit.clickTarget
import mtgoracle.ui.kit.region
import mtgoracle.ui.theme.LocalCells
import mtgoracle.ui.theme.Palette

/** The ladder box's inside: two columns of `[x] end combat` and a cell's margin. */
private const val LADDER_BOX_INNER = 30
const val LADDER_BOX_COLS = LADDER_BOX_INNER + 2
val LADDER_BOX_ROWS = LADDER_ROWS + 2

/** The box's `×`: closes it, as Esc and a click outside do. */
val LADDER_CLOSE = ClickTarget.Control("ladder-close")

/**
 * A stop ladder whose zone column has no room for it, opened over the table
 * from its folded line (`stops: main 1 main 2 ▸`): the whole ladder, its
 * stops toggled as in the column. It takes no layout space, so nothing moves
 * when it opens, and it is solid to the pointer: a click on it never reaches
 * the card beneath.
 */
@Composable
fun LadderBox(seatsTurn: Boolean, stops: PhaseStops, board: BoardState, onClick: (ClickTarget) -> Unit, modifier: Modifier = Modifier) {
    val cells = LocalCells.current
    val density = LocalDensity.current
    Box(
        modifier.cells(LADDER_BOX_COLS, LADDER_BOX_ROWS).chromeShape().background(Palette.background)
            .boxBorder("stops · ${if (seatsTurn) "your" else "their"} turn", right = "×", border = Border.DOUBLE, color = Palette.accent)
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .region("ladder-box"),
    ) {
        // The `×` and the cells around it, at the top edge's right end (the border's right label ends a cell from the corner).
        Box(Modifier.offset(x = with(density) { ((LADDER_BOX_COLS - 4) * cells.width).toDp() }).cells(4, 1).clickTarget(LADDER_CLOSE, onClick))
        Box(Modifier.padding(with(density) { cells.width.toDp() }, with(density) { cells.height.toDp() })) {
            LadderGrid(seatsTurn, stops, board, LADDER_BOX_INNER, onClick)
        }
    }
}
