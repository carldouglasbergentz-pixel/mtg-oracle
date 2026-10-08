package mtgoracle.app

import mtgoracle.core.deck.AiCopy
import mtgoracle.core.deck.Deck
import mtgoracle.core.deck.GameType
import mtgoracle.core.deck.PlayDeck
import mtgoracle.core.limited.PoolRule
import mtgoracle.core.limited.Sealed
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

    /**
     * The newest [keep] game logs stay, the rest go: a game writes about a megabyte, and nothing pruned them.
     * Named by when they began (`20261005-140054-705.log`), so the name's order is the time's. A games row
     * keeps its `log_path` when its file has gone. Returns how many went.
     */
    fun pruneLogs(keep: Int = KEEP_LOGS): Int {
        val logs = logDir.listFiles { f -> f.isFile && f.name.endsWith(".log") }?.sortedByDescending { it.name }.orEmpty()
        return logs.drop(keep).count { it.delete() }
    }

    /** [seatAiCopy]: seat A plays its AI copy too, as in a simulation; a human plays the deck as built. */
    fun prepare(me: Deck, opponent: Deck, useAiCopy: Boolean, seatAiCopy: Boolean = false): Prepared {
        val seat = (if (seatAiCopy) AiCopy.aiCopy(me) else null) ?: AiCopy.asBuilt(me)
        val opp = (if (useAiCopy) AiCopy.aiCopy(opponent) else null) ?: AiCopy.asBuilt(opponent)
        return check(me.name, seat, opponent.name, opp, seatAiCopy)
    }

    /** A limited deck against the deck the AI built from its pool: basic lands to sideboard with, and the checks of any match. */
    fun prepareLimited(me: Deck, ai: PlayDeck, excess: List<PoolRule.Excess>): Prepared {
        val ready = check(me.name, Sealed.withSideboardBasics(AiCopy.asBuilt(me)), ai.name, ai, seatAiCopy = false)
        val problem = ForgeMatch.limitedProblem(ready.seat)?.let { "Forge says ${me.name} isn't a legal limited deck: $it" }
        val outside = excess.takeIf { it.isNotEmpty() }?.let { "${me.name} holds more than its pool opened (${it.joinToString("; ")}): it plays, but it isn't the pool's deck." }
        return ready.copy(notes = listOfNotNull(problem, outside) + ready.notes, blocked = ready.blocked || problem != null)
    }

    private fun check(meName: String, seat: PlayDeck, opponentName: String, opp: PlayDeck, seatAiCopy: Boolean): Prepared {
        val notes = mutableListOf<String>()
        if (seat.gameType != opp.gameType) notes += "$meName is ${seat.gameType.label} and $opponentName is ${opp.gameType.label}: they can't play each other."
        val mine = ForgeCards.check(seat)
        val theirs = ForgeCards.check(opp)
        val how = "open the deck, right-click the card, AI substitute..."
        if (mine.unknown.isNotEmpty()) notes += "Forge lacks ${mine.unknown.joinToString()} in $meName; replace them in the deck, or give its AI copy a substitute ($how)."
        if (theirs.unknown.isNotEmpty()) notes += "Forge lacks ${theirs.unknown.joinToString()} in $opponentName; give its AI copy a substitute ($how)."
        if (theirs.aiUnplayable.isNotEmpty()) notes += "The AI can't play ${theirs.aiUnplayable.joinToString()} in $opponentName; a substitute fixes that ($how)."
        if (seatAiCopy && mine.aiUnplayable.isNotEmpty()) notes += "The AI can't play ${mine.aiUnplayable.joinToString()} in $meName either; a substitute fixes that ($how)."
        if (seat.gameType == GameType.COMMANDER) notes += "Forge plays Commander at 40 life, with 21 commander damage lethal; a deck whose format is duel plays Duel Commander at 20."
        if (seat.gameType == GameType.DUEL_COMMANDER && seat.gameType == opp.gameType) {
            ForgeMatch.duelCommanderProblem(seat)?.let { notes += "Forge says $meName isn't a legal Duel Commander deck: $it" }
            ForgeMatch.duelCommanderProblem(opp)?.let { notes += "Forge says $opponentName isn't a legal Duel Commander deck: $it" }
        }
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
        /** False for a simulation: no spectator, so no pause on every event. */
        paced: Boolean = true,
        /** HUMAN_VS_HUMAN: the two people's names, the host's first. */
        names: Pair<String, String>? = null,
    ): RunningMatch {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
        val watched = if (mode == GameMode.AI_VS_AI) MatchFormat.BO1 else format
        val spec = MatchSpec(mode, prepared.seat, prepared.opponent, logDir.resolve("$stamp.log"), seed, stops, startState, watched, paced)
        return ForgeMatch.start(names?.let { (host, guest) -> spec.copy(seatName = host, guestName = guest) } ?: spec)
    }

    /**
     * Why a guest's [deck] can't sit at a table where the host plays [host],
     * or null when it can: what a local game would refuse, said to the guest.
     */
    fun judgeGuest(host: PlayDeck, deck: PlayDeck): String? {
        if (deck.gameType != host.gameType) return "This table plays ${host.gameType.label}, and ${deck.name} is ${deck.gameType.label}."
        val unknown = ForgeCards.check(deck).unknown
        return if (unknown.isEmpty()) null else "The host's Forge lacks ${unknown.joinToString()}, so ${deck.name} can't be played there."
    }

    /**
     * [row] into `games`. Two people's game: the opponent is the other person's deck, named `deck (person)`, with no
     * deck id, since the guest's id is a row in the guest's database, not this one.
     */
    private fun insert(match: RunningMatch, row: GameRecord): Long? {
        val spec = match.spec
        val stored = if (row.mode != GameMode.HUMAN_VS_HUMAN) row else row.copy(
            opponentDeckId = null, opponentAiVariant = false,
            opponentName = "${spec.opponent.name} (${ForgeMatch.tableName(spec.seatName, spec.guestName)})",
        )
        return store?.insert(stored)
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
            deckId = spec.seat.storedId, deckName = spec.seat.name,
            opponentDeckId = spec.opponent.storedId, opponentName = spec.opponent.name,
            opponentAiVariant = spec.opponent.isAiCopy, seed = spec.seed, winner = null, turns = turns,
            durationMs = java.time.Duration.between(match.gameStartedAt, java.time.Instant.now()).toMillis(),
            forgeVersion = ForgeRuntime.version, logPath = spec.logFile.absolutePath,
            matchId = match.matchId, gameNo = match.games.value.size + 1, matchFormat = spec.format, conceded = false,
        )
        val id = insert(match, row) ?: return null
        match.recorder.note("recorded as games #$id (unfinished: the app broke off)")
        return id
    }

    /**
     * One simulated game: `ai_vs_ai`, in the simulation [simId] as game
     * [gameNo], with no best-of (a simulation is a count of games, not a
     * match), and whether each side played its AI copy.
     */
    fun recordSimulated(match: RunningMatch, result: MatchResult, simId: String, gameNo: Int): Long? {
        val spec = match.spec
        val row = GameRecord(
            playedAt = (result.startedAt ?: match.startedAt).truncatedTo(ChronoUnit.SECONDS), mode = GameMode.AI_VS_AI,
            deckId = spec.seat.storedId, deckName = spec.seat.name,
            opponentDeckId = spec.opponent.storedId, opponentName = spec.opponent.name,
            opponentAiVariant = spec.opponent.isAiCopy, seed = spec.seed, winner = result.winner, turns = result.turns,
            durationMs = result.durationMs, forgeVersion = ForgeRuntime.version, logPath = spec.logFile.absolutePath,
            matchId = simId, gameNo = gameNo, matchFormat = null, conceded = false, deckAiVariant = spec.seat.isAiCopy,
        )
        val id = insert(match, row) ?: return null
        match.recorder.note("recorded as games #$id (simulation $simId, game $gameNo)")
        return id
    }

    /**
     * A game played as the guest at someone's network table: your [deck] against the host's, named
     * `deck (host)`, as the host's outcome told it from your side.
     */
    fun recordAsGuest(deck: PlayDeck, host: String, hostDeck: String, outcome: mtgoracle.net.GameOutcome, format: MatchFormat?, matchId: String, startedAt: java.time.Instant): Long? {
        val row = GameRecord(
            playedAt = startedAt.truncatedTo(ChronoUnit.SECONDS), mode = GameMode.HUMAN_VS_HUMAN,
            deckId = deck.deckId, deckName = deck.name, opponentDeckId = null, opponentName = "$hostDeck ($host)",
            opponentAiVariant = false, seed = null, winner = outcome.winner.takeIf { !outcome.unfinished }, turns = outcome.turns,
            durationMs = java.time.Duration.between(startedAt, java.time.Instant.now()).toMillis(),
            forgeVersion = null, logPath = null, matchId = matchId, gameNo = outcome.gameNo, matchFormat = format, conceded = false,
        )
        return store?.insert(row)
    }

    /** One `games` row for a finished game; null when this session doesn't record (no store). */
    fun record(match: RunningMatch, result: MatchResult): Long? {
        val spec = match.spec
        val row = GameRecord(
            playedAt = (result.startedAt ?: match.startedAt).truncatedTo(ChronoUnit.SECONDS),
            mode = spec.mode,
            deckId = spec.seat.storedId,
            deckName = spec.seat.name,
            opponentDeckId = spec.opponent.storedId,
            opponentName = spec.opponent.name,
            opponentAiVariant = spec.opponent.isAiCopy,
            seed = spec.seed,
            // Broken off (the other person's link went, the app broke): the game happened, and nobody won it.
            winner = result.winner.takeIf { !result.brokenOff },
            turns = result.turns,
            durationMs = result.durationMs,
            forgeVersion = ForgeRuntime.version,
            logPath = spec.logFile.absolutePath,
            matchId = match.matchId,
            gameNo = result.gameNo,
            matchFormat = spec.format,
            conceded = result.conceded,
        )
        val id = insert(match, row) ?: return null
        match.recorder.note("recorded as games #$id")
        return id
    }
}

/** Game logs kept in `data/game_logs/`: a hundred games' worth, about 100 MB. */
const val KEEP_LOGS = 100
