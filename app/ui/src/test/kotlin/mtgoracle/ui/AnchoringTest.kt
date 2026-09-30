package mtgoracle.ui

import androidx.compose.runtime.CompositionLocalProvider
import mtgoracle.core.model.BoardState
import mtgoracle.core.model.CardState
import mtgoracle.ui.board.BoardScreen
import mtgoracle.ui.kit.ArtImages
import mtgoracle.ui.kit.CardMode
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.LocalArt
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A physical table: lands at each player's own edge, nonland permanents at the midline, the free rows between. */
class AnchoringTest {
    private val art = ArtImages(FakeArt(File(pngDir, "fake-art")))

    private fun board(mine: List<CardState>, theirs: List<CardState>, withStack: Boolean = false): BoardState = sampleBoard().let { b ->
        b.copy(stack = if (withStack) b.stack else emptyList(), combat = emptyList(),
            players = b.players.map { p -> p.copy(battlefield = if (p.isSeat) mine else theirs) })
    }

    private val myLands = listOf(card(80, "Island", land = true), card(81, "Plains", land = true), card(82, "Flooded Strand", land = true))
    private val theirLands = listOf(card(90, "Swamp", land = true), card(91, "Mountain", land = true))
    private val myCreatures = listOf(card(83, "Grizzly Bears", creature = true, cost = "{1}{G}"), card(84, "Serra Angel", creature = true, cost = "{3}{W}{W}"))
    private val theirCreatures = listOf(card(92, "Hill Giant", creature = true, cost = "{3}{R}"))

    @Test
    fun `lands sit at each player's edge, and stay there when creatures arrive`() {
        val seat = FakeSeat(board(myLands, theirLands), null)
        OffscreenDriver(1800, 1600) { CompositionLocalProvider(LocalArt provides art) { BoardScreen(seat, "MTG Oracle", CardMode.ART) } }.use { d ->
            d.settle(5)
            val near = d.registry[ClickTarget.Control("region:near-field")]!!
            val far = d.registry[ClickTarget.Control("region:far-field")]!!
            val mine = d.registry[ClickTarget.Card(80)]!!
            val theirs = d.registry[ClickTarget.Card(90)]!!
            // A frame and its turn shift, a label line and the border: within a band of the edge.
            val band = 13 * 16f
            assertTrue(near.bottom - mine.bottom < band, "my lands at my edge (the hand side): $mine in $near")
            assertTrue(mine.top - near.top > band, "with the free rows between them and the midline: $mine in $near")
            assertTrue(theirs.top - far.top < band, "their lands at their edge: $theirs in $far")
            d.savePng(File(pngDir, "anchoring-lands-only.png"))

            seat.board.value = board(myLands + myCreatures, theirLands + theirCreatures)
            d.settle(3)
            assertEquals(mine, d.registry[ClickTarget.Card(80)], "my lands did not move when my creatures arrived")
            assertEquals(theirs, d.registry[ClickTarget.Card(90)], "nor theirs")
            val bears = d.registry[ClickTarget.Card(83)]!!
            assertTrue(bears.top - near.top < band, "creatures at the midline: $bears")
            assertTrue(d.registry[ClickTarget.Card(92)]!!.bottom > far.bottom - band, "theirs too")
            d.savePng(File(pngDir, "anchoring-with-creatures.png"))
        }
    }

    @Test
    fun `the right column is the zoom pane and the log - the stack lives in its box, with the full text on hover`() {
        val seat = FakeSeat(board(myLands + myCreatures, theirLands + theirCreatures, withStack = true), null)
        OffscreenDriver(1800, 1600) { CompositionLocalProvider(LocalArt provides art) { BoardScreen(seat, "MTG Oracle", CardMode.ART) } }.use { d ->
            d.settle(5)
            assertNull(d.registry[ClickTarget.Control("region:stack")], "no stack pane")
            val zoom = d.registry[ClickTarget.Control("region:zoom")]!!
            val log = d.registry[ClickTarget.Control("region:log")]!!
            assertEquals(zoom.bottom, log.top, 1f, "the log takes the freed height, right under the zoom pane")
            assertTrue("STACK 2: Counterspell" in d.text.all(), "the midline still says what is on the stack")
            assertTrue(d.hover(ClickTarget.StackItem(90)))
            val zoomed = d.text.all()
            assertTrue("AI (Rakdos): Lightning Bolt" in zoomed && "→ Grizzly Bears" in zoomed, "hover shows the item in full, with its targets, in the zoom pane: $zoomed")
            d.savePng(File(pngDir, "right-column-with-stack.png"))
        }
    }
}
