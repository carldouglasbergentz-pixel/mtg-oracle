package mtgoracle.net

import mtgoracle.core.deck.GameType
import mtgoracle.core.deck.PlayCard
import mtgoracle.core.deck.PlayDeck
import mtgoracle.core.deck.Section
import mtgoracle.core.limited.LimitedSet
import mtgoracle.core.limited.OpenedPool
import mtgoracle.core.limited.PoolCard
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A sealed table over a real sealed link on this machine (ADR 0003): each
 * side opens its own pool from its secret and the other's number, the host
 * is ready first, the guest's deck is judged against the guest's pool as
 * the host opens it, and the host's deck against the host's pool after the
 * match. Packs come from a stand-in for Forge, the same on both sides, as
 * the same app's Forge is.
 */
class SealedTableTest {
    private val closing = mutableListOf<AutoCloseable>()
    @AfterTest fun close() { closing.forEach { runCatching { it.close() } } }

    private val set = LimitedSet("TST", "tst", "Test Set", "2026-10-08")
    private val table = LimitedTable(set, packs = 6, packsDigest = "digest")
    private val hello = HostMessage.Hello(PROTOCOL_VERSION, "test", "Alice", table)

    /** Six packs of fourteen, drawn from 150 names by the seed: the same seed, the same packs. */
    private fun packs(seed: Long): OpenedPool {
        val random = Random(seed)
        return OpenedPool(set, seed, List(6) { List(14) { PoolCard("Card ${random.nextInt(150)}", "tst", null) } })
    }

    /** A deck of [pool]: its first forty cards in the main deck, the rest in the sideboard, and basic lands to swap in. */
    private fun deckOf(pool: OpenedPool, name: String = "Mine"): PlayDeck {
        val main = pool.cards.take(40).groupingBy { it.name }.eachCount().map { (n, q) -> PlayCard(n, q, Section.MAIN, "tst", null) }
        val side = pool.cards.drop(40).groupingBy { it.name }.eachCount().map { (n, q) -> PlayCard(n, q, Section.SIDEBOARD, "tst", null) }
        return PlayDeck(1, name, GameType.LIMITED, main + side + PlayCard("Forest", 10, Section.SIDEBOARD, null, null), isAiCopy = false, applied = emptyList(), notes = emptyList())
    }

    private inner class Guest : SealedSeat {
        @Volatile var pool: OpenedPool? = null
        override fun packsDigest(table: LimitedTable) = "digest"
        override fun open(table: LimitedTable, seed: Long) = packs(seed)
        override fun poolOpened(table: LimitedTable, pool: OpenedPool) { this.pool = pool }
    }

    private class Host(val link: Link, table: LimitedTable, open: (Long) -> OpenedPool) {
        /** After the match, as the host's SeatHost sends it ([SeatHost.reveal]). */
        fun reveal(secret: String, deck: PlayDeck) { link.send(Wire.encode(HostMessage.Reveal(secret, deck))) }
        val events = ConcurrentLinkedQueue<SealedEvent>()
        val sealed = SealedHost(link, table, open) { events += it }
        @Volatile var outcome: SealedHost.Outcome? = null
        val thread = thread(name = "test-sealed-host", isDaemon = true) { outcome = sealed.run() }
    }

    private fun waitFor(what: String, until: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!until()) { if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what"); Thread.sleep(10) }
    }

    /** A host's room and a guest seated at it: the host's sealed table running, the guest's seat started. */
    private fun seated(guest: Guest = Guest()): Triple<Host, RemoteSeat, Guest> {
        val room = Room.local().also { closing += it }
        var admitted: Room.Guest? = null
        val door = thread { admitted = room.awaitGuest(hello, judge = { null }) }
        val seat = RemoteSeat(room.invite.join(), "Bob", deck = null, app = "test", sealed = guest).also { closing += it }.start()
        door.join(10_000)
        val link = assertNotNull(admitted).link.also { closing += it }
        Door.seat(link, "Bob")
        return Triple(Host(link, table, ::packs), seat, guest)
    }

