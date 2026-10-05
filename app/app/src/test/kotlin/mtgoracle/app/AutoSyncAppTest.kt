package mtgoracle.app

import mtgoracle.core.sync.Source
import mtgoracle.data.FixtureDb
import mtgoracle.data.sync.Upstream
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The daily sync in the app: due once a day, Spellbook once a week, a failing
 * source said in the status line (across a restart too) until it syncs, and
 * off with `autosync off`. The fixture's exports stand in for the network,
 * and the clock is the test's.
 */
class AutoSyncAppTest {
    private val assets = File(System.getProperty("mtgoracle.forgeAssets"))
    private val data = kotlin.io.path.createTempDirectory("mtg-oracle-autosync-").toFile()
    private var now = Instant.parse("2026-10-05T08:00:00Z")

    @AfterTest fun close() { data.deleteRecursively() }

    private fun app(upstream: Upstream = FixtureDb.Exports(FixtureDb.raw)) =
        AppController(AppPaths(data, assets, forgeHome = Scenario.home)).also { it.sync.upstream = upstream; it.sync.clock = { now }; it.boot() }

    private fun AppController.awaitSync() {
        val deadline = System.currentTimeMillis() + 180_000
        while (notice?.startsWith("sync done") != true) {
            if (System.currentTimeMillis() > deadline) fail("the sync did not finish: $notice")
            Thread.sleep(100)
        }
        java.awt.EventQueue.invokeAndWait {} // what the sync's end posted has run
    }

    @Test
    fun `once a day, Spellbook once a week, a failure said until it syncs, and off when turned off`() {
        val app = app()
        assertTrue(app.sync.autoIfDue(), "never synced: due at once")
        app.awaitSync()
        assertEquals(now, app.settings.syncLast)
        assertEquals(now, app.settings.syncLastCombos, "the first run takes Spellbook too")
        assertNull(app.sync.warning)
        assertFalse(app.lookupUi!!.showOutput, "a daily run doesn't open the output over the library")

        now += Duration.ofHours(23)
        assertFalse(app.sync.autoIfDue(), "23 hours on: not yet")

        // A day on, Wizards' rules page is down: the rest syncs, Spellbook waits for its week, and the failure is said.
        now += Duration.ofHours(2)
        val down = object : Upstream by FixtureDb.Exports(FixtureDb.raw) { override fun rulesPage(): String = error("Wizards is down") }
        app.sync.upstream = down
        app.notice = null
        assertTrue(app.sync.autoIfDue())
        app.awaitSync()
        assertEquals(Instant.parse("2026-10-05T08:00:00Z"), app.settings.syncLastCombos, "Spellbook stays weekly")
        assertEquals(setOf(Source.RULES), app.settings.syncFailed.keys)
        assertTrue(app.sync.warning.orEmpty().startsWith("sync: rules failed"), "${app.sync.warning}")

        // A restart still says it; a sync that gets the rules clears it.
        val again = app(down)
        assertTrue(again.sync.warning.orEmpty().startsWith("sync: rules failed"), "${again.sync.warning}")
        again.sync.upstream = FixtureDb.Exports(FixtureDb.raw)
        again.notice = null
        again.sync.run(only = setOf(Source.RULES))
        again.awaitSync()
        assertNull(again.sync.warning, "the rules synced: nothing to say")

        assertEquals("daily sync: off · `sync` fetches by hand", again.sync.setting(false).substringBefore(" · last"))
        now += Duration.ofDays(2)
        assertFalse(again.sync.autoIfDue(), "off: never by itself")
        assertNotNull(again.sync.setting(true))
        assertTrue(again.sync.autoIfDue(), "on again, and a day has gone: due")
        again.awaitSync()
    }
}
