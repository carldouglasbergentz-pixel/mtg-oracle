package mtgoracle.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import mtgoracle.ui.board.MatAnchor
import mtgoracle.ui.board.Playmat
import mtgoracle.ui.board.PlaymatLayer
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.FitText
import mtgoracle.ui.kit.GridText
import mtgoracle.ui.kit.LinkButton
import mtgoracle.ui.kit.RuleLine
import mtgoracle.ui.kit.WrapText
import mtgoracle.ui.kit.cells
import mtgoracle.ui.theme.LocalCells
import mtgoracle.ui.theme.Palette

/** One side's playmat in the lobby: its name (null: none) and the mat as it will be drawn. */
data class MatSide(val name: String?, val mat: Playmat?)

/** The lobby's playmats: what there is to choose from, each side's choice, and where the pictures live. */
data class LobbyMats(val names: List<String>, val mine: MatSide, val theirs: MatSide, val folder: String)

/** What the playmat controls ask: a side's next or previous mat, its dim (0 to 9 tenths), the part it shows, a new one. */
sealed interface MatAction {
    data class Cycle(val mine: Boolean, val by: Int) : MatAction
    data class Dim(val mine: Boolean, val tenths: Int) : MatAction
    data class Anchor(val mine: Boolean, val anchor: MatAnchor) : MatAction
    data object Add : MatAction
}

/** The dim's steps: none to nine tenths of the background over the picture. */
private const val DIM_STEPS = 10

/** A preview's size in cells: long and low, as a half of the table is. */
private const val PREVIEW_COLS = 36
private const val PREVIEW_ROWS = 4

/**
 * The playmats in the lobby's match pane: yours and the AI's, each chosen with
 * < and >, and for a chosen one its dim and which part of it shows, with a
 * preview of the two together. Add takes a picture from the clipboard.
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
            WrapText("    Copy a picture (or its file in Explorer), then Add; or put pictures in ${mats.folder}.", color = Palette.dim, hang = 4)
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
        GridText("] %2d %%   show ".format(level * 10))
        MatAnchor.entries.forEach { a ->
            LinkButton(if (a == mat.anchor) "[${a.label}]" else " ${a.label} ", ClickTarget.Control("mat:$key:anchor:${a.name}")) { onMat(MatAction.Anchor(mine, a)) }
        }
    }
    Row {
        GridText("             ")
        // The mat as the table will have it, a zone's rule over it: what the dim is for.
        Box(Modifier.cells(PREVIEW_COLS, PREVIEW_ROWS)) {
            PlaymatLayer(mat, Modifier.fillMaxSize())
            Column { RuleLine(PREVIEW_COLS, label = "creatures"); GridText(" "); RuleLine(PREVIEW_COLS, label = "lands") }
        }
    }
    GridText("")
}
