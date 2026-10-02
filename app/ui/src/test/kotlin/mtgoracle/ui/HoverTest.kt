package mtgoracle.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toArgb
import mtgoracle.core.model.BoardState
import mtgoracle.ui.board.BoardScreen
import mtgoracle.ui.kit.ArtImages
import mtgoracle.ui.kit.CardChip
import mtgoracle.ui.kit.CardFrame
import mtgoracle.ui.kit.CardMode
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.Emphasis
import mtgoracle.ui.kit.LocalArt
import mtgoracle.ui.kit.face
import mtgoracle.ui.theme.Palette
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the mouse is on is marked with the hover tone behind it: a chip, a
 * frame, a card on the table (an overlapped one through its strip). Card art
 * is never tinted: its pixels are the same hovered or not.
 */
class HoverTest {
    private val art = ArtImages(FakeArt(File(pngDir, "fake-art")), decoder = { it.run() })
    private val hover get() = Palette.hover.toArgb()
    /** FakeArt's crop colour, as its art reads on screen. */
    private fun isArt(c: Int) = near(c, org.jetbrains.skia.Color.makeRGB(140, 90, 60))
    private fun near(a: Int, b: Int) = listOf(16, 8, 0).all { s -> kotlin.math.abs((a shr s and 0xFF) - (b shr s and 0xFF)) <= 3 }
    private fun hovered(d: OffscreenDriver, t: ClickTarget) = d.pixels(d.registry[t]!!).count { near(it, hover) }

    @Test
    fun `a chip is marked while the mouse is on it, and not after`() {
        val island = card(1, "Island", land = true)
        val swamp = card(2, "Swamp", land = true)
        OffscreenDriver(600, 200) {
            Column {
                CardChip(island.face(), Emphasis.NONE, ClickTarget.Card(1), {}, {})
                CardChip(swamp.face(), Emphasis.NONE, ClickTarget.Card(2), {}, {})
            }
        }.use { d ->
            d.settle(3)
            assertEquals(0, hovered(d, ClickTarget.Card(1)), "nothing is marked before the mouse comes")
            d.hover(ClickTarget.Card(1))
            val area = d.pixels(d.registry[ClickTarget.Card(1)]!!).size
            assertTrue(hovered(d, ClickTarget.Card(1)) > area / 2, "the Island's chip is marked")
            d.savePng(File(pngDir, "hover-chip.png"))
            d.hover(ClickTarget.Card(2))
            assertEquals(0, hovered(d, ClickTarget.Card(1)), "the mark follows the mouse")
            assertTrue(hovered(d, ClickTarget.Card(2)) > 0)
        }
    }

    @Test
    fun `a frame is marked behind its art, and the art is untouched`() {
        val bears = card(1, "Grizzly Bears", creature = true, cost = "{1}{G}")
        OffscreenDriver(800, 600) {
            CompositionLocalProvider(LocalArt provides art) { CardFrame(bears.face(), CardMode.ART, Emphasis.NONE, ClickTarget.Card(1), {}, {}) }
        }.use { d ->
            d.settle(5)
            val rect = d.registry[ClickTarget.Card(1)]!!
            val before = d.pixels(rect)
            assertTrue(before.count(::isArt) > 0, "the art is drawn")
            d.hover(ClickTarget.Card(1))
            val after = d.pixels(rect)
            assertTrue(after.count { near(it, hover) } > 0, "the frame's background takes the hover tone")
            assertEquals(before.indices.filter { isArt(before[it]) }, after.indices.filter { isArt(after[it]) }, "every art pixel is where it was, untinted")
        }
    }

    @Test
    fun `an overlapped card on the table is marked through the strip of it showing`() {
        // More creatures than the half has room for: the table overlaps them.
        val crowd = (100 until 116).map { card(it, "Creature $it", creature = true, cost = "{1}") }
        val board: BoardState = quietBoard().let { b -> b.copy(players = b.players.map { p -> if (p.isSeat) p.copy(battlefield = crowd) else p }) }
        OffscreenDriver(1280, 720) {
            CompositionLocalProvider(LocalArt provides art) { BoardScreen(FakeSeat(board, null), "MTG Oracle", CardMode.ART) }
        }.use { d ->
            d.settle(5)
            val widths = crowd.associate { it.id to (d.registry[ClickTarget.Card(it.id)]?.width ?: 0f) }
            val strip = widths.entries.filter { it.value > 0 }.minBy { it.value }
            assertTrue(strip.value < widths.values.max(), "some creature is overlapped: $widths")
            val target = ClickTarget.Card(strip.key)
            val before = d.pixels(d.registry[target]!!)
            d.hover(target)
            val after = d.pixels(d.registry[target]!!)
            assertTrue(after.count { near(it, hover) } > before.count { near(it, hover) }, "the strip's card is marked")
            d.savePng(File(pngDir, "hover-board.png"))
            assertEquals(before.indices.filter { isArt(before[it]) }, after.indices.filter { isArt(after[it]) }, "and its art is not tinted")
        }
    }
}
