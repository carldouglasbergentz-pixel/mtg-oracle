package mtgoracle.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import mtgoracle.core.model.BoardState
import mtgoracle.core.model.GameSeat
import mtgoracle.core.model.PhaseStops
import mtgoracle.core.model.Prompt
import mtgoracle.core.play.MatchFormat
import kotlin.concurrent.thread

/** What the guest did that the host's match must act on. */
sealed interface GuestEvent {
    data object Conceded : GuestEvent
    data object Left : GuestEvent
    /** The link closed without a goodbye: the guest's app, or the connection, went. */
    data class Lost(val reason: String) : GuestEvent
}

/**
 * The host's end of a remote seat: [seat] (the guest's, already filtered for
 * the guest inside `forge`) sent down [link] as it changes, and the guest's
 * answers and commands put to it. A board goes before the prompt asking
 * about it, and its log as the lines the guest lacks ([LogSender]).
 */
class SeatHost(private val seat: GameSeat, private val link: Link, private val onGuest: (GuestEvent) -> Unit) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = LogSender()
    @Volatile private var closing = false
    private val guestMatFlow = MutableStateFlow<MatPicture?>(null)
    /** The guest's playmat, as pixels, once they send one: whether it is shown is the host's choice. */
    val guestMat: StateFlow<MatPicture?> get() = guestMatFlow

    /** The match the guest sat down to, against the host's [deck]. */
    fun match(format: MatchFormat, deck: String) = send(HostMessage.Match(format, deck))

    /** How the game just played ended, from the guest's side; null once the next has begun. */
    fun result(outcome: GameOutcome?) = send(HostMessage.Result(outcome))

    /** The host's playmat for the guest's table, or none. */
    fun mat(picture: MatPicture?) = send(HostMessage.Mat(picture))

    private data class View(val board: BoardState?, val prompt: Prompt?, val stops: PhaseStops, val yieldStatus: String?, val warning: String?)

    fun start(): SeatHost {
        scope.launch {
            var sent: View? = null
            combine(seat.board, seat.prompt, seat.stops, seat.yieldStatus, seat.warning, ::View).collect { now ->
                val last = sent
                if (last == null || now.board != last.board) {
                    now.board?.let { board -> log.next(board.log)?.let { send(it) } }
                    send(HostMessage.Board(now.board?.copy(log = emptyList())))
                }
                if (last == null || now.prompt != last.prompt) send(HostMessage.Ask(now.prompt))
                if (last == null || now.stops != last.stops) send(HostMessage.Stops(now.stops))
                if (last == null || now.yieldStatus != last.yieldStatus) send(HostMessage.YieldStatus(now.yieldStatus))
                if (last == null || now.warning != last.warning) send(HostMessage.Warning(now.warning))
                sent = now
            }
        }
        thread(name = "seat-host-reader", isDaemon = true) { read() }
        return this
    }

    private fun send(message: HostMessage) { link.send(Wire.encode(message)) }

    private fun read() {
        while (true) {
            val line = link.receive() ?: break
            val message = try { Wire.guest(line) } catch (e: WireError) { lost("the guest sent what this table can't read (${e.message})"); return }
            when (message) {
                is GuestMessage.Answer -> seat.answer(message.promptId, message.action)
                is GuestMessage.Command -> seat.command(message.command)
                is GuestMessage.SetStops -> seat.setStops(message.stops)
                GuestMessage.Concede -> onGuest(GuestEvent.Conceded)
                GuestMessage.Leave -> { closing = true; onGuest(GuestEvent.Left); close(); return }
                is GuestMessage.Hello -> Unit // said once, at the door
                is GuestMessage.Mat -> guestMatFlow.value = message.mat
            }
        }
        lost("the connection to the guest was lost")
    }

    private fun lost(reason: String) {
        if (closing) return
        closing = true
        onGuest(GuestEvent.Lost(reason))
        close()
    }

    /** The table closes: the guest is told why, and the link goes. */
    fun end(reason: String) {
        closing = true
        send(HostMessage.End(reason))
        close()
    }

    override fun close() {
        closing = true
        scope.cancel()
        link.close()
    }
}
