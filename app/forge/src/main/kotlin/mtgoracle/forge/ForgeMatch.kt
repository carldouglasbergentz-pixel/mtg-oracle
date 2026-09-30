package mtgoracle.forge

import forge.LobbyPlayer
import forge.game.GameRules
import forge.game.GameState
import forge.game.GameType
import forge.game.player.RegisteredPlayer
import forge.gamemodes.match.HostedMatch
import forge.player.GamePlayerUtil
import forge.player.LobbyPlayerHuman
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import mtgoracle.core.deck.PlayDeck
import mtgoracle.core.model.GameSeat
import mtgoracle.core.model.PhaseStops
import mtgoracle.core.play.GameMode
import mtgoracle.core.play.MatchFormat
import mtgoracle.core.play.MatchResult
import mtgoracle.core.play.Winner
import java.io.File
import java.time.Instant
import java.util.EnumSet
import mtgoracle.core.deck.GameType as DeckGameType

/**
 * One game to start. [seat] is the human (or AI seat A); [opponent] is the AI.
 * [startState] is a Forge GameState (the dev-mode / puzzle format) applied as
 * turn one begins — how the tests set up exact situations.
 */
data class MatchSpec(
    val mode: GameMode,
    val seat: PlayDeck,
    val opponent: PlayDeck,
    val logFile: File,
    val seed: Long? = null,
    val stops: PhaseStops = PhaseStops.DEFAULT,
    val startState: List<String>? = null,
    /** Best of 1, 3 or 5 (a human's match; watching is always one game). */
    val format: MatchFormat = MatchFormat.BO1,
    /**
     * AI vs AI at Forge's spectator pace, which sleeps on every event so a
     * person can follow it. A simulation has nobody watching and turns it off.
     */
    val paced: Boolean = true,
)

/**
 * A running match of one or more games: the seat that plays or watches it,
 * its recording (one log for the whole match), and each game's result as it
 * ends. Between games nothing happens until [continueMatch]; Forge then asks
 * for sideboarding and has the loser choose to play or draw.
 */
class RunningMatch internal constructor(
    val spec: MatchSpec,
    private val gui: SeatGui,
    val recorder: GameRecorder,
    val startedAt: Instant,
    internal val hosted: HostedMatch,
) {
    val seat: GameSeat get() = gui
    /** The same for every game of this match: `games.match_id`. */
    val matchId: String = java.util.UUID.randomUUID().toString()

    internal val resultFlow = MutableStateFlow<MatchResult?>(null)
    /** The game just finished, until the next one starts; null while a game is on. */
    val result: StateFlow<MatchResult?> get() = resultFlow
    private val gamesFlow = MutableStateFlow<List<MatchResult>>(emptyList())
    /** Every finished game, in order. */
    val games: StateFlow<List<MatchResult>> get() = gamesFlow
    /** No game follows: the match was won, or left. */
    val over: Boolean get() = gamesFlow.value.lastOrNull()?.matchOver == true

    /** When the current (or last) game began: its `played_at`. */
    @Volatile var gameStartedAt: Instant = startedAt
        private set
    @Volatile private var leaving = false

    /** The next game: Forge asks about sideboards and play/draw, as its own match screen does. */
    fun continueMatch() {
        check(resultFlow.value != null && !over) { "no game to continue to" }
        resultFlow.value = null
        gameStartedAt = Instant.now()
        ForgeRuntime.edt.later { hosted.continueMatch() }
    }

    /** Concedes the game being played; in a match, the next one can still follow. */
    fun concede() = gui.concedeNow()

    /**
     * Leaves the match: the game on is conceded (and recorded so), and no
     * other game follows. Between games, the match simply ends here.
     */
    fun leave() {
        leaving = true
        val last = resultFlow.value
        if (last == null) gui.concedeNow()
        else if (!last.matchOver) finishWith(last.copy(matchOver = true), replaceLast = true)
    }

    internal fun gameEnded(result: MatchResult) {
        val so = gamesFlow.value
        val wins = so.count { it.winner == Winner.ME } + if (result.winner == Winner.ME) 1 else 0
        val losses = so.count { it.winner == Winner.OPPONENT } + if (result.winner == Winner.OPPONENT) 1 else 0
        val needed = spec.format.games / 2 + 1
        val over = leaving || hosted.isMatchOver || wins >= needed || losses >= needed || so.size + 1 >= spec.format.games
        finishWith(result.copy(gameNo = so.size + 1, conceded = gui.conceded, wins = wins, losses = losses, matchOver = over), replaceLast = false)
    }

    private fun finishWith(result: MatchResult, replaceLast: Boolean) {
        gamesFlow.value = (if (replaceLast) gamesFlow.value.dropLast(1) else gamesFlow.value) + result
        if (result.matchOver) {
            gui.dispose()
            if (ForgeRuntime.guiBase.activeSeat === gui) ForgeRuntime.guiBase.activeSeat = null
        }
        resultFlow.value = result
    }
}

/**
 * Starts games through Forge's `HostedMatch` — the entry point its own GUIs
 * use — so the engine sees an ordinary match with an ordinary
 * `PlayerControllerHuman` for our seat.
 */
