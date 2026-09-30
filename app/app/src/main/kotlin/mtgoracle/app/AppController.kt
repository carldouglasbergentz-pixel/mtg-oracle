package mtgoracle.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import mtgoracle.core.deck.Deck
import mtgoracle.core.deck.DeckCard
import mtgoracle.core.deck.DeckSummary
import mtgoracle.core.model.PhaseStops
import mtgoracle.core.play.GameMode
import mtgoracle.data.GameStore
import mtgoracle.data.Library
import mtgoracle.data.DeckWriter
import mtgoracle.data.LibraryWriter
import mtgoracle.forge.ForgeCards
import mtgoracle.data.Lookup
import mtgoracle.ui.kit.CardFace
import mtgoracle.ui.kit.face
import mtgoracle.ui.lookup.LookupUi
import mtgoracle.data.MissingDatabaseException
import mtgoracle.data.MtgDb
import mtgoracle.data.SchemaTooOldException
import mtgoracle.forge.ForgeRuntime
import mtgoracle.forge.Log
import mtgoracle.forge.RunningMatch
import mtgoracle.ui.kit.CardMode
import mtgoracle.ui.library.OpponentChoice
import mtgoracle.ui.theme.Palette
import mtgoracle.ui.theme.Theme
import mtgoracle.ui.theme.Themes
import java.io.File
import kotlin.concurrent.thread

/** `"theme": "rose-pine"` from the TUI's config.json; null when absent or unreadable. A regex: one string field needs no JSON library. */
fun tuiTheme(config: File): String? = runCatching {
    Regex("\"theme\"\\s*:\\s*\"([^\"]+)\"").find(config.readText())?.groupValues?.get(1)
}.getOrNull()

sealed interface Screen {
    data object Loading : Screen
    /** The app can't run: the message says what to do (run self_heal, build the DB...). */
    data class Blocked(val message: String) : Screen
    data object Library : Screen
    data object Setup : Screen
    data object Playing : Screen
    /** The board broke (see AppController.onCrash): what happened, and the way out. */
    data object Crashed : Screen
}

/**
 * The window's state and its transitions: library -> setup -> playing ->
 * library. Compose state, so the screens recompose as it changes; the slow
 * parts (Forge's start, a game's recording) run on their own threads.
 */
class AppController(private val paths: AppPaths) {
    val settings = Settings(paths.settings)
    var screen by mutableStateOf<Screen>(Screen.Loading)
    var decks by mutableStateOf(emptyList<DeckSummary>())
    var selectedId by mutableStateOf<Int?>(null)
    var deck by mutableStateOf<Deck?>(null)
    var mode by mutableStateOf(settings.cardMode)
    var notice by mutableStateOf<String?>(null)
    var forgeReady by mutableStateOf(false)
    var opponentId by mutableStateOf<Int?>(null)
    var useAiCopy by mutableStateOf(true)
    /** Watch AI vs AI instead of playing (recorded as ai_vs_ai). */
    var watch by mutableStateOf(false)
    var match by mutableStateOf<RunningMatch?>(null)
    /** The command line and its output (step 3); null until the database is open. */
    var lookupUi by mutableStateOf<LookupUi?>(null)
    var commands: LookupCommands? = null
        private set
    /** `quit` typed on the command line: the window's own quit path closes the app (AppContent). */
    var quitRequested by mutableStateOf(false)

    init { Palette.theme = startTheme() }

    private lateinit var library: Library
    private lateinit var sessions: Sessions
    private val deckCache = mutableMapOf<Int, Deck>()

