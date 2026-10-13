package mtgoracle.app

import mtgoracle.core.deck.DeckSummary
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The lobby says "Start waits for it" while Forge starts, and so it does:
 * Start pressed then is kept, and played out when Forge is ready; leaving
 * the lobby takes it back.
 */
class StartWaitsTest {
    private val said = mutableListOf<String?>()
    private var ready = false

    private fun play(): PlayControl {
        val settings = Settings(File(Files.createTempDirectory("start-waits").toFile(), "settings.properties"))
        val decks = listOf(DeckSummary(1, "UW Control", "premodern", null, null, 60))
        return PlayControl(
            settings, sessions = { error("no match is started in this test") }, decks = { decks }, deckById = { null }, games = { null },
            forgeReady = { ready }, show = {}, say = { said += it },
        ).also { it.openLobby(1) }
    }

    @Test
    fun `Start pressed while Forge starts waits for it, and says so`() {
        val play = play()
        play.start()
        assertTrue(play.startWaiting, "kept")
        assertTrue(said.last().orEmpty().contains("starts as soon as it is ready"), "$said")
        // Ready, with no opponent chosen (the deck reads as none here): it says why it can't start, and waits no more.
        ready = true
        play.forgeIsReady()
        assertFalse(play.startWaiting)
        assertEquals("Forge is ready: choose the decks and press Start", said.last())
    }

    @Test
    fun `leaving the lobby takes the waiting Start back`() {
        val play = play()
        play.start()
        play.cancelWaitingStart()
        ready = true
        val before = said.size
        play.forgeIsReady()
        assertFalse(play.startWaiting)
        assertEquals(before, said.size, "nothing starts, nothing is said")
    }
}
