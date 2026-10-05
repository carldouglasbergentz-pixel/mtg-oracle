package mtgoracle.app

import mtgoracle.core.library.PackageScope
import mtgoracle.data.DbFixture
import mtgoracle.ui.library.LibraryIntent
import mtgoracle.ui.lookup.Ask
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Packages through the app: exported from the library's menu to
 * data/exports, imported from a path on the clipboard (asked first, the
 * history, games and combos each a toggle), and taken from data/import at
 * start, moved to done/ once in.
 */
class PackageActionsTest {
    private val assets = File(System.getProperty("mtgoracle.forgeAssets"))
    private val dirs = mutableListOf<File>()
    @AfterTest fun clean() = dirs.forEach { it.deleteRecursively() }

    private fun dataDir(): File = DbFixture.copy().parentFile.also { dirs += it }
    private fun app(data: File) = AppController(AppPaths(data, assets, forgeHome = Scenario.home)).also { it.boot() }
    private fun count(data: File, table: String) = DriverManager.getConnection("jdbc:sqlite:${File(data, "mtg.db").path}").use { c ->
        c.createStatement().use { it.executeQuery("SELECT COUNT(*) FROM $table").use { rs -> rs.next(); rs.getInt(1) } }
    }
    private fun buttons(app: AppController) = (app.lookupUi!!.ask as Ask.Buttons)
    /** A button pressed as the bar presses it: the question closes, then the button acts. */
    private fun press(app: AppController, label: String) {
        val (_, act) = buttons(app).buttons.first { it.first.startsWith(label) || it.first.endsWith(label) || label in it.first }
        app.lookupUi!!.ask = null
        act()
    }

    @Test
    fun `exported from the menu, imported from the clipboard - asked first, nothing twice`() {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        val data = dataDir()
        val app = app(data)
        app.lookupUi!!.intent(LibraryIntent.ExportPackage(PackageScope.Library))
        val file = File(data, "exports").listFiles()!!.single { it.name.endsWith(".mtgoracle") }
        assertTrue("exported the library" in app.notice.orEmpty(), "${app.notice}")

        app.readClipboard = { "\"${file.path}\"" } // copied as a path, quotes and all
        app.lookupUi!!.intent(LibraryIntent.Import(null, askFolder = true))
        val asked = buttons(app)
        assertTrue("0 deck(s) to import" in asked.title && "already here, the same" in asked.title, asked.title)
        assertTrue(asked.buttons.first().first == "Import")
        // A toggle asks again with it flipped.
        val historyBefore = asked.buttons.first { "history" in it.first }.first
        press(app, "history")
        assertTrue(buttons(app).buttons.first { "history" in it.first }.first != historyBefore, "the history toggled")
        val decks = count(data, "decks")
        press(app, "Import")
        assertEquals(decks, count(data, "decks"), "the same library: nothing twice")
        assertTrue(app.notice.orEmpty().startsWith("imported 0 deck(s)"), "${app.notice}")
        assertTrue(File(data, "backups").listFiles()!!.any { it.name.endsWith("-pre-import.db") }, "a backup first")
    }

    @Test
    fun `a package dropped in data import is asked about at start, and moved to done once in`() {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        val source = dataDir()
        app(source).lookupUi!!.intent!!(LibraryIntent.ExportPackage(PackageScope.Library))
        val pkg = File(source, "exports").listFiles()!!.single()
        val target = dataDir()
        DriverManager.getConnection("jdbc:sqlite:${File(target, "mtg.db").path}").use { c ->
            c.createStatement().use { st -> listOf("PRAGMA foreign_keys = ON", "DELETE FROM decks", "DELETE FROM deck_folders").forEach(st::executeUpdate) }
        }
        val dropped = File(target, "import").also { it.mkdirs() }.resolve(pkg.name)
        pkg.copyTo(dropped)
        val app = app(target)
        val asked = buttons(app)
        assertTrue(pkg.name in asked.title && "Later" in asked.buttons.map { it.first }, asked.title)
        press(app, "Import")
        assertEquals(count(source, "decks"), count(target, "decks"))
        assertTrue(!dropped.exists() && File(target, "import/done/${pkg.name}").isFile, "moved to done")
        assertTrue(app.decks.isNotEmpty(), "the library read again")
    }
}
