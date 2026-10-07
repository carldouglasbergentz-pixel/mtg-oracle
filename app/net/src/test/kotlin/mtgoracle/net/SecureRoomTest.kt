package mtgoracle.net

import mtgoracle.core.deck.AiCopy
import mtgoracle.core.deck.Deck
import mtgoracle.core.deck.DeckCard
import java.net.InetAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The invite, the sealed link, and a room that strangers can't get into. */
class SecureRoomTest {
    private val closing = mutableListOf<AutoCloseable>()
    @AfterTest fun close() { closing.forEach { runCatching { it.close() } } }

    private val deck = AiCopy.asBuilt(Deck(1, "Green", null, null, listOf(DeckCard("Forest", 60, false, false))))
    private val hello = HostMessage.Hello(PROTOCOL_VERSION, "test", "Alice")

    /** Two ends of a link in memory: what one sends, the other receives; [tap] sees and may change each line on the way. */
    private class Pipe(private val out: LinkedBlockingQueue<String>, private val into: LinkedBlockingQueue<String>, private val tap: (String) -> String?) : Link {
        @Volatile var closed = false
        override fun send(line: String): Boolean { if (closed) return false; tap(line)?.let(out::put); return true }
        override fun receive(): String? = if (closed) null else into.poll(2, TimeUnit.SECONDS)
        override fun close() { closed = true }
        companion object {
            fun pair(tap: (String) -> String? = { it }): Pair<Pipe, Pipe> {
                val a = LinkedBlockingQueue<String>(); val b = LinkedBlockingQueue<String>()
                return Pipe(a, b, tap) to Pipe(b, a, tap)
            }
        }
    }

    // --- the invite ---------------------------------------------------------

    @Test
    fun `an invite comes back as it was made, however it was pasted`() {
        val v4 = Invite.create(InetAddress.getByName("81.230.4.17"), 51234)
        val back = Invite.parse(v4.code)
        assertEquals(v4.address, back.address)
        assertEquals(51234, back.port)
        assertContentEquals(v4.secret, back.secret)
        assertTrue(v4.code.startsWith("MTG-") && v4.code.drop(4).split('-').dropLast(1).all { it.length == 4 }, v4.code)
        assertContentEquals(v4.secret, Invite.parse("  " + v4.code.lowercase().replace("-", " ") + "\n").secret, "case, spaces and dashes don't matter")
        val v6 = Invite.create(InetAddress.getByName("2001:db8::1"), 50000)
        assertEquals(v6.address, Invite.parse(v6.code).address)
        assertFalse(v4.secret.joinToString("") { "%02x".format(it) } in v4.toString(), "the secret is never in a log line")
        assertFalse(Invite.create(v4.address, 51234).secret.contentEquals(v4.secret), "every room has a secret of its own")
    }

    @Test
    fun `a broken invite says what is wrong with it`() {
        val code = Invite.create(InetAddress.getByName("81.230.4.17"), 51234).code
        listOf("", "MTG-", "hello there", code.dropLast(5), code + "AAAA", code.replaceRange(4, 5, "1"), "MTG-" + Base32.encode(byteArrayOf(9, 4) + ByteArray(22)))
            .forEach { text -> assertFailsWith<InviteError>(text) { Invite.parse(text) } }
        assertTrue("another version" in assertFailsWith<InviteError> { Invite.parse("MTG-" + Base32.encode(byteArrayOf(9, 4) + ByteArray(22))) }.message!!)
    }

    // --- the sealed link ----------------------------------------------------

    private val secret = ByteArray(Invite.SECRET_BYTES) { it.toByte() }

    @Test
    fun `sealed lines open at the other end, and nothing readable travels`() {
        val seen = ConcurrentLinkedQueue<String>()
        val (a, b) = Pipe.pair { seen += it; it }
        val host = SecureLink(a, secret, SecureLink.Role.HOST)
        val guest = SecureLink(b, secret, SecureLink.Role.GUEST)
        host.send("""{"type":"board","card":"Raging Goblin"}""")
        guest.send("åäö — Ponder")
        assertEquals("""{"type":"board","card":"Raging Goblin"}""", guest.receive())
        assertEquals("åäö — Ponder", host.receive())
        host.send("again"); host.send("again")
        assertEquals("again", guest.receive()); assertEquals("again", guest.receive())
        assertTrue(seen.none { "Goblin" in it || "Ponder" in it || "again" in it }, "the wire carries only sealed lines: $seen")
        assertEquals(seen.size, seen.distinct().size, "the same text twice is two different sealed lines")
    }

    @Test
    fun `a changed, repeated or foreign line never opens, and the link closes`() {
        fun check(name: String, tap: (String) -> String?, sender: (Link) -> Link = { SecureLink(it, secret, SecureLink.Role.HOST) },
                  receiver: (Link) -> SecureLink = { SecureLink(it, secret, SecureLink.Role.GUEST) }) {
            val (a, b) = Pipe.pair(tap)
            val to = receiver(b)
            sender(a).send("pay 2 life")
            assertNull(to.receive(), "$name: doesn't open")
            assertNotNull(to.failure, "$name: says why")
            assertTrue(b.closed, "$name: the link closed")
        }
        check("a flipped character", tap = { line -> line.replaceRange(5, 6, if (line[5] == 'A') "B" else "A") })
        check("another invite's secret", tap = { it }, sender = { SecureLink(it, ByteArray(Invite.SECRET_BYTES) { 7 }, SecureLink.Role.HOST) })
        check("both sides as host", tap = { it }, receiver = { SecureLink(it, secret, SecureLink.Role.HOST) })
        check("plain text", tap = { it }, sender = { it })
        // The same sealed line twice: the second has the wrong number for its place.
        var first: String? = null
        val (a, b) = Pipe.pair { line -> if (first == null) { first = line; line } else first }
        val from = SecureLink(a, secret, SecureLink.Role.HOST)
        val to = SecureLink(b, secret, SecureLink.Role.GUEST)
        from.send("pay 2 life"); from.send("pass")
        assertEquals("pay 2 life", to.receive())
        assertNull(to.receive(), "a replayed line doesn't open")
        assertNotNull(to.failure)
    }

