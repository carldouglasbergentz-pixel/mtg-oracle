package mtgoracle.net

import kotlinx.coroutines.flow.MutableStateFlow
import mtgoracle.core.deck.AiCopy
import mtgoracle.core.deck.Deck
import mtgoracle.core.deck.DeckCard
import mtgoracle.core.model.BoardState
import mtgoracle.core.model.ConfirmPrompt
import mtgoracle.core.model.GameSeat
import mtgoracle.core.model.LogKind
import mtgoracle.core.model.LogLine
import mtgoracle.core.model.PhaseStops
import mtgoracle.core.model.Prompt
import mtgoracle.core.model.SeatAction
import mtgoracle.core.model.SeatCommand
import mtgoracle.core.model.Step
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A remote seat and the host's end of it, over a real TCP link on this machine. */
class RemoteSeatTest {
    /** The host's side of the guest's seat, as `forge` would give it: flows to send, calls to take. */
    private class HostSideSeat : GameSeat {
        override val board = MutableStateFlow<BoardState?>(null)
        override val prompt = MutableStateFlow<Prompt?>(null)
        override val stops = MutableStateFlow(PhaseStops.DEFAULT)
        override val yieldStatus = MutableStateFlow<String?>(null)
        override val warning = MutableStateFlow<String?>(null)
        override val showAllHands = MutableStateFlow(false)
        val calls = ConcurrentLinkedQueue<String>()
        override fun answer(promptId: Long, action: SeatAction) { calls += "answer #$promptId $action" }
        override fun command(command: SeatCommand) { calls += "command $command" }
        override fun setStops(stops: PhaseStops) { calls += "stops ${stops.serialise()}"; this.stops.value = stops }
    }

