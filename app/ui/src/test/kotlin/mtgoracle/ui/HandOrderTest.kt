package mtgoracle.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import mtgoracle.core.model.InputKind
import mtgoracle.core.model.InputPrompt
import mtgoracle.ui.board.BoardScreen
import mtgoracle.ui.board.arrangeHand
import mtgoracle.ui.board.dropIndex
import mtgoracle.ui.board.moveInHand
import mtgoracle.ui.kit.ArtImages
import mtgoracle.ui.kit.CardMode
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.LocalArt
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The hand in the viewer's own order: a card dragged sideways moves there, and is not played. */
class HandOrderTest {
    @Test
    fun `the order keeps what was placed, draws go last, a card that left is forgotten`() {
        assertEquals(listOf(3, 1, 2), arrangeHand(listOf(3, 1, 2), listOf(1, 2, 3)))
        assertEquals(listOf(3, 1, 4), arrangeHand(listOf(3, 1, 2), listOf(1, 3, 4)), "2 played, 4 drawn")
        assertEquals(listOf(1, 2), arrangeHand(emptyList(), listOf(1, 2)), "nothing placed: Forge's order")
        assertEquals(listOf(2, 3, 1), moveInHand(listOf(1, 2, 3), 1, 2))
        assertEquals(listOf(3, 1, 2), moveInHand(listOf(1, 2, 3), 3, -5), "clamped")
        assertEquals(2, dropIndex(listOf(10f, 30f, 50f), from = 0, centre = 55f), "past both others")
        assertEquals(0, dropIndex(listOf(10f, 30f, 50f), from = 2, centre = 5f))
        assertEquals(1, dropIndex(listOf(10f, 30f, 50f), from = 1, centre = 32f), "a short drag stays put")
    }

    @Test
    fun `dragging a hand card moves it in the hand and plays nothing`() {
        val art = ArtImages(FakeArt(File(pngDir, "fake-art")))
        val seat = FakeSeat(quietBoard(), InputPrompt(1, "Priority", InputKind.PRIORITY, "InputPassPriority", 0, "OK", "End Turn", true, true, emptySet(), setOf(20, 21, 22)))
        OffscreenDriver(1800, 1600) { CompositionLocalProvider(LocalArt provides art) { BoardScreen(seat, "MTG Oracle", CardMode.ART) } }.use { d ->
            d.settle(5)
            fun x(id: Int) = d.registry[ClickTarget.Card(id)]!!.left
            assertTrue(x(20) < x(21) && x(21) < x(22), "Forge's order first: Bolt, Counterspell, Mountain")
            val bolt = d.registry[ClickTarget.Card(20)]!!
            assertTrue(d.drag(ClickTarget.Card(20), Offset(x(22) - bolt.left + bolt.width * 0.6f, 0f)))
            d.settle(3)
            assertTrue(x(21) < x(22) && x(22) < x(20), "Bolt dropped past Mountain: ${listOf(20, 21, 22).map(::x)}")
            assertEquals(emptyList(), seat.answers, "a drag is no click: nothing was played")
            assertTrue(d.click(ClickTarget.Card(20)), "and a click still plays")
            d.settle(2)
            assertTrue(seat.answers.isNotEmpty(), "the click reached the seat")
        }
    }
}
