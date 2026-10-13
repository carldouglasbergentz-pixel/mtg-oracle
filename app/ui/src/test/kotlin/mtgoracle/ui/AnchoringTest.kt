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
 * A physical table, built from the middle out: each player's lands in an
 * area of their own at their edge, the other permanents from the midline,
 * the free rows between, and every row centred across. The halves are
 * even: a straight 50/50.
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
    fun `lands sit at each player's edge, centred, and stay there when creatures arrive`() {
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
            // From the middle across: the lands' run is centred in the field.
            val run = listOf(80, 81, 82).map { d.registry[ClickTarget.Card(it)]!! }
            val before = run.minOf { it.left } - near.left
            val after = near.right - run.maxOf { it.right }
            assertTrue(kotlin.math.abs(before - after) < 3 * 16f, "my lands in the middle of the table: $before px before them, $after after")
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
    fun `a crowded side against a sparse one - even halves, every card on the board, the hand no larger`() {
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
            assertTrue(kotlin.math.abs(near.height - far.height) <= 20f, "a straight 50/50: $near, $far")
            val mine = d.registry[ClickTarget.Card(100)]!!
            // One frame for every card on the table: a creature, an enchantment and a land the same size.
            // An overlapped card is clicked by its strip; the last of a row lies whole.
            for (id in listOf(111, 128)) assertEquals(mine.width, d.registry[ClickTarget.Card(id)]!!.width, 1f, "card $id is the creature's size")
            for (id in 120..128) assertEquals(mine.height, d.registry[ClickTarget.Card(id)]!!.height, 1f, "land $id is the creature's height")
            val held = d.registry.targets.filterIsInstance<ClickTarget.Card>().filter { it.id in listOf(20, 21, 22) }.mapNotNull { d.registry[it] }
            assertTrue(held.isNotEmpty() && held.all { it.height <= mine.height + 1f }, "no card in hand drawn larger than yours on the table: $mine vs $held")
            val ninth = d.registry[ClickTarget.Card(128)]
            assertTrue(ninth != null && ninth.bottom <= near.bottom, "every land on the board: $ninth in $near")
            d.savePng(File(pngDir, "anchoring-crowded-vs-sparse.png"))
        }
    }

    @Test
    fun `a card is the same size on either side - one row of yours, two of theirs`() {
        // As in game 1 against Ellie & Ellie: your lands, Chandra and Teferi; their lands and two creatures.
        val mine = listOf("Tundra", "Otawara, Soaring City", "Island", "Plains", "Meticulous Archive").mapIndexed { i, n -> card(400 + i, n, land = true, tapped = i > 0) } +
            listOf(card(410, "Chandra, Chill of Compliance", type = "Legendary Planeswalker - Chandra"), card(411, "Teferi, Time Raveler", type = "Legendary Planeswalker - Teferi"))
        val theirs = listOf("Swamp", "Blackcleave Cliffs", "Mountain", "Snow-Covered Swamp", "Blood Crypt").mapIndexed { i, n -> card(420 + i, n, land = true, tapped = true) } +
            listOf(card(430, "Emperor of Bones", creature = true), card(431, "Wriggling Grub", creature = true))
        val seat = FakeSeat(board(mine, theirs), null)
        OffscreenDriver(1700, 1020) { CompositionLocalProvider(LocalArt provides art) { BoardScreen(seat, "MTG Oracle", CardMode.ART) } }.use { d ->
            d.settle(5)
            val myLand = d.registry[ClickTarget.Card(400)]!!
            val theirCreature = d.registry[ClickTarget.Card(430)]!!
            assertEquals(myLand.height, theirCreature.height, 1f, "one size for the table: $myLand, $theirCreature")
            d.savePng(File(pngDir, "anchoring-one-size.png"))
        }
    }

    @Test
    fun `planeswalkers that spill over grow inwards in their own block, by the others`() {
        // As the user's game: six creatures; eight lands, Shorikai and four planeswalkers in the back row.
        val creatures = (0 until 6).map { card(500 + it, "Faerie Dragon $it", creature = true) }
        val lands = (0 until 8).map { card(510 + it, "Land $it", land = true) }
        val shorikai = card(520, "Shorikai, Genesis Engine", type = "Legendary Artifact - Vehicle")
        val walkers = listOf("Elminster", "Jace Token", "Chandra, Chill of Compliance", "Teferi, Time Raveler").mapIndexed { i, n -> card(530 + i, n, type = "Legendary Planeswalker") }
        val seat = FakeSeat(board(creatures + lands + shorikai + walkers, theirLands), null)
        OffscreenDriver(1450, 1100) { CompositionLocalProvider(LocalArt provides art) { BoardScreen(seat, "MTG Oracle", CardMode.ART) } }.use { d ->
            d.settle(5)
            val at = walkers.map { d.registry[ClickTarget.Card(it.id)]!! }
            val land = d.registry[ClickTarget.Card(510)]!!
            assertTrue(at.map { it.top }.toSet().size <= 2, "the planeswalkers on at most two lines: $at")
            assertTrue(at.all { it.left >= d.registry[ClickTarget.Card(520)]!!.right }, "all of them right of Shorikai, in one block: $at")
            assertTrue(at.maxOf { it.bottom } - land.bottom < 4f, "the block's first line is the lands' line: $at, $land")
            d.savePng(File(pngDir, "anchoring-walker-block.png"))
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
