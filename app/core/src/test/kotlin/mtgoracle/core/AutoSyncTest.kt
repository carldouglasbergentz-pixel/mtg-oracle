package mtgoracle.core

import mtgoracle.core.sync.AutoSync
import mtgoracle.core.sync.Source
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The daily sync's rule: once a day, Spellbook once a week, and a failure said until a later sync succeeds. */
class AutoSyncTest {
    private val now = Instant.parse("2026-10-05T10:00:00Z")
    private fun ago(hours: Long) = now - Duration.ofHours(hours)

    @Test
    fun `once a day, with combos once a week`() {
        assertEquals(Source.entries.toSet(), AutoSync.due(now, null, null), "never synced: everything")
        assertEquals(emptySet(), AutoSync.due(now, ago(23), ago(23)), "synced 23 hours ago: nothing yet")
        assertEquals(Source.entries.toSet() - Source.COMBOS, AutoSync.due(now, ago(25), ago(25)), "a day on: all but Spellbook")
        assertEquals(Source.entries.toSet(), AutoSync.due(now, ago(25), ago(7 * 24)), "a week on: Spellbook too")
    }

    @Test
    fun `the status says what failed until it succeeds, and a sync gone stale`() {
        val failed = mapOf(Source.COMBOS to Instant.parse("2026-10-05T08:00:00Z"), Source.PRINTINGS to Instant.parse("2026-10-04T08:00:00Z"))
        val status = AutoSync.status(now, ago(1), failed, ZoneOffset.UTC)!!
        assertTrue(status.startsWith("sync: combos, printings failed 10-04"), status)
        assertNull(AutoSync.status(now, ago(30), emptyMap(), ZoneOffset.UTC), "a day old and clean: nothing to say")
        assertEquals("sync: last run 4 days ago · `sync` to fetch what moved", AutoSync.status(now, ago(4 * 24), emptyMap(), ZoneOffset.UTC))
        assertNull(AutoSync.status(now, null, emptyMap(), ZoneOffset.UTC), "never synced: the first start says so itself")
    }
}
