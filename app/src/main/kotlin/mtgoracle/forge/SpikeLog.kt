package mtgoracle.forge

import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Console diagnostics for the spike. Game events go to [GameRecorder], not here. */
object SpikeLog {
    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    @Volatile var debug: Boolean = System.getProperty("mtgoracle.debug") == "true"

    fun info(message: String) = emit("INFO ", message)
    fun warn(message: String) = emit("WARN ", message)
    fun debug(message: String) { if (debug) emit("DEBUG", message) }
    fun error(message: String, error: Throwable? = null) {
        emit("ERROR", message)
        error?.printStackTrace()
    }

    private fun emit(level: String, message: String) {
        System.err.println("${LocalTime.now().format(clock)} $level [${Thread.currentThread().name}] $message")
    }
}
