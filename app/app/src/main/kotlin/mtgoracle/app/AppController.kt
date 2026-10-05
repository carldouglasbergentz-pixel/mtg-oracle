package mtgoracle.app

import kotlin.math.roundToInt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import mtgoracle.core.analysis.DeckInsight
import mtgoracle.core.deck.Deck
import mtgoracle.core.deck.DeckCard
import mtgoracle.core.deck.DeckSummary
import mtgoracle.core.model.PhaseStops
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
    data object Lobby : Screen
    data object Playing : Screen
    /** Forge's achievements, from the library's toolbar. */
    data object Achievements : Screen
    /** The board broke (see AppController.onCrash): what happened, and the way out. */
    data object Crashed : Screen
}

/**
 * The window's state and its transitions: library -> lobby -> playing ->
 * lobby. Compose state, so the screens recompose as it changes; the slow
 * parts (Forge's start, a game's recording, a sync) run on their own
 * threads. Play is [play], the sync [sync], a release's updates [updates];
 * this holds the library, the workspace, the screens and the crash screen.
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
    /** The library's packages (export, import), with the lookup they read the card names from. */
    private var packages: PackageActions? = null
    /** The games played, read with the lookup: the lobby's records. */
    private var lookupGames: mtgoracle.data.GameStore? = null

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
    /** When Forge began to start (System.nanoTime), for the lobby's "starting: 4 s of about 11 s". */
    var forgeStartedAt: Long? = null
        private set
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
            selectedDeck = { selectedId }, sync = { force, only -> sync.run(force, only) }, autoSync = sync::setting, update = updates::install,
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
            packages = PackageActions(
                mtgoracle.data.PackageStore(db, lookup.names, writer), lookupCommands.ui, say = { notice = it }, refresh = ::refreshLibrary,
                app = System.getProperty("mtgoracle.version") ?: "dev build", exports = paths.exports, imports = paths.imports, backups = paths.backups,
            ).also { packages = it },
        )
        lookupUi = lookupCommands.ui.apply {
            grid = carry?.ui?.grid ?: settings.resultsGrid
            showOutput = carry?.ui?.showOutput ?: false
            intent = actions::handle
        }
        // The new commands have no last deck: entered as new, the deck would lose the output (prune's own report) that was just carried over.
        carry?.scope?.let { lookupCommands.enterDeck(it.deckId, carried = true) }
    }

    /** The lobby, the match on, the simulation running, and each game written as it ends. */
    val play = PlayControl(settings, sessions = { sessions }, decks = { decks }, deckById = ::deckById, games = { lookupGames },
        forgeReady = { forgeReady }, show = { screen = it }, say = { notice = it })

    /** The sync, by hand and daily; its report builds the lookup again and goes to the output. */
    val sync = SyncControl(paths, settings, db = { db }, busy = { play.match != null || play.simulation != null }, say = { notice = it },
        onDone = { report, auto ->
            commands?.let { current ->
                // Always: a points change (4 to 3) keeps the row count, so the report can't tell; a rebuild is ~130 ms.
                db?.let { buildLookup(it, carry = current) }
                commands?.output?.add(mtgoracle.ui.lookup.renderSyncReport(report))
                // A daily run reports to the output without opening it over what you are looking at.
                if (!auto) lookupUi?.showOutput = true
            }
            // A dropped package that waited for cards a sync might bring is asked about again.
            packages?.lookForDropped(afterSync = true)
        })

    /**
     * Whether a release has a newer version, seconds after start and then daily, and every hour
     * whether the daily sync is due, the first time a minute after start. Only the window starts it,
     * never a test. The first look came with the sync's, a minute in, and a window closed sooner
     * never heard of 0.2.0.
     */
    fun startAutoSync() {
        val timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "auto-sync").apply { isDaemon = true } }
        timer.schedule({ java.awt.EventQueue.invokeLater { updates.check() } }, 5, java.util.concurrent.TimeUnit.SECONDS)
        timer.scheduleAtFixedRate({
            java.awt.EventQueue.invokeLater {
                sync.autoIfDue()
                if (updates.due()) updates.check()
            }
        }, 1, 60, java.util.concurrent.TimeUnit.MINUTES)
    }

    /** A release's updates from GitHub, checked daily and installed by `update`. */
    val updates = UpdateControl(settings, clock = { sync.clock() }, busy = { play.match != null || play.simulation != null }, say = { notice = it }, shutdown = ::shutdown)

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
            sessions.pruneLogs().takeIf { it > 0 }?.let { Log.info("game logs: $it old ones removed, the newest $KEEP_LOGS kept") }
            decks = library.decks()
            folders = library.folders()
            decks.firstOrNull()?.let { select(it.id) }
            this.db = db
            buildLookup(db, carry = null)
            sync.refreshWarning()
            screen = Screen.Library
            showPackageFolders()
            packages?.lookForDropped()
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
        forgeStartedAt = System.nanoTime()
        thread(name = "forge-start", isDaemon = true) {
            try {
                ForgeRuntime.initialise(paths.forge)
                ForgeRuntime.onForgeError { title, text -> onCrash("Forge", RuntimeException("$title: ${text.lineSequence().firstOrNull().orEmpty()}")) }
                forgeStartedAt?.let { settings.forgeStartMillis = (System.nanoTime() - it) / 1_000_000 }
                forgeReady = true
            } catch (e: Exception) {
                Log.error("Forge did not start", e)
                notice = "Forge did not start: ${e.message}"
            }
        }
    }

    /**
     * data\import\ and data\exports\ from the first start, each saying what it is for: made only
     * when first used, neither was there to be found (the user, 2026-10-05).
     */
    private fun showPackageFolders() = runCatching {
        paths.exports.mkdirs()
        paths.imports.mkdirs()
        val readme = paths.imports.resolve("README.txt")
        if (!readme.exists()) readme.writeText(
            "Put a .mtgoracle package here (an export from MTG Oracle: decks, games, your own combos)\r\n" +
                "and it is imported at the next start. The app asks first, and nothing in your library\r\n" +
                "is overwritten. An imported package moves to done\\, a skipped one to skipped\\.\r\n" +
                "\r\n" +
                "Or copy the file in Explorer and press Import in the library.\r\n" +
                "Exports are saved in ..\\exports\\.\r\n",
        )
    }.onFailure { Log.warn("could not make the package folders: ${it.message}") }

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
        val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
        // A file copied in Explorer is a list of files, not text: its path stands for it (a package to import).
        runCatching { (clipboard.getData(java.awt.datatransfer.DataFlavor.javaFileListFlavor) as List<*>).filterIsInstance<java.io.File>().firstOrNull()?.path }.getOrNull()
            ?: runCatching { clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as String }.getOrNull()
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
        play.match?.recorder?.note("APP CRASH in $where: ${Log.trace(error).replace("\n", " | ")}")
        val line = "${error::class.simpleName}: ${error.message ?: "no message"}"
        if (where == "window") {
            // Only a broken window breaks the game off; after an error elsewhere it plays on, and leaving it is the player's choice.
            crashed = play.match
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
        val running = play.match
        if (running != null) {
            play.stopRecording(running, unfinished = true)
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
        screen = if (play.match != null) Screen.Playing else Screen.Library
    }

    /** The theme to start in: the one picked here, else the TUI's (the user runs rose-pine there), else the house one. */
    fun startTheme(): Theme = Themes.byKey(settings.theme) ?: Themes.fromTextual(tuiTheme(paths.tuiConfig)) ?: Themes.HOUSE

    /** The theme picker (F8, the toolbar's Theme button): open while non-null, holding the theme Esc goes back to. */
    var themePickerFrom by mutableStateOf<Theme?>(null)
        private set

    fun openThemePicker() { if (themePickerFrom == null) themePickerFrom = Palette.theme }

    /** How large the whole window is drawn ([TEXT_SCALES]); every cell, frame and image follows it. */
    var textScale by mutableStateOf(settings.textScale)
        private set

    /** One size up ([by] 1) or down (-1) from the nearest step; 0 is the designed size. */
    fun stepTextScale(by: Int) {
        val at = TEXT_SCALES.indexOfFirst { it >= textScale - 0.001f }.takeIf { it >= 0 } ?: TEXT_SCALES.lastIndex
        textScale = if (by == 0) 1f else TEXT_SCALES[(at + by).coerceIn(0, TEXT_SCALES.lastIndex)]
        settings.textScale = textScale
        notice = "text size ${(textScale * 100).roundToInt()} % (Ctrl+= / Ctrl+-, Ctrl+0 back)"
    }

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

    /**
     * The window is closing. A game still on is recorded as conceded — leaving
     * is what the player chose — unless the app broke it off, which makes it
     * unfinished.
     */
    fun shutdown() = play.shutdown(unfinished = crashed != null && crashed === play.match)

    /** Forge's achievements, for the achievements view; null until Forge is up and they are read. */
    var achievements by mutableStateOf<List<mtgoracle.core.play.AchievementGroup>?>(null)
        private set

    /** The achievements view, read fresh: a game since the last look may have earned one. */
    fun openAchievements() {
        screen = Screen.Achievements
        loadAchievements()
    }

    /** Reads them once Forge is up (the view asks again when it comes up). */
    fun loadAchievements() {
        if (!forgeReady) return
        achievements = try { mtgoracle.forge.ForgeAchievements.groups() } catch (e: Exception) {
            Log.error("could not read Forge's achievements", e)
            notice = "Forge's achievements could not be read: ${e.message}"
            emptyList()
        }
    }

    /** A card by name for the zoom pane, from the database: the achievements view's card. */
    fun cardFace(name: String): CardFace? = currentLookup?.let { zoomFace(it, name) }

    fun backToLibrary() {
        play.match = null
        notice = null
        screen = Screen.Library
    }
}
