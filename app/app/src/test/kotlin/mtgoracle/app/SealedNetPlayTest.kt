package mtgoracle.app

import mtgoracle.core.play.GameMode
import mtgoracle.core.seat.ScriptedSeat
import mtgoracle.data.DbFixture
import mtgoracle.data.DeckWriter
import mtgoracle.data.GameStore
import mtgoracle.data.Lookup
import mtgoracle.data.MtgDb
import mtgoracle.data.PoolStore
import mtgoracle.forge.ForgeLimited
import mtgoracle.net.Opening
import mtgoracle.net.Room
import mtgoracle.ui.library.NetAction
import mtgoracle.ui.library.NetState
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A sealed table between two apps in one JVM, on copies of the user's
 * database (ADR 0003): the host opens a room on the limited tab, the guest
 * joins from it, each opens six packs of its own in its own app, builds,
 * and is ready; the match begins, and when it ends the guest checks the
 * host's deck against the host's pool. Both sides record the game.
 */
class SealedNetPlayTest {
    private val assets = File(System.getProperty("mtgoracle.forgeAssets"))
    private val dirs = mutableListOf<File>()
    private val apps = mutableListOf<AppController>()
    @AfterTest fun clean() {
        apps.forEach { runCatching { it.net.close() } }
        Thread.sleep(500)
        dirs.forEach { it.deleteRecursively() }
    }

    private fun waitFor(what: String, timeoutMillis: Long = 60_000, state: () -> String = { "" }, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) { check(System.currentTimeMillis() < deadline) { "timed out waiting for $what ${state()}" }; Thread.sleep(20) }
    }

    private fun building(app: AppController) = app.net.state as? NetState.Building

    /** The guest's seat is gone once it left the table: its last word is in the notice. */
    private fun guestCheck(guest: AppController): String = guest.notice!!.substringAfterLast(". ")

    /** The newest deck in the app's Limited folder, which the table made: built as Forge's AI would build it. */
    private fun buildAsTheAi(app: AppController, before: Set<Int>, dir: File): Int {
        val id = app.decks.single { it.id !in before && it.folderName == LimitedControl.FOLDER }.id
        val db = MtgDb(File(dir, "mtg.db"))
        val lookup = Lookup(db)
        DeckWriter(db, lookup.names, lookup.formats).replace(id, app.limited.suggestion(app.deckById(id)!!))
        app.deckChanged(id)
        return id
    }

    @Test
    fun `two apps open their own pools at one table, build, are ready, play, and the guest checks the host's deck`() {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        Scenario.startForge()
        var clipboard = ""
        fun app(name: String) = AppController(AppPaths(DbFixture.copy().parentFile.also { dirs += it }, assets, forgeHome = Scenario.home)).apply {
            writeClipboard = { clipboard = it }
            readClipboard = { clipboard }
            openRoom = { Opening.Opened(Room.local()) }
            boot()
            settings.playerName = name
        }.also { apps += it }
        val host = app("Alice")
        val guest = app("Bob")
        waitFor("Forge") { host.forgeReady && guest.forgeReady }
        val before = listOf(host, guest).map { a -> a.decks.map { it.id }.toSet() }
        for (a in listOf(host, guest)) {
            a.play.openLobby(null)
            if (!a.play.limited) a.play.toggleTab()
        }
        host.chooseSet(assertNotNull(ForgeLimited.set("BLB")).code)

        host.net.act(NetAction.Host)
        waitFor("the room", state = { "${host.net.state}" }) { host.net.state is NetState.Hosting }
        guest.net.act(NetAction.Join)
        waitFor("both pools opened", state = { "host ${host.net.state} guest ${guest.net.state}" }) { building(host)?.deck != null && building(guest)?.deck != null }
        assertEquals("Bloomburrow", building(guest)!!.set)
        // Each builds in its workspace, the pool in the middle; the guest's board waits for the match.
        waitFor("both building in the workspace", state = { "${host.screen} ${guest.screen}" }) { listOf(host, guest).all { it.screen == Screen.Library && it.editing?.fromPool == true } }

        val hostDeck = buildAsTheAi(host, before[0], dirs[0])
        val guestDeck = buildAsTheAi(guest, before[1], dirs[1])
        val hostPool = PoolStore(MtgDb(File(dirs[0], "mtg.db"))).pool(host.deckById(hostDeck)!!.poolId!!)!!
        val guestPool = PoolStore(MtgDb(File(dirs[1], "mtg.db"))).pool(guest.deckById(guestDeck)!!.poolId!!)!!
        assertNotEquals(hostPool.pool.cards, guestPool.pool.cards, "each opened a pool of its own")

        // The guest can't send before the host is ready.
        guest.net.act(NetAction.Ready)
        assertTrue(building(guest)!!.ready.not(), "not before the host")
        host.net.act(NetAction.Ready)
        waitFor("the host ready", state = { "${guest.net.state}" }) { building(guest)?.note?.contains("the host is ready") == true }
        guest.net.act(NetAction.Ready)
        waitFor("the match", state = { "host ${host.net.state} guest ${guest.net.state} ${guest.notice}" }) { host.play.match != null && guest.screen == Screen.Guest }

        // The host's table keeps the guest's pool beside its own.
        val hostPools = PoolStore(MtgDb(File(dirs[0], "mtg.db")))
        val rival = assertNotNull(hostPools.pool(assertNotNull(hostPools.pool(hostPool.id)!!.rivalPoolId, "the rival linked")))
        assertEquals(guestPool.pool.cards.map { it.name }, rival.pool.cards.map { it.name }, "the guest's pool, as the host opened it from the guest's secret")

        // Both play a little, then the host concedes the match: it ends, the host's secret and deck go to the guest, who checks them.
        val match = host.play.match!!
        val remote = assertNotNull(guest.net.guest)
        listOf(match.seat, remote).map { seat ->
            thread { ScriptedSeat(seat, retryAfterMillis = 1500).play(timeoutMillis = 120_000) { match.over || (seat.board.value?.turn ?: 0) > 2 } }
        }.forEach { it.join() }
        host.play.leaveMatch()
        waitFor("the guest's check", state = { "${guest.notice}" }) { guest.notice?.contains("checked") == true }
        assertTrue(guest.notice!!.endsWith("The host's deck was built from its own pool: checked."), guest.notice)
        assertEquals("The host's deck was built from its own pool: checked.", guest.net.guest?.sealedProgress?.value?.hostCheck ?: guestCheck(guest))
        fun games(index: Int) = GameStore(MtgDb(File(dirs[index], "mtg.db"))).played().filter { it.mode == GameMode.HUMAN_VS_HUMAN }
        waitFor("both rows") { games(0).isNotEmpty() && games(1).isNotEmpty() }
        assertEquals(hostDeck, games(0).single().deck.id, "the host recorded the table's deck")
        assertEquals(guestDeck, games(1).single().deck.id, "and so did the guest")
    }
}
