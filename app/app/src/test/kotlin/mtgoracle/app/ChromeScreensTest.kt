package mtgoracle.app

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import mtgoracle.data.DbFixture
import mtgoracle.ui.OffscreenDriver
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.theme.Palette
import mtgoracle.ui.theme.Theme
import mtgoracle.ui.theme.Themes
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The library and the deck workspace in every look that draws its own
 * chrome, on a copy of the database: everything sits where the house look
 * puts it, and each is saved as `chrome-<screen>-<theme>.png` to look at.
 */
class ChromeScreensTest {
    private val assets = File(System.getProperty("mtgoracle.forgeAssets"))
    private lateinit var data: File

    @AfterTest fun close() {
        Palette.theme = Themes.HOUSE
        if (this::data.isInitialized) data.deleteRecursively()
    }

    /** Every clickable target and named region of [screen] in [theme], after saving its picture. */
    private fun layout(app: AppController, theme: Theme, screen: String): Map<ClickTarget, Rect> {
        Palette.theme = theme
        return OffscreenDriver(1800, 1100) { AppContent(app) {} }.use { d ->
            d.settle(5)
            if (screen == "workspace") {
                d.key(Key.Semicolon, char = ':'.code)
                app.lookupUi!!.command.set("t:creature")
                d.frame()
                d.key(Key.Enter)
                d.settle(5)
            }
            d.savePng(File(Scenario.pngDir, "chrome-$screen-${theme.key}.png"))
            d.registry.targets.associateWith { d.registry[it]!! }
        }
    }

    @Test
    fun `the library and the workspace keep their layout in every drawn chrome`() {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        val copy = DbFixture.copy()
        data = copy.parentFile
        val deckId = DriverManager.getConnection("jdbc:sqlite:${copy.path}").use { c ->
            c.prepareStatement("SELECT deck_id FROM deck_cards WHERE is_commander = 1 LIMIT 1").use { st -> st.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else null } }
        }
        assumeTrue(deckId != null, "a commander deck")
        Scenario.startForge()
        val app = AppController(AppPaths(data, assets, forgeHome = Scenario.home))
        app.boot()
        app.select(deckId!!)
        for (screen in listOf("library", "workspace")) {
            if (screen == "workspace") app.edit()
            val house = layout(app, Themes.HOUSE, screen)
            assertTrue(house.size > 10, "$screen registers its regions and controls: ${house.size}")
            for (theme in Themes.ALL.filter { it.chrome != null }) {
                val drawn = layout(app, theme, screen)
                val moved = (house.keys + drawn.keys).filter { house[it] != drawn[it] }.map { "$it: ${house[it]} -> ${drawn[it]}" }
                assertEquals(emptyList(), moved, "$screen in ${theme.key}")
            }
        }
    }
}