    /** Opens the database (the schema check first), then brings Forge up in the background. */
    fun boot() {
        try {
            val db = MtgDb(paths.db)
            db.checkSchema()
            library = Library(db)
            sessions = Sessions(GameStore(db), paths.gameLogs)
            decks = library.decks()
            folders = library.folders()
            decks.firstOrNull()?.let { select(it.id) }
            val started = System.nanoTime()
            val lookup = Lookup(db)
            Log.info("lookup ready in ${(System.nanoTime() - started) / 1_000_000} ms (${lookup.names.sorted.size} card names)")
            val writer = DeckWriter(db, lookup.names, lookup.formats)
            val lookupCommands = LookupCommands(
                lookup, decks = { decks }, faceOf = { zoomFace(lookup, it) }, onEnterDeck = ::select,
                copyToClipboard = ::copyToClipboard, onQuit = { quitRequested = true },
                writer = writer, onDeckChanged = ::deckChanged, notify = { notice = it },
            )
            commands = lookupCommands
            val actions = LibraryActions(
                library, LibraryWriter(db), writer, lookup, lookupCommands.ui,
                refresh = ::refreshLibrary, deckChanged = lookupCommands::refreshDeck,
                openDeck = { id -> lookupCommands.enterDeck(id) }, openDeckId = { editing?.deckId }, leaveDeck = lookupCommands::leaveDeck,
                say = { notice = it }, show = { r -> lookupCommands.output.add(r); lookupCommands.ui.showOutput = true },
                readClipboard = { readClipboard() }, writeClipboard = ::copyToClipboard,
                printingsOf = { name -> if (forgeReady) ForgeCards.printings(name) else emptyList() },
                faceOf = { name, printing -> printingFace(lookup, name, printing) },
            )
            lookupUi = lookupCommands.ui.apply { grid = settings.resultsGrid; intent = actions::handle }
            screen = Screen.Library
        } catch (e: SchemaTooOldException) {
            screen = Screen.Blocked(e.message!!)
            return
        } catch (e: MissingDatabaseException) {
            screen = Screen.Blocked(e.message!!)
            return
        }
        thread(name = "forge-start", isDaemon = true) {
            try {
                ForgeRuntime.initialise(paths.forge)
                ForgeRuntime.onForgeError { title, text -> onCrash("Forge", RuntimeException("$title: ${text.lineSequence().firstOrNull().orEmpty()}")) }
                forgeReady = true
            } catch (e: Exception) {
                Log.error("Forge did not start", e)
                notice = "Forge did not start: ${e.message}"
            }
        }
    }

    fun deckById(id: Int): Deck? = deckCache.getOrPut(id) { library.deck(id) ?: return null }

    /** Deck [id] was changed (in the workspace): read it again, and the library's counts. */
    private fun deckChanged(id: Int) {
        deckCache.remove(id)
        decks = library.decks()
        if (selectedId == id) deck = deckById(id)
    }

    /** Every folder, the empty ones too. */
    var folders by mutableStateOf(emptyList<mtgoracle.core.deck.Folder>())

    /** The library's shape changed (a deck made, renamed, moved or deleted; a folder): read it all again. */
    private fun refreshLibrary() {
        deckCache.clear()
        decks = library.decks()
        folders = library.folders()
        val still = decks.firstOrNull { it.id == selectedId } ?: decks.firstOrNull()
        if (still != null) select(still.id) else { selectedId = null; deck = null }
    }

