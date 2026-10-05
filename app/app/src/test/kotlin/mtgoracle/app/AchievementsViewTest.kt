package mtgoracle.app

import mtgoracle.data.DbFixture
import mtgoracle.ui.OffscreenDriver
import mtgoracle.ui.kit.ClickTarget
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Forge's achievements in a view of their own, from the library's toolbar:
 * the collections a game here adds to, each achievement's levels, and the
 * card a special one is about in the zoom pane.
 */
class AchievementsViewTest {
    private val assets = File(System.getProperty("mtgoracle.forgeAssets"))
    private lateinit var data: File

    @AfterTest fun close() { if (this::data.isInitialized) data.deleteRecursively() }

    @Test
    fun `the library opens Forge's achievements, levels and cards and all`() {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        data = DbFixture.copy().parentFile
        Scenario.startForge()
        val app = AppController(AppPaths(data, assets, forgeHome = Scenario.home)).also { it.boot() }
        val deadline = System.currentTimeMillis() + 30_000
        while (!app.forgeReady && System.currentTimeMillis() < deadline) Thread.sleep(50)
        OffscreenDriver(1800, 1100) { AppContent(app) {} }.use { d ->
            d.settle(5)
            assertTrue(d.click(ClickTarget.Control("achievements")), "the library's Achievements button")
            d.settle(5)
            assertEquals(Screen.Achievements, app.screen)
            val groups = assertNotNull(app.achievements)
            assertEquals(5, groups.size, "${groups.map { it.name }}")
            val all = groups.flatMap { it.achievements }
            assertTrue(all.filter { !it.special }.all { it.levels.isNotEmpty() }, "a tiered one has its levels")
            val lobotomy = assertNotNull(all.firstOrNull { it.card == "Jace, the Mind Sculptor" }, "a planeswalker's ultimate names its card: ${all.mapNotNull { it.card }.take(5)}")
            assertTrue("of ${all.size} earned" in d.text.all(), "the count")
            val index = all.indexOf(lobotomy)
            repeat(index) { d.key(androidx.compose.ui.input.key.Key.DirectionDown) } // further down the list than the pane shows
            d.settle(5)
            val text = d.text.all()
            assertTrue(lobotomy.name in text && "Jace, the Mind Sculptor" in text, "chosen: its card in the zoom pane")
            val row = assertNotNull(d.registry[ClickTarget.Control("achievement:$index")], "the chosen one scrolled into view")
            val pane = d.registry[ClickTarget.Control("region:achievements")]!!
            assertTrue(row.top >= pane.top && row.bottom <= pane.bottom, "inside the list's pane: $row in $pane")
            d.savePng(File(Scenario.pngDir, "achievements.png"))
            // E: the earned ones only, and back.
            d.key(androidx.compose.ui.input.key.Key.E)
            d.settle(3)
            val earned = all.filter { it.earned }
            assertTrue("earned only" in d.text.all())
            if (earned.isNotEmpty()) assertTrue(earned.first().name in d.text.all())
            assertTrue(all.filter { !it.earned }.none { "[ ] ${it.name}\n" in d.text.all() + "\n" }, "no unearned one listed")
            d.key(androidx.compose.ui.input.key.Key.E)
            d.settle(3)
            d.key(androidx.compose.ui.input.key.Key.Escape)
            d.settle(2)
            assertEquals(Screen.Library, app.screen)
        }
    }
}
