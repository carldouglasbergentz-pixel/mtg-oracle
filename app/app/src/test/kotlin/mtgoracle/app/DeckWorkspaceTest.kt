package mtgoracle.app

import androidx.compose.ui.input.key.Key
import mtgoracle.data.DbFixture
import mtgoracle.ui.OffscreenDriver
import mtgoracle.ui.kit.CardMode
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.lookup.LookupUi
import mtgoracle.ui.lookup.OutputLink
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The deck workspace in the real window, offscreen, on a copy of the
 * database: opened with Enter or `cd`, search beside the deck and following
 * it, results as a grid of cards or as lines, the arrow keys selecting.
 */
class DeckWorkspaceTest {
    private val assets = File(System.getProperty("mtgoracle.forgeAssets"))
    private lateinit var data: File
    private lateinit var app: AppController
    private lateinit var driver: OffscreenDriver
    private val ui: LookupUi get() = app.lookupUi!!

    /** The app on a copy, a commander deck selected (its search is filtered); returns that deck's id. */
    private fun open(): Int {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        val copy = DbFixture.copy()
        data = copy.parentFile
        val deckId = DriverManager.getConnection("jdbc:sqlite:${copy.path}").use { c ->
            c.prepareStatement("SELECT deck_id FROM deck_cards WHERE is_commander = 1 LIMIT 1").use { st -> st.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else null } }
        }
        assumeTrue(deckId != null, "a commander deck")
        Scenario.startForge()
        app = AppController(AppPaths(data, assets, forgeHome = Scenario.home))
        app.boot()
        app.select(deckId!!)
        driver = OffscreenDriver(1800, 1200) { AppContent(app) {} }
        driver.settle()
        return deckId
    }

    @AfterTest fun close() {
        if (this::driver.isInitialized) driver.close()
        if (this::data.isInitialized) data.deleteRecursively()
    }

    private fun search(line: String) {
        driver.key(Key.Semicolon, char = ':'.code)
        ui.command.set(line)
        driver.frame()
        driver.key(Key.Enter)
        driver.key(Key.Escape) // back to the screen's keys
        driver.settle()
    }

    private fun frames() = driver.registry.targets.filterIsInstance<ClickTarget.Link>().filter { it.at % 100_000 >= mtgoracle.ui.lookup.GRID_TARGETS }

    @Test
    fun `enter opens the deck beside a search that follows it, and esc goes back`() {
        val deckId = open()
        driver.key(Key.Enter)
        assertEquals(deckId, app.editing?.deckId, "Enter opens the selected deck to work on")
        assertNotNull(driver.registry[ClickTarget.Control("region:workspace-deck")])
        assertNotNull(driver.registry[ClickTarget.Control("region:workspace-search")])
        assertTrue(ui.prompt.endsWith("> ") && ui.prompt.length > 2, "the prompt names the deck: ${ui.prompt}")
        assertTrue("search · ci<=" in driver.text.all(), "the search pane names the deck's filters")

        search("t:creature mv<=2")
        val page = ui.output.latestSearch?.page
        assertNotNull(page)
        assertTrue(page.filters.first().startsWith("ci<="), "the search followed the deck")
        assertEquals(page.rows.size, frames().size, "the results are a grid of cards")
        driver.savePng(File(Scenario.pngDir, "workspace-grid.png"))

        driver.key(Key.Escape)
        assertNull(app.editing, "Esc: back to the library")
        assertEquals("> ", ui.prompt)
    }

    @Test
    fun `arrows select a result and zoom it, enter opens it, T switches grid and lines`() {
        open()
        driver.key(Key.Enter)
        search("goblin mv<=1")
        driver.key(Key.DirectionRight)
        assertEquals(0, ui.selected)
        val first = ui.selectedCard!!
        assertTrue("$first " in driver.text.all() || first in driver.text.all(), "the zoom pane shows the selected card")
        driver.key(Key.DirectionRight)
        driver.key(Key.DirectionLeft)
        assertEquals(0, ui.selected)
        driver.key(Key.DirectionDown)
        assertTrue(ui.selected!! > 1, "Down moves a whole grid row")

        driver.key(Key.T)
        assertTrue(!ui.grid && frames().isEmpty(), "T: the results as lines")
        driver.key(Key.DirectionUp)
        driver.key(Key.T)
        assertTrue(ui.grid, "T again: the grid")

        val chosen = ui.selectedCard!!
        driver.key(Key.Enter)
        driver.settle()
        assertTrue("> card $chosen" in driver.text.all(), "Enter opened the selected card")

        val deckMode = app.deckPaneMode
        driver.key(Key.T, shift = true)
        assertTrue(deckMode != app.deckPaneMode, "Shift+T: the deck pane between lines and frames")
        assertEquals(CardMode.TEXT, deckMode, "lines by default, beside a search")
        driver.savePng(File(Scenario.pngDir, "workspace-card.png"))
    }

    @Test
    fun `cd opens the workspace too, cd dot-dot leaves it, and a click on a card opens it`() {
        val deckId = open()
        val name = app.decks.first { it.id == deckId }.let { d ->
            if (app.decks.count { it.name.equals(d.name, true) } > 1) "${d.folderName ?: "(unsorted)"}/${d.name}" else d.name
        }
        search("cd $name")
        assertEquals(deckId, app.editing?.deckId)
        search("lightning bolt")
        val bolt = frames().firstOrNull { it.link == OutputLink.Card("Lightning Bolt") }
        if (bolt != null) { // only when the deck's colours include red
            assertTrue(driver.click(bolt))
            driver.settle()
            assertTrue("> card Lightning Bolt" in driver.text.all())
        }
        search("cd ..")
        assertNull(app.editing)
    }
}
