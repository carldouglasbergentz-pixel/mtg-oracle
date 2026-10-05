package mtgoracle.ui

import mtgoracle.core.model.ChoiceOption
import mtgoracle.core.model.ChoicePrompt
import mtgoracle.ui.board.BoardScreen
import mtgoracle.ui.kit.CardMode
import mtgoracle.ui.kit.ClickTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A prompt that has just shown takes no click: the click meant for the one
 * before (a second OK) closed the reveal of Cloud's Lion Sash in the same
 * half second it opened. Once the guard has passed, a click answers.
 */
class InputGuardTest {
    private fun reveal(id: Long) = ChoicePrompt(id, "Looking at cards in AI's hand", listOf(ChoiceOption("Lion Sash")), -1, -1)

    @Test
    fun `a click as the prompt shows is dropped, one after the guard answers, and each new prompt guards again`() {
        val seat = FakeSeat(quietBoard(), reveal(1))
        OffscreenDriver(1600, 900) { BoardScreen(seat, "MTG Oracle", CardMode.TEXT, inputGuardMillis = 400) }.use { d ->
            d.settle(2)
            assertTrue(d.click(ClickTarget.Done))
            assertEquals(emptyList(), seat.answers, "the click that came with the prompt is dropped")
            Thread.sleep(450)
            assertTrue(d.click(ClickTarget.Done))
            assertEquals(listOf(1L), seat.answers.map { it.first }, "a click once the prompt has been seen answers it")
            seat.prompt.value = reveal(2)
            d.settle(2)
            assertTrue(d.click(ClickTarget.Done))
            assertEquals(listOf(1L), seat.answers.map { it.first }, "the next prompt guards again")
        }
    }
}
