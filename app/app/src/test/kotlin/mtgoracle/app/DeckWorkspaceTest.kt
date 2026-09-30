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

    /** The grid's card frames (not the buttons under them). */
    private fun frames() = driver.registry.targets.filterIsInstance<ClickTarget.Link>()
        .filter { it.at % 100_000 in mtgoracle.ui.lookup.GRID_TARGETS until mtgoracle.ui.lookup.GRID_TARGETS + 100 && it.link is OutputLink.Card }

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

    private fun main(): Map<String, Int> = app.deck!!.cards.filter { !it.isSideboard && !it.isCommander }.associate { it.name to it.quantity }

    private fun editLink(action: mtgoracle.ui.lookup.EditAction) =
        driver.registry.targets.filterIsInstance<ClickTarget.Link>().firstOrNull { it.link == OutputLink.Edit(action) }

    @Test
    fun `the mouse edits - a result's plus, a row's minus, the tabs, the right-click menu, add anyway`() {
        open()
        driver.key(Key.Enter)
        search("n=\"Sol Ring\"")
        val add = assertNotNull(editLink(mtgoracle.ui.lookup.EditAction.Add("Sol Ring", mtgoracle.core.deck.DeckSection.MAIN)), "a result's [+]")
        val had = main()["Sol Ring"] ?: 0
        if (had == 0) {
            assertTrue(driver.click(add))
            driver.settle()
            assertEquals(1, main()["Sol Ring"], "[+] added it")
        }
        // Search follows the deck's identity, so what a click can still break is singleton: a second Sol Ring.
        assertTrue(driver.click(editLink(mtgoracle.ui.lookup.EditAction.Add("Sol Ring", mtgoracle.core.deck.DeckSection.MAIN))!!))
        driver.settle()
        assertTrue(ui.refusal?.forceable == true && "singleton" in ui.refusal!!.text, "the refusal is shown: ${ui.refusal}")
        assertTrue("add anyway" in driver.text.all())
        driver.savePng(File(Scenario.pngDir, "workspace-refusal.png"))
        assertTrue(driver.click(ClickTarget.Control("force")))
        driver.settle()
        assertEquals(2, main()["Sol Ring"], "add anyway put the second in")

        // The row's [-] takes it out again.
        val minus = assertNotNull(editLink(mtgoracle.ui.lookup.EditAction.Remove("Sol Ring", mtgoracle.core.deck.DeckSection.MAIN)))
        assertTrue(driver.click(minus))
        driver.settle()
        assertEquals(1, main()["Sol Ring"])

        // Right-click a deck row: its menu, and "to considering".
        val name = "Sol Ring"
        val row = mtgoracle.ui.library.deckRowTarget(name, mtgoracle.core.deck.DeckSection.MAIN)
        assertTrue(driver.rightClick(row))
        driver.savePng(File(Scenario.pngDir, "workspace-menu.png"))
        val menu = mtgoracle.ui.library.rowMenu(mtgoracle.ui.library.DeckRow(app.deck!!.cards.first { it.name == name }, mtgoracle.core.deck.DeckSection.MAIN, ""))
        val toConsidering = menu.indexOfFirst { it.first == "to considering" }
        assertTrue(driver.click(ClickTarget.Control("menu:$toConsidering")), "the menu offers it")
        driver.settle()
        assertTrue(app.deck!!.considering.any { it.name == name }, "$name moved to the considering list")

        // The tabs: the list, then the history, which has every change above.
        assertTrue(driver.click(ClickTarget.Control("tab:CONSIDERING")))
        assertEquals(mtgoracle.ui.lookup.DeckTab.CONSIDERING, ui.deckTab)
        assertTrue(driver.click(ClickTarget.Control("tab:HISTORY")))
        driver.settle()
        val text = driver.text.all()
        assertTrue("move" in text && "Sol Ring" in text, "the history lists the changes")
        driver.savePng(File(Scenario.pngDir, "workspace-history.png"))
    }

    @Test
    fun `the keyboard edits - plus on a result, tab to the deck, delete a row`() {
        open()
        driver.key(Key.Enter)
        search("n=\"Arcane Signet\"")
        driver.key(Key.DirectionRight)
        val card = ui.selectedCard!!
        driver.key(Key.C, char = 'c'.code)
        assertTrue(app.deck!!.considering.any { it.name == card }, "C: onto the considering list")
        driver.key(Key.Two)
        driver.key(Key.Tab)
        driver.key(Key.DirectionDown)
        driver.key(Key.Delete)
        assertTrue(app.deck!!.considering.none { it.name == card }, "Delete took it off the list")
    }

    @Test
    fun `the AI copy tab lists the substitutions, and its x takes one back`() {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        val copy = DbFixture.copy()
        data = copy.parentFile
        Scenario.startForge()
        app = AppController(AppPaths(data, assets, forgeHome = Scenario.home))
        app.boot()
        val rakdos = app.decks.firstOrNull { it.name == "Rakdos Midrange" }
        assumeTrue(rakdos != null, "the user's Rakdos Midrange, which has substitutions")
        app.select(rakdos!!.id)
        driver = OffscreenDriver(1800, 1200) { AppContent(app) {} }
        driver.settle()
        driver.key(Key.Enter)
        driver.key(Key.Four)
        driver.settle()
        val subs = app.deckById(rakdos.id)!!.substitutions
        assumeTrue(subs.isNotEmpty(), "substitutions to show")
        val text = driver.text.all()
        assertTrue("AI copy ${subs.size}" in text, "the tab counts them")
        subs.forEach { assertTrue(it.cardName in text && it.substitute in text, "${it.cardName} -> ${it.substitute} is listed") }
        driver.click(ClickTarget.Control("unsubstitute:0"))
        driver.settle()
        assertEquals(subs.size - 1, app.deckById(rakdos.id)!!.substitutions.size, "[x] took one back")
        driver.savePng(File(Scenario.pngDir, "workspace-ai-copy.png"))
    }
}