    private val deck = AiCopy.asBuilt(Deck(1, "Green", null, null, listOf(DeckCard("Forest", 60, false, false))))
    private val closing = mutableListOf<AutoCloseable>()
    @AfterTest fun close() { closing.forEach { runCatching { it.close() } } }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) { check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }; Thread.sleep(5) }
    }

    private fun board(turn: Int, vararg lines: LogLine) = BoardState(turn, "Main", "MAIN1", 1, "Alice", emptyList(), emptyList(), emptyList(), lines.toList(), false, null)
    private fun line(seq: Long) = LogLine(seq, LogKind.OTHER, "line $seq")

    /** A host at [HostSideSeat] and a guest knocking: the guest's hello, as the host's door read it, or null. */
    private fun knock(judge: (GuestMessage.Hello) -> String? = { null }, guestStops: PhaseStops = PhaseStops.DEFAULT): Triple<HostSideSeat, RemoteSeat, Pair<Link, Door.Outcome>> {
        val listener = TcpLink.listenLocal().also { closing += it }
        assertTrue(listener.isLoopbackOnly, "nothing on the network reaches it")
        val guest = RemoteSeat(TcpLink.connectLocal(listener.port), "  Bob\n", deck, "test", guestStops).also { closing += it }.start()
        val link = listener.accept(5_000).also { closing += it }
        val outcome = Door.admit(link, HostMessage.Hello(PROTOCOL_VERSION, "test", "Alice"), judge)
        return Triple(HostSideSeat(), guest, link to outcome)
    }

    @Test
    fun `seated, the guest gets the seat as it changes and the host gets the guest's answers`() {
        val events = ConcurrentLinkedQueue<GuestEvent>()
        val (seat, guest, door) = knock(guestStops = PhaseStops(setOf(Step.MAIN1), emptySet()))
        val (link, outcome) = door
        val hello = assertIs<Door.Outcome.Admitted>(outcome).hello
        assertEquals("Bob", hello.name, "the name as the table shows it")
        assertEquals(deck, hello.deck, "the deck the guest brought")
        Door.seat(link, "Bob")
        val host = SeatHost(seat, link) { events += it }.also { closing += it }.start()
        waitFor("seated") { guest.seating.value == Seating.Seated("Bob") }
        waitFor("the guest's own stops") { "stops own=MAIN1;opponent=" in seat.calls }

        seat.board.value = board(1, line(1), line(2))
        waitFor("the board") { guest.board.value == board(1, line(1), line(2)) }
        seat.board.value = board(2, line(1), line(2), line(3))
        val prompt = ConfirmPrompt(7, "Pay 2 life?", "Yes", "No")
        seat.prompt.value = prompt
        waitFor("the next board and its prompt") { guest.board.value == board(2, line(1), line(2), line(3)) && guest.prompt.value == prompt }
        seat.warning.value = "auto-answered"
        seat.yieldStatus.value = "yielding"
        waitFor("the warning and the yield") { guest.warning.value == "auto-answered" && guest.yieldStatus.value == "yielding" }

        guest.answer(7, SeatAction.Confirm(true))
        guest.command(SeatCommand.PASS)
        waitFor("the answer and the command") { "answer #7 Confirm(yes=true)" in seat.calls && "command PASS" in seat.calls }
        guest.concede()
        waitFor("the concession") { GuestEvent.Conceded in events }

        host.end("Alice won the match")
        waitFor("the table closing") { guest.seating.value == Seating.Ended("Alice won the match") }
        assertEquals(null, guest.prompt.value, "nothing left to answer")
        assertTrue(events.none { it is GuestEvent.Lost }, "the host closing is no lost guest: $events")
    }

    @Test
    fun `a guest who leaves is gone, and one whose link drops is lost`() {
        val events = ConcurrentLinkedQueue<GuestEvent>()
        val (seat, guest, door) = knock()
        Door.seat(door.first, "Bob")
        SeatHost(seat, door.first) { events += it }.also { closing += it }.start()
        waitFor("seated") { guest.seating.value is Seating.Seated }
        guest.leave()
        waitFor("the guest left") { GuestEvent.Left in events }
        Thread.sleep(200)
        assertTrue(events.none { it is GuestEvent.Lost }, "a goodbye is no lost link: $events")

        val dropped = ConcurrentLinkedQueue<GuestEvent>()
        val (seat2, guest2, door2) = knock()
        Door.seat(door2.first, "Bob")
        SeatHost(seat2, door2.first) { dropped += it }.also { closing += it }.start()
        waitFor("seated") { guest2.seating.value is Seating.Seated }
        guest2.close() // the guest's app goes without a word
        waitFor("the link lost") { dropped.any { it is GuestEvent.Lost } }
    }

    @Test
    fun `turned away at the door, the guest is told why`() {
        val (_, guest, door) = knock(judge = { "This table plays Duel Commander, and Green is constructed." })
        assertEquals(Door.Outcome.Refused("This table plays Duel Commander, and Green is constructed."), door.second)
        waitFor("refused") { guest.seating.value is Seating.Refused }
        assertEquals("This table plays Duel Commander, and Green is constructed.", (guest.seating.value as Seating.Refused).reason)
    }

    @Test
    fun `a host of another protocol is refused by the guest, and a guest of another by the host`() {
        // A host that speaks protocol 0: the guest says so and never sends its deck.
        val listener = TcpLink.listenLocal().also { closing += it }
        val guest = RemoteSeat(TcpLink.connectLocal(listener.port), "Bob", deck, "test").also { closing += it }.start()
        val oldHost = listener.accept(5_000).also { closing += it }
        oldHost.send(Wire.encode(HostMessage.Hello(PROTOCOL_VERSION - 1, "old", "Alice")))
        waitFor("refused") { guest.seating.value is Seating.Refused }
        assertTrue("the host's app is older" in (guest.seating.value as Seating.Refused).reason)
        assertEquals(null, oldHost.receive(), "no deck was sent: the guest hung up")

        // A guest that speaks protocol 0: the door tells it.
        val listener2 = TcpLink.listenLocal().also { closing += it }
        val oldGuest = TcpLink.connectLocal(listener2.port).also { closing += it }
        var admitted: Door.Outcome? = null
        val door = thread { admitted = Door.admit(listener2.accept(5_000).also { closing += it }, HostMessage.Hello(PROTOCOL_VERSION, "test", "Alice")) { null } }
        assertIs<HostMessage.Hello>(Wire.host(oldGuest.receive()!!))
        oldGuest.send(Wire.encode(GuestMessage.Hello(PROTOCOL_VERSION - 1, "old", "Bob", deck)))
        val answer = Wire.host(oldGuest.receive()!!)
        door.join()
        assertIs<Door.Outcome.Refused>(admitted)
        assertTrue("your app is older" in (answer as HostMessage.Refused).reason)
    }

    @Test
    fun `a line past the limit closes the link instead of filling memory`() {
        val listener = TcpLink.listenLocal().also { closing += it }
        val sender = TcpLink.connectLocal(listener.port).also { closing += it }
        val receiver = listener.accept(5_000).also { closing += it }
        thread { sender.send("x".repeat(TcpLink.MAX_LINE + 10)) }
        assertEquals(null, receiver.receive())
    }
}
