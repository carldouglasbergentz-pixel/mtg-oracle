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

    private val host = HostMessage.Hello(PROTOCOL_VERSION, "x", "Alice")

    @Test
    fun `the same protocol sits down, another is told who needs the update`() {
        assertNull(Handshake.refusal(GuestMessage.Hello(PROTOCOL_VERSION, "x", "Bob", deck), host))
        assertTrue("your app is older" in Handshake.refusal(GuestMessage.Hello(PROTOCOL_VERSION - 1, "x", "Bob", deck), host)!!)
        assertTrue("the host's app is older" in Handshake.refusal(GuestMessage.Hello(PROTOCOL_VERSION + 1, "x", "Bob", deck), host)!!)
        assertEquals("Your deck has no cards.", Handshake.refusal(GuestMessage.Hello(PROTOCOL_VERSION, "x", "Bob", deck.copy(cards = emptyList())), host))
        assertEquals("Your deck has no cards.", Handshake.refusal(GuestMessage.Hello(PROTOCOL_VERSION, "x", "Bob", deck = null), host))
        assertNull(Handshake.refusal(host, "x"))
        assertTrue("the host's app is older" in Handshake.refusal(HostMessage.Hello(PROTOCOL_VERSION - 1, "x", "Alice"), "x")!!)
    }

    @Test
    fun `network play is between the same versions of the app`() {
        assertEquals(
            "Your app is 0.4.0 and the host's is 0.5.0: network play needs the same version on both sides (`update`).",
            Handshake.refusal(GuestMessage.Hello(PROTOCOL_VERSION, "0.4.0", "Bob", deck), host.copy(app = "0.5.0")),
        )
        assertEquals(
            "The host's app is 0.5.0 and yours is 0.4.0: network play needs the same version on both sides (`update`).",
            Handshake.refusal(host.copy(app = "0.5.0"), "0.4.0"),
        )
    }

    @Test
    fun `a sealed table takes a guest whose app opens the same packs, and no deck`() {
        val table = LimitedTable(mtgoracle.core.limited.LimitedSet("BLB", "blb", "Bloomburrow", "2024-08-02"), 6, "abc")
        val sealed = host.copy(table = table)
        assertNull(Handshake.refusal(GuestMessage.Hello(PROTOCOL_VERSION, "x", "Bob", deck = null, packsDigest = "abc"), sealed))
        assertEquals(
            "Your app opens other packs of Bloomburrow than the host's: both need the same version.",
            Handshake.refusal(GuestMessage.Hello(PROTOCOL_VERSION, "x", "Bob", deck = null, packsDigest = "abd"), sealed),
        )
        assertNull(Handshake.refusal(sealed, "x"))
        assertEquals("The host's table asks for 1000 packs each; a table opens 1 to 12.", Handshake.refusal(sealed.copy(table = table.copy(packs = 1000)), "x"))
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
