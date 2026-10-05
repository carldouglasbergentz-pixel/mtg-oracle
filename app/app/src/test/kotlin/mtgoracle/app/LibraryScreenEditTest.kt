package mtgoracle.app

import androidx.compose.ui.input.key.Key
import mtgoracle.core.deck.DeckSection
import mtgoracle.data.DbFixture
import mtgoracle.ui.OffscreenDriver
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.lookup.Ask
import mtgoracle.ui.lookup.LookupUi
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Managing decks with the mouse, in the window offscreen on a copy of the
 * database: the library's buttons and right-click menus, and the
 * workspace's import, export and printing chooser.
 */
class LibraryScreenEditTest {
    private val assets = File(System.getProperty("mtgoracle.forgeAssets"))
    private lateinit var data: File
    private lateinit var app: AppController
    private lateinit var driver: OffscreenDriver
    private var clipboard: String? = null
    private val ui: LookupUi get() = app.lookupUi!!

    private fun open() {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        data = DbFixture.copy().parentFile
        Scenario.startForge()
        app = AppController(AppPaths(data, assets, forgeHome = Scenario.home))
        app.boot()
        app.readClipboard = { clipboard }
        app.writeClipboard = { clipboard = it }
        driver = OffscreenDriver(1800, 1200) { AppContent(app) {} }
        driver.settle()
    }

    @AfterTest fun close() {
        if (this::driver.isInitialized) driver.close()
        if (this::data.isInitialized) data.deleteRecursively()
    }

    @Test
    fun `the library's buttons ask first, and a deck's menu sets its format`() {
        open()
        assertTrue(driver.click(ClickTarget.Control("new-folder")))
        assertIs<Ask.Text>(ui.ask, "New folder asks for a name")
        assertNotNull(driver.registry[ClickTarget.Control("region:ask")])
        driver.savePng(File(Scenario.pngDir, "library-ask.png"))
        assertTrue(driver.click(ClickTarget.Control("ask:cancel")))
        assertNull(ui.ask, "Cancel closes it; nothing made")

        val deck = app.decks.first()
        assertTrue(driver.rightClick(ClickTarget.Control("deck:${deck.id}")))
        driver.savePng(File(Scenario.pngDir, "library-menu.png"))
        assertTrue(driver.click(ClickTarget.Control("menu:4")), "format...")
        val choose = assertIs<Ask.Choose>(ui.ask)
        val vintage = choose.options.indexOfFirst { it.value == "vintage" }
        assertTrue(driver.click(ClickTarget.Control("choose:$vintage")))
        driver.settle()
        assertEquals("vintage", app.decks.first { it.id == deck.id }.format)
    }

    @Test
    fun `a menu closes on Esc and the screen has the keys again`() {
        open()
        val deck = app.selectedId!!
        assertTrue(driver.rightClick(ClickTarget.Control("deck:$deck")))
        driver.settle(3)
        assertNotNull(driver.registry[ClickTarget.Control("menu:0")], "the menu is open")
        driver.key(Key.Escape)
        driver.settle(3)
        assertNull(driver.registry[ClickTarget.Control("menu:0")], "Esc closes it (it had no focus, and stayed open)")
        driver.key(Key.DirectionDown)
        driver.settle(3)
        assertTrue(app.selectedId != deck, "↓ moves the selection again")
    }

    @Test
    fun `a deck exports for Arena to the clipboard, and for MTGO to a file as well`() {
        open()
        driver.key(Key.Enter) // open the selected deck: Export is the workspace's
        val deck = app.decks.first { it.id == app.editing!!.deckId }
        assertTrue(driver.click(ClickTarget.Control("export")))
        assertTrue(driver.click(ClickTarget.Control("ask:3")), "Arena")
        val arena = assertNotNull(clipboard)
        assertTrue(arena.startsWith("Commander\n") || arena.startsWith("Deck\n"), arena.take(80))
        assertTrue(" (" !in arena.lines().first { it.firstOrNull()?.isDigit() == true }, "no printing: ${arena.take(200)}")
        assertTrue("for Arena" in app.notice.orEmpty(), "${app.notice}")

        assertTrue(driver.click(ClickTarget.Control("export")))
        assertTrue(driver.click(ClickTarget.Control("ask:4")), "MTGO")
        val file = File(data, "exports").listFiles().orEmpty().single()
        assertEquals(file.readText(), clipboard, "the file and the clipboard hold the same list")
        assertTrue("Commander" !in file.readText() && "Deck\n" !in file.readText(), "MTGO's file has no headers")
        assertTrue("saved ${deck.name} for MTGO" in app.notice.orEmpty(), "${app.notice}")
    }

    @Test
    fun `the workspace exports, imports with a preview, and chooses a printing`() {
        open()
        driver.key(Key.Enter) // open the selected deck
        val id = app.editing!!.deckId
        assertTrue(driver.click(ClickTarget.Control("export")))
        assertTrue(driver.click(ClickTarget.Control("ask:0")), "Full names")
        val exported = assertNotNull(clipboard)
        assertTrue(exported.contains("\n"), exported)

        clipboard = exported // the deck's own list: replacing with it changes nothing
        assertTrue(driver.click(ClickTarget.Control("import")))
        assertTrue(driver.click(ClickTarget.Control("ask:1")), "Replace the deck...")
        driver.settle()
        assertTrue("would change:" in driver.text.all(), "the preview is in the output")
        assertIs<Ask.Buttons>(ui.ask)
        assertTrue(driver.click(ClickTarget.Control("ask:cancel")))

        // Forge is up by now in the shared test JVM (Scenario.startForge): its printings back the chooser.
        val card = app.deck!!.cards.first { !it.isSideboard && !it.isCommander }
        assertTrue(driver.rightClick(mtgoracle.ui.library.deckRowTarget(card.name, DeckSection.MAIN)))
        val menu = mtgoracle.ui.library.rowMenu(mtgoracle.ui.library.DeckRow(card, DeckSection.MAIN, "")).size
        assertTrue(driver.click(ClickTarget.Control("menu:$menu")), "choose printing... follows the row's own items")
        driver.settle()
        val chooser = ui.ask
        if (chooser is Ask.Choose && chooser.options.size > 1) {
            driver.savePng(File(Scenario.pngDir, "workspace-printings.png"))
            assertTrue(driver.click(ClickTarget.Control("choose:1")))
            driver.settle()
            assertNotNull(app.deck!!.cards.first { it.name == card.name && !it.isSideboard && !it.isCommander }.setCode, "the chosen printing is on the row")
            assertEquals("printing", ui.history.first().action)
        } else assertTrue(app.notice.orEmpty().contains("no printings"), "without Forge the chooser says so: ${app.notice}")
        assertEquals(id, app.editing?.deckId)
    }
}
