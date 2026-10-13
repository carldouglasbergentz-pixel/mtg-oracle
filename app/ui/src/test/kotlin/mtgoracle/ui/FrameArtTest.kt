package mtgoracle.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.CompositionLocalProvider
import mtgoracle.ui.kit.ArtImages
import mtgoracle.ui.kit.CardFace
import mtgoracle.ui.kit.CardFrame
import mtgoracle.ui.kit.CardMode
import mtgoracle.ui.kit.FrameTier
import mtgoracle.ui.kit.LocalArt
import mtgoracle.ui.theme.Cells
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The art crop stays inside its frame, at every size and density. A frame
 * 28 cells of 7.15 px wide is laid out at 200 px, not 200.2, and its border,
 * counting whole cells, was drawn one cell short: inside the art of the
 * large land frames.
 */
class FrameArtTest {
    private val art = ArtImages(FakeArt(File(pngDir, "fake-art")))

    @Test
    fun `a size rounded to the pixel is still its whole cells`() {
        val cells = Cells(7.15f, 15f)
        assertEquals(28, cells.cols(200f))
        assertEquals(27, cells.cols(199f), "a cell short is still short")
        assertEquals(11, cells.rows(165f))
    }

    @Test
    fun `the art stays inside the frame`() {
        for (density in listOf(1f, 1.25f, 1.5f)) {
            val land = CardFace(name = "Mystic Sanctuary", manaCost = "", typeLine = "Land - Island", stats = "", text = "", imageKey = "c:Mystic Sanctuary")
            val creature = CardFace(name = "Grizzly Bears", manaCost = "{1}{G}", typeLine = "Creature - Bear", stats = "2/2", text = "", imageKey = "c:Grizzly Bears")
            val png = File(pngDir, "frame-art-$density.png")
            OffscreenDriver((1700 * density).toInt(), (700 * density).toInt(), density) {
                CompositionLocalProvider(LocalArt provides art) {
                    Column {
                        for (face in listOf(land, creature)) Row {
                            for (tier in FrameTier.entries.filter { it != FrameTier.TEXT }) {
                                CardFrame(face, CardMode.ART, turnable = true, land = face === land, rotate = true, tier = tier)
                            }
                        }
                    }
                }
            }.use { d ->
                d.settle(4)
                d.savePng(png)
            }
            assertArtInside(png, density)
        }
    }

    /**
     * On every row through the fake art: no frame's side line crosses the
     * art, and one stands right of it before the next art. A side line is a
     * column grey on most of the rows around (the border's `│`, a glyph per
     * row); text is grey too, but never down a column.
     */
    private fun assertArtInside(png: File, density: Float) {
        val image = ImageIO.read(png)
        fun channels(rgb: Int) = Triple((rgb shr 16) and 255, (rgb shr 8) and 255, rgb and 255)
        fun isArt(rgb: Int) = channels(rgb).let { (r, g, b) -> r in 128..152 && g in 78..102 && b in 48..72 }
        fun isGrey(rgb: Int) = channels(rgb).let { (r, g, b) -> r in 70..230 && maxOf(r, g, b) - minOf(r, g, b) < 16 }
        val reach = (40 * density).toInt()
        // Above or below the row: one near a frame's top or bottom edge has the table on the other side.
        fun isSideLine(x: Int, y: Int): Boolean = listOf(maxOf(0, y - reach)..y, y..minOf(image.height - 1, y + reach)).any { ys ->
            ys.count { isGrey(image.getRGB(x, it)) } * 10 >= (ys.last - ys.first + 1) * 7
        }
        var checked = 0
        for (y in 0 until image.height step 3) {
            val row = IntArray(image.width) { image.getRGB(it, y) }
            var x = 0
            while (x < row.size) {
                if (!isArt(row[x])) { x++; continue }
                val start = x
                // A run of art, through a pixel or two of anything (a line drawn over it is what is looked for).
                while (x < row.size && (0..3).any { x + it < row.size && isArt(row[x + it]) }) x++
                val end = x - 1
                val crossing = (start..end).filter { !isArt(row[it]) && isSideLine(it, y) }
                assertTrue(crossing.isEmpty(), "density $density, row $y: a frame's side crosses the art at x $start..$end, at $crossing")
                val nextArt = (end + 1 until row.size).firstOrNull { isArt(row[it]) } ?: row.size
                assertTrue((end + 1 until nextArt).any { isSideLine(it, y) }, "density $density, row $y: the art at x $start..$end has no side right of it")
                checked++
            }
        }
        assertTrue(checked > 50, "the art was found on enough rows: $checked")
    }
}
