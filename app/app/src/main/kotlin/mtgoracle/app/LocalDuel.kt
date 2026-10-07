package mtgoracle.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import mtgoracle.core.deck.AiCopy
import mtgoracle.core.deck.PlayDeck
import mtgoracle.core.play.GameMode
import mtgoracle.data.Library
import mtgoracle.data.MtgDb
import mtgoracle.forge.ForgeMatch
import mtgoracle.forge.ForgeRuntime
import mtgoracle.forge.Log
import mtgoracle.forge.RunningMatch
import mtgoracle.net.Door
import mtgoracle.net.GuestEvent
import mtgoracle.net.HostMessage
import mtgoracle.net.Link
import mtgoracle.net.PROTOCOL_VERSION
import mtgoracle.net.RemoteSeat
import mtgoracle.net.SeatHost
import mtgoracle.net.Seating
import mtgoracle.net.TcpLink
import mtgoracle.ui.board.BoardScreen
import mtgoracle.ui.board.MatchControls
import mtgoracle.ui.kit.ArtImages
import mtgoracle.ui.kit.CardMode
import mtgoracle.ui.kit.LocalArt
import mtgoracle.ui.theme.HouseTheme
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.system.exitProcess

/**
 * Network play on one machine, before any network: a host's window and a
 * guest's, joined by a TCP link on the loopback address. The host's window
 * plays at Forge; the guest's draws a [RemoteSeat] fed by the link alone,
 * as it will be over the relay. Nothing is recorded (two people's games
 * aren't yet).
 *
 * Args: `<host deck>;<guest deck>` (gradlew :app:localDuel -Pargs="Jori En;Phelia Doggo")
 */
object LocalDuel {
    private const val HOST = "Host"

    fun run(paths: AppPaths, args: List<String>): Int {
        // -Pargs is split on spaces, and deck names have them: the two names are told apart by the semicolon.
        val names = args.joinToString(" ").split(';').map { it.trim() }.filter { it.isNotEmpty() }
        require(names.size == 2) { "two decks: -Pargs=\"<host deck>;<guest deck>\"" }
        Log.toFile(paths.appLog)
        val library = Library(MtgDb(paths.db))
        fun deck(name: String) = library.decks().firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { library.deck(it.id) }
            ?: error("no deck named '$name' (have: ${library.decks().joinToString { it.name }})")
        val hostDeck = AiCopy.asBuilt(deck(names[0]))
        val guestDeck = AiCopy.asBuilt(deck(names[1]))
        ForgeRuntime.initialise(paths.forge)
        val table = table(Sessions(null, paths.gameLogs), hostDeck, guestDeck, System.getProperty("mtgoracle.version") ?: "dev build")
        windows(table.match, table.guest, "${hostDeck.name} vs ${guestDeck.name}")
        return 0
    }

    /** Both ends of a table on this machine. */
    class Table(val match: RunningMatch, val guest: RemoteSeat, val host: SeatHost)

    /**
     * A host playing [hostDeck] and a guest bringing [guestDeck], joined over the loopback address, the match
     * started and the guest seated; the guest is told when the match is over. [wrap] sees the host's end of the link.
     */
    fun table(sessions: Sessions, hostDeck: PlayDeck, guestDeck: PlayDeck, version: String, seed: Long = Random.nextLong(), wrap: (Link) -> Link = { it }): Table {
        val listener = TcpLink.listenLocal()
        val guest = RemoteSeat(TcpLink.connectLocal(listener.port), "Guest", guestDeck, version).start()
        val link = wrap(listener.accept())
        listener.close()
        val hello = Door.admit(link, HostMessage.Hello(PROTOCOL_VERSION, version, HOST)) { sessions.judgeGuest(hostDeck, it.deck) }
            ?: error("the guest was turned away: ${guest.seating.value}")
        val guestName = ForgeMatch.tableName(HOST, hello.name)
        val match = sessions.start(Prepared(hostDeck, hello.deck, emptyList(), blocked = false), GameMode.HUMAN_VS_HUMAN, seed = seed, names = HOST to guestName)
        Door.seat(link, guestName)
        val host = SeatHost(match.guest!!, link) { event ->
            when (event) {
                GuestEvent.Conceded -> match.concedeGuest()
                GuestEvent.Left -> match.breakOff("the guest left")
                is GuestEvent.Lost -> match.breakOff(event.reason)
            }
        }.start()
        thread(name = "local-duel-end", isDaemon = true) {
            while (!match.over) Thread.sleep(200)
            host.end(match.games.value.lastOrNull()?.summary ?: "the match is over")
        }
        return Table(match, guest, host)
    }

    private fun windows(match: RunningMatch, guest: RemoteSeat, title: String) = application {
        val quit: () -> Unit = { exitProcess(0) }
        val art = ArtImages(ForgeRuntime.images)
        HouseTheme {
            CompositionLocalProvider(LocalArt provides art) {
                Window(onCloseRequest = quit, title = "Host — $title", state = rememberWindowState(width = 1500.dp, height = 950.dp)) {
                    BoardScreen(match.seat, "Host · $title", CardMode.ART,
                        matchControls = MatchControls(onContinue = match::continueMatch, onConcedeGame = match::concede, onLeaveMatch = match::leave, onBackToLobby = quit))
                }
                Window(onCloseRequest = quit, title = "Guest — $title", state = rememberWindowState(width = 1500.dp, height = 950.dp)) {
                    val seating by guest.seating.collectAsState()
                    val said = when (val s = seating) {
                        Seating.Knocking -> "knocking"
                        is Seating.Seated -> "seated as ${s.name}"
                        is Seating.Refused -> "turned away: ${s.reason}"
                        is Seating.Ended -> "the table closed: ${s.reason}"
                        is Seating.Lost -> s.reason
                    }
                    BoardScreen(guest, "Guest · $said", CardMode.ART,
                        matchControls = MatchControls(onContinue = {}, onConcedeGame = guest::concede, onLeaveMatch = guest::leave, onBackToLobby = quit))
                }
            }
        }
    }
}
