package mtgoracle.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.input.TextFieldValue
import mtgoracle.data.DbFixture
import mtgoracle.ui.OffscreenDriver
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.lookup.LookupUi
import mtgoracle.ui.lookup.OutputLink
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The command line in the real window, offscreen, on a copy of the database:
 * the keys that open and leave it, a search whose names are links, a click
 * and a hover on one, history and autofill, and the screen's own keys kept
 * away from what is typed.
 */
class LookupScreenTest {
    private val assets = File(System.getProperty("mtgoracle.forgeAssets"))
    private lateinit var data: File
    private lateinit var app: AppController
    private lateinit var driver: OffscreenDriver
    private val ui: LookupUi get() = app.lookupUi!!

    private fun open() {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        data = DbFixture.copy().parentFile
        Scenario.startForge()
        app = AppController(AppPaths(data, assets, forgeHome = Scenario.home))
        app.boot()
        driver = OffscreenDriver(1800, 1200) { AppContent(app) {} }
        driver.settle()
    }

    @AfterTest fun close() {
        if (this::driver.isInitialized) driver.close()
        if (this::data.isInitialized) data.deleteRecursively()
    }

    private fun typeAndRun(line: String) {
        ui.command.set(line)
        driver.frame()
        driver.key(Key.Enter)
        driver.settle()
    }

    private fun link(which: OutputLink): ClickTarget.Link {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            driver.registry.targets.filterIsInstance<ClickTarget.Link>().firstOrNull { it.link == which }?.let { return it }
            driver.frame()
        }
        fail("no link $which on screen; links: ${driver.registry.targets.filterIsInstance<ClickTarget.Link>().map { it.link }.take(20)}")
    }

    @Test
    fun `colon opens the line, a search links its names, a hover zooms and a click opens the card`() {
        open()
        assertFalse(ui.command.focused)
        driver.key(Key.Semicolon, char = ':'.code)
        assertTrue(ui.command.focused, ": gives the command line the keyboard")
        assertEquals("", ui.command.value.text, "the colon that opened it is not typed")

        typeAndRun("search n:\"lightning bolt\" order:asc_name")
        assertTrue(ui.showOutput, "the output replaces the deck in the middle")
        assertTrue("card(s) — showing 1-" in driver.text.all())

        val bolt = link(OutputLink.Card("Lightning Bolt"))
        driver.hover(bolt)
        driver.settle()
        assertTrue("Lightning Bolt  {R}" in driver.text.all(), "the zoom pane shows the hovered card")
        driver.savePng(File(Scenario.pngDir, "lookup-search.png"))

        assertTrue(driver.click(bolt))
        driver.settle()
        val text = driver.text.all()
        assertTrue("> card Lightning Bolt" in text && "Legality:" in text, "the click ran `card` on the name")
        driver.savePng(File(Scenario.pngDir, "lookup-card.png"))
    }

    @Test
    fun `up recalls, tab takes the suggestion, and the screen's letters stay out of the line`() {
        open()
        driver.key(Key.K, ctrl = true)
        assertTrue(ui.command.focused, "Ctrl+K opens the line too")
        typeAndRun("rule 100.1")
        driver.key(Key.DirectionUp)
        assertEquals("rule 100.1", ui.command.value.text)
        driver.key(Key.DirectionDown)
        assertEquals("", ui.command.value.text, "Down past the newest restores the (empty) draft")

        ui.command.value = TextFieldValue("combo thassa's or")
        driver.settle()
        assertTrue(driver.text.all().lines().any { it.startsWith(" ".repeat("combo thassa's or".length) + "acle") }, "the suggestion shows in grey after the text")
        driver.key(Key.Tab)
        assertEquals("combo Thassa's Oracle", ui.command.value.text)
        assertTrue(ui.command.focused, "Tab took the suggestion, it did not move the focus")

        val mode = app.mode
        val board = app.boardMode
        driver.key(Key.T, char = 't'.code)
        assertEquals(mode, app.mode, "T typed in the line is not the text/art key")
        driver.key(Key.Escape)
        assertFalse(ui.command.focused, "Esc gives the keyboard back")
        driver.key(Key.T, char = 't'.code)
        assertTrue(mode != app.mode, "now T is the screen's again")
        assertEquals(board, app.boardMode, "the library's T leaves the board's art alone")
        app.toggleMode()
    }

    @Test
    fun `tab switches between the deck and the output, and Ctrl+L clears it`() {
        open()
        driver.key(Key.Semicolon, char = ':'.code)
        typeAndRun("rule 702")
        driver.key(Key.Escape)
        assertTrue(ui.showOutput)
        assertNotNull(link(OutputLink.Rule("702.2")), "a child rule is a link")
        driver.key(Key.Tab)
        assertFalse(ui.showOutput, "Tab: the deck again")
        driver.key(Key.Tab)
        assertTrue(ui.showOutput)
        driver.key(Key.L, ctrl = true)
        assertEquals(0, ui.output.entries.size)
        assertTrue(driver.registry.targets.none { it is ClickTarget.Link }, "nothing cleared can still be clicked")
    }

    @Test
    fun `the hint reads the query back and counts it, a click in a pane ends typing, F7 works while typing`() {
        open()
        driver.key(Key.Semicolon, char = ':'.code)
        ui.command.set("t:instant c:u mv<=1 counter")
        // The count waits for typing to pause, then runs on an IO thread in real time: wait for it in real time.
        val counted = Regex("→  \\d+ cards?")
        val deadline = System.currentTimeMillis() + 5_000
        while (!counted.containsMatchIn(driver.text.all()) && System.currentTimeMillis() < deadline) { driver.frame(); Thread.sleep(20) }
        val text = driver.text.all()
        assertTrue("type has \"instant\" · colours include U · mana value ≤ 1" in text, "the query read back")
        assertTrue(counted.containsMatchIn(text), "and counted")
        driver.savePng(File(Scenario.pngDir, "lookup-hint.png"))

        val mode = app.mode
        driver.key(Key.F7)
        assertTrue(mode != app.mode, "F7 is text/art even while typing")
        assertEquals("t:instant c:u mv<=1 counter", ui.command.value.text, "and types nothing")
        app.toggleMode()

        assertTrue(ui.command.focused)
        assertTrue(driver.click(ClickTarget.Control("deck:${app.decks.first().id}")))
        assertFalse(ui.command.focused, "a click in the panes ends typing")
    }
}
