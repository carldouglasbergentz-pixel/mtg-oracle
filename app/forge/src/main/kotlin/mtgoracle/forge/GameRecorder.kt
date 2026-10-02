package mtgoracle.forge

import com.google.common.eventbus.Subscribe
import forge.game.Game
import forge.game.event.GameEvent
import forge.game.event.GameEventSpellAbilityCast
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
 *   LOG   — Forge's GameLog (the human-readable play-by-play), and our own
 *           lines in it ([play]: a draw)
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
    /** The game recorded now (a match records its games one after another). */
    @Volatile private var game: Game? = null

    init {
        file.parentFile.mkdirs()
        out = file.bufferedWriter()
    }

    /** Idempotent per game: both the seat's openView and a spectator's may call it. */
    @Synchronized
    fun attach(game: Game) {
        if (!attached.add(game.id)) return
        this.game = game
        game.subscribeToEvents(this)
        val log = game.gameLog
        log.addObserver(Observer { _, _ -> drainGameLog(game) })
        write("NOTE", "recording game ${game.id}: ${game.view.title}")
    }

    @Subscribe
    fun onGameEvent(event: GameEvent) {
        write("EVENT", "${event.javaClass.simpleName} $event")
        if (event is GameEventSpellAbilityCast) noteSpellsThisTurn(event)
    }

    /**
     * As a spell is cast, Forge's own count of the caster's earlier spells this
     * turn (the event comes before Forge adds this one), as "your second spell
     * each turn" counts them (Jori En, Cori-Steel Cutter): neither triggered
     * on a second spell in a real game, and the log could not show what Forge
     * had counted.
     */
    private fun noteSpellsThisTurn(event: GameEventSpellAbilityCast) {
        val item = event.si()?.takeIf { !it.isAbility && !it.isTrigger } ?: return
        val caster = item.activatingPlayer ?: return
        val cast = runCatching { game?.stack?.spellCardsCastThisTurn?.filter { it.controller?.id == caster.id } }.getOrNull() ?: return
        write("NOTE", "${caster.name}: earlier spells this turn, as Forge counts them: ${cast.size} (${cast.joinToString { it.name }})")
    }

    fun seat(line: String) = write("SEAT", line)
    fun note(line: String) = write("NOTE", line)

    /**
     * A play-by-play line of ours, beside Forge's. [merge] may fold it into
     * the line before it (two draws in a row): it gets the last line and this
     * one, and returns the line to stand for both, or null to keep them apart.
     */
    @Synchronized
    fun play(line: String, merge: ((last: String, next: String) -> String?)? = null) {
        write("LOG", line)
        val folded = recent.peekLast()?.let { last -> merge?.invoke(last, line) }
        if (folded != null) recent.removeLast()
        recent.addLast(folded ?: line)
        while (recent.size > 200) recent.removeFirst()
    }

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
