package mtgoracle.forge

import com.google.common.eventbus.Subscribe
import forge.game.Game
import forge.game.event.GameEvent
import forge.game.event.GameEventSpellAbilityCast
import mtgoracle.core.model.LogKind
import mtgoracle.core.model.LogLine
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
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
    /** The log pane's lines, the whole match, oldest first; [shown] is the copy last handed out, until a line is added. */
    private val lines = ArrayList<LogLine>()
    private var shown: List<LogLine>? = null
    private val parse = LogLines(
        nameOf = { id -> game?.let { g -> runCatching { g.findById(id)?.takeUnless { it.isFaceDown }?.name }.getOrNull() } },
        oracle = { name -> if (name in oracles) oracles[name] else runCatching { oracleCard(name) }.getOrNull().also { oracles[name] = it } },
    )
    /** Cards as printed by name, null kept for a name Forge lacks; read under the recorder's lock. */
    private val oracles = HashMap<String, mtgoracle.core.model.CardState?>()
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
        // Each game has a log of its own: game 2 read past as many of its lines as game 1 had.
        logLinesSeen = 0
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
        // A copy (replicate, storm) is logged as cast but isn't one (CR 707.10), and Forge rightly leaves it out.
        if (runCatching { game?.stack?.firstOrNull { it.id == item.id }?.spellAbility?.isCopied }.getOrNull() == true) {
            write("NOTE", "${caster.name}: a copy, not a cast (${item.sourceCard?.name})")
            return
        }
        val cast = runCatching { game?.stack?.spellCardsCastThisTurn?.filter { it.controller?.id == caster.id } }.getOrNull() ?: return
        write("NOTE", "${caster.name}: earlier spells this turn, as Forge counts them: ${cast.size} (${cast.joinToString { it.name }})")
    }

    fun seat(line: String) = write("SEAT", line)
    fun note(line: String) = write("NOTE", line)

    /**
     * A play-by-play line of ours, beside Forge's: [caption] is its word in
     * the file (Forge's own are `Draw`, `Zone Change`), [names] the cards it
     * names. [merge] may fold it into the line before it of the same kind
     * (two draws in a row): it gets that line's text and this one, and
     * returns the text to stand for both, or null to keep them apart.
     */
    @Synchronized
    fun play(
        caption: String?, kind: LogKind, text: String, names: List<String> = emptyList(),
        merge: ((last: String, next: String) -> String?)? = null,
        /** The players it was shown to; null for a line everyone reads (LogLine.seenBy). */
        seenBy: Set<Int>? = null,
    ) {
        write("LOG", (caption?.let { "$it: $text" } ?: text) + (seenBy?.let { " [seen by $it]" }.orEmpty()))
        val last = lines.lastOrNull()?.takeIf { it.kind == kind && it.seenBy == seenBy }
        val folded = last?.let { merge?.invoke(it.text, text) }
        if (folded != null) lines.removeAt(lines.lastIndex)
        add(kind, folded ?: text, seenBy) { parse.ours(kind, folded ?: text, players(), names, private = seenBy != null).copy(seenBy = seenBy) }
    }

    /** The match's play-by-play so far, oldest first, for the board's log pane: the same list until a line is added. */
    @Synchronized
    fun log(): List<LogLine> = shown ?: lines.toList().also { shown = it }

    /**
     * The line [parsed] makes, or, should reading it fail, the text as it
     * stands, with a warning: this runs inside Forge's own game-log and event
     * handlers, and an exception thrown there broke the game it was logging.
     */
    private fun add(kind: LogKind, text: String, seenBy: Set<Int>? = null, parsed: () -> LogLine) {
        val line = runCatching(parsed).getOrElse { e ->
            Log.warn("log line not read ($kind '$text'): $e")
            write("NOTE", "WARNING log line not read: $e")
            // Kept for whom it was, a line shown to one player too: the fallback must not make it public.
            LogLine(lines.lastOrNull()?.seq?.plus(1) ?: 0, kind, text, seenBy = seenBy)
        }
        lines += line
        if (lines.size > MAX_LINES) lines.subList(0, lines.size - MAX_LINES).clear()
        shown = null
    }

    private fun players(): List<String> = game?.players?.map { it.name }.orEmpty()

    @Synchronized
    private fun drainGameLog(game: Game) {
        val entries = game.gameLog.allEntries
        for (entry in entries.drop(logLinesSeen)) {
            write("LOG", "${entry.type().caption}: ${entry.message()}")
            add(LogLines.kindOf(entry.type(), entry.message()), entry.message()) { parse.forge(entry.type(), entry.message(), players()) }
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

    private companion object {
        /** A long match's worth (a game is a few hundred lines, most of them steps); Forge's file keeps everything. */
        const val MAX_LINES = 5000
    }

    @Synchronized
    override fun close() {
        closed = true
        out.close()
    }
}
