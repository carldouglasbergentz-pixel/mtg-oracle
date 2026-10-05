package mtgoracle.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import mtgoracle.forge.Log
import kotlin.concurrent.thread

/**
 * A release's updates from GitHub: asked at start and daily ([check]), and
 * installed by `update` ([install]), which closes the app for the swap.
 * Nothing at all when the app doesn't run from a release package (the repo's
 * snapshot, a test). [busy] is a game or simulation on, which an update
 * would close; [shutdown] records what is on before the app exits.
 */
class UpdateControl(
    private val settings: Settings,
    private val clock: () -> java.time.Instant,
    private val busy: () -> Boolean,
    private val say: (String?) -> Unit,
    private val shutdown: () -> Unit,
) {
    private val updates: Updates? = run {
        val release = ReleaseVersion.parse(System.getProperty("mtgoracle.release"))
        val install = System.getProperty("mtgoracle.install")?.let { java.io.File(it).canonicalFile }
        if (release != null && install != null) Updates(install, release) else null
    }
    /** The newer release found, if one is. */
    @Volatile private var newerRelease: Release? = null
    @Volatile private var updating = false
    /** What the status line says of a newer release while nothing else is said. */
    var notice by mutableStateOf<String?>(null)
        private set

    /** Whether a day has gone since the last look (or there was none). */
    fun due(): Boolean = settings.updateLastCheck.let { last -> last == null || java.time.Duration.between(last, clock()) >= java.time.Duration.ofHours(24) }

    /** Asks GitHub, in the background, whether a newer release is out; quiet when it can't tell (no login, no network). */
    fun check() {
        val u = updates ?: return
        thread(name = "update-check", isDaemon = true) {
            val newer = runCatching { u.newer() }.onFailure { Log.info("update check: ${it.message}") }.getOrNull()
            settings.updateLastCheck = clock()
            java.awt.EventQueue.invokeLater {
                newerRelease = newer
                notice = newer?.let { "MTG Oracle ${it.version} is out (this is ${u.running}) · `update` installs it" }
            }
        }
    }

    /**
     * `update`: the newer release downloaded, its SHA-256 checked, and unpacked; then the app
     * closes for the swap script, which keeps data\ and starts the new version. Not during a game.
     */
    fun install(): String {
        val u = updates ?: return "updates come with a release package; this one runs from the repo (gradlew :app:installLocal)"
        if (busy()) return "finish or leave the game first: an update closes the app"
        if (updating) return "an update is under way"
        updating = true
        say("update: asking GitHub for the newest release")
        thread(name = "update", isDaemon = true) {
            try {
                val release = newerRelease ?: u.newer()
                if (release == null) {
                    java.awt.EventQueue.invokeLater { say("update: ${u.running} is the newest release") }
                    return@thread
                }
                val program = u.download(release) { line -> say("update: $line") }
                val script = u.applyScript(program, ProcessHandle.current().pid())
                Log.info("update: ${u.running} -> ${release.version}, swapping with $script")
                java.awt.EventQueue.invokeLater {
                    say("update: restarting into ${release.version}")
                    u.launch(script)
                    shutdown()
                    kotlin.system.exitProcess(0)
                }
            } catch (e: Exception) {
                Log.warn("update failed: ${e.message}")
                java.awt.EventQueue.invokeLater { say("update failed: ${e.message}") }
            } finally {
                updating = false
            }
        }
        return "update: looking for a newer release (the status line follows it)"
    }
}
