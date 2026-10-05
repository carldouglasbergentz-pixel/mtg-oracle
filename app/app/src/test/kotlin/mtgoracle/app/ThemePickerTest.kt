package mtgoracle.app

import androidx.compose.ui.input.key.Key
import mtgoracle.data.DbFixture
import mtgoracle.ui.OffscreenDriver
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.theme.Palette
import mtgoracle.ui.theme.Themes
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The theme picker: the toolbar's Theme button and F8 open it, the mouse or
 * the arrows show a theme on everything at once, Enter or a click keeps it
 * (and the next start has it), Esc goes back to the one kept.
 */
class ThemePickerTest {
    private val assets = File(System.getProperty("mtgoracle.forgeAssets"))
    private lateinit var data: File
    private val picker = ClickTarget.Control("region:theme-picker")

    @AfterTest fun close() {
        Palette.theme = Themes.HOUSE
        if (this::data.isInitialized) data.deleteRecursively()
    }

    @Test
    fun `the picker shows a theme as you point at it, keeps it on a click, and Esc goes back`() {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        data = DbFixture.copy().parentFile
        Scenario.startForge()
        val app = AppController(AppPaths(data, assets, forgeHome = Scenario.home)).also { it.boot() }
        app.keepTheme(Themes.HOUSE)
        OffscreenDriver(1800, 1100) { AppContent(app) {} }.use { d ->
            d.settle(5)
            assertTrue(d.click(ClickTarget.Control("theme")), "the toolbar's Theme button")
            d.settle(3)
            assertNotNull(d.registry[picker], "the picker is open")
            d.savePng(File(Scenario.pngDir, "theme-picker-house.png"))
            d.hover(ClickTarget.Control("theme:win95"))
            d.settle(3)
            assertEquals(Themes.WIN95, Palette.theme, "pointing at a theme shows it")
            d.savePng(File(Scenario.pngDir, "theme-picker-win95.png"))
            d.key(Key.Escape)
            d.settle(3)
            assertNull(d.registry[picker], "Esc closes it")
            assertEquals(Themes.HOUSE, Palette.theme, "and goes back to the theme kept")

            // Away from where the picker opens: a row under the mouse is shown as soon as it appears.
            d.hover(ClickTarget.Control("play"))
            d.key(Key.F8)
            d.settle(3)
            assertNotNull(d.registry[picker], "F8 opens it too")
            d.key(Key.DirectionDown)
            assertEquals(Themes.ROSE_PINE, Palette.theme, "↓ shows the next one")
            d.key(Key.Enter)
            d.settle(3)
            assertNull(d.registry[picker])
            assertEquals(Themes.ROSE_PINE, Palette.theme, "Enter keeps it")

            d.key(Key.F8)
            d.settle(3)
            assertTrue(d.click(ClickTarget.Control("theme:win95")), "a click on a row")
            d.settle(3)
            assertNull(d.registry[picker])
        }
        assertEquals(Themes.WIN95, Palette.theme)
        Palette.theme = Themes.HOUSE
        AppController(AppPaths(data, assets, forgeHome = Scenario.home))
        assertEquals(Themes.WIN95, Palette.theme, "the next start has the theme kept")
    }
}
