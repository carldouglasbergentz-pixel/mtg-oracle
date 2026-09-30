package mtgoracle.app

import mtgoracle.ui.board.BoardLayout
import mtgoracle.ui.theme.Palette
import mtgoracle.ui.theme.Themes
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the app remembers between runs, in a scratch data directory (never the real one). */
class SettingsTest {
    private val data: File = Files.createTempDirectory("mtgoracle-settings").toFile()
    private val paths = AppPaths(data, File(System.getProperty("mtgoracle.forgeAssets")))

    @AfterTest fun cleanUp() { Palette.theme = Themes.HOUSE; data.deleteRecursively() }

    @Test
    fun `the first run takes the TUI's theme, a picked theme is restored after that`() {
        assertEquals(Themes.HOUSE, AppController(paths).startTheme(), "no TUI config, no choice: the house theme")
        data.resolve("config.json").writeText("""{"theme": "rose-pine", "nav_width": 58}""")
        val first = AppController(paths)
        assertEquals(Themes.ROSE_PINE, Palette.theme, "the old app's saved choice")
        first.cycleTheme()
        val picked = Palette.theme
        assertEquals(Themes.ALL[(Themes.ALL.indexOf(Themes.ROSE_PINE) + 1) % Themes.ALL.size], picked)
        Palette.theme = Themes.HOUSE
        AppController(paths)
        assertEquals(picked, Palette.theme, "the next run starts in the theme picked here, over the TUI's")
        assertEquals("""{"theme": "rose-pine", "nav_width": 58}""", data.resolve("config.json").readText(), "the TUI's file is only read")
    }

    @Test
    fun `the board's arrangement - stack box, fold, pane widths - survives a restart`() {
        val layout = BoardLayout(stackCol = 12, stackRow = 30, stackCollapsed = true, zoneCols = 36, sideCols = 58, rotateTapped = false)
        Settings(paths.settings).boardLayout = layout
        assertEquals(layout, Settings(paths.settings).boardLayout)
        assertEquals(BoardLayout(), Settings(File(data, "missing.properties")).boardLayout, "no file: the defaults")
    }

    @Test
    fun `Textual theme names map to the nearest of ours`() {
        assertEquals(Themes.ROSE_PINE, Themes.fromTextual("rose-pine"))
        assertEquals(Themes.PAPER, Themes.fromTextual("rose-pine-dawn"))
        assertEquals(Themes.PAPER, Themes.fromTextual("textual-light"))
        assertEquals(Themes.SOLARIZED, Themes.fromTextual("solarized-dark"))
        assertEquals(null, Themes.fromTextual("nord"))
    }
}
