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
import mtgoracle.ui.kit.WrapText
import mtgoracle.ui.kit.boxBorder
import mtgoracle.ui.kit.cellWidth
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
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RevealPanel(prompt: ChoicePrompt, onClick: (ClickTarget) -> Unit, onHover: (CardFace) -> Unit, modifier: Modifier = Modifier) {
    val cards = prompt.options.mapNotNull { it.card }
    val frameCols = FrameSize.cols(FrameTier.LARGE)
    val cols = maxOf(48, minOf(cards.size, PER_ROW) * (frameCols + 1) + 3)
    val cells = LocalCells.current
    val pad = with(LocalDensity.current) { cells.width.toDp() }
    Box(modifier.cellWidth(cols).chromeShape().background(Palette.background)
        .boxBorder("shown to you", border = Border.DOUBLE, color = Palette.accent)
        // A click on the panel is the panel's: it does not fall through to the board under it.
        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
        .region("reveal")) {
        Column(Modifier.padding(start = pad, top = with(LocalDensity.current) { cells.height.toDp() }, end = pad, bottom = with(LocalDensity.current) { cells.height.toDp() })) {
            WrapText(prompt.message, bold = true)
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
                GridText("  Enter · kept in the log", color = Palette.dim)
            }
        }
    }
}
