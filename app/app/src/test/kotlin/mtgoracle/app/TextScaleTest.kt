package mtgoracle.app

import androidx.compose.ui.input.key.Key
import mtgoracle.data.DbFixture
import mtgoracle.ui.OffscreenDriver
import mtgoracle.ui.kit.ClickTarget
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Ctrl+= and Ctrl+- size the whole window, kept for the next start; Ctrl+0 goes back to the designed size. */
class TextScaleTest {
    private val assets = File(System.getProperty("mtgoracle.forgeAssets"))
    private lateinit var data: File

    @AfterTest fun close() { if (this::data.isInitialized) data.deleteRecursively() }

    @Test
    fun `the window grows a step, the step is kept, and Ctrl+0 brings it back`() {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        data = DbFixture.copy().parentFile
        val app = AppController(AppPaths(data, assets, forgeHome = Scenario.home)).also { it.boot() }
        OffscreenDriver(1600, 900) { AppContent(app) {} }.use { d ->
            d.settle(5)
            val toolbar = { d.registry[ClickTarget.Control("region:toolbar")]!!.height }
            val designed = toolbar()
            d.key(Key.Equals, ctrl = true)
            d.settle(5)
            assertEquals(1.1f, app.textScale)
            assertTrue(toolbar() > designed * 1.05f, "everything is drawn larger: $designed -> ${toolbar()}")
            assertEquals(1.1f, Settings(AppPaths(data, assets, forgeHome = Scenario.home).settings).textScale, "kept for the next start")
            d.key(Key.Minus, ctrl = true); d.key(Key.Minus, ctrl = true)
            d.settle(3)
            assertEquals(0.9f, app.textScale)
            d.key(Key.Zero, ctrl = true)
            d.settle(5)
            assertEquals(1f, app.textScale)
            assertEquals(designed, toolbar(), "back at the designed size")
        }
    }
}
