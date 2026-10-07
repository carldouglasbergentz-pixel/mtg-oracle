package mtgoracle.app

import mtgoracle.core.deck.AiCopy
import mtgoracle.core.deck.Deck
import mtgoracle.core.deck.DeckCard
import mtgoracle.core.seat.ScriptedSeat
import mtgoracle.net.Link
import mtgoracle.net.Seating
import mtgoracle.ui.OffscreenDriver
import mtgoracle.ui.board.BoardScreen
import mtgoracle.ui.kit.CardMode
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A whole game between a host at Forge and a guest who has only a remote
 * seat over a TCP link, both played by scripted seats, the guest's board
 * drawn offscreen as its window would. Every line the host sends is kept and
 * searched: the host's hand never travels.
 */
class RemoteGameTest {
    private val out = File(Scenario.home, "remote").also { it.mkdirs() }

    /** The host's end of the link, every line sent kept. */
    private class Recording(private val link: Link) : Link by link {
        val sent = ConcurrentLinkedQueue<String>()
        override fun send(line: String): Boolean { sent += line; return link.send(line) }
    }

    private fun deck(id: Int, name: String, vararg cards: Pair<String, Int>) =
        AiCopy.asBuilt(Deck(id, name, null, null, cards.map { (card, n) -> DeckCard(card, n, false, false) }))

    @Test
    fun `a game over the link to its end, the host's hand never sent`() {
        Scenario.startForge()
        // Draco costs 16 and the host has only Mountains: drawn, it stays in the hand all game. A game is not the same
        // from run to run (what else ran in the JVM moves Forge's random), so a fifth of the deck: one is all but sure.
        val host = deck(1, "Red", "Mountain" to 24, "Raging Goblin" to 24, "Draco" to 12)
        val guest = deck(2, "Green", "Forest" to 24, "Grizzly Bears" to 12, "Giant Growth" to 12, "Llanowar Elves" to 12)
        var recording: Recording? = null
        val table = LocalDuel.table(Sessions(null, out), host, guest, "test", seed = 7, wrap = { link -> Recording(link).also { recording = it } })
        val match = table.match
        val remote = table.guest
        val driver = OffscreenDriver(1600, 1000) { BoardScreen(remote, "Guest", CardMode.TEXT) }

        val dracoInHand = ConcurrentLinkedQueue<Int>()
        val dracoPublic = ConcurrentLinkedQueue<String>()
        val players = listOf(match.seat, remote).map { seat ->
            thread { ScriptedSeat(seat, retryAfterMillis = 1500).play(timeoutMillis = 300_000) { (seat.board.value?.turn ?: 0) > 40 } }
        }
        while (players.any { it.isAlive }) {
            match.seat.board.value?.let { board ->
                val alice = board.seat ?: return@let
                if (alice.hand.any { it.name == "Draco" }) dracoInHand += board.turn
                board.players.forEach { p -> (p.battlefield + p.graveyard + p.exile).filter { it.name == "Draco" }.forEach { dracoPublic += "${p.name}'s ${it.name} on turn ${board.turn}" } }
                board.stack.filter { it.sourceName == "Draco" }.forEach { dracoPublic += "on the stack, turn ${board.turn}" }
            }
            driver.frame()
            Thread.sleep(20)
        }
        players.forEach { it.join() }
        driver.settle(5)
        driver.savePng(File(Scenario.pngDir, "remote-guest-board.png"))
        driver.close()

        assertTrue(remote.board.value!!.gameOver, "the game ended for the guest too")
        val deadline = System.currentTimeMillis() + 20_000
        while (remote.seating.value !is Seating.Ended && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(remote.seating.value is Seating.Ended, "the guest was told the table closed: ${remote.seating.value}")
        assertEquals(match.games.value.last().summary, (remote.seating.value as Seating.Ended).reason)

        assertTrue(dracoInHand.isNotEmpty(), "the host held a Draco, so the search means something")
        assertEquals(emptyList(), dracoPublic.distinct(), "no Draco reached a public zone")
        val sent = recording!!.sent.toList()
        assertTrue(sent.any { "Raging Goblin" in it }, "the host's public cards do travel")
        assertEquals(emptyList(), sent.filter { "Draco" in it }.map { it.take(300) }, "the host's hand never does")
        println("host sent ${sent.size} lines, ${sent.sumOf { it.length } / 1024} KB; Draco in hand on turns ${dracoInHand.distinct()}")
    }
}
