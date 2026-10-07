package mtgoracle.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import mtgoracle.core.deck.AiCopy
import mtgoracle.core.deck.Deck
import mtgoracle.core.play.MatchResult
import mtgoracle.core.play.Winner
import mtgoracle.forge.ForgeMatch
import mtgoracle.forge.Log
import mtgoracle.forge.RunningMatch
import mtgoracle.net.Door
import mtgoracle.net.GameOutcome
import mtgoracle.net.GuestEvent
import mtgoracle.net.Handshake
import mtgoracle.net.HostMessage
import mtgoracle.net.Invite
import mtgoracle.net.InviteError
import mtgoracle.net.Opening
import mtgoracle.net.PROTOCOL_VERSION
import mtgoracle.net.RemoteSeat
import mtgoracle.net.Room
import mtgoracle.net.SeatHost
import mtgoracle.net.Seating
import mtgoracle.ui.board.Playmat
import mtgoracle.ui.library.LobbyNetwork
import mtgoracle.ui.library.NetAction
import mtgoracle.ui.library.NetState
import mtgoracle.ui.lookup.Ask
import java.time.Instant
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Network play from the lobby: hosting a room (the router opens a port, the
 * invite goes to the clipboard, the friend let in starts the match through
 * [play], so it is shown and recorded as any match) and joining one (the
 * invite from the clipboard, the board over a [RemoteSeat], each game
 * recorded on this side too). [openRoom] is UPnP's public room in the app,
 * a loopback room in the tests.
 */