    // --- the room -----------------------------------------------------------

    @Test
    fun `strangers are hung up on and the guest with the invite gets in`() {
        val room = Room.local().also { closing += it }
        val knocks = ConcurrentLinkedQueue<Door.Outcome>()
        var guest: Room.Guest? = null
        val waiting = thread { guest = room.awaitGuest(hello, judge = { null }, onKnock = { knocks += it }) }

        // A scan: connects and says nonsense. Then one with a link sealed by another invite's secret.
        TcpLink.connectLocal(room.invite.port).also { closing += it }.let { it.send("GET / HTTP/1.1"); assertNotNull(it.receive(), "it hears only a sealed hello"); assertNull(it.receive(), "and is hung up on") }
        val forged = Invite(room.invite.address, room.invite.port, ByteArray(Invite.SECRET_BYTES) { 1 })
        RemoteSeat(forged.join(), "Mallory", deck, "test").also { closing += it }.start().let { mallory ->
            val deadline = System.currentTimeMillis() + 5_000
            while (mallory.seating.value !is Seating.Lost && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertIs<Seating.Lost>(mallory.seating.value, "a forged invite gets nowhere")
        }
        // A line far past what a knock may be.
        TcpLink.connectLocal(room.invite.port).also { closing += it }.let { it.receive(); thread { it.send("x".repeat(Door.KNOCK_LINE * 2)) }; assertNull(it.receive()) }

        val bob = RemoteSeat(room.invite.join(), "Bob", deck, "test").also { closing += it }.start()
        waiting.join(10_000)
        assertEquals("Bob", assertNotNull(guest, "the guest got in").hello.name)
        assertEquals(3, knocks.size, "$knocks")
        assertTrue(knocks.all { it is Door.Outcome.Stranger }, "$knocks")
        assertTrue(bob.seating.value is Seating.Knocking, "in, and waiting to be seated")
        assertFailsWith<java.io.IOException>("no one else: the listener closed") { TcpLink.connectLocal(room.invite.port, 1_000) }
    }

    @Test
    fun `a guest turned away may knock again, and too many strangers close the room`() {
        val room = Room.local().also { closing += it }
        val knocks = ConcurrentLinkedQueue<Door.Outcome>()
        var guest: Room.Guest? = null
        val waiting = thread { guest = room.awaitGuest(hello, judge = { if (it.deck.cards.size < 2) "Bring a deck with spells." else null }, onKnock = { knocks += it }) }
        val first = RemoteSeat(room.invite.join(), "Bob", deck, "test").also { closing += it }.start()
        val deadline = System.currentTimeMillis() + 5_000
        while (first.seating.value !is Seating.Refused && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(Seating.Refused("Bring a deck with spells."), first.seating.value)
        val better = deck.copy(cards = deck.cards + deck.cards.first().copy(forgeName = "Giant Growth", quantity = 4))
        RemoteSeat(room.invite.join(), "Bob", better, "test").also { closing += it }.start()
        waiting.join(10_000)
        assertNotNull(guest, "the second knock, with another deck, got in")
        assertEquals(listOf<Door.Outcome>(Door.Outcome.Refused("Bring a deck with spells.")), knocks.toList())

        val crowded = Room.local().also { closing += it }
        var gaveUp = false
        val waited = thread { gaveUp = crowded.awaitGuest(hello, judge = { null }) == null }
        repeat(Room.MAX_STRANGERS) { TcpLink.connectLocal(crowded.invite.port).use { link -> link.receive(); link.send("nonsense"); link.receive() } }
        waited.join(10_000)
        assertTrue(gaveUp, "the room gave up")
        assertTrue("closed" in crowded.closedBecause!!, crowded.closedBecause)
    }

    @Test
    fun `a stranger who says nothing is hung up on when the knock's time is up`() {
        val room = Room.local().also { closing += it }
        val knocks = ConcurrentLinkedQueue<Door.Outcome>()
        thread(isDaemon = true) { room.awaitGuest(hello, judge = { null }, onKnock = { knocks += it }) }
        val silent = TcpLink.connectLocal(room.invite.port).also { closing += it }
        silent.receive() // the sealed hello
        val started = System.currentTimeMillis()
        assertNull(silent.receive(), "hung up on")
        val waited = System.currentTimeMillis() - started
        assertTrue(waited in (Door.KNOCK_MILLIS - 1_000)..(Door.KNOCK_MILLIS + 3_000), "after the knock's time: $waited ms")
        Thread.sleep(100)
        assertIs<Door.Outcome.Stranger>(knocks.single())
    }
}
