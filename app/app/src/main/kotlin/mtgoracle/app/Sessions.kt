package mtgoracle.app

import mtgoracle.core.deck.AiCopy
import mtgoracle.core.deck.Deck
import mtgoracle.core.deck.GameType
import mtgoracle.core.deck.PlayDeck
import mtgoracle.core.model.PhaseStops
import mtgoracle.core.play.GameMode
import mtgoracle.core.play.GameRecord
import mtgoracle.core.play.MatchResult
import mtgoracle.core.play.MatchFormat
import mtgoracle.core.play.Winner
import mtgoracle.data.GameStore
import mtgoracle.forge.ForgeCards
import mtgoracle.forge.ForgeMatch
import mtgoracle.forge.ForgeRuntime
import mtgoracle.forge.MatchSpec
import mtgoracle.forge.RunningMatch
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.random.Random

/** Two decks ready to play, and what the player should know first. */
data class Prepared(
    val seat: PlayDeck,
    val opponent: PlayDeck,
    val notes: List<String>,
    /** Forge lacks cards: the game can't start until the deck is fixed (as `forge export` refuses). */
    val blocked: Boolean,
)

/**
 * Setting up, starting and recording games — the app's one use case that
 * spans data, Forge and the log (like Python's services layer, it composes).
 */
class Sessions(private val store: GameStore?, private val logDir: File) {

    fun prepare(me: Deck, opponent: Deck, useAiCopy: Boolean): Prepared {
        val seat = AiCopy.asBuilt(me)
        val opp = (if (useAiCopy) AiCopy.aiCopy(opponent) else null) ?: AiCopy.asBuilt(opponent)
        val notes = mutableListOf<String>()
        if (seat.gameType != opp.gameType) notes += "${me.name} is ${seat.gameType.name.lowercase()} and ${opponent.name} is ${opp.gameType.name.lowercase()}: they can't play each other."
        val mine = ForgeCards.check(seat)
        val theirs = ForgeCards.check(opp)
        if (mine.unknown.isNotEmpty()) notes += "Forge lacks ${mine.unknown.joinToString()} in ${me.name}; substitute or remove them in the TUI."
        if (theirs.unknown.isNotEmpty()) notes += "Forge lacks ${theirs.unknown.joinToString()} in ${opp.name}; substitute them in the TUI (`forge sub add`)."
        if (theirs.aiUnplayable.isNotEmpty()) notes += "The AI can't play ${theirs.aiUnplayable.joinToString()} in ${opp.name}; a substitution (`forge sub add`) fixes that."
        if (seat.gameType == GameType.COMMANDER) notes += "Forge plays Commander at 40 life, with 21 commander damage lethal — not Duel Commander's 20 life."
        notes += opp.notes
        return Prepared(seat, opp, notes, blocked = mine.unknown.isNotEmpty() || theirs.unknown.isNotEmpty() || seat.gameType != opp.gameType)
    }

    fun start(
        prepared: Prepared,
        mode: GameMode = GameMode.HUMAN_VS_AI,
        seed: Long = Random.nextLong(),
        stops: PhaseStops = PhaseStops.DEFAULT,
        startState: List<String>? = null,
        format: MatchFormat = MatchFormat.BO1,
    ): RunningMatch {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
        val watched = if (mode == GameMode.AI_VS_AI) MatchFormat.BO1 else format
        return ForgeMatch.start(MatchSpec(mode, prepared.seat, prepared.opponent, logDir.resolve("$stamp.log"), seed, stops, startState, watched))
    }

    /**
     * The game on when the window closed, which Forge never finished: recorded
     * as conceded (a loss), since leaving is what the player chose.
     */
    fun recordAbandoned(match: RunningMatch, turns: Int?): Long? {
        val gameNo = match.games.value.size + 1
        val result = MatchResult(Winner.OPPONENT, turns, java.time.Duration.between(match.gameStartedAt, java.time.Instant.now()).toMillis(),
            "closed the window", gameNo = gameNo, conceded = true, startedAt = match.gameStartedAt)
        return record(match, result)
    }

    /**
     * A game the app broke off (a crash): no winner and not conceded — the
     * row says the game happened, and nothing about who would have won.
     */
    fun recordUnfinished(match: RunningMatch, turns: Int?): Long? {
        val spec = match.spec
        val row = GameRecord(
            playedAt = match.gameStartedAt.truncatedTo(ChronoUnit.SECONDS), mode = spec.mode,
            deckId = spec.seat.deckId, deckName = spec.seat.name, opponentDeckId = spec.opponent.deckId, opponentName = spec.opponent.name,
            opponentAiVariant = spec.opponent.isAiCopy, seed = spec.seed, winner = null, turns = turns,
            durationMs = java.time.Duration.between(match.gameStartedAt, java.time.Instant.now()).toMillis(),
            forgeVersion = ForgeRuntime.version, logPath = spec.logFile.absolutePath,
            matchId = match.matchId, gameNo = match.games.value.size + 1, matchFormat = spec.format, conceded = false,
        )
        val id = store?.insert(row) ?: return null
        match.recorder.note("recorded as games #$id (unfinished: the app broke off)")
        return id
    }

    /** One `games` row for a finished game; null when this session doesn't record (no store). */
    fun record(match: RunningMatch, result: MatchResult): Long? {
        val spec = match.spec
        val row = GameRecord(
            playedAt = (result.startedAt ?: match.startedAt).truncatedTo(ChronoUnit.SECONDS),
            mode = spec.mode,
            deckId = spec.seat.deckId,
            deckName = spec.seat.name,
            opponentDeckId = spec.opponent.deckId,
            opponentName = spec.opponent.name,
            opponentAiVariant = spec.opponent.isAiCopy,
            seed = spec.seed,
            winner = result.winner,
            turns = result.turns,
            durationMs = result.durationMs,
            forgeVersion = ForgeRuntime.version,
            logPath = spec.logFile.absolutePath,
            matchId = match.matchId,
            gameNo = result.gameNo,
            matchFormat = spec.format,
            conceded = result.conceded,
        )
        val id = store?.insert(row) ?: return null
        match.recorder.note("recorded as games #$id")
        return id
    }
}
