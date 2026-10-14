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
    fun `the back row stays one row, the cards smaller, while a smaller size can keep it`() {
        // As the user's game on 2026-10-14: nine lands, Elixir of Immortality and Teferi, at 2230 x 1351 and 125 %.
        val lands = listOf("Island", "Plains", "Theorist's Sanctum", "Karakas", "Tundra", "Floodfarm Verge", "Prismatic Vista", "Undercity Sewers", "Marsh Flats")
            .mapIndexed { i, n -> card(600 + i, n, land = true) }
        val elixir = card(610, "Elixir of Immortality", type = "Artifact")
        val teferi = card(611, "Teferi, Time Raveler", type = "Legendary Planeswalker - Teferi")
        val seat = FakeSeat(board(lands + elixir + teferi, theirLands), null)
        OffscreenDriver(2230, 1351, 1.25f) { CompositionLocalProvider(LocalArt provides art) { BoardScreen(seat, "MTG Oracle", CardMode.ART) } }.use { d ->
            d.settle(5)
            val tops = (lands + elixir + teferi).map { d.registry[ClickTarget.Card(it.id)]!!.top }.toSet()
            assertEquals(1, tops.size, "one back row: $tops")
            d.savePng(File(pngDir, "anchoring-one-back-row.png"))
        }
    }

    @Test
    fun `a manland, nine land piles, two artifacts and a token planeswalker keep their two rows`() {
        // As the user's game on 2026-10-14 (snapshot 0.7.2 had Sol Ring alone on a level of its own).
        val colossus = card(700, "Celestial Colossus", creature = true, type = "Creature Land")
        val lands = List(3) { card(710 + it, "Island", land = true, tapped = true) } + List(2) { card(713 + it, "Plains", land = true, tapped = true) } +
            listOf("Theorist's Sanctum", "Karakas", "Tundra", "Floodfarm Verge", "Undercity Sewers", "Arch of Orazca").mapIndexed { i, n -> card(720 + i, n, land = true) }
        val artifacts = listOf(card(730, "Sol Ring", type = "Artifact", tapped = true), card(731, "Elixir of Immortality", type = "Artifact"))
        val jace = card(740, "Jace Token", type = "Planeswalker - Jace").copy(isToken = true)
        val seat = FakeSeat(board(listOf(colossus) + lands + artifacts + jace, theirLands), null)
        OffscreenDriver(2230, 1351, 1.25f) { CompositionLocalProvider(LocalArt provides art) { BoardScreen(seat, "MTG Oracle", CardMode.ART) } }.use { d ->
            d.settle(5)
            val back = (lands + artifacts + jace).mapNotNull { d.registry[ClickTarget.Card(it.id)] } // a pile is clicked by its first
            assertEquals(1, back.map { it.top }.toSet().size, "one back row: ${back.map { it.top }.toSet()}")
            d.savePng(File(pngDir, "anchoring-manland-artifacts.png"))
        }
    }

    @Test
    fun `eleven lands and a planeswalker - one back row, the planeswalker right of the lands`() {
        // As the user's game on 2026-10-14 (0.7.2 drew large overlapped lands and Teferi alone on a line above them).
        val lands = listOf("Meticulous Archive", "Undercity Sewers", "Island", "Windswept Heath", "Restless Anchorage", "Library of Alexandria",
            "Plains", "Tundra", "Field of Ruin", "Kor Haven", "Marsh Flats").mapIndexed { i, n -> card(800 + i, n, land = true) }
        val teferi = card(820, "Teferi, Hero of Dominaria", type = "Legendary Planeswalker - Teferi")
        val seat = FakeSeat(board(lands + teferi, theirLands), null)
        OffscreenDriver(2230, 1351, 1.25f) { CompositionLocalProvider(LocalArt provides art) { BoardScreen(seat, "MTG Oracle", CardMode.ART) } }.use { d ->
            d.settle(5)
            val at = d.registry[ClickTarget.Card(820)]!!
            val landRects = lands.map { d.registry[ClickTarget.Card(it.id)]!! }
            assertEquals(setOf(at.top), landRects.map { it.top }.toSet(), "Teferi on the lands' row")
            assertTrue(at.left >= landRects.maxOf { it.right }, "right of the lands: $at")
            d.savePng(File(pngDir, "anchoring-eleven-lands.png"))
        }
    }

    @Test
    fun `a manland animated - the creature row comes, the back row keeps its row`() {
        // As the user's game on 2026-10-14: Restless Anchorage animated and attacking, ten lands, a Map token, Teferi.
        val anchorage = card(900, "Restless Anchorage", creature = true, tapped = true, type = "Creature Land - Bird").copy(attacking = true)
        val lands = listOf("Meticulous Archive", "Undercity Sewers", "Island", "Windswept Heath", "Library of Alexandria", "Plains",
            "Tundra", "Field of Ruin", "Kor Haven", "Marsh Flats").mapIndexed { i, n -> card(910 + i, n, land = true, tapped = n == "Island" || n == "Plains") }
        val map = card(930, "Map Token", type = "Artifact - Map").copy(isToken = true)
        val teferi = card(931, "Teferi, Hero of Dominaria", type = "Legendary Planeswalker - Teferi")
        val seat = FakeSeat(board(listOf(anchorage) + lands + map + teferi, theirLands), null)
        OffscreenDriver(2230, 1351, 1.25f) { CompositionLocalProvider(LocalArt provides art) { BoardScreen(seat, "MTG Oracle", CardMode.ART) } }.use { d ->
            d.settle(5)
            val back = (lands + map + teferi).map { d.registry[ClickTarget.Card(it.id)]!! }
            assertEquals(1, back.map { it.top }.toSet().size, "one back row")
            d.savePng(File(pngDir, "anchoring-manland-animated.png"))
            // The turn after: the manland a land again, eleven lands, the Map token and Teferi. 0.7.2 split the lands over two lines.
            seat.board.value = board(listOf(anchorage.copy(isCreature = false, isLand = true, tapped = false, attacking = false, power = null, toughness = null, typeLine = "Land")) + lands + map + teferi, theirLands)
            d.settle(4)
            d.savePng(File(pngDir, "anchoring-manland-back.png"))
            // No size keeps all thirteen on one line here: the lands keep theirs (a turned card sits lower in its slot, so by bottom).
            val landsAgain = (listOf(anchorage) + lands).map { d.registry[ClickTarget.Card(it.id)]!! }
            assertEquals(1, landsAgain.map { it.bottom.toInt() / 16 }.toSet().size, "the lands on one line: ${landsAgain.map { it.bottom }}")
            val near = d.registry[ClickTarget.Control("region:near-field")]!!
            assertEquals(2, d.text.all().lines().count { it.contains("─ lands") }, "one lands label a side")
            // Map and Teferi above, ending where the lands end.
            val above = listOf(map, teferi).map { d.registry[ClickTarget.Card(it.id)]!! }
            assertTrue(above.all { it.bottom < landsAgain.minOf { l -> l.top } }, "above the lands: $above")
            assertTrue(kotlin.math.abs(above.maxOf { it.right } - landsAgain.maxOf { it.right }) < 3 * 16f, "at the lands' right end: $above, $near")
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
