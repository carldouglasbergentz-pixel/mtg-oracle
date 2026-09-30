package mtgoracle.app

import mtgoracle.core.play.GameMode
import mtgoracle.core.play.MatchResult
import mtgoracle.core.play.Winner
import mtgoracle.forge.Log
import mtgoracle.forge.RunningMatch
import java.util.UUID
import kotlin.concurrent.thread

/** Where a simulation is: what the status line and the setup screen show. */
data class SimProgress(
    val me: String,
    val opponent: String,
    val total: Int,
    val played: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    val draws: Int = 0,
    val done: Boolean = false,
    val stopped: Boolean = false,
    val error: String? = null,
) {
    val running: Boolean get() = !done

    fun after(result: MatchResult) = copy(
        played = played + 1,
        wins = wins + if (result.winner == Winner.ME) 1 else 0,
        losses = losses + if (result.winner == Winner.OPPONENT) 1 else 0,
        draws = draws + if (result.winner == Winner.DRAW) 1 else 0,
    )

    /** `sim 7/20: Elminster 4 – 2 Blue Moon (1 draw)`, or how it ended. */
    fun line(): String {
        val score = "$me $wins – $losses $opponent" + if (draws > 0) " ($draws draw${if (draws == 1) "" else "s"})" else ""
        return when {
            error != null -> "simulation failed after $played game(s): $error · $score"
            stopped -> "simulation stopped after $played of $total: $score"
            done -> "simulated $played game(s): $score · `results` for the record"
            else -> "simulating ${played + 1}/$total: $score"
        }
    }
}

/**
 * [games] AI-vs-AI games between two prepared decks, one after the other and
 * without a board (Forge's own simulator, in our process). Each game is
 * recorded as it ends, so a stopped simulation keeps what it played; the
 * game on when it is stopped is not recorded, since it wasn't played out. A
 * game still going after [limitMillis] is stopped as a draw: Forge's AI can
 * loop, and its command-line simulator stops at 120 s for the same reason.
 */
class Simulation(
    private val sessions: Sessions,
    private val prepared: Prepared,
    val games: Int,
    private val onProgress: (SimProgress) -> Unit,
    private val limitMillis: Long = LIMIT_MILLIS,
) {
    /** The `games.match_id` every game of this simulation shares. */
    val id: String = UUID.randomUUID().toString()
    @Volatile private var stopping = false
    @Volatile private var current: RunningMatch? = null
    @Volatile var progress = SimProgress(prepared.seat.name, prepared.opponent.name, games)
        private set

    fun start(): Thread = thread(name = "simulation", isDaemon = true) { run() }

    /** Ends the simulation: the game on is ended (and not recorded), no other starts. */
    fun stop() {
        stopping = true
        current?.leave()
    }

    private fun report(next: SimProgress) {
        progress = next
        onProgress(next)
    }

    private fun run() {
        try {
            for (n in 1..games) {
                if (stopping) break
                val match = sessions.start(prepared, mode = GameMode.AI_VS_AI, paced = false)
                current = match
                val result = try { await(match) } finally { current = null; match.recorder.close() }
                if (result == null) break
                sessions.recordSimulated(match, result, id, n)
                report(progress.after(result))
            }
            report(progress.copy(done = true, stopped = stopping))
        } catch (e: Exception) {
            Log.error("simulation failed", e)
            report(progress.copy(done = true, error = e.message ?: e::class.simpleName))
        }
    }

    /**
     * The game's result, or null when the simulation was stopped during it.
     * Past the limit the game is ended as a draw (conceding while nobody
     * plays ends it so); if Forge doesn't end it even then, it is left and
     * counted a draw all the same.
     */
    private fun await(match: RunningMatch): MatchResult? {
        val started = System.currentTimeMillis()
        var endedAt = 0L // when we asked Forge to end the game, for the limit or for stop; 0 = not yet
        while (true) {
            match.result.value?.let { r ->
                return when {
                    stopping -> null
                    endedAt > 0 -> r.copy(winner = Winner.DRAW, conceded = false, summary = "stopped at ${limitMillis / 1000} s: a draw")
                    else -> r
                }
            }
            val now = System.currentTimeMillis()
            if (endedAt == 0L && (stopping || now - started > limitMillis)) {
                endedAt = now
                match.concede()
            }
            if (endedAt > 0 && now - endedAt > GRACE_MILLIS) {
                match.leave()
                return if (stopping) null
                else MatchResult(Winner.DRAW, match.seat.board.value?.turn, now - started, "stopped at ${limitMillis / 1000} s: Forge didn't end it", startedAt = match.gameStartedAt)
            }
            Thread.sleep(50)
        }
    }

    companion object {
        /** A game longer than this is a draw: generous for the AI, which plays a game in seconds. */
        const val LIMIT_MILLIS = 150_000L
        private const val GRACE_MILLIS = 15_000L
        /** What N cycles through on the setup screen. */
        val COUNTS = listOf(1, 5, 10, 20, 50)
    }
}
