package mtgoracle.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import mtgoracle.ui.kit.Border
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.GridText
import mtgoracle.ui.kit.LinkButton
import mtgoracle.ui.kit.WrapText
import mtgoracle.ui.kit.boxBorder
import mtgoracle.ui.kit.cellWidth
import mtgoracle.ui.kit.chromeShape
import mtgoracle.ui.kit.region
import mtgoracle.ui.theme.LocalCells
import mtgoracle.ui.theme.Palette

/** What a first game is told, one at a time: how a turn goes here, in the order it comes up. */
val FIRST_GAME_TIPS = listOf(
    "Your hand is at the bottom. Click a card to play it; the prompt pane under the table says what the game asks of you, and its OK answers.",
    "F2 passes priority, F4 ends your turn, F6 passes until your next turn. The stops on the left choose where the game halts for you.",
    "Hover any card for the zoom pane on the right. The log under it keeps the whole game; L hides the steps, and a card's name there zooms too.",
    "Ctrl+Q (or Esc with nothing to cancel) concedes. Every game is recorded, and the lobby shows your record against each deck.",
)

/** Columns the tip box takes. */
private const val TIP_COLS = 60

/**
 * The first game's tips over the table, one at a time: Next, or No more tips.
 * Its own clicks stay its own (the board under it is not clicked through).
 */
@Composable
internal fun BoardTips(tips: List<String>, onDone: () -> Unit, modifier: Modifier = Modifier) {
    var at by remember { mutableIntStateOf(0) }
    val tip = tips.getOrNull(at) ?: return
    val cells = LocalCells.current
    val pad = with(LocalDensity.current) { cells.width.toDp() }
    Box(
        modifier.cellWidth(TIP_COLS).chromeShape().background(Palette.background)
            .boxBorder("tip ${at + 1} of ${tips.size}", border = Border.DOUBLE, color = Palette.accent)
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .region("tips"),
    ) {
        Column(Modifier.padding(start = pad, end = pad, top = with(LocalDensity.current) { cells.height.toDp() }, bottom = with(LocalDensity.current) { cells.height.toDp() })) {
            WrapText(tip)
            GridText("")
            Row {
                val last = at == tips.lastIndex
                LinkButton(if (last) "[ Got it ]" else "[ Next tip ]", ClickTarget.Control("tips:next")) { if (last) onDone() else at++ }
                if (!last) { GridText("  "); LinkButton("[ No more tips ]", ClickTarget.Control("tips:done")) { onDone() } }
            }
        }
    }
}