object ForgeMatch {

    fun start(spec: MatchSpec): RunningMatch {
        check(ForgeRuntime.isInitialised) { "ForgeRuntime.initialise first" }
        require(spec.seat.gameType == spec.opponent.gameType) { "a ${spec.seat.gameType} deck can't play a ${spec.opponent.gameType} deck" }
        spec.seed?.let(ForgeRuntime::seedRandom)
        val recorder = GameRecorder(spec.logFile)
        val gui = SeatGui(recorder, ForgeRuntime.edt, spec.stops)
        ForgeRuntime.guiBase.activeSeat = gui // one game at a time: Forge's static dialogs reach this seat
        val commander = spec.seat.gameType == DeckGameType.COMMANDER

        val seatPlayer: LobbyPlayer = when (spec.mode) {
            GameMode.HUMAN_VS_AI -> LobbyPlayerHuman("You")
            GameMode.AI_VS_AI -> GamePlayerUtil.createAiPlayer("AI 1 (${spec.seat.name})", 0)
        }
        val aiPlayer = GamePlayerUtil.createAiPlayer("AI (${spec.opponent.name})", 1)
        val a = registered(spec.seat, commander).apply { player = seatPlayer }
        val b = registered(spec.opponent, commander).apply { player = aiPlayer }
        recorder.note("${spec.mode}: ${spec.seat.name} vs ${spec.opponent.name}; seed ${spec.seed ?: "none"}; Forge ${ForgeRuntime.version}")
        spec.seat.notes.plus(spec.opponent.notes).forEach(recorder::note)

        val hosted = HostedMatch()
        val running = RunningMatch(spec, gui, recorder, Instant.now(), hosted)
        val unpaced = spec.mode == GameMode.AI_VS_AI && !spec.paced
        if (spec.startState != null || unpaced) {
            hosted.setStartGameHook {
                if (unpaced) stopPacing(hosted, recorder)
                spec.startState?.let { lines ->
                    val state = GameState()
                    state.parse(lines)
                    state.applyToGame(hosted.game)
                }
            }
        }
        gui.onFinished = { running.gameEnded(result(gui, seatPlayer, running)) }

        val type = if (commander) GameType.Commander else GameType.Constructed
        val games = if (spec.mode == GameMode.AI_VS_AI) 1 else spec.format.games
        val rules = GameRules(type).apply { gamesPerMatch = games }
        when (spec.mode) {
            GameMode.HUMAN_VS_AI -> hosted.startMatch(rules, EnumSet.of(type), listOf(a, b), a, gui)
            GameMode.AI_VS_AI -> {
                // No human: HostedMatch asks GuiBase for a spectator GUI, which is our seat.
                ForgeRuntime.guiBase.spectatorFactory = { gui }
                hosted.startMatch(rules, EnumSet.of(type), listOf(a, b), emptyMap(), null)
            }
        }
        return running
    }

    /**
     * With no human in the match, HostedMatch subscribes an FControlGamePlayback
     * to the game, and it sleeps on every land, cast and resolve for a
     * spectator's eyes: a simulated game took about a minute instead of
     * seconds. Neither the playback nor the game's event bus is public, so it
     * is unsubscribed through them here, just before the game begins. If a
     * Forge release renames them, the game still plays, only at that pace.
     */
    private fun stopPacing(hosted: HostedMatch, recorder: GameRecorder) {
        try {
            val playback = HostedMatch::class.java.getDeclaredField("playbackControl").apply { isAccessible = true }.get(hosted) ?: return
            val events = forge.game.Game::class.java.getDeclaredField("events").apply { isAccessible = true }.get(hosted.game)
                as com.google.common.eventbus.EventBus
            events.unregister(playback)
        } catch (e: ReflectiveOperationException) {
            Log.warn("could not turn off Forge's spectator pacing: ${e.message}")
            recorder.note("WARNING Forge's spectator pacing is still on: ${e.message}")
        }
    }

    private fun registered(deck: PlayDeck, commander: Boolean): RegisteredPlayer {
        val forgeDeck = ForgeCards.toForgeDeck(deck)
        return if (commander) RegisteredPlayer.forCommander(forgeDeck) else RegisteredPlayer(forgeDeck)
    }

    private fun result(gui: SeatGui, seatPlayer: LobbyPlayer, running: RunningMatch): MatchResult {
        val outcome = gui.gameView?.outcome
        val winner = when {
            outcome == null || outcome.isDraw -> Winner.DRAW
            outcome.isWinner(seatPlayer) -> Winner.ME
            else -> Winner.OPPONENT
        }
        val ms = java.time.Duration.between(running.gameStartedAt, Instant.now()).toMillis()
        val summary = when (winner) {
            Winner.DRAW -> "draw"
            else -> "${outcome?.winningLobbyPlayer?.name} won"
        }
        return MatchResult(winner, outcome?.lastTurnNumber, ms, summary, startedAt = running.gameStartedAt).also {
            running.recorder.note("RESULT ${it.winner.column} after ${it.turns} turns in ${it.durationMs} ms: ${it.summary}")
        }
    }
}
