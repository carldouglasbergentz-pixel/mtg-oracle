package mtgoracle.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import mtgoracle.core.deck.Deck
import mtgoracle.core.deck.DeckSummary
import mtgoracle.core.deck.PlayDeck
import mtgoracle.core.lookup.Formats
import mtgoracle.core.play.GameMode
import mtgoracle.data.GameStore
import mtgoracle.forge.Log
import mtgoracle.forge.RunningMatch
import mtgoracle.ui.library.OpponentChoice
import kotlin.concurrent.thread

/**
 * Play: the lobby's pairing and options, the match on and the simulation
 * running, and each finished game written as it ends. [show] moves the window
 * (to the lobby, to the board), [say] is its notice; [games] reads the games
 * played for the lobby's records. One match at a time, so a game and a
 * simulation exclude each other.
 */
class PlayControl(
    private val settings: Settings,
    private val sessions: () -> Sessions,
    private val decks: () -> List<DeckSummary>,
    private val deckById: (Int) -> Deck?,
    private val games: () -> GameStore?,
    private val forgeReady: () -> Boolean,
    private val show: (Screen) -> Unit,
    private val say: (String?) -> Unit,
    private val limitedControl: () -> LimitedControl? = { null },
) {
    /** The lobby's tab: limited (decks built from opened packs, against the AI's own pool) or constructed. */
    var limited by mutableStateOf(settings.lobbyLimited)
        private set

    fun toggleTab() {
        limited = !limited
        settings.lobbyLimited = limited
        val ids = tabDecks().map { it.id }
        if (lobbyMeId !in ids) lobbyMeId = ids.firstOrNull()
        chooseOpponent(opponentId)
    }

    /** The decks the lobby's tab offers as yours: limited decks on the limited tab, every other deck on the constructed one. */
    fun tabDecks(): List<DeckSummary> = decks().filter { isLimited(it) == limited }

    private fun isLimited(deck: DeckSummary) = deck.format?.let { Formats.fold(it) in Formats.LIMITED } == true

    var opponentId by mutableStateOf<Int?>(null)
    /** Your deck in the lobby: its own choice, not what the library has selected. */
    var lobbyMeId by mutableStateOf<Int?>(null)
        private set
    /** The deck you play, as the lobby has it. */
    val lobbyMe: Deck? get() = lobbyMeId?.let(deckById)
    var useAiCopy by mutableStateOf(true)
    /** Watch AI vs AI instead of playing (recorded as ai_vs_ai). */
    var watch by mutableStateOf(false)
    /** The match on the board; null once it is left. */
    var match by mutableStateOf<RunningMatch?>(null)
        internal set

    /**
     * The lobby, where your deck and the AI's are chosen side by side. [me] (the deck you are on, in the
     * library or the workspace) is yours to start with, else the last you played; the AI's is the last
     * it played, when it can face yours.
     */
    fun openLobby(me: Int?) {
        // A limited deck opens the limited tab, any other deck the constructed one.
        decks().firstOrNull { it.id == me }?.let { if (isLimited(it) != limited) toggleTab() }
        val ids = tabDecks().map { it.id }.toSet()
        lobbyMeId = listOf(me, settings.lobbyMe).firstOrNull { it != null && it in ids } ?: tabDecks().firstOrNull()?.id
        show(Screen.Lobby)
        chooseOpponent(settings.lobbyOpponent)
    }

    /** Your deck in the lobby; the opponent stays when it can still face it. */
    fun chooseMe(id: Int) {
        lobbyMeId = id
        chooseOpponent(opponentId)
    }

    private fun chooseOpponent(preferred: Int?) {
        val choices = opponents()
        opponentId = choices.firstOrNull { it.deck.id == preferred }?.deck?.id
            ?: choices.firstOrNull { it.deck.id != lobbyMeId }?.deck?.id ?: choices.firstOrNull()?.deck?.id
    }

    /** The pairing just started, chosen again the next time the lobby opens. */
    private fun keepPairing() {
        settings.lobbyMe = lobbyMeId
        settings.lobbyOpponent = opponentId
    }

    fun opponents(): List<OpponentChoice> {
        if (limited) return emptyList()
        val me = lobbyMe ?: return emptyList()
        val records = records(me)
        return decks().mapNotNull { d ->
            deckById(d.id)?.takeIf { it.gameType == me.gameType }?.let { OpponentChoice(d, it.substitutions.size, records[d.id]) }
        }
    }

    /**
     * [me]'s record against each deck, by deck id, as the lobby shows it.
     * Read once per simulated game and per game recorded, not per frame.
     */
    private fun records(me: Deck): Map<Int, String> {
        val stamp = gamesRecorded to decks()
        recordCache?.takeIf { it.first == me.id && it.second == stamp }?.let { return it.third }
        val played = try { games()?.played() } catch (e: Exception) { Log.error("could not read the games", e); null } ?: return emptyMap()
        val out = mtgoracle.core.play.Records.of(mtgoracle.core.play.DeckKey(me.id, me.name), played).mapNotNull { m ->
            val id = m.opponent.id ?: return@mapNotNull null
            val parts = listOfNotNull(
                m.played.takeIf { it.games > 0 }?.let { "you ${it.score()}" },
                m.simulated.takeIf { it.games > 0 }?.let { "AI ${it.score()}" },
            )
            id to parts.joinToString(" · ")
        }.toMap()
        recordCache = Triple(me.id, stamp, out)
        return out
    }
    private var recordCache: Triple<Int, Pair<Int, List<DeckSummary>>, Map<Int, String>>? = null
    /** Games written this session, played or simulated: the records are read again when it moves. */
    @Volatile private var gamesRecorded = 0

    fun prepared(): Prepared? {
        if (!forgeReady()) return null
        val me = lobbyMe ?: return null
        if (limited) return preparedLimited(me)
        val opp = opponentId?.let(deckById) ?: return null
        return sessions().prepare(me, opp, useAiCopy)
    }

    /** Your limited deck against the deck the AI builds from its own pool; null for a deck with no AI pool. */
    private fun preparedLimited(me: Deck): Prepared? {
        if (simulating || match != null) return null
        val control = limitedControl() ?: return null
        val key = me.id to me.poolId
        val ai = aiDeck?.takeIf { it.first == key }?.second ?: try {
            control.opponentFor(me)
        } catch (e: Exception) {
            Log.error("the AI could not build its sealed deck", e)
            null
        }.also { aiDeck = key to it }
        ai ?: return null
        return sessions().prepareLimited(me, ai, control.excess(me))
    }
    /** The AI's sealed deck for (deck, pool): Forge builds it in a fraction of a second, and the lobby asks for it on every frame. */
    private var aiDeck: Pair<Pair<Int, Int?>, PlayDeck?>? = null

    /** The AI's side of the limited match, as the lobby names it, with the deck's record against it: `AI (BLB sealed) · you 2–1 · AI 3–7`. */
    fun limitedOpponent(): String? {
        if (!limited) return null
        val me = lobbyMe ?: return null
        val ai = aiDeck?.takeIf { it.first == (me.id to me.poolId) }?.second?.name ?: return null
        return listOfNotNull(ai, recordAgainst(me, ai)).joinToString(" · ")
    }

    /** [me]'s record against the deck named [opponent] with no id of its own (the AI's sealed deck), played and simulated; null before a game. */
    private fun recordAgainst(me: Deck, opponent: String): String? {
        val played = try { games()?.played() } catch (e: Exception) { Log.error("could not read the games", e); null } ?: return null
        val m = mtgoracle.core.play.Records.of(mtgoracle.core.play.DeckKey(me.id, me.name), played)
            .firstOrNull { it.opponent.id == null && it.opponent.name.equals(opponent, ignoreCase = true) } ?: return null
        return listOfNotNull(
            m.played.takeIf { it.games > 0 }?.let { "you ${it.score()}" },
            m.simulated.takeIf { it.games > 0 }?.let { "AI ${it.score()}" },
        ).joinToString(" · ").ifEmpty { null }
    }

    /** Best of 1, 3 or 5, remembered for the next match. */
    var format by mutableStateOf(settings.matchFormat)

    fun cycleFormat() {
        format = format.next()
        settings.matchFormat = format
    }

    /** Games of each match written so far: each game is recorded once, by whoever gets there first. */
    private val recorded = java.util.IdentityHashMap<RunningMatch, Int>()

    /** The simulation running, or the last one, until the next starts: the lobby and status line show it. */
    var simulation by mutableStateOf<SimProgress?>(null)
        private set
    private var sim: Simulation? = null
    /** How many games [simulate] plays; N cycles it. */
    var simGames by mutableStateOf(Simulation.COUNTS[2])

    fun cycleSimGames() {
        simGames = Simulation.COUNTS[(Simulation.COUNTS.indexOf(simGames) + 1) % Simulation.COUNTS.size]
    }

    val simulating: Boolean get() = simulation?.running == true

    /**
     * [simGames] AI-vs-AI games of the chosen pairing, without a board, each
     * recorded as it ends. Both AIs play their AI copies unless that is off.
     */
    fun simulate() {
        if (simulating) return
        if (match != null) { say("a game is on: finish it before simulating"); return }
        val me = lobbyMe ?: return
        if (!forgeReady()) { say("Forge is still loading"); return }
        // Limited: the deck against the deck the AI builds from its own pool, as a match is; constructed: the chosen pairing, on AI copies.
        val ready = if (limited) preparedLimited(me) ?: return say("this deck has no AI pool to simulate against: open a sealed pool")
        else sessions().prepare(me, opponentId?.let(deckById) ?: return, useAiCopy, seatAiCopy = useAiCopy)
        if (ready.blocked) { say(ready.notes.firstOrNull() ?: "these decks can't play each other"); return }
        keepPairing()
        val run = Simulation(sessions(), ready, simGames, onProgress = { p -> gamesRecorded++; simulation = p; say(p.line()) })
        sim = run
        simulation = run.progress
        say(run.progress.line())
        run.start()
    }

    fun stopSimulation() {
        sim?.takeIf { simulating }?.stop()
    }

    /** Starts the chosen match; [startState] (a Forge GameState, every game) is for the tests' exact situations. */
    fun start(startState: List<String>? = null) {
        if (simulating) { say("a simulation is running (${simulation?.line()}): stop it first"); return }
        val ready = prepared()?.takeIf { !it.blocked } ?: return
        keepPairing()
        val running = sessions().start(ready, mode = if (watch) GameMode.AI_VS_AI else GameMode.HUMAN_VS_AI, stops = settings.stops, format = format, startState = startState)
        begin(running)
    }

    /**
     * A match against a person at a network table, your lobby deck against [guestDeck] (which came over the link),
     * under the two people's [names] and the lobby's match format: shown and recorded as any match, as
     * human_vs_human. Null when a game or a simulation holds Forge.
     */
    fun startNetwork(guestDeck: mtgoracle.core.deck.PlayDeck, names: Pair<String, String>, hostDeck: mtgoracle.core.deck.PlayDeck? = null): RunningMatch? {
        if (simulating || match != null) return null
        // At a sealed table the host plays the deck it was ready with; else the lobby's deck, as built.
        val mine = hostDeck ?: lobbyMe?.let(mtgoracle.core.deck.AiCopy::asBuilt) ?: return null
        if (hostDeck == null) settings.lobbyMe = lobbyMeId
        val ready = Prepared(mine, guestDeck, emptyList(), blocked = false)
        val running = sessions().start(ready, mode = GameMode.HUMAN_VS_HUMAN, stops = settings.stops, format = format, names = names)
        begin(running)
        return running
    }

    /** Shows [running] and records each of its games as it ends. */
    private fun begin(running: RunningMatch) {
        synchronized(this) { recorded[running] = 0 }
        match = running
        say(null)
        show(Screen.Playing)
        thread(name = "game-results", isDaemon = true) {
            while (true) {
                recordFinished(running)
                // Done when the match is over and written, or when shutdown took the match over.
                val written = synchronized(this) { recorded[running] } ?: break
                if (running.over && written >= running.games.value.size) break
                Thread.sleep(100)
            }
            running.recorder.close()
            synchronized(this) { recorded.remove(running) }
            if (leaving === running) { leaving = null; if (match === running) backToLobby() }
        }
    }

    @Synchronized
    private fun recordFinished(running: RunningMatch) {
        val games = running.games.value
        while ((recorded[running] ?: return) < games.size) {
            val result = games[recorded.getValue(running)]
            val id = try {
                sessions().record(running, result)
            } catch (e: Exception) {
                Log.error("could not record game ${result.gameNo}", e)
                say("game ${result.gameNo} was NOT recorded: ${e.message}")
                null
            }
            recorded[running] = recorded.getValue(running) + 1
            if (id != null) { gamesRecorded++; say("game ${result.gameNo}: ${result.summary} · recorded as games #$id") }
        }
    }

    /** Set while the player leaves the match: once its last game is recorded, back to the lobby. */
    @Volatile private var leaving: RunningMatch? = null

    /** Concede the match: the game on is conceded and recorded, no other follows, and the lobby returns. */
    fun leaveMatch() {
        val running = match ?: return
        leaving = running
        running.leave()
    }

    /**
     * The window is closing. A game still on is recorded as conceded — leaving
     * is what the player chose — unless the app broke it off ([unfinished]).
     * Nothing waits on Forge, so nothing can hang.
     */
    fun shutdown(unfinished: Boolean) {
        val running = match ?: return
        recordFinished(running)
        stopRecording(running, unfinished)
        running.recorder.close()
    }

    /** Writes the game still on (conceded, or [unfinished]), and nothing more for [running]. */
    internal fun stopRecording(running: RunningMatch, unfinished: Boolean) = synchronized(this) {
        if (recorded[running] != null && running.result.value == null && !running.over) {
            try {
                val turns = running.seat.board.value?.turn
                if (unfinished) sessions().recordUnfinished(running, turns) else sessions().recordAbandoned(running, turns)
            } catch (e: Exception) {
                Log.error("could not record the game on", e)
            }
        }
        // The game Forge may still end is recorded above: no second row.
        recorded.remove(running)
    }

    /** A match over or left: the lobby again, on the same pairing, so another game is one Start away. */
    fun backToLobby() {
        match = null
        say(null)
        openLobby(lobbyMeId)
    }
}
