package mtgoracle.app

import mtgoracle.data.DbFixture
import mtgoracle.ui.OffscreenDriver
import mtgoracle.ui.kit.ClickTarget
import androidx.compose.ui.graphics.toArgb
import mtgoracle.ui.library.GuideControls
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Getting started: on a first start (no data, no decks) the checklist is in
 * the library and the tour over it, once; a library with decks shows neither
 * by itself, and Guide (or `guide`) brings the checklist back. Hide puts it
 * away for good.
 */
class GuideTest {
    private val assets = File(System.getProperty("mtgoracle.forgeAssets"))
    private val dirs = mutableListOf<File>()
    @AfterTest fun clean() = dirs.forEach { it.deleteRecursively() }

    private fun app(data: File) = AppController(AppPaths(data, assets, forgeHome = Scenario.home)).also { it.boot() }
    private fun settings(data: File) = Settings(AppPaths(data, assets, forgeHome = Scenario.home).settings)

    @Test
    fun `a first start opens the checklist and the tour, and each goes for good when done`() {
        val data = createTempDirectory("mtg-oracle-guide-").toFile().also { dirs += it }
        val app = app(data)
        assertTrue(app.guideOpen && app.touring, "no data and no decks: the guide and the tour")
        OffscreenDriver(1600, 900) { AppContent(app) {} }.use { d ->
            d.settle(5)
            val first = d.text.all()
            assertNotNull(d.registry[ClickTarget.Control("region:guide")], "the checklist in the middle column")
            assertTrue("Card data" in first && "None yet" in first && "Your first deck" in first, first)
            assertTrue("1 of 5" in first && "Welcome to MTG Oracle" in first, "the tour's first stop")
            d.savePng(File(Scenario.pngDir, "guide-first-start.png"))
            assertTrue(d.click(ClickTarget.Control("tour:next")))
            d.settle(3)
            assertTrue("2 of 5" in d.text.all() && "Your decks" in d.text.all())
            d.savePng(File(Scenario.pngDir, "guide-tour-decks.png"))
            repeat(3) { d.click(ClickTarget.Control("tour:next")); d.settle(2) }
            assertTrue("5 of 5" in d.text.all() && "[ Done ]" in d.text.all())
            d.click(ClickTarget.Control("tour:next"))
            d.settle(2)
            assertFalse(app.touring)
            assertTrue(settings(data).tourDone, "the tour is not shown again")
            assertTrue(d.click(ClickTarget.Control(GuideControls.HIDE)))
            d.settle(2)
            assertFalse(app.guideOpen)
            assertTrue(settings(data).guideDone, "nor the checklist, by itself")
        }
        val again = app(data)
        assertFalse(again.guideOpen || again.touring, "the next start: neither")
    }

    @Test
    fun `the tour outlines exactly what each stop points at, at any text size`() {
        val data = createTempDirectory("mtg-oracle-guide-").toFile().also { dirs += it }
        val app = app(data)
        OffscreenDriver(1600, 900) { AppContent(app) {} }.use { d ->
            for (scale in listOf(0, 2)) {
                repeat(scale) { app.stepTextScale(1) }
                app.startTour()
                d.settle(5)
                for (stop in mtgoracle.ui.library.LIBRARY_TOUR) {
                    stop.region?.let { region ->
                        val target = assertNotNull(d.registry[ClickTarget.Control("region:$region")], region)
                        val outline = assertNotNull(d.registry[ClickTarget.Control("region:tour-outline")], "an outline for $region")
                        for ((a, b) in listOf(target.left to outline.left, target.top to outline.top, target.right to outline.right, target.bottom to outline.bottom)) {
                            assertTrue(kotlin.math.abs(a - b) <= 1.5f, "at ${app.textScale}: the outline $outline is not on $region $target")
                        }
                        // And drawn there: the accent along the target's own top edge, not half a cell inside it.
                        val accent = mtgoracle.ui.theme.Palette.accent.toArgb()
                        val edge = d.pixels(androidx.compose.ui.geometry.Rect(target.left + 8, target.top, target.right - 8, target.top + 2))
                        assertTrue(edge.count { it == accent } > edge.size / 2, "at ${app.textScale}: no line on $region's top edge")
                    }
                    d.click(ClickTarget.Control("tour:next")); d.settle(3)
                }
                assertFalse(app.touring)
            }
        }
    }

    @Test
    fun `a library with decks opens no guide by itself, and Guide or the command brings it`() {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        val data = DbFixture.copy().parentFile.also { dirs += it }
        val app = app(data)
        assertFalse(app.guideOpen || app.touring)
        OffscreenDriver(1600, 900) { AppContent(app) {} }.use { d ->
            d.settle(5)
            assertTrue(d.click(ClickTarget.Control("guide")), "Guide in the toolbar")
            d.settle(3)
            assertTrue(app.guideOpen)
            assertTrue("[x] 3. Your first deck" in d.text.all(), "a step done ticks itself: ${d.text.all().lines().filter { "first deck" in it }}")
            app.guideAway()
            d.settle(2)
            app.lookupUi!!.submit("guide")
            d.settle(2)
            assertTrue(app.guideOpen, "`guide` brings it back")
            assertEquals(false, settings(data).guideDone, "neither of those puts it away")
        }
    }
}
