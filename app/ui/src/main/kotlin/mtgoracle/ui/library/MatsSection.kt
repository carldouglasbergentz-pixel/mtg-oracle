package mtgoracle.ui.library

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import mtgoracle.ui.board.Playmat
import mtgoracle.ui.board.PlaymatLayer
import mtgoracle.ui.board.coverSize
import mtgoracle.ui.board.playmatSize
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.GridText
import mtgoracle.ui.kit.LinkButton
import mtgoracle.ui.kit.RuleLine
import mtgoracle.ui.kit.WrapText
import mtgoracle.ui.kit.cells
import mtgoracle.ui.kit.region
import mtgoracle.ui.theme.LocalCells
import mtgoracle.ui.theme.Palette
import kotlin.math.roundToInt

/** One side's playmat in the lobby: its name (null: none) and the mat as it will be drawn. */
data class MatSide(val name: String?, val mat: Playmat?)

/** The lobby's playmats: what there is to choose from, each side's choice, and where the pictures live. */
data class LobbyMats(val names: List<String>, val mine: MatSide, val theirs: MatSide, val folder: String)

/** What the playmat controls ask: a side's next or previous mat, its dim (0 to 9 tenths), its place and zoom, a new one. */
sealed interface MatAction {
    data class Cycle(val mine: Boolean, val by: Int) : MatAction
    data class Dim(val mine: Boolean, val tenths: Int) : MatAction
    /** Where the picture sits in its crop (x, y: 0 to 1) and how far it is enlarged (1 to 3). */
    data class Frame(val mine: Boolean, val x: Float, val y: Float, val zoom: Float) : MatAction
    data object Add : MatAction
}

/** The dim's steps: none to nine tenths of the background over the picture. */
private const val DIM_STEPS = 10

/** A zoom button's step. */
private const val ZOOM_STEP = 0.1f

/** A preview's size in cells: long and low, about as a half of the table is. */
private const val PREVIEW_COLS = 40
private const val PREVIEW_ROWS = 6

/**
 * The playmats in the lobby's match pane: yours and the AI's, each chosen with
 * < and >, and for a chosen one its dim, its zoom, and a preview that a drag
 * places it in. Add takes a picture from the clipboard.
 */
@Composable
internal fun MatsSection(mats: LobbyMats, onMat: (MatAction) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cols = LocalCells.current.cols(constraints.maxWidth.toFloat())
        Column {
            RuleLine(cols, label = "playmats", end = mats.folder)
            GridText("")
            Side("yours", true, mats.mine, mats.names.isNotEmpty(), onMat)
            Side("the AI's", false, mats.theirs, mats.names.isNotEmpty(), onMat)
            Row {
                GridText("  ")
                LinkButton("[ Add a playmat from the clipboard ]", ClickTarget.Control("mat:add")) { onMat(MatAction.Add) }
            }
            WrapText("    Copy a picture (or its file in Explorer), then Add; or put pictures in ${mats.folder}.", color = Palette.dim)
        }
    }
}

@Composable
private fun Side(label: String, mine: Boolean, side: MatSide, any: Boolean, onMat: (MatAction) -> Unit) {
    val key = if (mine) "me" else "ai"
    Row {
        GridText("  %-11s".format(label))
        if (any) LinkButton("[<]", ClickTarget.Control("mat:$key:prev")) { onMat(MatAction.Cycle(mine, -1)) }
        GridText(" ${side.name ?: "none"} ", color = if (side.name != null) Palette.accent else Palette.dim)
        if (any) LinkButton("[>]", ClickTarget.Control("mat:$key:next")) { onMat(MatAction.Cycle(mine, +1)) }
    }
    val mat = side.mat ?: return GridText("")
    Row {
        // 0, then a cell per tenth up to nine: the cells up to the level are filled.
        val level = (mat.dim * DIM_STEPS + 0.5f).toInt().coerceIn(0, DIM_STEPS - 1)
        GridText("             dim ")
        LinkButton("0", ClickTarget.Control("mat:$key:dim:0")) { onMat(MatAction.Dim(mine, 0)) }
        GridText(" [")
        for (tenth in 1 until DIM_STEPS) {
            LinkButton(if (tenth <= level) "#" else "-", ClickTarget.Control("mat:$key:dim:$tenth")) { onMat(MatAction.Dim(mine, tenth)) }
        }
        GridText("] %2d %%   zoom ".format(level * 10))
        fun zoomed(by: Float) = MatAction.Frame(mine, mat.x, mat.y, (mat.zoom + by).coerceIn(1f, Playmat.MAX_ZOOM))
        LinkButton("[-]", ClickTarget.Control("mat:$key:zoom-out")) { onMat(zoomed(-ZOOM_STEP)) }
        GridText(" %3d %% ".format((mat.zoom * 100).roundToInt()))
        LinkButton("[+]", ClickTarget.Control("mat:$key:zoom-in")) { onMat(zoomed(+ZOOM_STEP)) }
        GridText("   ")
        LinkButton("[ reset ]", ClickTarget.Control("mat:$key:reset")) { onMat(MatAction.Frame(mine, 0.5f, 0.5f, 1f)) }
    }
    Row {
        GridText("             ")
        Preview(mat, Modifier.region("mat-preview-$key")) { x, y -> onMat(MatAction.Frame(mine, x, y, mat.zoom)) }
    }
    GridText("             drag the preview to place the picture", color = Palette.dim)
    GridText("")
}

/**
 * The mat as the table will have it, zones' rules over it, and dragged to place
 * the picture: the move follows the mouse while it is held and is kept, once,
 * when it is let go ([onPlace]), not on every move.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Preview(mat: Playmat, modifier: Modifier, onPlace: (Float, Float) -> Unit) {
    // One state for the preview's life, which the drag handler (keyed on Unit, so it outlives recompositions) holds;
    // the mat as saved replaces it whenever it changes from outside (a zoom, a reset, another mat).
    val liveState = remember { mutableStateOf(mat) }
    var live by liveState
    LaunchedEffect(mat) { liveState.value = mat }
    val place by rememberUpdatedState(onPlace)
    Box(
        modifier.cells(PREVIEW_COLS, PREVIEW_ROWS).pointerHoverIcon(PointerIcon.Hand).pointerInput(Unit) {
            detectDragGestures(onDragEnd = { place(live.x, live.y) }) { change, drag ->
                change.consume()
                val picture = playmatSize(live) ?: return@detectDragGestures
                val (w, h) = coverSize(picture, size.width.toFloat(), size.height.toFloat(), live.zoom)
                // The picture follows the mouse: a move right shows more of its left, so x falls.
                val roomX = w - size.width
                val roomY = h - size.height
                live = live.copy(
                    x = if (roomX > 1f) (live.x - drag.x / roomX).coerceIn(0f, 1f) else live.x,
                    y = if (roomY > 1f) (live.y - drag.y / roomY).coerceIn(0f, 1f) else live.y,
                )
            }
        },
    ) {
        PlaymatLayer(live, Modifier.fillMaxSize())
        Column { RuleLine(PREVIEW_COLS, label = "creatures"); repeat(2) { GridText(" ") }; RuleLine(PREVIEW_COLS, label = "lands") }
    }
}
