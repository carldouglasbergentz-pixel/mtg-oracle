package mtgoracle.net

import mtgoracle.core.deck.AiCopy
import mtgoracle.core.deck.Deck
import mtgoracle.core.deck.DeckCard
import mtgoracle.core.model.BoardState
import mtgoracle.core.model.LogKind
import mtgoracle.core.model.LogLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HandshakeAndLogTest {
    private val deck = AiCopy.asBuilt(Deck(1, "Red", null, null, listOf(DeckCard("Mountain", 60, false, false))))

    @Test
    fun `the same protocol sits down, another is told who needs the update`() {
        assertNull(Handshake.refusal(GuestMessage.Hello(PROTOCOL_VERSION, "x", "Bob", deck)))
        assertTrue("your app is older" in Handshake.refusal(GuestMessage.Hello(PROTOCOL_VERSION - 1, "x", "Bob", deck))!!)
        assertTrue("the host's app is older" in Handshake.refusal(GuestMessage.Hello(PROTOCOL_VERSION + 1, "x", "Bob", deck))!!)
        assertEquals("Your deck has no cards.", Handshake.refusal(GuestMessage.Hello(PROTOCOL_VERSION, "x", "Bob", deck.copy(cards = emptyList()))))
        assertNull(Handshake.refusal(HostMessage.Hello(PROTOCOL_VERSION, "x", "Alice")))
        assertTrue("the host's app is older" in Handshake.refusal(HostMessage.Hello(PROTOCOL_VERSION - 1, "x", "Alice"))!!)
    }

    @Test
    fun `a name the table can show`() {
        assertEquals("Bob", Handshake.cleanName("  Bob \n"))
        assertEquals("Guest", Handshake.cleanName(" \u0007 "))
        assertEquals("A".repeat(Handshake.MAX_NAME), Handshake.cleanName("A".repeat(40)))
        assertEquals("Ann-Sofie", Handshake.cleanName("Ann\u0000-Sofie"))
        // Invisible characters with which a name could pass for another: zero-width, a direction override, a line separator.
        assertEquals("Alice", Handshake.cleanName("Al\u200Bice"))
        assertEquals("Alice", Handshake.cleanName("\u202EAlice"))
        assertEquals("AliceBob", Handshake.cleanName("Alice\u2028Bob"))
    }

    private fun line(seq: Long, text: String = "line $seq") = LogLine(seq, LogKind.OTHER, text)
    private val board = BoardState(1, "", "-", 1, "Alice", emptyList(), emptyList(), emptyList(), emptyList(), false, null)

    @Test
    fun `the log travels as what the guest lacks, folds and all`() {
        val sender = LogSender()
        val receiver = LogReceiver()
        fun send(log: List<LogLine>) { sender.next(log)?.let(receiver::apply); assertEquals(log, receiver.join(board).log) }

        send(listOf(line(1), line(2)))
        val again = sender.next(listOf(line(1), line(2)))
        assertNull(again, "nothing new: nothing sent")
        send(listOf(line(1), line(2), line(3, "Alice drew a card")))
        // Two draws in a row fold: line 3 goes, line 4 holds both.
        val fold = assertNotNull(sender.next(listOf(line(1), line(2), line(4, "Alice drew 2 cards"))))
        assertEquals(2, fold.keepThrough)
        assertEquals(listOf(line(4, "Alice drew 2 cards")), fold.lines, "only the new line travels")
        receiver.apply(fold)
        assertEquals(listOf(line(1), line(2), line(4, "Alice drew 2 cards")), receiver.join(board).log)
        // The host drops its oldest lines in a long match: nothing to send for that, and the guest's own cap drops them there.
        assertNull(sender.next(listOf(line(2), line(4, "Alice drew 2 cards"))))
        val next = assertNotNull(sender.next(listOf(line(2), line(4, "Alice drew 2 cards"), line(5))))
        assertEquals(HostMessage.Log(4, listOf(line(5))), next)
        receiver.apply(next)
        assertEquals(listOf(1L, 2L, 4L, 5L), receiver.join(board).log.map { it.seq })
    }

    @Test
    fun `the guest keeps as many lines as the host does`() {
        val receiver = LogReceiver()
        receiver.apply(HostMessage.Log(-1, (1L..(LogReceiver.MAX_LINES + 10L)).map { line(it) }))
        val log = receiver.join(board).log
        assertEquals(LogReceiver.MAX_LINES, log.size)
        assertEquals(11L, log.first().seq)
    }
}