    @Test
    fun `each side opens a pool of its own, and an honest deck sits down after the host is ready`() {
        val (host, seat, guest) = seated()
        waitFor("both pools") { host.sealed.pool != null && guest.pool != null }
        val hostPool = host.sealed.pool!!
        val guestPool = guest.pool!!
        assertNotEquals(hostPool.cards, guestPool.cards, "two pools, not one")

        assertEquals("The host isn't ready yet: send your deck once they are.", seat.sendDeck(deckOf(guestPool)), "the host is ready first")
        assertNull(host.sealed.ready(deckOf(hostPool, "Host's")))
        waitFor("the host's ready") { seat.sealedProgress.value?.hostReady == true }
        assertNull(seat.sendDeck(deckOf(guestPool)))
        waitFor("the guest's deck") { host.outcome != null }
        val outcome = assertIs<SealedHost.Outcome.Seated>(host.outcome)
        assertEquals(guestPool, outcome.pool, "the host opened the guest's pool itself, and it is the guest's")
        waitFor("the verdict") { seat.sealedProgress.value?.accepted == true }

        // After the match: the host's secret and deck, checked against the host's pool by the guest.
        host.reveal(host.sealed.secret, host.sealed.deck!!)
        waitFor("the check") { seat.sealedProgress.value?.hostCheck != null }
        assertEquals("The host's deck was built from its own pool: checked.", seat.sealedProgress.value?.hostCheck)
    }

    @Test
    fun `a host can't be ready with a deck beyond its pool, nor play another deck than the one it was ready with`() {
        val (host, seat, guest) = seated()
        waitFor("both pools") { host.sealed.pool != null && guest.pool != null }
        val pool = host.sealed.pool!!
        val cheat = deckOf(pool).let { d -> d.copy(cards = d.cards + PlayCard("Black Lotus", 1, Section.MAIN, null, null)) }
        assertTrue("holds more than its pool opened: Black Lotus" in host.sealed.ready(cheat)!!)
        assertNull(host.sealed.ready(deckOf(pool)))
        waitFor("the host's ready") { seat.sealedProgress.value?.hostReady == true }
        seat.sendDeck(deckOf(guest.pool!!))
        waitFor("the guest's deck") { host.outcome != null }
        // Within its pool still, but not the deck it was ready with: one basic land fewer to swap in.
        val another = deckOf(pool).let { d -> d.copy(cards = d.cards.map { if (it.forgeName == "Forest") it.copy(quantity = 9) else it }) }
        host.reveal(host.sealed.secret, another)
        waitFor("the check") { seat.sealedProgress.value?.hostCheck != null }
        assertEquals("The host played another deck than the one it was ready with.", seat.sealedProgress.value?.hostCheck)
    }

