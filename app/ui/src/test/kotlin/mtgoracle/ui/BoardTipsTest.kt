package mtgoracle.ui

import mtgoracle.ui.board.BoardScreen
import mtgoracle.ui.board.FIRST_GAME_TIPS
import mtgoracle.ui.kit.CardMode
import mtgoracle.ui.kit.ClickTarget
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A first game's tips over the table, one at a time; No more tips puts them away. */
class BoardTipsTest {
    @Test
    fun `the tips go one by one, and no more tips ends them`() {
        var done = 0
        OffscreenDriver(1600, 900) { BoardScreen(FakeSeat(quietBoard(), null), "MTG Oracle", CardMode.TEXT, tips = FIRST_GAME_TIPS, onTipsDone = { done++ }) }.use { d ->
            d.settle(5)
            assertTrue("tip 1 of 4" in d.text.all() && "Click a card to play it" in d.text.all(), d.text.all())
            d.savePng(File(pngDir, "board-tips.png"))
            assertTrue(d.click(ClickTarget.Control("tips:next")))
            d.settle(2)
            assertTrue("tip 2 of 4" in d.text.all() && "F2 passes priority" in d.text.all())
            assertTrue(d.click(ClickTarget.Control("tips:done")))
            assertEquals(1, done)
        }
    }
}
