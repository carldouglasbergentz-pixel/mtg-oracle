package mtgoracle.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import mtgoracle.core.deck.AiCopy
import mtgoracle.core.deck.Deck
import mtgoracle.core.deck.PlayDeck
import mtgoracle.core.limited.LimitedSet
import mtgoracle.core.limited.OpenedPool
import mtgoracle.core.limited.Sealed
import mtgoracle.forge.ForgeLimited
import mtgoracle.net.LimitedTable
import mtgoracle.net.SealedEvent
import mtgoracle.net.SealedHost
import mtgoracle.net.SealedProgress
import mtgoracle.net.SealedSeat
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
    /** The limited tab's chosen set when the lobby shows that tab: a room opened there is a sealed table of it. */
    private val sealedSet: () -> LimitedSet? = { null },
    /** This side's pool at a sealed table, made a deck (and opened to build) against the named friend: its id. */
    private val tablePool: (OpenedPool, String) -> Int = { _, _ -> error("no sealed tables here") },
    private val limited: () -> LimitedControl? = { null },
) {
    private val version = System.getProperty("mtgoracle.version") ?: "dev build"

    var state by mutableStateOf<NetState>(NetState.Idle)
        private set
    /** A question over the lobby: your name, the first time you host or join. */
    var ask by mutableStateOf<Ask?>(null)
    /** Bumped when a setting the lobby shows changes. */
    private var changed by mutableStateOf(0)

    /**
     * The host's side of the table on now: the room (and its port), the guest's seat sent down the link, the match,
     * and at a sealed table the host's secret and deck, revealed to the guest when it ends.
     */
    private class HostTable(val room: Room, val host: SeatHost, val match: RunningMatch, val scope: CoroutineScope, val reveal: Pair<String, PlayDeck>? = null)

    /** A sealed table being built, as the host: its steps, and the link to close when the host leaves it. */
    @Volatile private var building: SealedHost? = null
    @Volatile private var buildingLink: mtgoracle.net.Link? = null
    /** The deck this side builds at a sealed table, once its pool is opened. */
    @Volatile private var tableDeckId: Int? = null
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
            NetAction.CancelJoin -> cancelJoin()
            NetAction.CopyInvite -> (state as? NetState.Hosting)?.let { writeClipboard(it.invite); say("the invite is on the clipboard: send it to your friend") }
            NetAction.Ready -> ready()
            NetAction.LeaveTable -> if (guest != null) leaveTable(concede = false) else leaveBuilding()
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

    /** Whether network play is quiet: no room open or opening, no table on, no knock going. A local game waits for it. */
    val idle: Boolean get() = (state is NetState.Idle || state is NetState.Failed) && hosting == null && guest == null && openRoomNow == null && !joining

    /** Whether network play holds this app: what the daily sync waits for, as it waits for a game. */
    val busy: Boolean get() = !idle

    /** Set when the host closes the room (or the app) while it is opening or a guest is coming in: the thread gives up there. */
    @Volatile private var cancelled = false

    private fun host() {
        val set = sealedSet()
        val deck = if (set != null) null else play.lobbyMe ?: return say("choose your deck first")
        if (play.match != null || play.simulating) return say("a game or a simulation is on: finish it first")
        if (!idle) return say("a room or a table is already on")
        cancelled = false
        state = NetState.Opening
        thread(name = "net-host", isDaemon = true) {
            var room: Room? = null
            var link: mtgoracle.net.Link? = null
            try {
                // What this app's Forge opens for the set: a guest whose app opens other packs is turned away at the door.
                val table = set?.let { LimitedTable(it, Sealed.PACKS, ForgeLimited.packsDigest(it, Sealed.PACKS)) }
                room = when (val opening = openRoom()) {
                    is Opening.NotReachable -> { state = NetState.Failed(opening.reason); return@thread }
                    is Opening.Opened -> opening.room
                }
                if (cancelled) return@thread
                openRoomNow = room
                val invite = room.invite.code
                writeClipboard(invite)
                state = NetState.Hosting(invite, strangers = 0)
                say("the room is open and its invite is on the clipboard: send it to your friend")
                val me = deck?.let(AiCopy::asBuilt)
                var strangers = 0
                // A constructed table judges the guest's deck (the handshake refused a hello with none); a sealed one builds it at the table.
                val admitted = room.awaitGuest(HostMessage.Hello(PROTOCOL_VERSION, version, settings.playerName, table), judge = { hello -> me?.let { sessions().judgeGuest(it, hello.deck!!) } },
                    onKnock = { knock ->
                        val now = state as? NetState.Hosting ?: return@awaitGuest
                        state = when (knock) {
                            is Door.Outcome.Stranger -> now.copy(strangers = ++strangers)
                            is Door.Outcome.Refused -> now.copy(lastRefused = knock.reason)
                            is Door.Outcome.Admitted -> now
                        }
                    })
                openRoomNow = null
                if (admitted == null || cancelled) {
                    state = room.closedBecause?.let { NetState.Failed(it) } ?: NetState.Idle
                    return@thread
                }
                link = admitted.link
                val guestName = ForgeMatch.tableName(settings.playerName, admitted.hello.name)
                var sealed: SealedHost? = null
                var guestDeck = admitted.hello.deck
                if (table != null) {
                    // A sealed table: seated now, each builds a deck from a pool of its own, and the match begins when both decks are in.
                    Door.seat(admitted.link, guestName)
                    val built = buildSealed(table, admitted.link, guestName) ?: return@thread
                    sealed = built.first
                    guestDeck = built.second
                }
                val match = play.startNetwork(guestDeck!!, settings.playerName to guestName, hostDeck = sealed?.deck)
                if (match == null) {
                    Door.turnAway(admitted.link, "The host started another game meanwhile: ask them to open the room again.")
                    state = NetState.Failed("a game or a simulation started meanwhile: open the room again")
                    return@thread
                }
                if (table == null) Door.seat(admitted.link, guestName)
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val host = SeatHost(match.guest!!, admitted.link) { event ->
                    when (event) {
                        GuestEvent.Conceded -> match.concedeGuest()
                        // Leaving is the guest's choice: their game on is lost, and no game follows.
                        GuestEvent.Left -> { say("$guestName left the table"); match.guestLeaves() }
                        // Their link went: nobody's choice, so nobody wins.
                        is GuestEvent.Lost -> { say(event.reason); match.breakOff(event.reason) }
                    }
                }
                hosting = HostTable(room, host, match, scope, reveal = sealed?.let { s -> s.deck?.let { s.secret to it } })
                // Before the seat starts: a guest gone at once ends the match, and that result must not go by unheard.
                scope.launch { host.guestMat.collect { refreshTheirMat() } }
                scope.launch {
                    match.result.collect { result ->
                        host.result(result?.let { r ->
                            GameOutcome(flip(r.winner), r.gameNo, wins = r.losses, losses = r.wins, matchOver = r.matchOver, summary = r.summary,
                                turns = r.turns, unfinished = r.brokenOff)
                        })
                        if (result?.matchOver == true) endHosting("the match is over: ${result.summary}")
                    }
                }
                host.start()
                host.match(match.spec.format, match.spec.seat.name)
                if (settings.shareMat) host.mat(mats.picture(mats.mine))
                state = NetState.Idle
                say("$guestName sat down with ${guestDeck.name}")
            } catch (e: Exception) {
                Log.error("hosting failed", e)
                state = NetState.Failed("Hosting failed: ${e.message}. The room is closed.")
            } finally {
                // Unless a table is on (it closes the room when it ends), nothing of this try stays open: the port least of all.
                if (hosting?.room !== room) { link?.close(); room?.close(); openRoomNow = null }
            }
        }
    }

    private fun flip(winner: Winner) = when (winner) { Winner.ME -> Winner.OPPONENT; Winner.OPPONENT -> Winner.ME; Winner.DRAW -> Winner.DRAW }

    /**
     * The host's table closes: the guest is told why, the port on the router closes. The router is
     * asked off the window's thread ([wait] only at quitting, when nothing else may close it).
     */
    private fun endHosting(reason: String, wait: Boolean = false) {
        val table = hosting ?: return
        hosting = null
        table.reveal?.let { (secret, deck) -> table.host.reveal(secret, deck) }
        table.host.end(reason)
        table.scope.cancel()
        theirMat = null
        if (wait) table.room.close() else thread(name = "net-close-room", isDaemon = true) { table.room.close() }
    }

    private fun closeRoom(wait: Boolean = false) {
        cancelled = true
        val room = openRoomNow
        openRoomNow = null
        if (room != null) { if (wait) room.close() else thread(name = "net-close-room", isDaemon = true) { room.close() } }
        if (state is NetState.Opening || state is NetState.Hosting) state = NetState.Idle
    }

    // --- joining ------------------------------------------------------------

    @Volatile private var joining = false
    @Volatile private var knocking: RemoteSeat? = null

    private fun join() {
        val sealedTable = play.limited
        val deck = if (sealedTable) null else play.lobbyMe ?: return say("choose your deck first")
        if (play.match != null || play.simulating) return say("a game or a simulation is on: finish it first")
        if (!idle) return say("a room or a table is already on")
        val invite = try {
            Invite.parse(readClipboard().orEmpty())
        } catch (e: InviteError) {
            state = NetState.Failed("${e.message} Copy the invite your friend sent, then Join.")
            return
        }
        joining = true
        cancelled = false
        state = NetState.Joining
        thread(name = "net-join", isDaemon = true) {
            val link = try { invite.join() } catch (e: Exception) {
                joining = false
                state = NetState.Failed("Your friend's room didn't answer (${e.message}). Is it still open, and did their router open the port?")
                return@thread
            }
            if (cancelled) { link.close(); joining = false; return@thread }
            val mine = deck?.let(AiCopy::asBuilt)
            val seat = RemoteSeat(link, settings.playerName, mine, version, settings.stops, mat = if (settings.shareMat) mats.picture(mats.mine) else null,
                sealed = if (sealedTable) guestSealed() else null).start()
            knocking = seat
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            guestScope = scope
            guestGames = emptyList()
            val matchId = UUID.randomUUID().toString()
            var gameStarted = Instant.now()
            fun giveUp(reason: String?) {
                scope.cancel()
                if (guestScope === scope) guestScope = null
                knocking = null
                joining = false
                state = reason?.let { NetState.Failed(it) } ?: NetState.Idle
            }
            scope.launch {
                seat.seating.collect { seating ->
                    when (seating) {
                        Seating.Knocking -> Unit
                        is Seating.Seated -> {
                            knocking = null; joining = false; guest = seat
                            // At a sealed table the board waits for the match: the decks are built first, in the lobby and the workspace.
                            val table = seat.sealedProgress.value?.table
                            if (table == null) { state = NetState.Idle; show(Screen.Guest); say("seated as ${seating.name}") }
                            else {
                                // The set as this app names it, never the host's text.
                                val set = ForgeLimited.set(table.set.code)?.name ?: table.set.code.filter(Char::isLetterOrDigit).take(8)
                                state = NetState.Building(set, seat.host ?: "the host", deck = null, ready = false, note = "opening your packs")
                                say("seated at a sealed table of $set")
                            }
                        }
                        is Seating.Refused -> giveUp("Your friend's room turned you away: ${seating.reason}")
                        is Seating.Lost -> if (guest !== seat) giveUp(if (cancelled) null else seating.reason) else say(seating.reason)
                        // A sealed table's end says what came of checking the host's deck too: it came just before.
                        is Seating.Ended -> if (guest !== seat) giveUp(null)
                            else say("the table closed: ${fromHost(seating.reason)}" + (seat.sealedProgress.value?.hostCheck?.let { ". $it" } ?: ""))
                    }
                }
            }
            scope.launch { seat.theirMat.collect { refreshTheirMat() } }
            scope.launch { seat.match.collect { if (it != null && seat.sealedProgress.value != null && guest === seat) { state = NetState.Idle; show(Screen.Guest) } } }
            scope.launch { seat.sealedProgress.collect { progress -> progress?.let { guestProgress(it) } } }
            scope.launch {
                seat.outcome.collect { outcome ->
                    if (outcome == null) { gameStarted = Instant.now(); return@collect }
                    val host = seat.match.value
                    // Checked, never trusted: a game the match can hold, and each game once (a match ended between games
                    // sends its last game again, now as the match's end).
                    if (outcome.gameNo !in 1..(host?.format?.games ?: 1)) return@collect
                    val entry = MatchResult(outcome.winner, outcome.turns, 0, fromHost(outcome.summary), gameNo = outcome.gameNo,
                        wins = outcome.wins, losses = outcome.losses, matchOver = outcome.matchOver)
                    val known = guestGames.indexOfFirst { it.gameNo == outcome.gameNo }
                    if (known >= 0) { guestGames = guestGames.toMutableList().also { it[known] = entry }; return@collect }
                    guestGames = guestGames + entry
                    try {
                        sessions().recordAsGuest(mine ?: seat.sealedProgress.value?.sent ?: return@collect, hostName(seat), fromHost(host?.deck ?: "the host's deck"), outcome, host?.format, matchId, gameStarted)
                    } catch (e: Exception) {
                        Log.error("could not record the network game", e)
                        say("game ${outcome.gameNo} was NOT recorded: ${e.message}")
                    }
                }
            }
        }
    }

    // --- a sealed table -------------------------------------------------------

    /**
     * The host's side of a sealed table, from the guest's seating to both decks in: the host's pool opened and made a
     * deck to build, the guest's deck judged against the guest's pool. The host's table and the guest's deck, or null
     * when the guest left or the link went (the lobby says which).
     */
    private fun buildSealed(table: LimitedTable, link: mtgoracle.net.Link, friend: String): Pair<SealedHost, PlayDeck>? {
        val sealed = SealedHost(link, table, open = { seed -> ForgeLimited.open(table.set, table.packs, seed) }) { event ->
            when (event) {
                is SealedEvent.PoolOpened -> {
                    tableDeckId = tablePool(event.pool, friend)
                    state = NetState.Building(table.set.name, friend, deck = deckById(tableDeckId!!)?.name, ready = false, note = "build your deck from the pool, then Ready")
                }
                is SealedEvent.Refused -> (state as? NetState.Building)?.let { state = it.copy(note = "$friend's deck was turned away: ${event.reason}") }
            }
        }
        building = sealed
        buildingLink = link
        state = NetState.Building(table.set.name, friend, deck = null, ready = false, note = "opening your packs")
        val outcome = sealed.run()
        building = null
        buildingLink = null
        return when (outcome) {
            is SealedHost.Outcome.Gone -> {
                state = NetState.Failed("The sealed table closed: ${outcome.reason}. Your pool is kept, in the Limited folder.")
                null
            }
            is SealedHost.Outcome.Seated -> {
                tableDeckId?.let { id -> runCatching { limited()?.addRival(id, outcome.pool, friend) }.onFailure { Log.error("could not keep $friend's pool", it) } }
                sealed to outcome.deck
            }
        }
    }

    /** The guest app's part at a sealed table: this app's packs, and its pool made a deck to build. */
    private fun guestSealed(): SealedSeat = object : SealedSeat {
        override fun packsDigest(table: LimitedTable): String? = ForgeLimited.set(table.set.code)?.let { ForgeLimited.packsDigest(it, table.packs) }
        override fun open(table: LimitedTable, seed: Long): OpenedPool = ForgeLimited.open(ForgeLimited.set(table.set.code)!!, table.packs, seed)
        override fun poolOpened(table: LimitedTable, pool: OpenedPool) { tableDeckId = tablePool(pool, (knocking ?: guest)?.host ?: "the host") }
    }

    /** The guest's sealed table as the lobby shows it: the pool, the host ready, what the host said of the deck; after the match, the host's check. */
    private fun guestProgress(progress: SealedProgress) {
        progress.hostCheck?.let { say(it); return }
        val now = state as? NetState.Building ?: return
        val deck = tableDeckId?.let { deckById(it)?.name }
        state = now.copy(deck = deck, ready = progress.sent != null && progress.refusal == null, note = when {
            progress.accepted -> "your deck is in: the match begins"
            progress.refusal != null -> "the host turned your deck away: ${progress.refusal}"
            progress.sent != null -> "your deck is sent: waiting for the host's word"
            progress.hostReady && progress.pool != null -> "the host is ready: build your deck from the pool, then Ready"
            progress.pool != null -> "build your deck from the pool; Ready once the host is"
            else -> "opening your packs"
        })
    }

    /** Ready, at a sealed table: this side's deck as built, checked against its pool, and sent (the guest's) or held to (the host's). */
    private fun ready() {
        val id = tableDeckId ?: return say("your pool is still being opened")
        val deck = limited()?.playDeck(id) ?: return say("your table's deck is gone")
        val host = building
        val seat = guest
        // Each returns why not, or null when done.
        val refusal = when {
            host != null -> host.ready(deck)
            seat != null -> seat.sendDeck(deck)
            else -> return say("no sealed table is on")
        }
        if (refusal != null) return say(refusal)
        (state as? NetState.Building)?.let { state = it.copy(ready = true, note = if (building != null) "ready: waiting for ${it.friend}'s deck" else "your deck is sent: waiting for the host's word") }
    }

    /** The host leaves a sealed table being built: the guest is told the table closed, and the pool stays. */
    private fun leaveBuilding() {
        val link = buildingLink ?: return
        link.send(mtgoracle.net.Wire.encode(HostMessage.End("the host left the table")))
        link.close()
    }

    /** Stops knocking on a friend's room. */
    private fun cancelJoin() {
        cancelled = true
        knocking?.close()
        if (state is NetState.Joining) state = NetState.Idle
        joining = false
    }

    /** The host's name as the guest's board has it: the other player at the table. */
    private fun hostName(seat: RemoteSeat): String = fromHost(seat.board.value?.players?.firstOrNull { !it.isSeat }?.name ?: "the host")

    /** Text the host chose, as it goes into this database: one line, cut to a sane length. */
    private fun fromHost(text: String): String = text.filterNot { it.isISOControl() }.take(80)

    /** Concedes the game on, as the guest. */
    fun concedeAsGuest() { guest?.concede() }

    /**
     * The guest leaves the table, back to the lobby. [concede]: as chosen, the game on is conceded first and
     * its loss recorded once the host says so (a few seconds at most); not after a crash, when the link just
     * closes and the host breaks the match off with no winner.
     */
    fun leaveTable(concede: Boolean = true) {
        val seat = guest ?: return show(Screen.Lobby)
        val scope = guestScope
        guest = null
        guestScope = null
        theirMat = null
        show(Screen.Lobby)
        thread(name = "net-leave", isDaemon = true) {
            if (!concede) seat.close()
            else {
                val on = seat.seating.value is Seating.Seated && seat.board.value?.gameOver == false
                if (on) {
                    val before = guestGames.size
                    seat.concede()
                    val deadline = System.currentTimeMillis() + 5_000
                    while (guestGames.size == before && System.currentTimeMillis() < deadline && seat.seating.value is Seating.Seated) Thread.sleep(50)
                }
                seat.leave()
            }
            scope?.cancel()
            guestGames = emptyList()
        }
    }

    /** The other side's mat, built from its pixels once, when you show it. */
    private fun refreshTheirMat() {
        val picture = if (!settings.showTheirMat) null else hosting?.host?.guestMat?.value ?: guest?.theirMat?.value
        theirMat = picture?.toPlaymat()
    }

    /** The app is closing: a guest leaves the table; a host's table and room close, and the router's port with them, before it exits. */
    fun close() {
        if (guest != null) leaveTable()
        cancelJoin()
        endHosting("the host closed the app", wait = true)
        closeRoom(wait = true)
    }

    /** The app broke at a table: a guest's link just closes (the host breaks off, no winner); a host's match is broken off. */
    fun breakOffAfterCrash(match: RunningMatch?) {
        if (guest != null) leaveTable(concede = false)
        if (match != null && hostingMatch(match)) match.breakOff("the host's app broke")
    }

    /** Whether a network match is on as the host: the board shows the guest's mat, not the AI's. */
    fun hostingMatch(match: RunningMatch?): Boolean = match != null && hosting?.match === match
}