    /** Where the app reads a pasted list from; the tests put their own list there. */
    var readClipboard: () -> String? = {
        runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as String }.getOrNull()
    }

    /** A card in one printing, for the zoom pane while the art chooser is open. */
    private fun printingFace(lookup: Lookup, name: String, printing: mtgoracle.core.deck.Printing?): CardFace? {
        val (canonical, info) = lookup.cards.info(name) ?: return null
        val card = DeckCard(canonical, quantity = 1, isCommander = false, isSideboard = false, setCode = printing?.setCode, collectorNumber = printing?.collectorNumber, info = info)
        return card.face(keyFor(card))
    }

    fun select(id: Int) {
        selectedId = id
        deck = deckById(id)
    }

    fun keyFor(card: DeckCard): String? =
        if (forgeReady) ForgeRuntime.images.keyFor(card.name, card.setCode, card.collectorNumber) else null

    /** What the zoom pane shows of a card named in the output: its default printing's art, and its text. */
    private val zoomInfo = object : LinkedHashMap<String, Pair<String, mtgoracle.core.deck.CardInfo>?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, mtgoracle.core.deck.CardInfo>?>) = size > 256
    }

    private fun zoomFace(lookup: Lookup, name: String): CardFace? {
        val (canonical, info) = zoomInfo.getOrPut(name) { lookup.cards.info(name) } ?: return null
        val card = DeckCard(canonical, quantity = 1, isCommander = false, isSideboard = false, info = info)
        return card.face(keyFor(card)) // the key is asked for each time: Forge may have come up since
    }

    /** Where the app puts what it copies (export, `copy`); the tests keep it off the system clipboard. */
    var writeClipboard: (String) -> Unit = { text ->
        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null)
    }

    private fun copyToClipboard(text: String) = writeClipboard(text)

    /** The deck open in the workspace; null in the library. */
    val editing: mtgoracle.core.lookup.DeckScope? get() = commands?.scope
    /** Lines or frames in the workspace's deck pane. */
    var deckPaneMode by mutableStateOf(settings.deckPaneMode)

    /** Enter in the library: the selected deck, opened to work on. */
    fun edit() {
        val id = selectedId ?: return
        commands?.enterDeck(id)
    }

    fun leaveEdit() {
        commands?.leaveDeck()
    }

    /**
     * T / F7: what "text or art" means where you are. In the workspace it is
     * the search results (grid or lines); elsewhere the deck view and the board.
     */
    fun toggleMode() {
        val ui = lookupUi
        if (screen == Screen.Library && editing != null && ui != null) {
            ui.grid = !ui.grid
            settings.resultsGrid = ui.grid
            return
        }
        mode = if (mode == CardMode.ART) CardMode.TEXT else CardMode.ART
        settings.cardMode = mode
    }

    /** Shift+T in the workspace: the deck pane between lines and frames. */
    fun toggleDeckPaneMode() {
        deckPaneMode = if (deckPaneMode == CardMode.TEXT) CardMode.ART else CardMode.TEXT
        settings.deckPaneMode = deckPaneMode
    }

    /** Offline prefetch: both image kinds for every card in every deck (and its AI copy). */
    fun prefetch() {
        if (!forgeReady) { notice = "Forge is still loading"; return }
        thread(name = "prefetch", isDaemon = true) {
            val keys = decks.flatMap { d -> ImagePrefetch.keysFor(deckById(d.id) ?: return@flatMap emptyList()) }
            val have = ForgeRuntime.images.prefetch(keys) { done, total -> if (done % 20 == 0 || done == total) notice = "images $done / $total" }
            notice = "images: $have on disk"
        }
    }

    fun openSetup() {
        val me = deck ?: return
        screen = Screen.Setup
        opponentId = opponents().firstOrNull { it.deck.id != me.id }?.deck?.id ?: opponents().firstOrNull()?.deck?.id
    }

    fun opponents(): List<OpponentChoice> {
        val me = deck ?: return emptyList()
        return decks.mapNotNull { d -> deckById(d.id)?.takeIf { it.gameType == me.gameType }?.let { OpponentChoice(d, it.substitutions.size) } }
    }

    fun prepared(): Prepared? {
        if (!forgeReady) return null
        val me = deck ?: return null
        val opp = opponentId?.let(::deckById) ?: return null
        return sessions.prepare(me, opp, useAiCopy)
    }

    /** Best of 1, 3 or 5, remembered for the next match. */
    var format by mutableStateOf(settings.matchFormat)

    fun cycleFormat() {
        format = format.next()
        settings.matchFormat = format
    }

    /** Games of each match written so far: each game is recorded once, by whoever gets there first. */
    private val recorded = java.util.IdentityHashMap<RunningMatch, Int>()

    /** Starts the chosen match; [startState] (a Forge GameState, every game) is for the tests' exact situations. */
    fun start(startState: List<String>? = null) {
        val ready = prepared()?.takeIf { !it.blocked } ?: return
        val running = sessions.start(ready, mode = if (watch) GameMode.AI_VS_AI else GameMode.HUMAN_VS_AI, stops = settings.stops, format = format, startState = startState)
        begin(running)
    }

    /** Shows [running] and records each of its games as it ends. */
    private fun begin(running: RunningMatch) {
        synchronized(this) { recorded[running] = 0 }
        match = running
        notice = null
        screen = Screen.Playing
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
            if (leaving === running) { leaving = null; if (match === running) backToLibrary() }
        }
    }

    @Synchronized
    private fun recordFinished(running: RunningMatch) {
        val games = running.games.value
        while ((recorded[running] ?: return) < games.size) {
            val result = games[recorded.getValue(running)]
            val id = try {
                sessions.record(running, result)
            } catch (e: Exception) {
                Log.error("could not record game ${result.gameNo}", e)
                notice = "game ${result.gameNo} was NOT recorded: ${e.message}"
                null
            }
            recorded[running] = recorded.getValue(running) + 1
            if (id != null) notice = "game ${result.gameNo}: ${result.summary} · recorded as games #$id"
        }
    }

    /** Set while the player leaves the match: once its last game is recorded, back to the library. */
    @Volatile private var leaving: RunningMatch? = null

    /** Concede the match: the game on is conceded and recorded, no other follows, and the library returns. */
    fun leaveMatch() {
        val running = match ?: return
        leaving = running
        running.leave()
    }

    /**
     * The window is closing. A game still on is recorded as conceded — leaving
     * is what the player chose — unless the app broke it off, which makes it
     * unfinished. Nothing waits on Forge, so nothing can hang.
     */
    fun shutdown() {
        val running = match ?: return
        recordFinished(running)
        stopRecording(running, unfinished = crashed === running)
        running.recorder.close()
    }

    /** Writes the game still on (conceded, or [unfinished]), and nothing more for [running]. */
    private fun stopRecording(running: RunningMatch, unfinished: Boolean) = synchronized(this) {
        if (recorded[running] != null && running.result.value == null && !running.over) {
            try {
                val turns = running.seat.board.value?.turn
                if (unfinished) sessions.recordUnfinished(running, turns) else sessions.recordAbandoned(running, turns)
            } catch (e: Exception) {
                Log.error("could not record the game on", e)
            }
        }
        // The game Forge may still end is recorded above: no second row.
        recorded.remove(running)
    }

    /** What went wrong, for the crash screen; null when nothing did. */
    var crash by mutableStateOf<String?>(null)
    /** A new window after a crash in the old one's composition (Main keys the window on it). */
    var windowEpoch by mutableStateOf(0)
    @Volatile private var crashed: RunningMatch? = null

    /**
     * An error the app didn't expect, anywhere: logged in full to the app log
     * (and the game's), shown, and never recorded as a concession. A broken
     * window gives way to the crash screen in a fresh one; an error elsewhere
     * leaves the game running with an error line on the board.
     */
    fun onCrash(where: String, error: Throwable) {
        Log.error("CRASH in $where", error)
        match?.recorder?.note("APP CRASH in $where: ${Log.trace(error).replace("\n", " | ")}")
        crashed = match
        val line = "${error::class.simpleName}: ${error.message ?: "no message"}"
        if (where == "window") {
            crash = line
            screen = Screen.Crashed
            windowEpoch++
        } else {
            notice = "error in $where: $line — the trace is in ${paths.appLog}"
        }
    }

    /** From the crash screen: the game on is recorded as unfinished, the match left, the library back. */
    fun leaveAfterCrash() {
        crash = null
        val running = match
        if (running != null) {
            stopRecording(running, unfinished = true)
            if (!running.over) running.leave()
            running.recorder.close()
        }
        backToLibrary()
    }

    /** From the crash screen: try the board again (the game is still on). */
    /** Where the app log is, for the crash screen. */
    val appLogPath: String get() = paths.appLog.absolutePath

    fun retryBoard() {
        crash = null
        screen = if (match != null) Screen.Playing else Screen.Library
    }

    /** The theme to start in: the one picked here, else the TUI's (the user runs rose-pine there), else the house one. */
    fun startTheme(): Theme = Themes.byKey(settings.theme) ?: Themes.fromTextual(tuiTheme(paths.tuiConfig)) ?: Themes.HOUSE

    /** F8: the next theme, everywhere at once, and remembered. */
    fun cycleTheme() {
        val next = Themes.ALL[(Themes.ALL.indexOf(Palette.theme) + 1) % Themes.ALL.size]
        Palette.theme = next
        settings.theme = next.key
    }

    fun saveStops(stops: PhaseStops) {
        if (stops != settings.stops) settings.stops = stops
    }

    fun backToLibrary() {
        match = null
        notice = null
        screen = Screen.Library
    }
}
