package mtgoracle.ui.kit

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import java.awt.Cursor

/** What a pane edge reports while dragged: pixels moved, then the end (when the new width is kept). */
class PaneDrag(val onDrag: (Float) -> Unit, val onEnd: () -> Unit)

/**
 * A pane's draggable edge: one cell wide, laid over the pane's own border
 * (so it takes no layout space), a resize cursor on hover. It only reports
 * the drag; the owner decides the width and keeps it once the drag ends.
 */
@Composable
fun PaneEdge(drag: PaneDrag, name: String, modifier: Modifier = Modifier) {
    // The gesture must outlive the recompositions it causes: keyed on nothing, reading the latest callbacks.
    val current by rememberUpdatedState(drag)
    Box(
        modifier.cellWidth(1).fillMaxHeight()
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
            .pointerInput(Unit) {
                detectHorizontalDragGestures(onDragEnd = { current.onEnd() }, onDragCancel = { current.onEnd() }) { change, amount -> change.consume(); current.onDrag(amount) }
            }
            .region(name),
    )
}
