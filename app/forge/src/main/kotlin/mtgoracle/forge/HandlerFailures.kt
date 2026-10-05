package mtgoracle.forge

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Our code that runs inside Forge's event handlers (the trail, the log
 * pane, known cards, floating mana) fails loudly here. Guava's EventBus
 * catches what a subscriber throws and logs it as SEVERE to
 * java.util.logging, which goes to stderr only: a null in the log pane's
 * cache broke Forge's game log mid-game (prowess, "your second spell")
 * and nothing the player could see said so.
 *
 * One handler on Guava's logger catches every such failure whose stack has
 * a frame of ours, including the ones that surface through a subscriber of
 * Forge's (the game log's observer runs inside Forge's `GameLogFormatter`):
 * the full trace to the app log, and a line to each listener (the seat
 * shows it on the board and in the game's log).
 */
object HandlerFailures {
    // Held: java.util.logging keeps loggers weakly, and a collected one takes its handler with it.
    private val logger: Logger = Logger.getLogger("com.google.common.eventbus")
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val failures = AtomicInteger()
    @Volatile private var installed = false

    /** How many failures of ours since the JVM started: a test asserts none happened. */
    val count: Int get() = failures.get()

    @Synchronized
    fun install() {
        if (installed) return
        installed = true
        logger.addHandler(object : Handler() {
            override fun publish(record: LogRecord) {
                if (record.level.intValue() < Level.SEVERE.intValue()) return
                val thrown = record.thrown ?: return
                val ours = ourFrame(thrown) ?: return
                report("${ours.className.substringAfterLast('.')}.${ours.methodName}: $thrown", thrown)
            }
            override fun flush() = Unit
            override fun close() = Unit
        })
    }

    /** Hears each failure's one-line summary until the returned handle is closed. */
    fun listen(listener: (String) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    internal fun report(summary: String, thrown: Throwable) {
        failures.incrementAndGet()
        Log.error("a handler of ours failed inside Forge: $summary", thrown)
        listeners.forEach { runCatching { it(summary) } }
    }

    /** The first frame of ours in [thrown] or its causes, or null when the failure is Forge's own. */
    internal fun ourFrame(thrown: Throwable): StackTraceElement? =
        generateSequence(thrown) { it.cause }.take(8).flatMap { it.stackTrace.asSequence() }
            .firstOrNull { it.className.startsWith("mtgoracle.") }
}
