package mtgoracle.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import mtgoracle.core.model.BoardState
import mtgoracle.ui.board.BoardScreen
import mtgoracle.ui.board.planZoneColumn
import mtgoracle.ui.kit.ArtImages
import mtgoracle.ui.kit.CardMode
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.LocalArt
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The type zones and the zone column: nothing off the board, nothing drawn over anything, at any size. */
class ZonesTest {
    private val art = ArtImages(FakeArt(File(pngDir, "fake-art")))

    /** My side: 9 lands, 3 artifacts, 2 planeswalkers, 6 creatures — with a long graveyard and exile. */
    private fun crowded(): BoardState {
        val b = quietBoard()
        var id = 500
        fun next() = id++
        val lands = listOf("Island", "Island", "Island", "Plains", "Plains", "Hallowed Fountain", "Flooded Strand", "Mystic Sanctuary", "Meticulous Archive")
            .map { card(next(), it, land = true) }
        val artifacts = listOf("Sol Ring", "Mind Stone", "Pithing Needle").map { card(next(), it, cost = "{1}", type = "Artifact") }
        val walkers = listOf("Teferi, Hero of Dominaria", "Jace, the Mind Sculptor").map { card(next(), it, cost = "{3}{W}{U}", type = "Legendary Planeswalker — Teferi") }
        val creatures = (1..6).map { card(next(), "Creature $it", creature = true, cost = "{1}{W}") }
        return b.copy(players = b.players.map { p ->
            if (p.isSeat) p.copy(battlefield = lands + artifacts + walkers + creatures,
                graveyard = (1..17).map { card(next(), if (it == 17) "Remand" else "Spell $it", cost = "{1}{U}") },
                exile = (1..5).map { card(next(), "Exiled $it") }, libraryCount = 63)
            else p
        })
    }

    private fun OffscreenDriver.rect(name: String) = registry[ClickTarget.Control("region:$name")]!!

    @Test
    fun `the crowded side fits the board at 1920x1080, 1600x900 and 1280x720 - every card inside, every card reachable`() {
        val board = crowded()
        val mine = board.seat!!.battlefield.map { it.id }
        for ((w, h) in listOf(1920 to 1080, 1600 to 900, 1280 to 720)) for (mode in CardMode.entries) {
            OffscreenDriver(w, h) { CompositionLocalProvider(LocalArt provides art) { BoardScreen(FakeSeat(board, null), "MTG Oracle", mode) } }.use { d ->
                d.settle(5)
                val field = d.rect("near-field")
                val onScreen = mine.mapNotNull { d.registry[ClickTarget.Card(it)] }.filter { it.width > 0 && it.height > 0 }
                for (r in onScreen) assertTrue(field.contains(r.topLeft) && r.right <= field.right + 1 && r.bottom <= field.bottom + 1, "${w}x$h $mode: $r inside $field")
                // Piles register once (their first card): what isn't on screen is counted by a +N marker.
                val text = d.text.all()
                val hidden = Regex("\\+(\\d+) [▸>]").findAll(text).sumOf { it.groupValues[1].toInt() }
                val slots = 6 + 3 + 2 + 6 // nine lands in six piles (Island ×3, Plains ×2)
                assertTrue(onScreen.size + hidden >= slots, "${w}x$h $mode: ${onScreen.size} shown + $hidden counted of $slots")
                // Adaptive frames: in art mode, every card shows down to 1280x720 — smaller frames before any "+N".
                if (h >= 720) assertEquals(0, hidden, "${w}x$h $mode: nothing behind +N")
                d.savePng(File(pngDir, "zones-crowded-${w}x$h-${mode.name.lowercase()}.png"))
            }
        }
    }

    @Test
    fun `a long graveyard and exile never overlap their neighbours, at any height`() {
        val board = crowded()
        for ((w, h) in listOf(1920 to 1080, 1600 to 900, 1280 to 720)) {
            OffscreenDriver(w, h) { CompositionLocalProvider(LocalArt provides art) { BoardScreen(FakeSeat(board, null), "MTG Oracle", CardMode.ART) } }.use { d ->
                d.settle(5)
                val column = d.rect("near-zones")
                // Every graveyard and exile chip that is drawn lies inside the column, and no two overlap.
                val chips = board.seat!!.let { it.graveyard + it.exile }.mapNotNull { d.registry[ClickTarget.Card(it.id)] }.filter { it.height > 0 }
                assertTrue(chips.isNotEmpty())
                chips.forEach { assertTrue(it.top >= column.top && it.bottom <= column.bottom + 1, "${w}x$h: $it in $column") }
                chips.forEachIndexed { i, a -> chips.drop(i + 1).forEach { b -> assertTrue(!overlapping(a, b), "${w}x$h: $a over $b") } }
                assertTrue("graveyard 17" in d.text.all() && "library 63" in d.text.all())
                d.savePng(File(pngDir, "zones-column-${w}x$h.png"))
            }
        }
    }

    @Test
    fun `the zone column plan - fixed parts always, the ladder when it fits, lists get the rest`() {
        val roomy = planZoneColumn(30, graveyard = 17, exile = 5, ladderWanted = true)
        assertEquals(7, roomy.ladderRows)
        assertTrue(roomy.graveyardRows in 1..6 && roomy.exileRows in 1..3)
        val tight = planZoneColumn(11, graveyard = 17, exile = 5, ladderWanted = true)
        assertEquals(listOf(1, 1, 1), listOf(tight.ladderRows, tight.graveyardRows, tight.exileRows), "the ladder folds to its line first")
        assertEquals(1, planZoneColumn(30, 0, 0, ladderWanted = true).graveyardRows, "an empty list keeps its one line")
    }

    @Test
    fun `a storm of board changes re-plans the zones without tripping Compose`() {
        val full = crowded()
        val seat = FakeSeat(quietBoard(), null)
        OffscreenDriver(1920, 1080) { CompositionLocalProvider(LocalArt provides art) { BoardScreen(seat, "MTG Oracle", CardMode.ART) } }.use { d ->
            val mine = full.seat!!.battlefield
            for (i in 0..60) {
                // Cards arriving and leaving, piles splitting, bands re-planning, a frame at a time.
                val n = (i * 7) % (mine.size + 1)
                seat.board.value = full.copy(players = full.players.map { p ->
                    if (p.isSeat) p.copy(battlefield = mine.take(n).map { if (i % 3 == 0) it.copy(tapped = !it.tapped) else it }) else p
                })
                d.frame()
            }
            d.settle(3)
        }
    }

    private fun overlapping(a: Rect, b: Rect) = a.left < b.right - 1 && b.left < a.right - 1 && a.top < b.bottom - 1 && b.top < a.bottom - 1
}
