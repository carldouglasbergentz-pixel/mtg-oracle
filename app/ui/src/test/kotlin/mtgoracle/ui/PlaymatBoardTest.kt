package mtgoracle.ui

import androidx.compose.ui.geometry.Rect
import mtgoracle.ui.board.BoardScreen
import mtgoracle.ui.board.MatAnchor
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
            BoardScreen(FakeSeat(quietBoard(), null), "MTG Oracle", CardMode.TEXT, myMat = Playmat(file, 0.3f, MatAnchor.MIDDLE))
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
}
