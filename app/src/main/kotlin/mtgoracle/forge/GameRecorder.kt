package mtgoracle.forge

import com.google.common.eventbus.Subscribe
import forge.game.Game
import forge.game.event.GameEvent
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.util.ArrayDeque
import java.util.Observer

/**
 * The recording Forge's own GUI can't make: every game event, every game-log
 * line, and every answer our seat gave, in one append-only file.
 *
 * Three line kinds, so a reader can filter:
 *   LOG   — Forge's GameLog (the human-readable play-by-play)
 *   EVENT — every GameEvent off the game's event bus, class + fields
 *   SEAT  — a prompt shown to our seat and the answer we gave
 */
class GameRecorder(val file: File) : Closeable {
    private val out: BufferedWriter
    private val started = System.nanoTime()
    private val recent = ArrayDeque<String>()
    private val attached = mutableSetOf<Int>()
    private var logLinesSeen = 0
    private var closed = false

    init {
        file.parentFile.mkdirs()
        out = file.bufferedWriter()
    }

    /** Idempotent per game: both the seat's openView and a spectator's may call it. */
    @Synchronized
    fun attach(game: Game) {
        if (!attached.add(game.id)) return
        game.subscribeToEvents(this)
        val log = game.gameLog
        log.addObserver(Observer { _, _ -> drainGameLog(game) })
        write("NOTE", "recording game ${game.id}: ${game.view.title}")
    }

    @Subscribe
    fun onGameEvent(event: GameEvent) {
        write("EVENT", "${event.javaClass.simpleName} $event")
    }

    fun seat(line: String) = write("SEAT", line)
    fun note(line: String) = write("NOTE", line)

    /** The last few play-by-play lines, newest last, for the board's log pane. */
    @Synchronized
    fun recentLog(max: Int): List<String> = recent.toList().takeLast(max)

    @Synchronized
    private fun drainGameLog(game: Game) {
        val entries = game.gameLog.allEntries
        for (entry in entries.drop(logLinesSeen)) {
            val line = "${entry.type().caption}: ${entry.message()}"
            write("LOG", line)
            recent.addLast(line)
            while (recent.size > 200) recent.removeFirst()
        }
        logLinesSeen = entries.size
    }

    @Synchronized
    private fun write(kind: String, line: String) {
        if (closed) return // the engine can still be winding down after the report is written
        val ms = (System.nanoTime() - started) / 1_000_000
        out.write("%8d %-5s %s".format(ms, kind, line.replace("\n", " | ")))
        out.newLine()
        out.flush()
    }

    @Synchronized
    override fun close() {
        closed = true
        out.close()
    }
}
