package mtgoracle.app

import mtgoracle.core.play.Winner
import mtgoracle.core.seat.ScriptedSeat
import mtgoracle.data.DbFixture
import mtgoracle.data.GameStore
import mtgoracle.data.Library
import mtgoracle.data.MtgDb
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AppTest {
    private val assets = File(System.getProperty("mtgoracle.forgeAssets"))

    @Test
    fun `an old database blocks the app with the way to complete it`() {
        val dir = kotlin.io.path.createTempDirectory("mtg-oracle-app-old-").toFile()
        DriverManager.getConnection("jdbc:sqlite:${File(dir, "mtg.db").path}").use { c ->
            c.createStatement().use { st ->
                st.execute("CREATE TABLE deck_folders (id INTEGER PRIMARY KEY, name TEXT, format TEXT)")
                st.execute("CREATE TABLE decks (id INTEGER PRIMARY KEY, folder_id INTEGER, name TEXT, format TEXT)")
            }
        }
        val app = AppController(AppPaths(dir, assets))
        app.boot()
        val screen = assertIs<Screen.Blocked>(app.screen)
        assertContains(screen.message, "python-final")
        assertContains(screen.message, "table games")
        dir.deleteRecursively()
    }

    @Test
    fun `a finished game is recorded as one games row, on a copy of the database`() {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        val realStamp = DbFixture.realDb.lastModified()
        val copy = DbFixture.copy()
        val db = MtgDb(copy)
        val library = Library(db)
        val rakdos = library.decks().firstOrNull { it.name == "Rakdos Midrange" }?.let { library.deck(it.id) }
        assumeTrue(rakdos != null, "the user's Rakdos Midrange deck")
        Scenario.startForge()
        val sessions = Sessions(GameStore(db), File(copy.parentFile, "game_logs"))
        val prepared = sessions.prepare(rakdos!!, rakdos, useAiCopy = true)
        assertTrue(prepared.opponent.isAiCopy, "the AI plays the deck's AI copy")
        assertTrue(!prepared.blocked, "notes: ${prepared.notes}")
        val match = sessions.start(prepared, seed = 5)
        ScriptedSeat(match.seat).play(timeoutMillis = 10_000) // a few decisions, then concede
        match.concede()
        val deadline = System.currentTimeMillis() + 20_000
        while (match.result.value == null && System.currentTimeMillis() < deadline) Thread.sleep(50)
        val result = assertNotNull(match.result.value)
        assertEquals(Winner.OPPONENT, result.winner, "we conceded")
        val id = assertNotNull(sessions.record(match, result))
        match.recorder.close()
        val row = db.read { c ->
            c.prepareStatement("SELECT mode, deck_name, opponent_name, opponent_ai_variant, seed, winner, forge_version, log_path FROM games WHERE id = ?").use { st ->
                st.setLong(1, id)
                st.executeQuery().use { rs -> rs.next(); (1..8).map { rs.getObject(it) } }
            }
        }
        assertEquals(listOf<Any?>("human_vs_ai", "Rakdos Midrange", "Rakdos Midrange (AI)", 1, 5, "opponent"), row.take(6))
        assertTrue(File(row[7] as String).isFile, "the log is where the row says")
        assertEquals(realStamp, DbFixture.realDb.lastModified(), "the real database is untouched")
        copy.parentFile.deleteRecursively()
    }
}
