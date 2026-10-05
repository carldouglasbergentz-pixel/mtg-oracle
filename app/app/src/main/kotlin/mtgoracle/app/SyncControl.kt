package mtgoracle.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import mtgoracle.core.sync.AutoSync
import mtgoracle.core.sync.Source
import mtgoracle.core.sync.SyncReport
import mtgoracle.data.MtgDb
import mtgoracle.data.sync.HttpUpstream
import mtgoracle.data.sync.Sync
import mtgoracle.data.sync.Upstream
import mtgoracle.forge.Log
import kotlin.concurrent.thread

/**
 * The window's sync: by hand (`sync`, [ Sync ]) and daily by itself
 * ([autoIfDue]), never two at once, and what the status line says about it
 * ([warning]) until the next one. [db] is the open database (null before it
 * is), [busy] a game or simulation on, whose writes would wait on the sync's
 * long transactions; [onDone] gets the report on the UI thread, to build the
 * lookup again and show it.
 */
class SyncControl(
    private val paths: AppPaths,
    private val settings: Settings,
    private val db: () -> MtgDb?,
    private val busy: () -> Boolean,
    private val say: (String?) -> Unit,
    private val onDone: (report: SyncReport, auto: Boolean) -> Unit,
) {
    /** The sync running, if one is; its log line is the notice. */
    @Volatile private var syncing = false
    /** The time, for the sync's record; the tests move it. */
    var clock: () -> java.time.Instant = java.time.Instant::now
    /** What the status line says about the sync while nothing else is said: a failure, or a stale sync (AutoSync.status). */
    var warning by mutableStateOf<String?>(null)
        private set
    /** Where the network is for a sync; the tests serve files instead. */
    var upstream: Upstream = HttpUpstream()

    /**
     * The data pipeline in the background: [only]'s sources, skipping those
     * whose upstream hasn't moved unless [force]. Its report goes to [onDone];
     * decks are never touched.
     */
    fun run(force: Boolean = false, only: Set<Source> = Source.entries.toSet(), auto: Boolean = false) {
        val database = db() ?: return
        if (syncing) { say("a sync is running already"); return }
        syncing = true
        say(if (auto) "sync (daily): starting" else "sync: starting")
        thread(name = "sync", isDaemon = true) {
            val report = try {
                Sync(database, upstream, paths.data.resolve("raw"), paths.formats, log = { say("sync: $it") }).run(force, only)
            } catch (e: Exception) {
                Log.error("sync failed", e)
                null
            } finally {
                syncing = false
            }
            java.awt.EventQueue.invokeLater {
                record(only, failed = report?.failures?.map { it.first }?.toSet() ?: only)
                if (report == null) { say("sync failed: see ${paths.appLog}"); return@invokeLater }
                onDone(report, auto)
                say(if (report.failures.isEmpty()) "sync done" + if (report.touched) "" else ": everything was up to date"
                    else "sync done, ${report.failures.size} source(s) failed: ${report.failures.joinToString { it.first.key }}")
            }
        }
    }

    /** A sync of [ran] finished, [failed] among them: kept in the settings, so the warning outlives a restart. */
    private fun record(ran: Set<Source>, failed: Set<Source>) {
        val now = clock()
        settings.syncLast = now
        if (Source.COMBOS in ran && Source.COMBOS !in failed) settings.syncLastCombos = now
        settings.syncFailed = settings.syncFailed.filterKeys { it !in ran } + failed.associateWith { settings.syncFailed[it] ?: now }
        refreshWarning()
    }

    fun refreshWarning() {
        warning = AutoSync.status(clock(), settings.syncLast, settings.syncFailed)
    }

    /**
     * The daily sync, if it is due (AutoSync.due) and nothing would be in its
     * way: auto-sync on, the database open, no sync running, nothing [busy].
     * True when it started.
     */
    fun autoIfDue(): Boolean {
        if (!settings.autoSync || db() == null || syncing || busy()) return false
        val sources = AutoSync.due(clock(), settings.syncLast, settings.syncLastCombos)
        if (sources.isEmpty()) return false
        Log.info("daily sync: ${sources.joinToString { it.key }}")
        run(only = sources, auto = true)
        return true
    }

    /** `autosync on|off`, or with null what it is: the answer for the output. */
    fun setting(on: Boolean?): String {
        if (on != null) settings.autoSync = on
        val last = settings.syncLast?.let { " · last sync ${java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(java.time.ZoneId.systemDefault()).format(it)}" }.orEmpty()
        return if (settings.autoSync) "daily sync: on (Spellbook weekly)$last" else "daily sync: off · `sync` fetches by hand$last"
    }
}
