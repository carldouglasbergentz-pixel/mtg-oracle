package mtgoracle.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import mtgoracle.core.model.BoardState
import mtgoracle.core.model.ChoicePrompt
import mtgoracle.core.model.ConfirmPrompt
import mtgoracle.core.model.DistributePrompt
import mtgoracle.core.model.NumberPrompt
import mtgoracle.core.model.OrderPrompt
import mtgoracle.core.model.Prompt
import mtgoracle.ui.kit.BUTTON_ROWS
import mtgoracle.ui.kit.Border
import mtgoracle.ui.kit.CardFace
import mtgoracle.ui.kit.CardMode
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.FrameSize
import mtgoracle.ui.kit.boxBorder
import mtgoracle.ui.kit.cells
import mtgoracle.ui.kit.chromeShape
import mtgoracle.ui.kit.region
import mtgoracle.ui.kit.wrap
import mtgoracle.ui.theme.LocalCells
import mtgoracle.ui.theme.Palette

/**
 * The questions answered in the floating choice box rather than in the left
 * column, where a planeswalker clicked on the right was answered on the
 * left: a pick from a list (a planeswalker's abilities, a spell's modes, a
 * land from a fetch), damage divided, a number, an order, a yes or no. A
 * reveal has its own panel; priority, targets on the table, paying mana and
 * sideboarding stay in the left column.
 */
fun Prompt.boxed(): Boolean = when (this) {
    is ChoicePrompt -> !isReveal
    is ConfirmPrompt, is OrderPrompt, is DistributePrompt, is NumberPrompt -> true
    else -> false
}

/** A reveal of cards, drawn in the reveal panel over the table (RevealPanel), which answers it. */
fun Prompt.revealPanel(): Boolean = this is ChoicePrompt && isReveal && options.any { it.card != null }

/** Answered over the table, in the choice box or the reveal panel: the left column only says it. */
fun Prompt.answeredElsewhere(): Boolean = boxed() || revealPanel()

/** The box's width when its options are words. */
const val CHOICE_BOX_COLS = 64

/** Card frames a row of the box holds. */
private const val CARDS_PER_ROW = 4

private fun cardOptions(prompt: Prompt): Int = (prompt as? ChoicePrompt)?.options?.count { it.card != null } ?: 0

/** The box's width in cells: wide enough for its cards' frames in rows of four, else [CHOICE_BOX_COLS]. */
fun choiceBoxCols(prompt: Prompt, mode: CardMode): Int {
    val cards = cardOptions(prompt)
    return if (cards == 0) CHOICE_BOX_COLS else maxOf(CHOICE_BOX_COLS, 2 + minOf(cards, CARDS_PER_ROW) * (FrameSize.cols(mode) + 1))
}

/** The box's height in cells: the question, its options, the hint, the buttons and the border, at most [maxRows] (then the options scroll). */
fun choiceBoxRows(prompt: Prompt, mode: CardMode, maxRows: Int): Int {
    val cols = choiceBoxCols(prompt, mode) - 2
    val question = wrap(prompt.message.trim(), cols).size
    val cards = cardOptions(prompt)
    val options = when {
        cards > 0 -> ((cards + CARDS_PER_ROW - 1) / CARDS_PER_ROW) * FrameSize.rows(mode)
        prompt is ChoicePrompt -> prompt.options.size
        prompt is OrderPrompt -> prompt.items.size
        prompt is DistributePrompt -> prompt.targets.size + 1
        prompt is NumberPrompt -> 1
        else -> 0
    }
    val hint = hintLines(prompt, cols).size
    val frame = 2 + PROMPT_BUTTON_LINES * BUTTON_ROWS
    return (frame + question + options + hint).coerceAtMost(maxRows).coerceAtLeast(frame + 2 + hint)
}

/**
 * The question, floating over the table by what it is about: its options
 * (cards in their frames), the buttons, and the keys as ever (1–9, Enter,
 * Esc). It takes no layout space; it is dragged by its top edge. The left
 * column says the question too, and that it is answered here.
 */
@Composable
fun ChoiceBox(
    prompt: Prompt,
    board: BoardState?,
    interaction: Interaction,
    mode: CardMode,
    rows: Int,
    onClick: (ClickTarget) -> Unit,
    onHover: (ClickTarget?) -> Unit,
    onCardHover: (CardFace) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cells = LocalCells.current
    val density = LocalDensity.current
    val cols = choiceBoxCols(prompt, mode)
    val title = when (prompt) {
        is ConfirmPrompt -> "yes or no"
        is OrderPrompt -> "order"
        is DistributePrompt -> "divide"
        is NumberPrompt -> "a number"
        else -> "choose"
    }
    Box(
        modifier.cells(cols, rows).chromeShape().background(Palette.background)
            .boxBorder(title, right = "drag", border = Border.DOUBLE, color = Palette.accent)
            // Solid to the pointer as well as the eye: a click on the box never reaches the card beneath.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .region("choice-box"),
    ) {
        val drag by rememberUpdatedState(onDrag)
        val dragEnd by rememberUpdatedState(onDragEnd)
        Box(Modifier.cells(cols, 1).region("choice-box-handle").pointerInput(Unit) {
            detectDragGestures(onDragEnd = { dragEnd() }, onDragCancel = { dragEnd() }) { change, amount -> change.consume(); drag(amount) }
        })
        Box(Modifier.padding(with(density) { cells.width.toDp() }, with(density) { cells.height.toDp() }).cells(cols - 2, rows - 2)) {
            PromptBody(
                prompt, board, interaction, cols - 2, onClick, onHover,
                contentRows = rows - 2 - PROMPT_BUTTON_LINES * BUTTON_ROWS, regionPrefix = "choice",
                cardOptions = true, cardMode = mode, onCardHover = onCardHover,
            )
        }
    }
}