    @Test
    fun `a changed guest app is judged by the host, a card beyond its pool, a secret not its own, a deck before the host is ready`() {
        val room = Room.local().also { closing += it }
        var admitted: Room.Guest? = null
        val door = thread { admitted = room.awaitGuest(hello, judge = { null }) }
        // A hand-written guest: the messages a changed app could send, none of the checks the real one makes first.
        val link = room.invite.join().also { closing += it }
        assertIs<HostMessage.Hello>(Wire.host(link.receive()!!))
        link.send(Wire.encode(GuestMessage.Hello(PROTOCOL_VERSION, "test", "Mallory", deck = null, packsDigest = "digest")))
        door.join(10_000)
        val hostLink = assertNotNull(admitted).link.also { closing += it }
        Door.seat(hostLink, "Mallory")
        val host = Host(hostLink, table, ::packs)
        fun next(): HostMessage { while (true) { val m = Wire.host(link.receive() ?: fail("the host hung up")); if (m != HostMessage.Ping) return m } }
        assertIs<HostMessage.Accepted>(next())
        assertIs<HostMessage.Envelope>(next())
        val keys = SealedKeys()
        link.send(Wire.encode(GuestMessage.Envelope(keys.envelope)))
        val hostNonce = assertIs<HostMessage.Nonce>(next()).value
        link.send(Wire.encode(GuestMessage.Nonce(SealedKeys().nonce)))
        val mine = packs(SealedKeys.seed(keys.secret, hostNonce)!!)

        link.send(Wire.encode(GuestMessage.Deck(deckOf(mine), keys.secret)))
        assertEquals("The host isn't ready yet: send your deck once they are.", assertIs<HostMessage.Verdict>(next()).refusal)
        waitFor("the host's pool") { host.sealed.pool != null }
        assertNull(host.sealed.ready(deckOf(host.sealed.pool!!)))
        assertIs<HostMessage.Ready>(next())

        val lotus = deckOf(mine).let { d -> d.copy(cards = d.cards + PlayCard("Black Lotus", 1, Section.SIDEBOARD, null, null)) }
        link.send(Wire.encode(GuestMessage.Deck(lotus, keys.secret)))
        assertTrue("holds more than its pool opened: Black Lotus" in assertIs<HostMessage.Verdict>(next()).refusal!!)
        // Another secret opens other packs, perhaps better ones: it isn't the one the envelope sealed.
        val other = SealedKeys()
        link.send(Wire.encode(GuestMessage.Deck(deckOf(packs(SealedKeys.seed(other.secret, hostNonce)!!)), other.secret)))
        assertEquals("That secret isn't the one your envelope sealed.", assertIs<HostMessage.Verdict>(next()).refusal)
        assertTrue(host.events.count { it is SealedEvent.Refused } == 3, "each refusal said to the host's lobby too")

        link.send(Wire.encode(GuestMessage.Deck(deckOf(mine), keys.secret)))
        assertNull(assertIs<HostMessage.Verdict>(next()).refusal)
        waitFor("the outcome") { host.outcome != null }
        assertIs<SealedHost.Outcome.Seated>(host.outcome)
    }

    @Test
    fun `a number before its envelope breaks the table off`() {
        val room = Room.local().also { closing += it }
        var admitted: Room.Guest? = null
        val door = thread { admitted = room.awaitGuest(hello, judge = { null }) }
        val link = room.invite.join().also { closing += it }
        link.receive()
        link.send(Wire.encode(GuestMessage.Hello(PROTOCOL_VERSION, "test", "Mallory", deck = null, packsDigest = "digest")))
        door.join(10_000)
        val hostLink = assertNotNull(admitted).link.also { closing += it }
        Door.seat(hostLink, "Mallory")
        val host = Host(hostLink, table, ::packs)
        // Seen the host's envelope, it picks its number first: then its own secret could still be chosen to suit the host's number.
        link.send(Wire.encode(GuestMessage.Nonce(SealedKeys().nonce)))
        waitFor("the outcome") { host.outcome != null }
        assertEquals(SealedHost.Outcome.Gone("the guest's number came out of turn, or was no number"), host.outcome)
    }

    @Test
    fun `a guest who joins a sealed table from the constructed tab, or the other way round, is told where to join from`() {
        val room = Room.local().also { closing += it }
        thread { room.awaitGuest(hello, judge = { null }) }
        val constructed = RemoteSeat(room.invite.join(), "Bob", deck = deckOf(packs(1)), app = "test").also { closing += it }.start()
        waitFor("the refusal") { constructed.seating.value is Seating.Refused }
        assertEquals(Seating.Refused("This table plays sealed (Test Set): join from the lobby's limited tab."), constructed.seating.value)

        val plain = Room.local().also { closing += it }
        thread { plain.awaitGuest(HostMessage.Hello(PROTOCOL_VERSION, "test", "Alice"), judge = { null }) }
        val limited = RemoteSeat(plain.invite.join(), "Bob", deck = null, app = "test", sealed = Guest()).also { closing += it }.start()
        waitFor("the refusal") { limited.seating.value is Seating.Refused }
        assertEquals(Seating.Refused("This table plays constructed: choose your deck in the lobby's constructed tab, then join."), limited.seating.value)
    }

}
