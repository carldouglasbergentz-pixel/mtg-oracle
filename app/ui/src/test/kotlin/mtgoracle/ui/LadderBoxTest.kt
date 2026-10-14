package mtgoracle.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import mtgoracle.core.model.InputKind
import mtgoracle.core.model.InputPrompt
import mtgoracle.core.model.Step
import mtgoracle.ui.board.BoardScreen
import mtgoracle.ui.board.LADDER_CLOSE
import mtgoracle.ui.board.ladderToggle
import mtgoracle.ui.kit.CardMode
import mtgoracle.ui.kit.ClickTarget
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A stop ladder folded for want of room opens over the table from its line,
 * its stops toggle there as in the column, and it closes by its `×`, Esc or
 * a click outside. The fold's click had done nothing when the column lacked
 * the room.
 */
class LadderBoxTest {
    private val box = ClickTarget.Control("region:ladder-box")

    /** Every card and fixed region where it is drawn, but the box's own. */
    private fun layout(d: OffscreenDriver): Map<ClickTarget, Rect> = d.registry.targets
        .filter { it is ClickTarget.Card || (it is ClickTarget.Control && it.name.startsWith("region:") && !it.name.startsWith("region:ladder-box")) }
        .mapNotNull { t -> d.registry[t]?.let { t to it } }.toMap()

    @Test
    fun `a folded ladder opens over the table, toggles its stops and closes, and nothing else moves`() {
        val prompt = InputPrompt(1, "Priority", InputKind.PRIORITY, "InputPassPriority", 0, "OK", "End Turn", true, true, emptySet(), emptySet())
        val seat = FakeSeat(sampleBoard(), prompt)
        OffscreenDriver(1600, 640) { BoardScreen(seat, "MTG Oracle", CardMode.TEXT) }.use { d ->
            d.settle(5)
            assertNotNull(d.registry[ladderToggle(true)], "the window is too short for the ladders: they fold to a line")
            assertNull(d.registry[ClickTarget.Stop(true, Step.UPKEEP)])
            val before = layout(d)

            assertTrue(d.click(ladderToggle(true)))
            d.settle(3)
            val open = assertNotNull(d.registry[box], "the line opens the ladder")
            assertEquals(before, layout(d), "nothing else moved")
            assertTrue("stops · your turn" in d.text.all(), d.text.all())
            for (card in sampleBoard().players.flatMap { it.battlefield }) {
                val at = d.registry[ClickTarget.Card(card.id)] ?: continue
                assertTrue(!at.overlaps(open), "it opens where it covers no card: ${card.name} at $at, the box $open")
            }
            d.savePng(File(pngDir, "ladder-box.png"))

            val upkeep = seat.stops.value.stopsAt(true, Step.UPKEEP)
            assertTrue(d.click(ClickTarget.Stop(true, Step.UPKEEP)))
            d.settle(2)
            assertEquals(!upkeep, seat.stops.value.stopsAt(true, Step.UPKEEP), "a stop toggles in the box")
            assertNotNull(d.registry[box], "and the box stays open")

            assertTrue(d.click(LADDER_CLOSE))
            d.settle(2)
            assertNull(d.registry[box], "the × closes it")

            assertTrue(d.click(ladderToggle(false)))
            d.settle(2)
            assertTrue("stops · their turn" in d.text.all(), "the opponent's column opens their turn's ladder")
            d.key(Key.Escape)
            d.settle(2)
            assertNull(d.registry[box], "Esc closes it")

            assertTrue(d.click(ladderToggle(true)))
            d.settle(2)
            assertNotNull(d.registry[box])
            assertTrue(d.click(ClickTarget.Control("region:log")))
            d.settle(2)
            assertNull(d.registry[box], "a click outside closes it")
            assertEquals(before, layout(d), "and everything is where it was")
        }
    }
}
