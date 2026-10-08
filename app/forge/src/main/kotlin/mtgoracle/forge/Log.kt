package mtgoracle.forge

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The app's own diagnostics: to the console, and — once [toFile] is set, as
 * the window does at start-up — to `data/app/app.log`, full stack traces
 * included, so a crash can be read without the terminal it happened in.
 * Game events go to [GameRecorder], not here.
 */
object Log {
    private val clock = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    @Volatile var debug: Boolean = System.getProperty("mtgoracle.debug") == "true"
    @Volatile private var file: File? = null

    /** Also append every line to [log] (created if missing); null stops it. */
    fun toFile(log: File?) {
        log?.parentFile?.mkdirs()
        file = log
        log?.let { info("app log: ${it.absolutePath}") }
    }

    fun info(message: String) = emit("INFO ", message)
    fun warn(message: String) = emit("WARN ", message)
    fun debug(message: String) { if (debug) emit("DEBUG", message) }
    fun error(message: String, error: Throwable? = null) = emit("ERROR", message + (error?.let { "\n" + trace(it) } ?: ""))

    fun trace(error: Throwable): String = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString().trimEnd()

    @Synchronized
    /**
     * [value] as one line of at most [max] characters, control characters shown as `·`: for text a network peer
     * chose (an answer, a name), which must not start lines of its own in a log.
     */
    fun oneLine(value: Any?, max: Int = 300): String =
        value.toString().take(max).map { if (it.isISOControl()) '·' else it }.joinToString("")

    private fun emit(level: String, message: String) {
        val line = "${LocalDateTime.now().format(clock)} $level [${Thread.currentThread().name}] $message"
        System.err.println(line)
        file?.let { f -> runCatching { f.appendText(line + "\n") } }
    }
}
