package mtgoracle.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import mtgoracle.data.DbFixture
import mtgoracle.ui.OffscreenDriver
import mtgoracle.ui.board.PANE_STEP
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.library.MIN_MIDDLE_COLS
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The library's and the workspace's side columns resize as the board's do:
 * drag an edge, Ctrl+←/→ for the right one, Ctrl+Shift+←/→ for the left;
 * kept in settings, so the next start has them, and clamped to the window.
 */
class PaneWidthsTest {
    private val assets = File(System.getProperty("mtgoracle.forgeAssets"))
    private lateinit var data: File

    @AfterTest fun close() { if (this::data.isInitialized) data.deleteRecursively() }

    private fun boot(): AppController {
        Scenario.startForge()
        return AppController(AppPaths(data, assets, forgeHome = Scenario.home)).also { it.boot(); it.select(it.decks.first().id) }
    }

    private fun OffscreenDriver.width(region: String) = registry[ClickTarget.Control("region:$region")]!!.width

    @Test
    fun `the library and the workspace keep the widths their edges are dragged to`() {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        data = DbFixture.copy().parentFile
        val app = boot()
        OffscreenDriver(1800, 1100) { AppContent(app) {} }.use { d ->
            d.settle(5)
            val zoom = d.width("library-zoom")
            val decks = d.width("library-decks")
            val cell = zoom / app.settings.libraryColumns.right
            d.drag(ClickTarget.Control("region:right-edge"), Offset(-10 * cell, 0f))
            assertEquals(zoom + 10 * cell, d.width("library-zoom"), 1f, "dragging the zoom pane's edge ten cells left widens it")
            d.key(Key.DirectionRight, ctrl = true, shift = true)
            assertEquals(decks + PANE_STEP * cell, d.width("library-decks"), 1f, "Ctrl+Shift+→ widens the deck list")
            d.key(Key.DirectionRight, ctrl = true)
            assertEquals(zoom + (10 - PANE_STEP) * cell, d.width("library-zoom"), 1f, "Ctrl+→ moves the zoom pane's edge right")

            app.edit()
            d.settle(5)
            val deck = d.width("workspace-deck")
            d.drag(ClickTarget.Control("region:left-edge"), Offset(6 * cell, 0f))
            assertEquals(deck + 6 * cell, d.width("workspace-deck"), 1f, "dragging the deck pane's edge right")
            // Far past the window: the middle keeps its room.
            repeat(60) { d.key(Key.DirectionLeft, ctrl = true) }
            val window = 1800 / cell
            assertTrue(d.width("workspace-deck") + d.width("workspace-zoom") <= (window - MIN_MIDDLE_COLS + 1) * cell, "the search keeps its room")
        }
        val library = app.settings.libraryColumns
        val workspace = app.settings.workspaceColumns(app.deckPaneMode)
        // A new start reads what was kept.
        val again = boot()
        assertEquals(library, again.settings.libraryColumns)
        assertEquals(workspace, again.settings.workspaceColumns(again.deckPaneMode))
        assertEquals(app.settings.libraryColumns.left, mtgoracle.ui.library.DECK_LIST_COLS + PANE_STEP)
    }
}
