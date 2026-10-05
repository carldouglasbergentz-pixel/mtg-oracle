package mtgoracle.app

import androidx.compose.ui.input.key.Key
import mtgoracle.data.DbFixture
import mtgoracle.ui.OffscreenDriver
import mtgoracle.ui.kit.ClickTarget
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The lobby chooses both decks itself: it opens on the deck you are on, else
 * the last you played, and offers only decks of its game type. The library
 * opens a deck to edit on a double-click, with no Edit step between.
 */
class LobbyTest {
    private val assets = File(System.getProperty("mtgoracle.forgeAssets"))
    private lateinit var data: File

    @AfterTest fun close() { if (this::data.isInitialized) data.deleteRecursively() }

    private fun app(): AppController {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        data = DbFixture.copy().parentFile
        Scenario.startForge()
        return AppController(AppPaths(data, assets, forgeHome = Scenario.home)).also { it.boot() }
    }

    @Test
    fun `your deck is chosen in the lobby, the opponents are those that can face it, and the last pairing is kept`() {
        val app = app()
        val first = app.decks.first()
        app.play.openLobby(first.id)
        assertEquals(Screen.Lobby, app.screen)
        assertEquals(first.id, app.play.lobbyMeId, "the deck you were on")
        fun gameType(id: Int) = app.deckById(id)!!.gameType
        val other = app.decks.first { gameType(it.id) != gameType(first.id) }
        app.play.chooseMe(other.id)
        assertEquals(other.id, app.play.lobbyMeId)
        assertTrue(app.play.opponents().isNotEmpty() && app.play.opponents().all { gameType(it.deck.id) == gameType(other.id) }, "only decks that can face it")
        assertTrue(app.play.opponentId in app.play.opponents().map { it.deck.id }, "and the opponent follows: ${app.play.opponentId}")

        // With no deck to start from, the last pairing played (kept by start and simulate).
        app.settings.lobbyMe = other.id
        app.backToLibrary()
        app.play.openLobby(me = null)
        assertEquals(other.id, app.play.lobbyMeId)
    }

    @Test
    fun `a double-click on a deck opens it to edit, a single click only selects it`() {
        val app = app()
        OffscreenDriver(1800, 1200) { AppContent(app) {} }.use { d ->
            d.settle(5)
            val deck = app.decks[1]
            assertTrue(d.click(ClickTarget.Control("deck:${deck.id}")))
            d.settle(2)
            assertEquals(deck.id, app.selectedId)
            assertNull(app.editing, "one click selects")
            Thread.sleep(700) // past the double-click window
            assertTrue(d.click(ClickTarget.Control("deck:${deck.id}")))
            assertTrue(d.click(ClickTarget.Control("deck:${deck.id}")))
            d.settle(3)
            assertEquals(deck.id, assertNotNull(app.editing, "two quick clicks open it").deckId)
            assertNull(d.registry[ClickTarget.Control("edit")], "no Edit button")
            d.key(Key.Escape)
        }
    }
}
