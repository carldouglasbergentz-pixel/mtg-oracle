package mtgoracle.app

import androidx.compose.ui.input.key.Key
import mtgoracle.core.model.ChoicePrompt
import mtgoracle.core.model.InputKind
import mtgoracle.core.model.InputPrompt
import mtgoracle.core.model.NumberPrompt
import mtgoracle.core.model.SeatAction
import mtgoracle.core.seat.ScriptedSeat
import mtgoracle.data.DbFixture
import mtgoracle.forge.Log
import mtgoracle.ui.OffscreenDriver
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * The crash in the user's Bo3: Wrath of the Skies' unbounded X, and what the
 * app does when something breaks — logged in full, shown, survived, and never
 * recorded as a concession.
 */
class CrashTest {
    private var data: File? = null

    @AfterTest fun cleanUp() { Log.toFile(null); data?.deleteRecursively() }

    private val wrath = listOf(
        "turn=3", "activeplayer=human", "activephase=MAIN1", "removesummoningsickness=true", "humanlife=20", "ailife=20",
        "humanhand=Wrath of the Skies", "humanbattlefield=Plains;Plains;Plains;Plains;Plains", "humanlibrary=" + List(20) { "Plains" }.joinToString(";"),
        "aihand=", "aibattlefield=Grizzly Bears;Hill Giant", "ailibrary=" + List(20) { "Swamp" }.joinToString(";"),
    )

    @Test
    fun `Wrath of the Skies - X of 0 to Int MAX typed, the energy paid, the permanents of that mana value destroyed`() {
        Scenario("wrath", wrath) { p, b, _ ->
            when {
                p is InputPrompt && p.kind == InputKind.PRIORITY && b.seat!!.hand.any { it.name == "Wrath of the Skies" } -> SeatAction.ClickCard(b.seat!!.hand.first().id)
                // "Pay any amount of {E}": all of it, so the Bears (mana value 2) go.
                p is ChoicePrompt && "energy" in p.message.lowercase() -> SeatAction.Choose(listOf(p.options.indexOfFirst { it.label == "2" }))
                p is InputPrompt && p.kind == InputKind.CONFIRM -> SeatAction.Ok
                p is InputPrompt && p.kind == InputKind.PRIORITY -> SeatAction.Ok
                else -> null
            }
        }.use { s ->
            s.playUntil { s.match.seat.prompt.value is NumberPrompt }
            val x = s.match.seat.prompt.value as NumberPrompt
            assertEquals(Int.MAX_VALUE, x.max, "Forge asks with no upper bound")
            assertEquals(3, x.suggested, "five Plains, {W}{W} of it for the rest: X up to 3")
            assertContains(s.screenText(), "max affordable 3")
            // Typed, as a person would: clear the suggestion, 2, Enter.
            s.key(Key.Backspace); s.key(Key.Two); s.key(Key.Enter)
            s.playUntil { s.board.players.first { !it.isSeat }.graveyard.any { it.name == "Grizzly Bears" } }
            assertTrue(s.board.players.first { !it.isSeat }.battlefield.any { it.name == "Hill Giant" }, "mana value 4 survives an X of 2")
            assertContains(s.logText(), "ANSWER #${x.id} Number(value=2)")
        }
    }

    @Test
    fun `an error while drawing the board is logged, shown and survived - and the game is recorded as unfinished, not conceded`() {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        val copy = DbFixture.copy()
        data = copy.parentFile
        val deckId = DriverManager.getConnection("jdbc:sqlite:${copy.path}").use { c ->
            c.prepareStatement("SELECT id FROM decks WHERE name = 'Rakdos Midrange'").use { st -> st.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else null } }
        }
        assumeTrue(deckId != null, "the user's Rakdos Midrange deck")
        Scenario.startForge()
        val paths = AppPaths(copy.parentFile, File(System.getProperty("mtgoracle.forgeAssets")), forgeHome = Scenario.home)
        Log.toFile(paths.appLog)
        val app = AppController(paths)
        app.boot()
        var inject by androidx.compose.runtime.mutableStateOf(false) // state: flipping it recomposes
        OffscreenDriver(1800, 1600, onError = { app.onCrash("window", it) }) {
            AppContent(app) {}
            // A renderer that breaks, once the board is up: the stand-in for whatever the real one was.
            if (inject && app.screen == Screen.Playing) error("injected: a prompt renderer failed")
        }.use { d ->
            fun waitFor(what: String, until: () -> Boolean) {
                val deadline = System.currentTimeMillis() + 60_000
                while (!until()) { if (System.currentTimeMillis() > deadline) fail("timed out: $what"); d.frame(); Thread.sleep(20) }
            }
            waitFor("Forge") { app.forgeReady }
            app.select(deckId!!); app.openLobby(); app.opponentId = deckId
            app.start(wrath)
            val seat = app.match!!.seat
            ScriptedSeat(seat, submit = { p, a -> if (d.perform(p, a) == null) seat.answer(p.id, a) })
                .play(timeoutMillis = 60_000, until = { d.frame(); (seat.prompt.value as? InputPrompt)?.kind == InputKind.PRIORITY })
            inject = true
            d.frame()
            waitFor("the crash screen") { app.screen == Screen.Crashed }
            assertTrue("the board hit an error" in d.text.all() && "injected" in d.text.all(), "shown: ${d.text.all().take(300)}")
            assertContains(paths.appLog.readText(), "CRASH in window")
            assertContains(paths.appLog.readText(), "injected: a prompt renderer failed", message = "the full trace in the app log")
            assertContains(app.match!!.recorder.file.readText(), "APP CRASH in window", message = "and in the game's")
            d.key(Key.Enter)
            waitFor("the library") { app.screen == Screen.Library }
            val row = DriverManager.getConnection("jdbc:sqlite:${copy.path}").use { c ->
                c.prepareStatement("SELECT winner, conceded FROM games").use { st -> st.executeQuery().use { rs -> assertTrue(rs.next(), "one row"); rs.getString(1) to rs.getInt(2) } }
            }
            assertEquals(null to 0, row, "unfinished: no winner, not conceded")
            assertIs<Screen.Library>(app.screen)
        }
    }
}
