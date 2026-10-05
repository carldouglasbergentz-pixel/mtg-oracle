package mtgoracle.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import mtgoracle.core.analysis.DeckInsight
import mtgoracle.core.deck.Deck
import mtgoracle.core.deck.DeckCard
import mtgoracle.core.deck.DeckSummary
import mtgoracle.core.model.PhaseStops
import mtgoracle.core.play.GameMode
import mtgoracle.data.Analysis
import mtgoracle.data.Substitutions
import mtgoracle.data.GameStore
import mtgoracle.data.Library
import mtgoracle.data.DeckWriter
import mtgoracle.data.LibraryWriter
import mtgoracle.forge.ForgeCards
import mtgoracle.data.Lookup
import mtgoracle.ui.kit.CardFace
import mtgoracle.ui.kit.face
import mtgoracle.ui.lookup.LookupUi
import mtgoracle.data.MtgDb
import mtgoracle.data.SchemaTooNewException
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
    /** The app can't run: the message says what to do (an old or a newer database). */
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
    /** `{U}{W}` for a commander deck, `9/10 pts` in a points format; read with the analysis, not per frame. */
    private fun badges() {
        val lookup = currentLookup ?: return
        val d = deck ?: run { deckPoints = emptyMap(); deckBadges = emptyList(); return }
        val scope = runCatching { lookup.deckScope(d.id) }.getOrNull()
        val info = scope?.format
        deckPoints = info?.pointsBudget?.let { lookup.points(info.key) } ?: emptyMap()
        val spent = d.cards.filter { !it.isSideboard }.sumOf { (deckPoints[it.name.lowercase()] ?: 0) * it.quantity }
        deckBadges = listOfNotNull(
            scope?.commanderCi?.let { ci -> if (ci.isEmpty()) "{C}" else ci.joinToString("") { "{$it}" } },
            info?.pointsBudget?.let { "$spent/$it pts" + if (spent > it) "!" else "" },
        )
    }
    private var currentLookup: Lookup? = null

    /** The selected deck's points list (lower-cased name -> points) and its title's badges: what the library shows. */
    var deckPoints by mutableStateOf<Map<String, Int>>(emptyMap())
        private set
    var deckBadges by mutableStateOf<List<String>>(emptyList())
        private set

    /** The analysis block's numbers for [deck], computed again whenever it changes. */
    var insight by mutableStateOf<DeckInsight?>(null)
        private set
    var mode by mutableStateOf(settings.cardMode)
    /** Text or art on the board, toggled only there (T, F7) and shown in its status line. */
    var boardMode by mutableStateOf(settings.boardCardMode)
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
    private var analysis: Analysis? = null
    private lateinit var sessions: Sessions
    // Read by the prefetch thread while the UI thread clears it.
    private val deckCache = java.util.concurrent.ConcurrentHashMap<Int, Deck>()
    /** Each deck's analysis block until the deck changes: arrowing through the list re-reads nothing. */
    private val insightCache = mutableMapOf<Int, DeckInsight>()

    private var db: MtgDb? = null

    /**
     * The lookup and everything that reads its caches (card names, formats,
     * the search vocabulary, the classifier's cache): built at start, and
     * again after a sync changed the cards, with [carry]'s scrollback,
     * command line and open deck kept.
     */
    private fun buildLookup(db: MtgDb, carry: LookupCommands?) {
        val started = System.nanoTime()
        val lookup = Lookup(db)
        Log.info("lookup ready in ${(System.nanoTime() - started) / 1_000_000} ms (${lookup.names.sorted.size} card names)")
        val writer = DeckWriter(db, lookup.names, lookup.formats)
        val lookupCommands = LookupCommands(
            lookup, decks = { decks }, faceOf = { zoomFace(lookup, it) }, onEnterDeck = ::select,
            copyToClipboard = ::copyToClipboard, onQuit = { quitRequested = true },
            writer = writer, onDeckChanged = ::deckChanged, notify = { notice = it },
            selectedDeck = { selectedId }, sync = { force, only -> sync(force, only) }, autoSync = ::autoSyncSetting,
            onCardsChanged = { commands?.let { current -> buildLookup(db, carry = current) } },
            output = carry?.output ?: mtgoracle.ui.lookup.OutputLog(), command = carry?.ui?.command ?: mtgoracle.ui.lookup.CommandLineState(),
        )
        analysis = lookup.analysis
        lookupGames = lookup.games
        currentLookup = lookup
        zoomInfo.clear()
        insightCache.clear()
        analyse()
        commands = lookupCommands
        val actions = LibraryActions(
            library, LibraryWriter(db), writer, lookup, lookupCommands.ui,
            refresh = ::refreshLibrary, deckChanged = lookupCommands::refreshDeck,
            openDeck = { id -> lookupCommands.enterDeck(id) }, openDeckId = { editing?.deckId }, leaveDeck = lookupCommands::leaveDeck,
            say = { notice = it }, show = { r -> lookupCommands.output.add(r); lookupCommands.ui.showOutput = true },
            readClipboard = { readClipboard() }, writeClipboard = ::copyToClipboard,
            // Scryfall's paper printings once the printings source has synced; Forge's own list before that.
            printingsOf = { name -> lookup.printings.forCard(name).map { it.printing }.ifEmpty { if (forgeReady) ForgeCards.printings(name) else emptyList() } },
            printingKnown = { name, set, number ->
                when {
                    lookup.printings.find(name, set, number) != null -> true
                    forgeReady && ForgeCards.hasPrinting(name, set, number) -> true
                    lookup.printings.isEmpty() -> null // before the first printings sync nothing can be told
                    else -> false
                }
            },
            faceOf = { name, printing -> printingFace(lookup, name, printing) },
            substitutions = Substitutions(db),
            exports = paths.exports,
            forgeSupport = { name -> if (forgeReady) ForgeCards.support(name) else null },
        )
        lookupUi = lookupCommands.ui.apply {
            grid = carry?.ui?.grid ?: settings.resultsGrid
            showOutput = carry?.ui?.showOutput ?: false
            intent = actions::handle
        }
        // The new commands have no last deck: entered as new, the deck would lose the output (prune's own report) that was just carried over.
        carry?.scope?.let { lookupCommands.enterDeck(it.deckId, carried = true) }
    }

    /** The sync running, if one is; its log line is the notice. */
    @Volatile private var syncing = false
    /** The time, for the sync's record; the tests move it. */
    var clock: () -> java.time.Instant = java.time.Instant::now
    /** What the status line says about the sync while nothing else is said: a failure, or a stale sync (AutoSync.status). */
    var syncWarning by mutableStateOf<String?>(null)
        private set
    /** Where the network is for a sync; the tests serve files instead. */
    var upstream: mtgoracle.data.sync.Upstream = mtgoracle.data.sync.HttpUpstream()

    /**
     * The data pipeline in the background: [only]'s sources, skipping those
     * whose upstream hasn't moved unless [force]. Its report goes to the
     * output; when it changed the cards, the lookup is built again so search
     * and names see them. Decks are never touched.
     */
    fun sync(force: Boolean = false, only: Set<mtgoracle.core.sync.Source> = mtgoracle.core.sync.Source.entries.toSet(), auto: Boolean = false) {
        val database = db ?: return
        if (syncing) { notice = "a sync is running already"; return }
        syncing = true
        notice = if (auto) "sync (daily): starting" else "sync: starting"
        thread(name = "sync", isDaemon = true) {
            val report = try {
                mtgoracle.data.sync.Sync(database, upstream, paths.data.resolve("raw"), paths.formats, log = { notice = "sync: $it" }).run(force, only)
            } catch (e: Exception) {
                Log.error("sync failed", e)
                null
            } finally {
                syncing = false
            }
            java.awt.EventQueue.invokeLater {
                recordSync(only, failed = report?.failures?.map { it.first }?.toSet() ?: only)
                val current = commands ?: return@invokeLater
                if (report == null) { notice = "sync failed: see ${paths.appLog}"; return@invokeLater }
                // Always: a points change (4 to 3) keeps the row count, so the report can't tell; a rebuild is ~130 ms.
                buildLookup(database, carry = current)
                commands?.output?.add(mtgoracle.ui.lookup.renderSyncReport(report))
                // A daily run reports to the output without opening it over what you are looking at.
                if (!auto) lookupUi?.showOutput = true
                notice = if (report.failures.isEmpty()) "sync done" + if (report.touched) "" else ": everything was up to date"
                else "sync done, ${report.failures.size} source(s) failed: ${report.failures.joinToString { it.first.key }}"
            }
        }
    }

    /** A sync of [ran] finished, [failed] among them: kept in the settings, so the warning outlives a restart. */
    private fun recordSync(ran: Set<mtgoracle.core.sync.Source>, failed: Set<mtgoracle.core.sync.Source>) {
        val now = clock()
        settings.syncLast = now
        if (mtgoracle.core.sync.Source.COMBOS in ran && mtgoracle.core.sync.Source.COMBOS !in failed) settings.syncLastCombos = now
        settings.syncFailed = settings.syncFailed.filterKeys { it !in ran } + failed.associateWith { settings.syncFailed[it] ?: now }
        refreshSyncWarning()
    }

    private fun refreshSyncWarning() {
        syncWarning = mtgoracle.core.sync.AutoSync.status(clock(), settings.syncLast, settings.syncFailed)
    }

    /**
     * The daily sync, if it is due (AutoSync.due) and nothing would be in its
     * way: auto-sync on, no sync running, and no game or simulation, whose
     * writes would wait on the sync's long transactions. True when it started.
     */
    fun autoSyncIfDue(): Boolean {
        if (!settings.autoSync || db == null || syncing || match != null || simulation != null) return false
        val sources = mtgoracle.core.sync.AutoSync.due(clock(), settings.syncLast, settings.syncLastCombos)
        if (sources.isEmpty()) return false
        Log.info("daily sync: ${sources.joinToString { it.key }}")
        sync(only = sources, auto = true)
        return true
    }

    /** Looks every hour whether the daily sync is due, the first time a minute after start. Only the window starts it, never a test. */
    fun startAutoSync() {
        val timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "auto-sync").apply { isDaemon = true } }
        timer.scheduleAtFixedRate({ java.awt.EventQueue.invokeLater { autoSyncIfDue() } }, 1, 60, java.util.concurrent.TimeUnit.MINUTES)
    }

    /** `autosync on|off`, or with null what it is: the answer for the output. */
    fun autoSyncSetting(on: Boolean?): String {
        if (on != null) settings.autoSync = on
        val last = settings.syncLast?.let { " · last sync ${java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(java.time.ZoneId.systemDefault()).format(it)}" }.orEmpty()
        return if (settings.autoSync) "daily sync: on (Spellbook weekly)$last" else "daily sync: off · `sync` fetches by hand$last"
    }

    /** Opens the database (migrating it to this build's schema first), then brings Forge up in the background. */
    fun boot() {
        try {
            val created = !paths.db.exists()
            val db = if (created) MtgDb.create(paths.db) else MtgDb(paths.db)
            val migrated = db.migrate(paths.backups)
            if (migrated.changed) {
                Log.info("schema v${migrated.from} -> v${migrated.to}: ${migrated.lines.joinToString("; ")}")
                notice = "database schema updated to version ${migrated.to}" + (migrated.backup?.let { "; backup in ${it.name}" } ?: "")
            }
            if (created) {
                Log.info("created an empty database at ${paths.db}")
                notice = "a new, empty database: the daily sync fetches the cards, rules and combos within a minute (a few minutes in all), or press [ Sync ]"
            }
            library = Library(db)
            sessions = Sessions(GameStore(db), paths.gameLogs)
            decks = library.decks()
            folders = library.folders()
            decks.firstOrNull()?.let { select(it.id) }
            this.db = db
            buildLookup(db, carry = null)
            refreshSyncWarning()
            screen = Screen.Library
        } catch (e: SchemaTooOldException) {
            screen = Screen.Blocked(e.message!!)
            return
        } catch (e: SchemaTooNewException) {
            screen = Screen.Blocked(e.message!!)
            return
        } catch (e: Exception) {
            // A locked or corrupt file, a backup that couldn't be written: a window that says so, not none at all.
            Log.error("could not open ${paths.db}", e)
            screen = Screen.Blocked("Couldn't open ${paths.db}: ${e.message ?: e::class.simpleName}. The full trace is in ${paths.appLog}.")
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
        insightCache.remove(id)
        decks = library.decks()
        if (selectedId == id) {
            deck = deckById(id)
            analyse()
        }
    }

    /** Every folder, the empty ones too. */
    var folders by mutableStateOf(emptyList<mtgoracle.core.deck.Folder>())

    /** The library's shape changed (a deck made, renamed, moved or deleted; a folder): read it all again. */
    private fun refreshLibrary() {
        deckCache.clear()
        insightCache.clear()
        decks = library.decks()
        folders = library.folders()
        val still = decks.firstOrNull { it.id == selectedId } ?: decks.firstOrNull()
        if (still != null) select(still.id) else { selectedId = null; deck = null; insight = null }
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
        analyse()
    }

    /**
     * The analysis block for the selected deck. On the UI thread: a hundred
     * cards classify in a few milliseconds, and a block a frame late would
     * show the numbers from before the edit.
     */
    private fun analyse() {
        badges()
        val a = analysis ?: return
        val d = deck ?: return run { insight = null }
        insight = try {
            insightCache.getOrPut(d.id) { a.insight(d.id, d.name) }
        } catch (e: Exception) {
            Log.error("analysis of ${d.name} failed", e)
            notice = "analysis failed: ${e.message}"
            null
        }
    }

    fun keyFor(card: DeckCard): String? = shownArt.keyFor(card.name, card.setCode, card.collectorNumber)

    /** Forge's images, and Scryfall's for a printing Forge lacks: what every screen draws with once Forge is up. */
    val art: PrintingArt by lazy { PrintingArt(ForgeRuntime.images, { currentLookup?.printings }, paths.home.resolve("scryfall")) }

    /** What the window draws with: [art] once Forge is up, and before that the images the index knows are on disk. */
    val shownArt: IndexedArt by lazy { IndexedArt(ArtIndex(paths.home.resolve("art-index.tsv"))) { if (forgeReady) art else null } }

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
     * T / F7: what "text or art" means where you are. In a game it is the
     * board; in the workspace the search results (grid or lines); in the
     * library the deck view.
     */
    fun toggleMode() {
        if (screen == Screen.Playing) {
            boardMode = if (boardMode == CardMode.ART) CardMode.TEXT else CardMode.ART
            settings.boardCardMode = boardMode
            return
        }
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
        val records = records(me)
        return decks.mapNotNull { d ->
            deckById(d.id)?.takeIf { it.gameType == me.gameType }?.let { OpponentChoice(d, it.substitutions.size, records[d.id]) }
        }
    }

    /**
     * [me]'s record against each deck, by deck id, as the setup screen shows
     * it. Read once per simulated game and per game recorded, not per frame.
     */
    private fun records(me: Deck): Map<Int, String> {
        val stamp = gamesRecorded to decks
        recordCache?.takeIf { it.first == me.id && it.second == stamp }?.let { return it.third }
        val games = try { lookupGames?.played() } catch (e: Exception) { Log.error("could not read the games", e); null } ?: return emptyMap()
        val out = mtgoracle.core.play.Records.of(mtgoracle.core.play.DeckKey(me.id, me.name), games).mapNotNull { m ->
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
    private var lookupGames: mtgoracle.data.GameStore? = null

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

    /** The simulation running, or the last one, until the next starts: the setup screen and status line show it. */
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
     * One match at a time in Forge, so a simulation and a game exclude each other.
     */
    fun simulate() {
        if (simulating) return
        if (match != null) { notice = "a game is on: finish it before simulating"; return }
        val me = deck ?: return
        val opp = opponentId?.let(::deckById) ?: return
        if (!forgeReady) { notice = "Forge is still loading"; return }
        val ready = sessions.prepare(me, opp, useAiCopy, seatAiCopy = useAiCopy)
        if (ready.blocked) { notice = ready.notes.firstOrNull() ?: "these decks can't play each other"; return }
        val run = Simulation(sessions, ready, simGames, onProgress = { p -> gamesRecorded++; simulation = p; notice = p.line() })
        sim = run
        simulation = run.progress
        notice = run.progress.line()
        run.start()
    }

    fun stopSimulation() {
        sim?.takeIf { simulating }?.stop()
    }

    /** Starts the chosen match; [startState] (a Forge GameState, every game) is for the tests' exact situations. */
    fun start(startState: List<String>? = null) {
        if (simulating) { notice = "a simulation is running (${simulation?.line()}): stop it first"; return }
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
            if (id != null) { gamesRecorded++; notice = "game ${result.gameNo}: ${result.summary} · recorded as games #$id" }
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
    /** The match whose window broke: closing the app then records its game as unfinished, not conceded. */
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
        val line = "${error::class.simpleName}: ${error.message ?: "no message"}"
        if (where == "window") {
            // Only a broken window breaks the game off; after an error elsewhere it plays on, and leaving it is the player's choice.
            crashed = match
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

    /** Where the app log is, for the crash screen. */
    val appLogPath: String get() = paths.appLog.absolutePath

    /** From the crash screen: try the board again (the game is still on, and leaving it is a concession again). */
    fun retryBoard() {
        crash = null
        crashed = null
        screen = if (match != null) Screen.Playing else Screen.Library
    }

    /** The theme to start in: the one picked here, else the TUI's (the user runs rose-pine there), else the house one. */
    fun startTheme(): Theme = Themes.byKey(settings.theme) ?: Themes.fromTextual(tuiTheme(paths.tuiConfig)) ?: Themes.HOUSE

    /** The theme picker (F8, the toolbar's Theme button): open while non-null, holding the theme Esc goes back to. */
    var themePickerFrom by mutableStateOf<Theme?>(null)
        private set

    fun openThemePicker() { if (themePickerFrom == null) themePickerFrom = Palette.theme }

    /** Shown on everything at once while the picker is open, kept only by [keepTheme]. */
    fun previewTheme(theme: Theme) { Palette.theme = theme }

    fun keepTheme(theme: Theme) {
        Palette.theme = theme
        settings.theme = theme.key
        themePickerFrom = null
    }

    fun cancelThemePicker() {
        themePickerFrom?.let { Palette.theme = it }
        themePickerFrom = null
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
