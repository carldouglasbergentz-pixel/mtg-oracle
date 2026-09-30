package mtgoracle.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import mtgoracle.core.model.BoardState
import mtgoracle.core.model.InputKind
import mtgoracle.core.model.InputPrompt
import mtgoracle.core.model.Prompt
import mtgoracle.core.model.SeatAction
import mtgoracle.core.seat.ScriptedSeat
import mtgoracle.data.GameStore
import mtgoracle.data.Library
import mtgoracle.data.MtgDb
import mtgoracle.forge.ForgeRuntime
import mtgoracle.forge.Log
import mtgoracle.ui.OffscreenDriver
import mtgoracle.ui.board.BoardScreen
import mtgoracle.ui.kit.ArtImages
import mtgoracle.ui.kit.CardMode
import mtgoracle.ui.kit.LocalArt
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The headless evidence run: a scripted seat plays one of your decks against
 * the AI through the real board composable (offscreen, every decision a
 * click), on a COPY of the database — the `games` row it records goes to the
 * copy, never to data/mtg.db. Writes PNGs of the board, the log, and a summary.
 *
 * Args: --me <deck> --opponent <deck> --seed <n> --out <dir>
 */
object Scripted {
    fun run(paths: AppPaths, args: List<String>): Int {
        val opt = args.chunked(2).associate { it[0].removePrefix("--") to it.getOrElse(1) { "" } }
        val out = File(opt["out"] ?: System.getProperty("mtgoracle.evidence") ?: "build/evidence").canonicalFile.also { it.mkdirs() }
        val copy = File(out, "mtg.db")
        check(copy.canonicalFile != paths.db.canonicalFile) { "refusing to use the real database" }
        Files.copy(paths.db.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING)
        val db = MtgDb(copy).also { it.migrate(backups = null) } // a copy: no backup of it
        val library = Library(db)
        fun deck(name: String) = library.decks().firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { library.deck(it.id) }
            ?: error("no deck named '$name' (have: ${library.decks().joinToString { it.name }})")
        val me = deck(opt["me"] ?: "UW Draw Go - Control")
        val opponent = deck(opt["opponent"] ?: "Rakdos Midrange")
        val seed = opt["seed"]?.toLongOrNull() ?: 20260929L

        ForgeRuntime.initialise(paths.forge)
        val keys = (ImagePrefetch.keysFor(me) + ImagePrefetch.keysFor(opponent)).distinct()
        Log.info("art for ${keys.size} cards: ${ForgeRuntime.images.prefetch(keys)} images on disk")

        val sessions = Sessions(GameStore(db), File(out, "game_logs"))
        val prepared = sessions.prepare(me, opponent, useAiCopy = true)
        prepared.notes.forEach { Log.info("note: $it") }
        val match = sessions.start(prepared, seed = seed)
        val mode = mutableStateOf(CardMode.ART)
        val art = ArtImages(ForgeRuntime.images)
        val driver = OffscreenDriver(1800, 2200) {
            CompositionLocalProvider(LocalArt provides art) {
                BoardScreen(match.seat, "${prepared.seat.name} vs ${prepared.opponent.name} · scripted", mode.value)
            }
        }
        var clicks = 0
        var fallbacks = 0
        val pictures = mutableSetOf<String>()
        fun picture(name: String, board: BoardState) {
            if (!pictures.add(name)) return
            driver.savePng(File(out, "$name.png"))
            mode.value = CardMode.TEXT
            driver.savePng(File(out, "$name-text.png"))
            mode.value = CardMode.ART
            match.recorder.note("PNG $name at turn ${board.turn} (${board.phase})")
        }
        val seat = ScriptedSeat(match.seat, onDecision = { Log.info("seat $it") }, submit = { prompt: Prompt, action: SeatAction ->
            val board = match.seat.board.value
            if (board != null && prompt is InputPrompt) {
                val permanents = board.players.sumOf { p -> p.battlefield.count { !it.isLand } }
                if (prompt.kind == InputKind.PRIORITY && board.turn >= 5 && permanents >= 2 && board.activePlayerId == board.seat?.id) picture("board", board)
                if (prompt.kind == InputKind.TARGET) picture("board-target", board)
            }
            val made = driver.perform(prompt, action) { match.recorder.seat("  UI click #${prompt.id}: $it") }
            if (made != null) clicks++
            else if (match.seat.prompt.value?.id != prompt.id) match.recorder.seat("  (prompt #${prompt.id} moved on before the click; not answered)")
            else {
                fallbacks++
                match.recorder.seat("  UI-FALLBACK: nothing on the board to click for $action; answered directly")
                match.seat.answer(prompt.id, action)
            }
        })
        val finished = seat.play(timeoutMillis = 20 * 60_000)
        val deadline = System.currentTimeMillis() + 15_000
        while (match.result.value == null && System.currentTimeMillis() < deadline) { driver.frame(); Thread.sleep(50) }
        val result = match.result.value
        match.seat.board.value?.let { picture("board-end", it) }
        driver.close()
        val id = result?.let { sessions.record(match, it) }
        match.recorder.close()
        val summary = "game ${if (finished && result != null) "finished" else "DID NOT finish"}: ${result?.summary} after ${result?.turns} turns; " +
            "${seat.decisions} decisions, $clicks through the UI, $fallbacks answered directly; games #$id in $copy; log ${match.spec.logFile}"
        File(out, "summary.txt").writeText(summary + "\n")
        Log.info(summary)
        return if (finished && result != null && fallbacks == 0) 0 else 1
    }
}
