package mtgoracle.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import mtgoracle.core.deck.PlayDeck
import mtgoracle.core.limited.OpenedPool
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

/** The guest app's part at a sealed table: the packs it opens, and its own pool once opened (ADR 0003). */
interface SealedSeat {
    /** The digest of the packs this app opens for [table]'s set from a fixed seed, or null when it opens none of it. */
    fun packsDigest(table: LimitedTable): String?
    /** [table]'s packs, opened from [seed]. */
    fun open(table: LimitedTable, seed: Long): OpenedPool
    /** This side's own pool, opened: the deck to build is made from it. */
    fun poolOpened(table: LimitedTable, pool: OpenedPool)
}

/**
 * Where the guest stands at a sealed table: its pool opened, the host ready,
 * its deck sent and what the host said of it, and, after the match, what
 * came of checking the host's deck against the host's pool.
 */
data class SealedProgress(
    val table: LimitedTable,
    val pool: OpenedPool? = null,
    val hostReady: Boolean = false,
    val sent: PlayDeck? = null,
    val accepted: Boolean = false,
    val refusal: String? = null,
    val hostCheck: String? = null,
)

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
    /** The deck brought to a constructed table; none for a sealed one, where it is built at the table. */
    private val deck: PlayDeck?,
    private val app: String,
    private val initialStops: PhaseStops = PhaseStops.DEFAULT,
    /** The guest's own playmat, sent once seated; null to show none. */
    private val mat: MatPicture? = null,
    /** This app's part at a sealed table; null takes constructed tables only. */
    private val sealed: SealedSeat? = null,
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

    private val matchFlow = MutableStateFlow<HostMessage.Match?>(null)
    private val outcomeFlow = MutableStateFlow<GameOutcome?>(null)
    private val theirMatFlow = MutableStateFlow<MatPicture?>(null)
    /** The match the host set (best of one, three or five) and the host's deck; null until seated. */
    val match: StateFlow<HostMessage.Match?> get() = matchFlow
    /** The game just played, between games; null while one is on. */
    val outcome: StateFlow<GameOutcome?> get() = outcomeFlow
    /** The host's playmat, as pixels: whether it is shown is the guest's choice. */
    val theirMat: StateFlow<MatPicture?> get() = theirMatFlow

    private val log = LogReceiver()
    private var lastBoard: BoardState? = null

    private val keys = SealedKeys()
    private val sealedFlow = MutableStateFlow<SealedProgress?>(null)
    /** A sealed table's steps as they come; null at a constructed one. */
    val sealedProgress: StateFlow<SealedProgress?> get() = sealedFlow
    @Volatile private var hostEnvelope: String? = null
    /** The host's name, as its hello said it (cleaned as any name a table shows); null before it. */
    @Volatile var host: String? = null
        private set
    @Volatile private var hostDigest: String? = null

    /**
     * The guest's deck for a sealed table, once the host is ready: checked here
     * first against the guest's own pool, then sent with the secret it was
     * opened from. Null when it was sent, else why it wasn't.
     */
    fun sendDeck(deck: PlayDeck): String? {
        val now = sealedFlow.value ?: return "This is no sealed table."
        val pool = now.pool ?: return "Your pool is still being opened."
        if (!now.hostReady) return "The host isn't ready yet: send your deck once they are."
        if (now.accepted) return "Your deck is already in."
        mtgoracle.core.limited.Sealed.judge(deck, pool)?.let { return it }
        sealedFlow.value = now.copy(sent = deck, refusal = null)
        send(GuestMessage.Deck(deck, keys.secret))
        return null
    }

    fun start(): RemoteSeat {
        // A host that accepted the connection must say hello soon; seated, the pings keep the link from going quiet.
        link.bound(TcpLink.MAX_LINE, Wire.SILENCE_MILLIS / 3)
        thread(name = "remote-seat-reader", isDaemon = true) { read() }
        thread(name = "remote-seat-ping", isDaemon = true) {
            while (seatingFlow.value.let { it is Seating.Knocking || it is Seating.Seated }) {
                Thread.sleep(Wire.PING_MILLIS)
                if (seatingFlow.value is Seating.Seated) send(GuestMessage.Ping)
            }
        }
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

    /** The answer to the host's hello: the deck for a constructed table, the packs' digest for a sealed one. Null, or why not. */
    private fun hello(table: LimitedTable?): String? {
        if (table == null) {
            val deck = deck ?: return "This table plays constructed: choose your deck in the lobby's constructed tab, then join."
            send(GuestMessage.Hello(PROTOCOL_VERSION, app, name, deck))
            return null
        }
        val sealed = sealed ?: return "This table plays sealed (${table.set.name}): join from the lobby's limited tab."
        val digest = sealed.packsDigest(table) ?: return "Your app opens no packs of ${table.set.name}."
        sealedFlow.value = SealedProgress(table)
        send(GuestMessage.Hello(PROTOCOL_VERSION, app, name, deck = null, packsDigest = digest))
        return null
    }

    /** What came of checking the host's revealed deck: its secret against its envelope, the deck against the digest it was ready with, then against the host's pool. */
    private fun checkHost(now: SealedProgress, reveal: HostMessage.Reveal): String {
        val envelope = hostEnvelope ?: return "The host revealed a secret it never sealed."
        if (!SealedKeys.opens(envelope, reveal.secret)) return "The host's secret isn't the one its envelope sealed: its pool can't be checked."
        if (SealedKeys.deckDigest(reveal.deck) != hostDigest) return "The host played another deck than the one it was ready with."
        val pool = sealed!!.open(now.table, SealedKeys.seed(reveal.secret, keys.nonce)!!)
        return mtgoracle.core.limited.Sealed.judge(reveal.deck, pool)?.let { "The host's deck doesn't fit its pool: $it" }
            ?: "The host's deck was built from its own pool: checked."
    }

    /** The host broke the sealed table's order: the link closes. */
    private fun lost(reason: String) { settle(Seating.Lost(reason)) }

    private fun read() {
        while (true) {
            val line = link.receive() ?: break
            val message = try { Wire.host(line) } catch (e: WireError) { settle(Seating.Lost("the host sent what this app can't read (${e.message})")); return }
            when (message) {
                is HostMessage.Hello -> {
                    host = Handshake.cleanName(message.host)
                    val refusal = Handshake.refusal(message, app) ?: hello(message.table)
                    if (refusal != null) { settle(Seating.Refused(refusal)); return }
                }
                is HostMessage.Accepted -> {
                    link.bound(TcpLink.MAX_LINE, Wire.SILENCE_MILLIS)
                    seatingFlow.value = Seating.Seated(message.name)
                    send(GuestMessage.SetStops(stopsFlow.value))
                    mat?.let { send(GuestMessage.Mat(it)) }
                    if (sealedFlow.value != null) send(GuestMessage.Envelope(keys.envelope))
                }
                is HostMessage.Envelope -> {
                    if (sealedFlow.value == null || hostEnvelope != null || !SealedKeys.isEnvelope(message.hash)) return lost("the host's envelope came twice, or was no envelope")
                    hostEnvelope = message.hash
                    send(GuestMessage.Nonce(keys.nonce))
                }
                is HostMessage.Nonce -> {
                    // Their number counts only after their envelope: before it, they could have chosen their secret to suit ours.
                    val now = sealedFlow.value
                    if (now == null || hostEnvelope == null || now.pool != null || !SealedKeys.isNonce(message.value)) return lost("the host's number came out of turn, or was no number")
                    val pool = sealed!!.open(now.table, SealedKeys.seed(keys.secret, message.value)!!)
                    // The app makes its deck of the pool first: whoever watches the progress then finds the deck there.
                    sealed.poolOpened(now.table, pool)
                    sealedFlow.value = now.copy(pool = pool)
                }
                is HostMessage.Ready -> {
                    val now = sealedFlow.value ?: return lost("the host is ready at a table that is no sealed one")
                    hostDigest = message.deckDigest
                    sealedFlow.value = now.copy(hostReady = true)
                }
                is HostMessage.Verdict -> sealedFlow.value?.let { now ->
                    sealedFlow.value = now.copy(accepted = message.refusal == null, refusal = message.refusal?.let { it.filterNot(Char::isISOControl).take(400) })
                }
                is HostMessage.Reveal -> sealedFlow.value?.let { now -> sealedFlow.value = now.copy(hostCheck = checkHost(now, message)) }
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
                is HostMessage.Match -> matchFlow.value = message
                is HostMessage.Result -> outcomeFlow.value = message.outcome
                is HostMessage.Mat -> theirMatFlow.value = message.mat
                HostMessage.Ping -> Unit
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
