package mtgoracle.app

import androidx.compose.ui.input.key.Key
import mtgoracle.core.model.InputKind
import mtgoracle.core.model.InputPrompt
import mtgoracle.core.model.SeatAction
import mtgoracle.core.model.SideboardPrompt
import mtgoracle.core.play.MatchFormat
import mtgoracle.core.seat.Policy
import mtgoracle.core.seat.ScriptedSeat
import mtgoracle.data.DbFixture
import mtgoracle.ui.OffscreenDriver
import mtgoracle.ui.board.MatchTargets
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Matches through the whole app, offscreen, on a copy of the database:
 * best of three with sideboarding between games and the loser choosing to
 * play or draw, conceding games and matches, and leaving back to the
 * library with nothing left running.
 */
class MatchTest {
    private val assets = File(System.getProperty("mtgoracle.forgeAssets"))
    private lateinit var data: File
    private lateinit var app: AppController
    private lateinit var driver: OffscreenDriver

    /** Every game: our main phase, a Bolt in hand, the AI at 3 life — a game we can win in one click. */
    private val state = listOf(
        "turn=3", "activeplayer=human", "activephase=MAIN1", "humanlife=20", "ailife=3",
        "humanhand=Lightning Bolt", "humanbattlefield=Mountain", "humanlibrary=" + List(20) { "Mountain" }.joinToString(";"),
        "aihand=", "aibattlefield=", "ailibrary=" + List(20) { "Swamp" }.joinToString(";"),
    )

