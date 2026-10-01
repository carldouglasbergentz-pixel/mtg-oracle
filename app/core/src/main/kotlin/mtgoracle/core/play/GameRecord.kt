package mtgoracle.core.play

import java.time.Instant

enum class GameMode(val column: String) { HUMAN_VS_AI("human_vs_ai"), AI_VS_AI("ai_vs_ai") }

/** `games.winner`: seat A (the human, or the first AI) is "me". */
enum class Winner(val column: String) { ME("me"), OPPONENT("opponent"), DRAW("draw") }

/**
 * One row of the `games` table — its columns are schema version 1's
 * (data/Schema.kt). `deck*` is the human seat, or
 * seat A in an AI-vs-AI game.
 */
data class GameRecord(
    val playedAt: Instant,
    val mode: GameMode,
    val deckId: Int?,
    val deckName: String,
    val opponentDeckId: Int?,
    val opponentName: String,
    val opponentAiVariant: Boolean,
    val seed: Long?,
    val winner: Winner?,
    val turns: Int?,
    val durationMs: Long?,
    val forgeVersion: String?,
    val logPath: String?,
    /** The same for every game of one match; null for a game played alone. */
    val matchId: String? = null,
    val gameNo: Int? = null,
    val matchFormat: MatchFormat? = null,
    /** This seat conceded the game (the winner is then the opponent). */
    val conceded: Boolean = false,
    /** Seat A played its AI copy: only in a simulation, since the human plays the deck as built. */
    val deckAiVariant: Boolean = false,
)

/** Best of 1, 3 or 5: `games.match_format`. */
enum class MatchFormat(val column: String, val games: Int, val label: String) {
    BO1("bo1", 1, "best of 1"), BO3("bo3", 3, "best of 3"), BO5("bo5", 5, "best of 5");

    fun next(): MatchFormat = entries[(ordinal + 1) % entries.size]
}

/**
 * How one game ended, as the engine reports it, and where the match stands
 * after it: [wins] and [losses] count this seat's games so far.
 */
data class MatchResult(
    val winner: Winner,
    val turns: Int?,
    val durationMs: Long,
    val summary: String,
    val gameNo: Int = 1,
    val conceded: Boolean = false,
    val wins: Int = if (winner == Winner.ME) 1 else 0,
    val losses: Int = if (winner == Winner.OPPONENT) 1 else 0,
    /** No game follows: someone won the match, or the seat left it. */
    val matchOver: Boolean = true,
    /** When this game began: its `played_at`. */
    val startedAt: Instant? = null,
)
