package mtgoracle.app

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import mtgoracle.data.MissingDatabaseException
import mtgoracle.data.MtgDb
import mtgoracle.data.SchemaTooOldException
import mtgoracle.forge.Log
import kotlin.system.exitProcess

/**
 * Modes:
 *   window                 the app (default)
 *   check-schema           exit 0 when data/mtg.db has the schema this app needs, 2 with the fix otherwise
 *   prefetch [deck name]   fetch card art for your decks (or one deck) into the image cache
 *   scripted               headless evidence run on a copy of the database (see Scripted.kt)
 *   snapshots              the StagedBoards rendered with real art (review pictures)
 */
fun main(args: Array<String>) {
    val paths = AppPaths.fromSystemProperties()
    val mode = args.firstOrNull() ?: "window"
    if (mode == "window") return runWindow(paths)
    // Headless modes always end in exitProcess, failure included: Forge leaves non-daemon
    // threads (its timers, the game pool) that would otherwise keep the JVM alive.
    val code = try {
        when (mode) {
            "check-schema" -> checkSchema(paths)
            "migrate" -> migrate(paths)
            "prefetch" -> Headless.prefetch(paths, args.drop(1).joinToString(" ").ifBlank { null })
            "scripted" -> Scripted.run(paths, args.drop(1))
            "snapshots" -> StagedPictures.run(paths, java.io.File(System.getProperty("mtgoracle.evidence") ?: "build/evidence", "staged"), args.drop(1).toSet())
            else -> { System.err.println("unknown mode $mode"); 64 }
        }
    } catch (e: Throwable) {
        Log.error("$mode failed", e)
        1
    }
    exitProcess(code)
}

/** Read-only: whether the database has its version's whole schema. */
private fun checkSchema(paths: AppPaths): Int = schemaErrors {
    MtgDb(paths.db).checkSchema()
    println("${paths.db}: schema OK")
}

/** What the app does at start, alone: bring the database to this build's schema, with a backup first. */
private fun migrate(paths: AppPaths): Int = schemaErrors {
    val report = MtgDb(paths.db).migrate(paths.backups)
    println("${paths.db}: schema v${report.from} -> v${report.to}")
    report.lines.forEach { println("  $it") }
}

private fun schemaErrors(block: () -> Unit): Int = try {
    block()
    0
} catch (e: SchemaTooOldException) {
    System.err.println(e.message); 2
} catch (e: mtgoracle.data.SchemaTooNewException) {
    System.err.println(e.message); 2
} catch (e: MissingDatabaseException) {
    System.err.println(e.message); 2
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun runWindow(paths: AppPaths) {
    Log.toFile(paths.appLog)
    // Which build is running, so a crash report names it: `installLocal` stamps its snapshots.
    val version = System.getProperty("mtgoracle.version") ?: "dev build (gradlew run)"
    Log.info("MTG Oracle $version")
    val app = AppController(paths)
    // Any thread's uncaught error (Forge's included) is logged and shown, never lost with the terminal.
    Thread.setDefaultUncaughtExceptionHandler { thread, error -> app.onCrash("thread ${thread.name}", error) }
    app.boot()
    if (app.screen is Screen.Blocked) Log.error((app.screen as Screen.Blocked).message)
    application {
        val quit = {
            app.shutdown()
            // Forge's game threads are not daemons: closing the window ends the process.
            exitProcess(0)
        }
        // Compose's default for an error in a window is a dialog, then closing it — which recorded a
        // crash as a concession. Ours logs it and opens a fresh window on the crash screen instead.
        val onWindowError = androidx.compose.ui.window.WindowExceptionHandlerFactory {
            androidx.compose.ui.window.WindowExceptionHandler { error -> app.onCrash("window", error) }
        }
        CompositionLocalProvider(androidx.compose.ui.window.LocalWindowExceptionHandlerFactory provides onWindowError) {
            val state = rememberWindowState(width = 1720.dp, height = 1060.dp)
            key(app.windowEpoch) {
                Window(onCloseRequest = quit, title = "MTG Oracle — $version", state = state) {
                    AppContent(app, onQuit = quit)
                }
            }
        }
    }
}
