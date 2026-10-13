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

/**
 * A table built from the middle out, as MTGO lays one: each player's
 * permanents from the midline, the lands right behind them, the rows to
 * spare at the player's edge. The halves share the rows: a crowded side
 * takes what a sparse one doesn't need.
 */
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
    fun `the cards sit at the midline, the lands right behind the creatures, the spare rows at the edge`() {
        val seat = FakeSeat(board(myLands, theirLands), null)
        OffscreenDriver(1800, 1600) { CompositionLocalProvider(LocalArt provides art) { BoardScreen(seat, "MTG Oracle", CardMode.ART) } }.use { d ->
            d.settle(5)
            val near = d.registry[ClickTarget.Control("region:near-field")]!!
            val far = d.registry[ClickTarget.Control("region:far-field")]!!
            val mine = d.registry[ClickTarget.Card(80)]!!
            val theirs = d.registry[ClickTarget.Card(90)]!!
            // A label line, the border and a little: within a couple of rows of the midline.
            val rows = 3 * 16f
            assertTrue(mine.top - near.top < rows, "lands alone sit at the midline: $mine in $near")
            assertTrue(far.bottom - theirs.bottom < rows, "theirs too: $theirs in $far")
            d.savePng(File(pngDir, "anchoring-lands-only.png"))

            seat.board.value = board(myLands + myCreatures, theirLands + theirCreatures)
            d.settle(3)
            val bears = d.registry[ClickTarget.Card(83)]!!
            val island = d.registry[ClickTarget.Card(80)]!!
            assertTrue(bears.top - near.top < rows, "creatures at the midline: $bears")
            assertTrue(island.top > bears.bottom && island.top - bears.bottom < rows, "the lands right behind them: $island after $bears")
            val giant = d.registry[ClickTarget.Card(92)]!!
            val swamp = d.registry[ClickTarget.Card(90)]!!
            assertTrue(far.bottom - giant.bottom < rows && swamp.bottom < giant.top, "theirs mirrored: $giant, $swamp in $far")
            d.savePng(File(pngDir, "anchoring-with-creatures.png"))
        }
    }

    @Test
    fun `a crowded side takes the rows a sparse one doesn't need - frames no smaller than the hand's`() {
        // As at the office (2230 x 1351 at 125 %): three creatures, a planeswalker, an enchantment and nine lands of yours, three lands of theirs.
        val creatures = (0 until 3).map { card(100 + it, "Human Token $it", creature = true) }
        val others = listOf(card(110, "Teferi, Hero of Dominaria", type = "Legendary Planeswalker - Teferi", cost = "{3}{W}{U}"),
            card(111, "Rest in Peace", type = "Enchantment", cost = "{1}{W}"))
        val lands = listOf("Island", "Plains", "Mystic Gate", "Tundra", "Castle Ardenvale", "Irrigated Farmland", "Hall of Heliod's Generosity", "Flooded Strand", "Prairie Stream")
            .mapIndexed { i, n -> card(120 + i, n, land = true) }
        val theirs = listOf(card(130, "Snow-Covered Plains", land = true), card(131, "Gemstone Caverns", land = true), card(132, "Snow-Covered Plains", land = true, tapped = true))
        val seat = FakeSeat(board(creatures + others + lands, theirs), null)
        OffscreenDriver(2230, 1351, 1.25f) { CompositionLocalProvider(LocalArt provides art) { BoardScreen(seat, "MTG Oracle", CardMode.ART) } }.use { d ->
            d.settle(5)
            val near = d.registry[ClickTarget.Control("region:near-field")]!!
            val far = d.registry[ClickTarget.Control("region:far-field")]!!
            assertTrue(near.height > far.height, "your crowded half has more of the table than their three lands: $near, $far")
            val mine = d.registry[ClickTarget.Card(100)]!!
            val held = d.registry.targets.filterIsInstance<ClickTarget.Card>().filter { it.id in listOf(20, 21, 22) }.mapNotNull { d.registry[it] }
            assertTrue(held.isNotEmpty() && held.all { it.height <= mine.height + 1f }, "no card in hand drawn larger than yours on the table: $mine vs $held")
            val ninth = d.registry[ClickTarget.Card(128)]
            assertTrue(ninth != null && ninth.bottom <= near.bottom, "every land on the board: $ninth in $near")
            d.savePng(File(pngDir, "anchoring-crowded-vs-sparse.png"))
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
