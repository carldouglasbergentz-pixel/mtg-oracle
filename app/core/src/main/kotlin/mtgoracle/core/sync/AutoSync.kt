package mtgoracle.core.sync

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * When the app syncs by itself, and what it says about it. A daily sync, as
 * Scryfall rebuilds its exports daily; Commander Spellbook's 675 MB export
 * once a week, since its marker moves nearly every day. A failure stays said
 * until a later sync of that source succeeds: a scheduler that failed in
 * silence once left the cards 3.5 months old (August 2026).
 */
object AutoSync {
    val EVERY: Duration = Duration.ofHours(24)
    val COMBOS_EVERY: Duration = Duration.ofDays(7)
    /** A sync older than this is said even when nothing failed: auto-sync off, or the app not opened. */
    val STALE: Duration = Duration.ofDays(3)

    /** The sources to sync now, or none when the last run is less than [EVERY] ago. */
    fun due(now: Instant, lastRun: Instant?, lastCombos: Instant?): Set<Source> {
        if (lastRun != null && Duration.between(lastRun, now) < EVERY) return emptySet()
        val combos = lastCombos == null || Duration.between(lastCombos, now) >= COMBOS_EVERY
        return Source.entries.filter { it != Source.COMBOS || combos }.toSet()
    }

    /** The status line's word on the sync: what failed, or how old the last run is; null when all is well. */
    fun status(now: Instant, lastRun: Instant?, failed: Map<Source, Instant>, zone: ZoneId = ZoneId.systemDefault()): String? {
        val day = DateTimeFormatter.ofPattern("MM-dd").withZone(zone)
        if (failed.isNotEmpty()) {
            val since = failed.values.min()
            return "sync: ${failed.keys.sortedBy { it.ordinal }.joinToString { it.key }} failed ${day.format(since)} · the output says why · `sync` again"
        }
        if (lastRun != null && Duration.between(lastRun, now) >= STALE) {
            return "sync: last run ${Duration.between(lastRun, now).toDays()} days ago · `sync` to fetch what moved"
        }
        return null
    }
}
