package mtgoracle.app

import mtgoracle.data.DbFixture
import mtgoracle.data.GameStore
import mtgoracle.data.Library
import mtgoracle.data.MtgDb
import mtgoracle.forge.ForgeRuntime
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Simulations on a copy of the database, with the tests' Forge: games are
 * played AI vs AI without a board and recorded one by one under one
 * `match_id`; stopping keeps what was played and leaves nothing running; a
 * game past the limit is a draw.
 */
class SimulationTest {
    private val assets = File(System.getProperty("mtgoracle.forgeAssets"))
    private lateinit var data: File

    private fun copy(): File {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        val db = DbFixture.copy()
        data = db.parentFile
        Scenario.startForge()
        return db
    }

    @AfterTest fun close() {
        if (this::data.isInitialized) data.deleteRecursively()
    }

    private fun rows(db: File): List<Map<String, Any?>> = DriverManager.getConnection("jdbc:sqlite:${db.path}").use { c ->
        c.prepareStatement("SELECT mode, deck_name, opponent_name, match_id, game_no, match_format, winner, turns, deck_ai_variant, opponent_ai_variant, conceded FROM games ORDER BY id").use { st ->
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add((1..rs.metaData.columnCount).associate { rs.metaData.getColumnName(it) to rs.getObject(it) }) }
            }
        }
    }

    private fun waitFor(millis: Long, what: String, until: () -> Boolean) {
        val deadline = System.currentTimeMillis() + millis
        while (!until()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
            Thread.sleep(100)
        }
    }

    private fun deckId(library: Library, name: String) = library.decks().firstOrNull { it.name == name }?.id.also { assumeTrue(it != null, "the user's $name") }!!

    @Test
    fun `a simulation plays and records each game under one match, AI copies on both sides`() {
        val db = copy()
        val app = AppController(AppPaths(data, assets, forgeHome = Scenario.home))
        app.boot()
        waitFor(120_000, "Forge") { app.forgeReady }
        val library = Library(MtgDb(db))
        app.select(deckId(library, "Rakdos Midrange"))
        app.openSetup()
        app.opponentId = deckId(library, "Boros Death and Taxes")
        app.simGames = 2
        val started = System.currentTimeMillis()
        app.simulate()
        assertTrue(app.simulating)
        app.start()
        assertNull(app.match, "no game while a simulation runs")
        waitFor(2 * (Simulation.LIMIT_MILLIS + 30_000), "the simulation") { !app.simulating }
        println("2 simulated games in ${(System.currentTimeMillis() - started) / 1000} s: ${app.simulation?.line()}")

        val games = rows(db)
        assertEquals(2, games.size, games.toString())
        assertTrue(games.all { it["mode"] == "ai_vs_ai" && it["match_format"] == null && it["conceded"] == 0 }, games.toString())
        assertEquals(1, games.map { it["match_id"] }.distinct().size, "one simulation, one match_id")
        assertEquals(listOf(1, 2), games.map { it["game_no"] })
        assertTrue(games.all { it["deck_ai_variant"] == 1 && it["opponent_ai_variant"] == 1 }, "both decks have substitutions, so both AIs play their copies")
        assertTrue(games.all { it["winner"] in setOf("me", "opponent", "draw") })
        assertEquals(2, app.simulation!!.played)
        // The record, on the setup screen and from `results`.
        val boros = app.opponents().first { it.deck.name == "Boros Death and Taxes" }
        assertTrue(boros.record!!.startsWith("AI "), boros.toString())
        val ui = app.lookupUi!!
        ui.submit("results Rakdos Midrange")
        val text = app.commands!!.output.entries.flatMap { it.rendering.lines(100) }.map { it.text }
        assertTrue(text.any { it.startsWith("Boros Death and Taxes") && "–" in it }, text.joinToString("\n"))
        assertTrue(!ForgeRuntime.busy, "nothing is left running")
    }

    @Test
    fun `stopping keeps what was played, and a game past the limit is a draw`() {
        val db = copy()
        waitFor(120_000, "Forge") { ForgeRuntime.isInitialised }
        val library = Library(MtgDb(db))
        val sessions = Sessions(GameStore(MtgDb(db)), File(data, "logs").apply { mkdirs() })
        val prepared = sessions.prepare(library.deck(deckId(library, "Rakdos Midrange"))!!, library.deck(deckId(library, "Boros Death and Taxes"))!!, useAiCopy = false)
        var last: SimProgress? = null
        // A limit no game can meet: each game is ended as a draw.
        val limited = Simulation(sessions, prepared, games = 2, onProgress = { last = it }, limitMillis = 500)
        limited.start().join(120_000)
        assertEquals(2, last?.played)
        assertEquals(2, last?.draws)
        val drawn = rows(db)
        assertTrue(drawn.all { it["winner"] == "draw" && it["deck_ai_variant"] == 0 && it["opponent_ai_variant"] == 0 }, drawn.toString())

        // Stopped during its first game: nothing recorded for it, nothing left running.
        val stopped = Simulation(sessions, prepared, games = 5, onProgress = { last = it })
        val thread = stopped.start()
        Thread.sleep(1_500)
        stopped.stop()
        thread.join(60_000)
        assertTrue(last!!.stopped && last!!.done, last.toString())
        assertEquals(drawn.size + last!!.played, rows(db).size, "only finished games are recorded")
        assertTrue(!ForgeRuntime.busy, "nothing is left running")
    }
}
