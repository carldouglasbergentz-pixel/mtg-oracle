package mtgoracle.app

import mtgoracle.core.play.GameMode
import mtgoracle.core.play.MatchFormat
import mtgoracle.core.play.Winner
import mtgoracle.core.seat.ScriptedSeat
import mtgoracle.data.DbFixture
import mtgoracle.data.GameStore
import mtgoracle.data.MtgDb
import mtgoracle.net.Opening
import mtgoracle.net.Room
import mtgoracle.net.Seating
import mtgoracle.ui.OffscreenDriver
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.library.MatAction
import mtgoracle.ui.library.NetAction
import mtgoracle.ui.library.NetState
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Network play from the lobby, two apps in one JVM on copies of the user's
 * database: the host opens a room (on loopback here, the router's port in the
 * app), the invite goes by the clipboard, the guest joins from it, both play
 * a game through to the match's end, and each side records it.
 */
class NetPlayTest {
    private val assets = File(System.getProperty("mtgoracle.forgeAssets"))
    private val dirs = mutableListOf<File>()
    @AfterTest fun clean() {
        // The games' logs are kept where a failure can be read.
        val keep = File(Scenario.home, "netplay-logs").also { it.deleteRecursively(); it.mkdirs() }
        dirs.forEachIndexed { i, dir -> File(dir, "game_logs").listFiles()?.forEach { it.copyTo(File(keep, "${if (i == 0) "host" else "guest"}-${it.name}"), overwrite = true) } }
        dirs.forEach { it.deleteRecursively() }
    }

    private fun waitFor(what: String, timeoutMillis: Long = 60_000, state: () -> String = { "" }, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) { check(System.currentTimeMillis() < deadline) { "timed out waiting for $what ${state()}" }; Thread.sleep(20) }
    }

    @Test
    fun `a friend joins from the invite, they play, and both sides record the game`() {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        Scenario.startForge()
        var clipboard = ""
        fun app(name: String) = AppController(AppPaths(DbFixture.copy().parentFile.also { dirs += it }, assets, forgeHome = Scenario.home)).apply {
            writeClipboard = { clipboard = it }
            readClipboard = { clipboard }
            openRoom = { Opening.Opened(Room.local()) }
            boot()
            settings.playerName = name
        }
        val host = app("Alice")
        val guest = app("Bob")
        waitFor("Forge") { host.forgeReady && guest.forgeReady }
        fun deck(app: AppController, name: String) = app.decks.first { it.name == name }.id
        host.play.openLobby(deck(host, "Jori En"))
        host.play.format = MatchFormat.BO1
        guest.play.openLobby(deck(guest, "Phelia Doggo"))
        // The guest's playmat, which the host chooses to see.
        File(guest.mats.lobby().folder.let { File(dirs[1], "playmats") }.also { it.mkdirs() }, "green.png").also { f ->
            ImageIO.write(BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB).apply { createGraphics().apply { color = Color(20, 160, 60); fillRect(0, 0, 400, 200); dispose() } }, "png", f)
        }
        guest.mats.act(MatAction.Cycle(mine = true, by = 1))
        host.net.act(NetAction.ToggleShowTheirMat)

        OffscreenDriver(1600, 1100) { AppContent(host) {} }.use { d ->
            d.settle(5)
            assertTrue("network play" in d.text.all() && "your name" in d.text.all() && "Alice" in d.text.all(), d.text.all())
            assertTrue(d.click(ClickTarget.Control("net:host")))
            waitFor("the room") { host.net.state is NetState.Hosting }
            d.settle(3)
            assertTrue("invite: MTG-" in d.text.all(), "the invite is shown")
        }
        val invite = (host.net.state as NetState.Hosting).invite
        assertEquals(invite, clipboard, "and on the clipboard")

        guest.net.act(NetAction.Join)
        waitFor("the match") { host.play.match != null && host.screen == Screen.Playing && guest.net.guest?.seating?.value is Seating.Seated && guest.screen == Screen.Guest }
        assertEquals(Screen.Playing, host.screen)
        assertEquals(Screen.Guest, guest.screen)
        val match = host.play.match!!
        val remote = guest.net.guest!!
        waitFor("the guest's mat, shown to the host") { host.net.theirMat != null }

        // A few turns, then the host leaves: the concession ends the match, which is the path a long game takes too.
        val players = listOf(match.seat, remote).map { seat -> thread { ScriptedSeat(seat, retryAfterMillis = 1500).play(timeoutMillis = 120_000) { match.over || (seat.board.value?.turn ?: 0) > 4 } } }
        players.forEach { it.join() }
        if (!match.over) host.play.leaveMatch()
        waitFor("the match over on both sides", state = {
            File(Scenario.home, "netplay-threads.txt").writeText(Thread.getAllStackTraces().entries.joinToString("\n\n") { (t, st) ->
                "${t.name} ${t.state}\n" + st.take(40).joinToString("\n") { "    at $it" }
            })
            "(over ${match.over}, games ${match.games.value}, the guest ${remote.seating.value}, host turn ${match.seat.board.value?.turn} " +
                "prompt ${match.seat.prompt.value?.message}, guest turn ${remote.board.value?.turn} prompt ${remote.prompt.value?.message})"
        }) { match.over && remote.seating.value is Seating.Ended }
        waitFor("the guest's record") { guest.net.guestGames.lastOrNull()?.matchOver == true }

        fun games(app: AppController) = GameStore(MtgDb(File(dirs[if (app === host) 0 else 1], "mtg.db"))).played().filter { it.mode == GameMode.HUMAN_VS_HUMAN }
        waitFor("the host's row") { games(host).isNotEmpty() }
        waitFor("the guest's row") { games(guest).isNotEmpty() }
        val hostRow = games(host).single()
        val guestRow = games(guest).single()
        assertEquals("Phelia Doggo (Bob)", hostRow.opponent.name)
        assertEquals(null, hostRow.opponent.id, "the guest's deck is not one of the host's")
        assertEquals("Jori En (Alice)", guestRow.opponent.name)
        val flipped = mapOf(Winner.ME to Winner.OPPONENT, Winner.OPPONENT to Winner.ME, Winner.DRAW to Winner.DRAW)
        assertEquals(flipped[assertNotNull(hostRow.winner)], guestRow.winner, "one game, seen from both sides")

        guest.net.leaveTable()
        assertEquals(Screen.Lobby, guest.screen)
        host.play.backToLobby()
        assertEquals(Screen.Lobby, host.screen)
    }
}