    /** The app on a DB copy whose Rakdos deck has a sideboard (Duress), sharing the tests' Forge. */
    private fun open(): Int {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        val copy = DbFixture.copy()
        data = copy.parentFile
        val deckId = DriverManager.getConnection("jdbc:sqlite:${copy.path}").use { c ->
            val id = c.prepareStatement("SELECT id FROM decks WHERE name = 'Rakdos Midrange'").use { st -> st.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else null } }
            assumeTrue(id != null, "the user's Rakdos Midrange deck")
            c.prepareStatement("INSERT INTO deck_cards (deck_id, card_name, quantity, is_commander, is_sideboard, added_at) VALUES (?, 'Duress', 1, 0, 1, datetime('now'))").use { it.setInt(1, id!!); it.executeUpdate() }
            id!!
        }
        Scenario.startForge()
        app = AppController(AppPaths(data, assets, forgeHome = Scenario.home))
        app.boot()
        driver = OffscreenDriver(1800, 2400) { AppContent(app) {} }
        waitFor { app.forgeReady }
        app.select(deckId)
        app.play.openLobby(app.selectedId)
        app.play.opponentId = deckId
        return deckId
    }

    @AfterTest fun close() {
        if (this::driver.isInitialized) driver.close()
        if (this::data.isInitialized) data.deleteRecursively()
    }

    private fun waitFor(millis: Long = 60_000, what: String = "", until: () -> Boolean) {
        val deadline = System.currentTimeMillis() + millis
        while (!until()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting $what; screen: ${app.screen}, games ${app.play.match?.games?.value}")
            driver.frame(); Thread.sleep(20)
        }
    }

    /** Plays [policy] (the scripted seat's default otherwise) by clicking the offscreen app, until [until]. */
    private fun play(policy: Policy = { _, _, _ -> null }, until: () -> Boolean) {
        val seat = app.play.match!!.seat
        val scripted = ScriptedSeat(seat, policy, retryAfterMillis = 1500, submit = { p, a -> if (driver.perform(p, a) == null) seat.answer(p.id, a) })
        check(scripted.play(timeoutMillis = 90_000, until = { driver.frame(); until() })) { "timed out; games ${app.play.match?.games?.value}" }
    }

    private fun ourPriority() = app.play.match?.seat?.let { s -> (s.prompt.value as? InputPrompt)?.kind == InputKind.PRIORITY && s.board.value?.activePlayerId == s.board.value?.seat?.id } == true

    private fun rows(): List<List<Any?>> = DriverManager.getConnection("jdbc:sqlite:${File(data, "mtg.db").path}").use { c ->
        c.prepareStatement("SELECT match_id, game_no, match_format, conceded, winner FROM games ORDER BY id").use { st ->
            st.executeQuery().use { rs -> buildList { while (rs.next()) add((1..5).map { rs.getObject(it) }) } }
        }
    }

    /** Ctrl+Q, then the menu's key for [choice]. */
    private fun concede(choice: Key) {
        driver.key(Key.Q, ctrl = true)
        assertTrue(driver.registry[mtgoracle.ui.kit.ClickTarget.Control("region:concede-menu")] != null, "Ctrl+Q opens the menu")
        driver.key(choice)
    }

    @Test
    fun `best of three - concede game 1, sideboard, win game 2 on the draw we chose, concede game 3 - three rows, one match`() {
        open()
        while (app.play.format != MatchFormat.BO3) app.play.cycleFormat()
        app.play.start(state)
        val match = app.play.match!!

        play { ourPriority() }
        concede(Key.One) // this game
        waitFor(what = "game 1's result") { match.result.value != null }
        assertEquals(1, match.games.value.size)
        assertTrue(match.games.value[0].conceded)
        assertTrue(driver.click(MatchTargets.CONTINUE), "the result panel's Continue")

        var sideboarded = false
        var choseDraw = false
        play({ p, b, _ ->
            when {
                p is SideboardPrompt && !sideboarded -> {
                    sideboarded = true
                    // One out, the sideboard's Duress in (the deck may already play one: then it holds two).
                    val deck = p.main.associate { it.name to it.count }
                    val out = p.main.first { it.name != "Duress" }.name
                    SeatAction.Sideboard(deck + (out to deck.getValue(out) - 1) + ("Duress" to (deck["Duress"] ?: 0) + 1))
                }
                // The loser of game 1 chooses: Forge asks us play or draw.
                p is InputPrompt && p.kind == InputKind.CONFIRM && p.okLabel == "Play" -> SeatAction.Ok.also { choseDraw = true }
                p is InputPrompt && p.kind == InputKind.PRIORITY && b.seat?.hand?.any { it.name == "Lightning Bolt" } == true && b.stack.isEmpty() ->
                    SeatAction.ClickCard(b.seat!!.hand.first { it.name == "Lightning Bolt" }.id)
                p is InputPrompt && p.kind == InputKind.TARGET -> SeatAction.ClickPlayer(b.players.first { !it.isSeat }.id)
                else -> null
            }
        }) { match.games.value.size == 2 }
        assertTrue(sideboarded, "the sideboard prompt came between games")
        assertTrue(choseDraw, "and we, the loser, were asked to play or draw")
        assertTrue("SIDEBOARD main 100, sideboard 1" in match.recorder.file.readText(), "one card swapped, the deck still 100")
        waitFor { match.result.value != null }
        assertTrue(driver.click(MatchTargets.CONTINUE))

        play { ourPriority() && match.games.value.size == 2 }
        concede(Key.One)
        waitFor(what = "the match to end") { match.over }
        waitFor(what = "the rows") { rows().size == 3 }
        assertTrue(driver.click(MatchTargets.LOBBY), "match over: back to the lobby")
        waitFor { app.screen == Screen.Lobby }

        val rows = rows()
        assertEquals(1, rows.map { it[0] }.distinct().size, "one match_id: $rows")
        assertEquals(listOf(1, 2, 3), rows.map { (it[1] as Number).toInt() })
        assertEquals(listOf("bo3", "bo3", "bo3"), rows.map { it[2] })
        assertEquals(listOf(1, 0, 1), rows.map { (it[3] as Number).toInt() })
        assertEquals(listOf("opponent", "me", "opponent"), rows.map { it[4] })
        driver.savePng(File(Scenario.pngDir, "match-lobby-after.png"))
    }

    @Test
    fun `leaving mid-game goes back to the lobby, a new game starts at once, and nothing is left running`() {
        open()
        while (app.play.format != MatchFormat.BO1) app.play.cycleFormat()
        fun gameThreads() = Thread.getAllStackTraces().filter { (t, s) -> t.isAlive && s.any { "awaitDialog" in it.methodName || "InputSyncronizedBase" in it.className } }.keys

        app.play.start(state)
        play { ourPriority() }
        driver.savePng(File(Scenario.pngDir, "match-concede-menu-before.png"))
        concede(Key.One) // best of one: 1 is "concede the match"
        waitFor(what = "the lobby") { app.screen == Screen.Lobby }
        waitFor { rows().size == 1 }
        assertEquals(1, (rows()[0][3] as Number).toInt(), "recorded as conceded")
        val after1 = Thread.activeCount()

        app.play.start(state) // from the lobby, on the same pairing
        assertIs<Screen.Playing>(app.screen)
        play { ourPriority() }
        concede(Key.One)
        waitFor(what = "the lobby again") { app.screen == Screen.Lobby }
        waitFor { rows().size == 2 }
        Thread.sleep(500)
        assertTrue(gameThreads().isEmpty(), "no Forge game thread is still waiting on us: ${gameThreads().map { it.name }}")
        assertTrue(Thread.activeCount() <= after1 + 2, "threads don't pile up per game: ${after1} -> ${Thread.activeCount()}")

        // Closing the window mid-game records a conceded game, and returns at once.
        app.play.openLobby(app.selectedId)
        app.play.start(state)
        play { ourPriority() }
        val started = System.currentTimeMillis()
        app.shutdown()
        assertTrue(System.currentTimeMillis() - started < 3_000, "closing never waits on Forge")
        assertEquals(3, rows().size)
        assertEquals(listOf(1, "opponent"), rows()[2].let { listOf((it[3] as Number).toInt(), it[4]) })
        // (The test's JVM lives on: end that game, and check it is not written twice.)
        val last = app.play.match!!
        last.leave()
        waitFor(what = "the abandoned game to end") { last.over }
        Thread.sleep(300)
        assertEquals(3, rows().size, "the game closed with the window is recorded once")
    }
}
