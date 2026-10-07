package mtgoracle.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import mtgoracle.core.deck.PlayDeck
import mtgoracle.core.model.BoardState
import mtgoracle.core.model.GameSeat
import mtgoracle.core.model.PhaseStops
import mtgoracle.core.model.Prompt
import mtgoracle.core.model.SeatAction
import mtgoracle.core.model.SeatCommand
import kotlin.concurrent.thread

/** Where a remote seat stands with its table. */
sealed interface Seating {
    data object Knocking : Seating
    data class Seated(val name: String) : Seating
    data class Refused(val reason: String) : Seating
    /** The table closed: the match is over, or the host left. */
    data class Ended(val reason: String) : Seating
    data class Lost(val reason: String) : Seating
}

/**
 * The guest's seat at a table hosted elsewhere: a [GameSeat] fed by the
 * host's messages, so the board draws it as it draws a seat at its own
 * Forge. It runs no engine and sees only what the host's `forge` filtered
 * for it. Its answers go back down the [link]; the host ignores one whose
 * prompt has moved on, as a local seat does (the seam's rule 3).
 */
class RemoteSeat(
    private val link: Link,
    private val name: String,
    private val deck: PlayDeck,
    private val app: String,
    private val initialStops: PhaseStops = PhaseStops.DEFAULT,
) : GameSeat, AutoCloseable {
    private val seatingFlow = MutableStateFlow<Seating>(Seating.Knocking)
    val seating: StateFlow<Seating> get() = seatingFlow

    private val boardFlow = MutableStateFlow<BoardState?>(null)
    private val promptFlow = MutableStateFlow<Prompt?>(null)
    private val stopsFlow = MutableStateFlow(initialStops)
    private val yieldFlow = MutableStateFlow<String?>(null)
    private val warningFlow = MutableStateFlow<String?>(null)
    private val noHands = MutableStateFlow(false)
    override val board: StateFlow<BoardState?> get() = boardFlow
    override val prompt: StateFlow<Prompt?> get() = promptFlow
    override val stops: StateFlow<PhaseStops> get() = stopsFlow
    override val yieldStatus: StateFlow<String?> get() = yieldFlow
    override val warning: StateFlow<String?> get() = warningFlow
    override val showAllHands: StateFlow<Boolean> get() = noHands

    private val log = LogReceiver()
    private var lastBoard: BoardState? = null

    fun start(): RemoteSeat {
        thread(name = "remote-seat-reader", isDaemon = true) { read() }
        return this
    }

    override fun answer(promptId: Long, action: SeatAction) { send(GuestMessage.Answer(promptId, action)) }
    override fun command(command: SeatCommand) { send(GuestMessage.Command(command)) }
    override fun setStops(stops: PhaseStops) {
        stopsFlow.value = stops
        send(GuestMessage.SetStops(stops))
    }

    /** Concedes the game being played; in a match, the next one can still follow. */
    fun concede() { send(GuestMessage.Concede) }

    /** Leaves the table for good. */
    fun leave() {
        send(GuestMessage.Leave)
        settle(Seating.Ended("you left the table"))
    }

    private fun send(message: GuestMessage) { link.send(Wire.encode(message)) }

    private fun read() {
        while (true) {
            val line = link.receive() ?: break
            val message = try { Wire.host(line) } catch (e: WireError) { settle(Seating.Lost("the host sent what this app can't read (${e.message})")); return }
            when (message) {
                is HostMessage.Hello -> {
                    val refusal = Handshake.refusal(message)
                    if (refusal != null) { settle(Seating.Refused(refusal)); return }
                    send(GuestMessage.Hello(PROTOCOL_VERSION, app, name, deck))
                }
                is HostMessage.Accepted -> {
                    seatingFlow.value = Seating.Seated(message.name)
                    send(GuestMessage.SetStops(stopsFlow.value))
                }
                is HostMessage.Refused -> { settle(Seating.Refused(message.reason)); return }
                is HostMessage.Log -> {
                    log.apply(message)
                    lastBoard?.let { boardFlow.value = log.join(it) }
                }
                is HostMessage.Board -> {
                    lastBoard = message.board
                    boardFlow.value = message.board?.let(log::join)
                }
                is HostMessage.Ask -> promptFlow.value = message.prompt
                is HostMessage.Stops -> stopsFlow.value = message.stops
                is HostMessage.YieldStatus -> yieldFlow.value = message.text
                is HostMessage.Warning -> warningFlow.value = message.text
                is HostMessage.End -> { settle(Seating.Ended(message.reason)); return }
            }
        }
        settle(Seating.Lost("the connection to the host was lost"))
    }

    /** The table is gone: nothing can be answered any more, and the link closes. */
    private fun settle(seating: Seating) {
        if (seatingFlow.value.let { it is Seating.Ended || it is Seating.Refused || it is Seating.Lost }) return
        seatingFlow.value = seating
        promptFlow.value = null
        link.close()
    }

    override fun close() = link.close()
}
