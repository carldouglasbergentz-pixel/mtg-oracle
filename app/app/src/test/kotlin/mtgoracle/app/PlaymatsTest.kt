package mtgoracle.app

import mtgoracle.data.DbFixture
import mtgoracle.ui.OffscreenDriver
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.library.MatAction
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Playmats from the lobby: a picture from the clipboard (its file, or the
 * picture itself) into data\playmats, each side's chosen with < and >, a
 * mat's dim and the part it shows kept for the next start.
 */
class PlaymatsTest {
    private val assets = File(System.getProperty("mtgoracle.forgeAssets"))
    private lateinit var data: File
    @AfterTest fun close() { if (this::data.isInitialized) data.deleteRecursively() }

    private fun picture(w: Int = 610, h: Int = 350): BufferedImage = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).apply {
        createGraphics().apply { color = Color(30, 110, 60); fillRect(0, 0, w, h); color = Color(200, 170, 40); fillRect(0, 0, w, h / 3); dispose() }
    }

    @Test
    fun `a playmat added from the clipboard, chosen for each side, its dim and part kept`() {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        data = DbFixture.copy().parentFile
        val app = AppController(AppPaths(data, assets, forgeHome = Scenario.home)).also { it.boot() }
        assertTrue(File(data, "playmats").isDirectory, "the folder is there from the start")

        // A file copied in Explorer (its path), then a picture copied from somewhere (saved as PNG).
        val file = File(data, "Forest Glade.png").also { ImageIO.write(picture(), "png", it) }
        app.readClipboard = { file.path }
        assertTrue(app.mats.act(MatAction.Add)!!.startsWith("playmat Forest Glade.png added"))
        app.readClipboard = { "not a path" }
        app.clipboardImage = { picture(400, 200) }
        assertTrue(app.mats.act(MatAction.Add)!!.startsWith("playmat playmat.png added"))
        assertEquals(listOf("Forest Glade.png", "playmat.png"), app.mats.names())
        assertEquals("playmat.png", app.mats.mine, "the one added is yours")
        app.clipboardImage = { null }
        assertTrue(app.mats.act(MatAction.Add)!!.startsWith("no playmat added: the clipboard holds no picture"))

        // The AI's: none, then the first; yours dimmed and showing its top.
        app.mats.act(MatAction.Cycle(mine = false, by = 1))
        assertEquals("Forest Glade.png", app.mats.theirs)
        app.mats.act(MatAction.Dim(mine = true, tenths = 7))
        app.mats.act(MatAction.Frame(mine = true, x = 0.2f, y = 0f, zoom = 1.5f))
        val again = AppController(AppPaths(data, assets, forgeHome = Scenario.home))
        val mat = assertNotNull(again.mats.mat(again.mats.mine), "kept for the next start")
        assertEquals(0.7f, mat.dim, 0.001f)
        assertEquals(Triple(0.2f, 0f, 1.5f), Triple(mat.x, mat.y, mat.zoom))
        assertEquals("Forest Glade.png", again.mats.theirs)

        app.play.openLobby(app.decks.first().id)
        OffscreenDriver(1600, 1000) { AppContent(app) {} }.use { d ->
            d.settle(5)
            assertTrue("playmats" in d.text.all() && "playmat.png" in d.text.all(), d.text.all())
            assertTrue(d.click(ClickTarget.Control("mat:me:dim:3")))
            assertEquals(0.3f, app.mats.mat(app.mats.mine)!!.dim, 0.001f)
            // A drag places the picture, kept once let go: down shows more of its top (y falls), right more of its left.
            val before = app.mats.mat(app.mats.mine)!!
            assertTrue(d.drag(ClickTarget.Control("region:mat-preview-me"), androidx.compose.ui.geometry.Offset(40f, 30f)))
            d.settle(3)
            val after = app.mats.mat(app.mats.mine)!!
            assertTrue(after.y < before.y || before.y == 0f, "dragged down, the picture moves down: y ${before.y} -> ${after.y}")
            assertTrue(after.x < before.x, "dragged right: x ${before.x} -> ${after.x}")
            assertEquals(before.zoom, after.zoom)
            assertTrue(d.click(ClickTarget.Control("mat:me:zoom-in")))
            assertEquals(before.zoom + 0.1f, app.mats.mat(app.mats.mine)!!.zoom, 0.001f)
            assertTrue(d.click(ClickTarget.Control("mat:me:reset")))
            assertEquals(Triple(0.5f, 0.5f, 1f), app.mats.mat(app.mats.mine)!!.let { Triple(it.x, it.y, it.zoom) })
            d.settle(3)
            d.savePng(File(Scenario.pngDir, "playmats-lobby.png"))
        }
    }
}