class NetPlay(
    private val settings: Settings,
    private val play: PlayControl,
    private val sessions: () -> Sessions,
    private val mats: Playmats,
    private val deckById: (Int) -> Deck?,
    private val readClipboard: () -> String?,
    private val writeClipboard: (String) -> Unit,
    private val show: (Screen) -> Unit,
    private val say: (String?) -> Unit,
    private val openRoom: () -> Opening = { Room.public() },
) {
    private val version = System.getProperty("mtgoracle.version") ?: "dev build"

    var state by mutableStateOf<NetState>(NetState.Idle)
        private set
    /** A question over the lobby: your name, the first time you host or join. */
    var ask by mutableStateOf<Ask?>(null)
    /** Bumped when a setting the lobby shows changes. */
    private var changed by mutableStateOf(0)

    /** The host's side of the table on now: the room (and its port), the guest's seat sent down the link, the match. */
    private class HostTable(val room: Room, val host: SeatHost, val match: RunningMatch, val scope: CoroutineScope)
    @Volatile private var hosting: HostTable? = null
    @Volatile private var openRoomNow: Room? = null

    /** The guest's side of the table on now. */
    var guest by mutableStateOf<RemoteSeat?>(null)
        private set
    private var guestScope: CoroutineScope? = null

    /** The other side's playmat as the board draws it, when you show it: theirs, from pixels. */
    var theirMat by mutableStateOf<Playmat?>(null)
        private set
    /** Every game of the guest's match, as the host's outcomes told it: the between-games panel. */
    var guestGames by mutableStateOf<List<MatchResult>>(emptyList())
        private set

    fun lobby(): LobbyNetwork {
        changed // read: a toggle or a rename is seen
        return LobbyNetwork(settings.playerName, settings.shareMat, settings.showTheirMat, state)
    }

    fun act(action: NetAction) {
        when (action) {
            NetAction.Rename -> rename {}
            NetAction.Host -> named { host() }
            NetAction.Join -> named { join() }
            NetAction.CloseRoom -> closeRoom()
            NetAction.CopyInvite -> (state as? NetState.Hosting)?.let { writeClipboard(it.invite); say("the invite is on the clipboard: send it to your friend") }
            NetAction.ToggleShareMat -> { settings.shareMat = !settings.shareMat; changed++ }
            NetAction.ToggleShowTheirMat -> { settings.showTheirMat = !settings.showTheirMat; changed++; refreshTheirMat() }
        }
    }

    /** [then], once you have a name of your own at a table: asked for the first time. */
    private fun named(then: () -> Unit) = if (settings.playerNamed) then() else rename(then)

    private fun rename(then: () -> Unit) {
        ask = Ask.Text("your name at a network table (the other side sees it)", initial = settings.playerName.takeIf { settings.playerNamed }.orEmpty()) { name ->
            settings.playerName = Handshake.cleanName(name)
            changed++
            then()
        }
    }

    // --- hosting ------------------------------------------------------------

    private fun host() {
        val deck = play.lobbyMe ?: return say("choose your deck first")
        if (play.match != null || play.simulating) return say("a game or a simulation is on: finish it first")
        state = NetState.Opening
        thread(name = "net-host", isDaemon = true) {
            val room = when (val opening = openRoom()) {
                is Opening.NotReachable -> { state = NetState.Failed(opening.reason); return@thread }
                is Opening.Opened -> opening.room
            }
            openRoomNow = room
            val invite = room.invite.code
            writeClipboard(invite)
            state = NetState.Hosting(invite, strangers = 0)
            say("the room is open and its invite is on the clipboard: send it to your friend")
            val me = AiCopy.asBuilt(deck)
            var strangers = 0
            val admitted = room.awaitGuest(HostMessage.Hello(PROTOCOL_VERSION, version, settings.playerName), judge = { sessions().judgeGuest(me, it.deck) },
                onKnock = { knock ->
                    val now = state as? NetState.Hosting ?: return@awaitGuest
                    state = when (knock) {
                        is Door.Outcome.Stranger -> now.copy(strangers = ++strangers)
                        is Door.Outcome.Refused -> now.copy(lastRefused = knock.reason)
                        is Door.Outcome.Admitted -> now
                    }
                })
            openRoomNow = null
            if (admitted == null) {
                room.close()
                state = room.closedBecause?.let { NetState.Failed(it) } ?: NetState.Idle
                return@thread
            }
            val guestName = ForgeMatch.tableName(settings.playerName, admitted.hello.name)
            val match = play.startNetwork(admitted.hello.deck, settings.playerName to guestName)
            if (match == null) {
                admitted.link.close(); room.close()
                state = NetState.Failed("a game or a simulation started meanwhile: open the room again")
                return@thread
            }
            Door.seat(admitted.link, guestName)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val host = SeatHost(match.guest!!, admitted.link) { event ->
                when (event) {
                    GuestEvent.Conceded -> match.concedeGuest()
                    GuestEvent.Left -> { say("$guestName left the table"); match.breakOff("$guestName left") }
                    is GuestEvent.Lost -> { say(event.reason); match.breakOff(event.reason) }
                }
            }.start()
            host.match(match.spec.format, match.spec.seat.name)
            if (settings.shareMat) host.mat(mats.picture(mats.mine))
            hosting = HostTable(room, host, match, scope)
            state = NetState.Idle
            say("$guestName sat down with ${admitted.hello.deck.name}")
            scope.launch { host.guestMat.collect { refreshTheirMat() } }
            scope.launch {
                match.result.drop(1).collect { result ->
                    host.result(result?.let { r ->
                        GameOutcome(flip(r.winner), r.gameNo, wins = r.losses, losses = r.wins, matchOver = r.matchOver, summary = r.summary, turns = r.turns)
                    })
                    if (result?.matchOver == true) endHosting("the match is over: ${result.summary}")
                }
            }
        }
    }

    private fun flip(winner: Winner) = when (winner) { Winner.ME -> Winner.OPPONENT; Winner.OPPONENT -> Winner.ME; Winner.DRAW -> Winner.DRAW }

    /** The host's table closes: the guest is told why, the port on the router closes. */
    private fun endHosting(reason: String) {
        val table = hosting ?: return
        hosting = null
        table.host.end(reason)
        table.room.close()
        table.scope.cancel()
        theirMat = null
    }

    private fun closeRoom() {
        openRoomNow?.close()
        openRoomNow = null
        state = NetState.Idle
    }

    // --- joining ------------------------------------------------------------

    private fun join() {
        val deck = play.lobbyMe ?: return say("choose your deck first")
        if (play.match != null || play.simulating) return say("a game or a simulation is on: finish it first")
        val invite = try {
            Invite.parse(readClipboard().orEmpty())
        } catch (e: InviteError) {
            state = NetState.Failed("${e.message} Copy the invite your friend sent, then Join.")
            return
        }
        state = NetState.Joining
        thread(name = "net-join", isDaemon = true) {
            val link = try { invite.join() } catch (e: Exception) {
                state = NetState.Failed("Your friend's room didn't answer (${e.message}). Is it still open, and did their router open the port?")
                return@thread
            }
            val mine = AiCopy.asBuilt(deck)
            val seat = RemoteSeat(link, settings.playerName, mine, version, settings.stops, mat = if (settings.shareMat) mats.picture(mats.mine) else null).start()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            guestScope = scope
            guestGames = emptyList()
            val matchId = UUID.randomUUID().toString()
            var gameStarted = Instant.now()
            scope.launch {
                seat.seating.collect { seating ->
                    when (seating) {
                        Seating.Knocking -> Unit
                        is Seating.Seated -> { guest = seat; state = NetState.Idle; show(Screen.Guest); say("seated as ${seating.name}") }
                        is Seating.Refused -> { leaveTable(); state = NetState.Failed("Your friend's room turned you away: ${seating.reason}") }
                        is Seating.Lost -> { if (guest == null) state = NetState.Failed(seating.reason) else say(seating.reason) }
                        is Seating.Ended -> say("the table closed: ${seating.reason}")
                    }
                }
            }
            scope.launch { seat.theirMat.collect { refreshTheirMat() } }
            scope.launch {
                seat.outcome.collect { outcome ->
                    if (outcome == null) { gameStarted = Instant.now(); return@collect }
                    guestGames = guestGames + MatchResult(outcome.winner, outcome.turns, 0, outcome.summary, gameNo = outcome.gameNo,
                        wins = outcome.wins, losses = outcome.losses, matchOver = outcome.matchOver)
                    val host = seat.match.value
                    try {
                        sessions().recordAsGuest(mine, hostName(seat), host?.deck ?: "the host's deck", outcome, host?.format, matchId, gameStarted)
                    } catch (e: Exception) {
                        Log.error("could not record the network game", e)
                        say("game ${outcome.gameNo} was NOT recorded: ${e.message}")
                    }
                }
            }
        }
    }

    /** The host's name as the guest's board has it: the other player at the table. */
    private fun hostName(seat: RemoteSeat): String = seat.board.value?.players?.firstOrNull { !it.isSeat }?.name ?: "the host"

    /** Concedes the game on, as the guest. */
    fun concedeAsGuest() { guest?.concede() }

    /** The guest leaves the table, conceding the game on if one is: back to the lobby. */
    fun leaveTable() {
        guest?.let { seat ->
            if (seat.seating.value is Seating.Seated && seat.board.value?.gameOver == false) seat.concede()
            seat.leave()
        }
        guestScope?.cancel()
        guestScope = null
        guest = null
        theirMat = null
        guestGames = emptyList()
        show(Screen.Lobby)
    }

    /** The other side's mat, built from its pixels once, when you show it. */
    private fun refreshTheirMat() {
        val picture = if (!settings.showTheirMat) null else hosting?.host?.guestMat?.value ?: guest?.theirMat?.value
        theirMat = picture?.toPlaymat()
    }

    /** The app is closing: a guest leaves the table; a host's table and room close, and the router's port with them. */
    fun close() {
        if (guest != null) leaveTable()
        endHosting("the host closed the app")
        closeRoom()
    }

    /** Whether a network match is on as the host: the board shows the guest's mat, not the AI's. */
    fun hostingMatch(match: RunningMatch?): Boolean = match != null && hosting?.match === match
}
