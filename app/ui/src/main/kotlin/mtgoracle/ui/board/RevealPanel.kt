package mtgoracle.ui.board

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import mtgoracle.core.model.ChoicePrompt
import mtgoracle.ui.kit.Border
import mtgoracle.ui.kit.CardFace
import mtgoracle.ui.kit.CardFrame
import mtgoracle.ui.kit.CardMode
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.FrameSize
import mtgoracle.ui.kit.FrameTier
import mtgoracle.ui.kit.GridText
import mtgoracle.ui.kit.BUTTON_ROWS
import mtgoracle.ui.kit.cells
import mtgoracle.ui.kit.fit
import mtgoracle.ui.kit.wrap
import mtgoracle.ui.kit.boxBorder
import mtgoracle.ui.kit.chromeShape
import mtgoracle.ui.kit.face
import mtgoracle.ui.kit.region
import mtgoracle.ui.theme.LocalCells
import mtgoracle.ui.theme.Palette

/** Cards a row of the reveal panel holds: four large frames stay on a 1280-wide window. */
private const val PER_ROW = 4

/**
 * Cards Forge shows you (a tutor's find, a hand looked at), drawn: the
 * prompt pane only listed their names, and one click closed them for good.
 * The cards in large frames, what Forge said of them, and OK (Enter), which
 * answers the reveal as the prompt pane's does. A hovered card goes to the
 * zoom pane; the log keeps a line of what was shown.
 */
/** The panel's width in cells: four large frames a row, or fewer cards' worth, and never under 48. */
internal fun revealPanelCols(prompt: ChoicePrompt): Int = maxOf(48, minOf(prompt.options.count { it.card != null }, PER_ROW) * (FrameSize.cols(FrameTier.LARGE) + 1) + 3)

/** The panel's height in cells: the border, what Forge said, the rows of frames, OK, and a blank row between each. */
internal fun revealPanelRows(prompt: ChoicePrompt): Int {
    val cols = revealPanelCols(prompt)
    val perRow = maxOf(1, (cols - 2 + 1) / (FrameSize.cols(FrameTier.LARGE) + 1))
    val bands = (prompt.options.count { it.card != null } + perRow - 1) / perRow
    return 1 + wrap(prompt.message, cols - 2).size + 1 + bands * FrameSize.rows(FrameTier.LARGE) + 1 + BUTTON_ROWS + 1
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RevealPanel(prompt: ChoicePrompt, onClick: (ClickTarget) -> Unit, onHover: (CardFace) -> Unit, modifier: Modifier = Modifier) {
    val cards = prompt.options.mapNotNull { it.card }
    val cols = revealPanelCols(prompt)
    val cells = LocalCells.current
    val pad = with(LocalDensity.current) { cells.width.toDp() }
    Box(modifier.cells(cols, revealPanelRows(prompt)).chromeShape().background(Palette.background)
        .boxBorder("shown to you", border = Border.DOUBLE, color = Palette.accent)
        // A click on the panel is the panel's: it does not fall through to the board under it.
        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
        .region("reveal")) {
        Column(Modifier.padding(start = pad, top = with(LocalDensity.current) { cells.height.toDp() }, end = pad)) {
            wrap(prompt.message, cols - 2).forEach { GridText(fit(it, cols - 2), bold = true) }
            GridText("")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(pad)) {
                cards.forEachIndexed { i, card ->
                    val face = card.face()
                    CardFrame(face, CardMode.ART, target = ClickTarget.Control("reveal:$i"), onHover = { onHover(face) }, tier = FrameTier.LARGE)
                }
            }
            GridText("")
            Row {
                GridButton("OK", ClickTarget.Done, true, onClick)
                GridText("  Enter OK · kept in the log", color = Palette.dim)
            }
        }
    }
}
