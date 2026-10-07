package mtgoracle.ui

import androidx.compose.ui.geometry.Rect
import mtgoracle.ui.board.BoardScreen
import mtgoracle.ui.board.coverSize
import androidx.compose.ui.unit.IntSize
import kotlin.test.assertEquals
import mtgoracle.ui.board.Playmat
import mtgoracle.ui.kit.CardMode
import mtgoracle.ui.kit.ClickTarget
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/** A playmat lies under its half's battlefield, filling it, dimmed: the other half keeps the plain table. */
class PlaymatBoardTest {
    @Test
    fun `your mat is under your battlefield, not the opponent's`() {
        val file = File(pngDir, "test-mat.png").also { f ->
            ImageIO.write(BufferedImage(600, 350, BufferedImage.TYPE_INT_RGB).apply { createGraphics().apply { color = Color(30, 130, 200); fillRect(0, 0, 600, 350); dispose() } }, "png", f)
        }
        OffscreenDriver(1600, 900) {
            BoardScreen(FakeSeat(quietBoard(), null), "MTG Oracle", CardMode.TEXT, myMat = Playmat(file, 0.3f))
        }.use { d ->
            d.settle(5)
            fun bluish(region: String): Int {
                val r = d.registry[ClickTarget.Control("region:$region")]!!
                // The field's right end, where no card is: the mat or the plain table.
                val strip = d.pixels(Rect(r.right - 60, r.top + 30, r.right - 20, r.bottom - 30))
                return strip.count { c -> (c and 0xFF) > 110 && (c shr 16 and 0xFF) < 80 }
            }
            assertTrue(bluish("near-field") > 100, "the mat shows under your half")
            assertTrue(bluish("far-field") == 0, "and not under the opponent's")
            d.savePng(File(pngDir, "playmat-board.png"))
        }
    }

    @Test
    fun `the picture covers its space, enlarged by the zoom, and the place picks the part shown`() {
        // A 600 × 350 mat on a 1500 × 400 half: scaled to the width (2.5), 875 high, so 475 to move up and down.
        assertEquals(1500f to 875f, coverSize(IntSize(600, 350), 1500f, 400f, zoom = 1f))
        assertEquals(3000f to 1750f, coverSize(IntSize(600, 350), 1500f, 400f, zoom = 2f), "doubled: room both ways")
        assertEquals(1500f to 875f, coverSize(IntSize(600, 350), 1500f, 400f, zoom = 0.5f), "never less than covering")
        // Its top third yellow: placed at the top, the strip is yellow; at the bottom, green.
        val file = File(pngDir, "test-mat-bands.png").also { f ->
            ImageIO.write(BufferedImage(600, 350, BufferedImage.TYPE_INT_RGB).apply {
                createGraphics().apply { color = Color(30, 110, 60); fillRect(0, 0, 600, 350); color = Color(220, 190, 30); fillRect(0, 0, 600, 116); dispose() }
            }, "png", f)
        }
        fun yellowAt(y: Float): Int = OffscreenDriver(1600, 900) {
            BoardScreen(FakeSeat(quietBoard(), null), "MTG Oracle", CardMode.TEXT, myMat = Playmat(file, 0f, x = 0.5f, y = y))
        }.use { d ->
            d.settle(5)
            val r = d.registry[ClickTarget.Control("region:near-field")]!!
            d.pixels(Rect(r.right - 60, r.top + 30, r.right - 20, r.bottom - 30)).count { c -> (c shr 16 and 0xFF) > 180 && (c shr 8 and 0xFF) > 150 && (c and 0xFF) < 90 }
        }
        assertTrue(yellowAt(0f) > 100, "placed at the top: its yellow top shows")
        assertEquals(0, yellowAt(1f), "placed at the bottom: none of it")
    }
}
