package mtgoracle.app

import mtgoracle.core.model.PhaseStops
import mtgoracle.core.play.MatchFormat
import mtgoracle.forge.ForgeSetup
import mtgoracle.forge.Log
import mtgoracle.ui.board.BoardLayout
import mtgoracle.ui.kit.CardMode
import mtgoracle.ui.library.DECK_LIST_COLS
import mtgoracle.ui.library.SideColumns
import mtgoracle.ui.library.defaultWorkspaceColumns
import java.io.File
import java.util.Properties

/**
 * Where the app reads and writes, all under one data directory (the repo's
 * `data/` by default; Gradle passes `-Dmtgoracle.data`):
 *
 *   mtg.db        the database: the app owns its schema and sync, and writes decks, games and combos
 *   backups/      the database as it was before each migration (three kept)
 *   game_logs/    one full log per game
 *   exports/      decks exported for Magic Online, which imports a file
 *   app/forge/    Forge's user data and image cache (never %APPDATA%\Forge)
 *   app/settings.properties   the theme, phase stops, text or art, pane widths and the board's layout
 *   app/app.log   every warning and error, crash traces in full
 */
data class AppPaths(
    val data: File,
    val forgeAssets: File,
    /** Forge's home, when not `app/forge/` under [data]: the tests share one Forge per JVM. */
    val forgeHome: File? = null,
    /**
     * The curated points lists the `formats` source reads: the repo's `data/formats/` when
     * run from the repo, the package's own copy in a release, so a new version brings its lists.
     */
    val formats: File = data.resolve("formats"),
) {
    val db: File get() = data.resolve("mtg.db")
    val gameLogs: File get() = data.resolve("game_logs")
    /** Decks exported for Magic Online, which imports a `.txt` file rather than the clipboard. */
    val exports: File get() = data.resolve("exports")
    /** Where a `.mtgoracle` package dropped in is taken from, at start (done ones go to `done/`). */
    val imports: File get() = data.resolve("import")
    /** The playmats: pictures for each side of the table, chosen in the lobby. */
    val playmats: File get() = data.resolve("playmats")
    /** Where a migration's backup goes (the three newest are kept). */
    val backups: File get() = data.resolve("backups")
    val home: File get() = data.resolve("app")
    val forge: ForgeSetup get() = ForgeSetup(forgeAssets, forgeHome ?: home.resolve("forge"))
    val settings: File get() = home.resolve("settings.properties")
    /** The app's own log (Log.toFile): every warning and error, crash traces in full. */
    val appLog: File get() = home.resolve("app.log")
    /** The retired Python TUI's settings: its theme is the first-start default. Read, never written. */
    val tuiConfig: File get() = data.resolve("config.json")

    companion object {
        fun fromSystemProperties(): AppPaths {
            val data = File(required("mtgoracle.data")).canonicalFile
            return AppPaths(
                data = data,
                forgeAssets = File(required("mtgoracle.forgeAssets")).canonicalFile,
                formats = System.getProperty("mtgoracle.formats")?.let { File(it).canonicalFile } ?: data.resolve("formats"),
            )
        }

        private fun required(key: String) = System.getProperty(key)
            ?: throw IllegalStateException("system property $key is not set; start the app through Gradle (app/app/build.gradle.kts)")
    }
}

/** The few preferences the app keeps; a missing or broken file means the defaults. */
class Settings(private val file: File) {
    private val props = Properties().apply {
        // A broken file means the defaults, but said: the next save writes over it.
        if (file.isFile) runCatching { file.reader(Charsets.UTF_8).use(::load) }.onFailure { Log.warn("settings in $file unreadable, using the defaults: $it") }
    }

    var stops: PhaseStops
        get() = PhaseStops.parse(props.getProperty("stops"))
        set(value) { props.setProperty("stops", value.serialise()); save() }

    var cardMode: CardMode
        get() = CardMode.entries.firstOrNull { it.name == props.getProperty("cardMode") } ?: CardMode.ART
        set(value) { props.setProperty("cardMode", value.name); save() }

    /** The board's own text-or-art, apart from the library's: a T there once turned a game's art off unseen. */
    var boardCardMode: CardMode
        get() = CardMode.entries.firstOrNull { it.name == props.getProperty("board.cardMode") } ?: CardMode.ART
        set(value) { props.setProperty("board.cardMode", value.name); save() }

    /** The board as the viewer arranged it: where the stack box is and whether it is folded, the pane widths. */
    var boardLayout: BoardLayout
        get() = BoardLayout().let { d ->
            BoardLayout(
                stackCol = props.getProperty("stackBox.col")?.toIntOrNull(),
                stackRow = props.getProperty("stackBox.row")?.toIntOrNull(),
                stackCollapsed = props.getProperty("stackBox.folded") == "true",
                zoneCols = props.getProperty("pane.zoneCols")?.toIntOrNull() ?: d.zoneCols,
                sideCols = props.getProperty("pane.sideCols")?.toIntOrNull() ?: d.sideCols,
                rotateTapped = props.getProperty("board.rotateTapped")?.let { it == "true" } ?: d.rotateTapped,
                logSteps = props.getProperty("board.logSteps")?.let { it == "true" } ?: d.logSteps,
            )
        }
        set(value) {
            fun put(key: String, v: Any?) { if (v == null) props.remove(key) else props.setProperty(key, v.toString()) }
            put("stackBox.col", value.stackCol); put("stackBox.row", value.stackRow); put("stackBox.folded", value.stackCollapsed)
            put("pane.zoneCols", value.zoneCols); put("pane.sideCols", value.sideCols); put("board.rotateTapped", value.rotateTapped)
            put("board.logSteps", value.logSteps)
            save()
        }

    /** The library's deck list and zoom pane, in cells, as last kept. */
    var libraryColumns: SideColumns
        get() = SideColumns(
            props.getProperty("library.leftCols")?.toIntOrNull() ?: DECK_LIST_COLS,
            props.getProperty("library.rightCols")?.toIntOrNull() ?: mtgoracle.ui.board.SIDE_COLS,
        )
        set(value) { props.setProperty("library.leftCols", value.left.toString()); props.setProperty("library.rightCols", value.right.toString()); save() }

    /** The workspace's deck pane in [mode] (lines and frames want different widths) and its zoom pane, as last kept. */
    fun workspaceColumns(mode: CardMode): SideColumns = defaultWorkspaceColumns(mode).let { d ->
        SideColumns(
            props.getProperty("workspace.deckCols.${mode.name}")?.toIntOrNull() ?: d.left,
            props.getProperty("workspace.rightCols")?.toIntOrNull() ?: d.right,
        )
    }

    fun keepWorkspaceColumns(mode: CardMode, value: SideColumns) {
        props.setProperty("workspace.deckCols.${mode.name}", value.left.toString())
        props.setProperty("workspace.rightCols", value.right.toString())
        save()
    }

    /** The deck workspace's search results: a grid of cards (the default) or lines. */
    var resultsGrid: Boolean
        get() = props.getProperty("workspace.resultsGrid") != "false"
        set(value) { props.setProperty("workspace.resultsGrid", value.toString()); save() }

    /** The deck workspace's deck pane: lines (the default, dense beside a search) or frames. */
    var deckPaneMode: CardMode
        get() = CardMode.entries.firstOrNull { it.name == props.getProperty("workspace.deckMode") } ?: CardMode.TEXT
        set(value) { props.setProperty("workspace.deckMode", value.name); save() }

    /** Best of 1, 3 or 5: the last one chosen in setup. */
    var matchFormat: MatchFormat
        get() = MatchFormat.entries.firstOrNull { it.column == props.getProperty("matchFormat") } ?: MatchFormat.BO1
        set(value) { props.setProperty("matchFormat", value.column); save() }

    /** The colour theme's key (Themes); null until the viewer picks one. */
    var theme: String?
        get() = props.getProperty("theme")
        set(value) { if (value == null) props.remove("theme") else props.setProperty("theme", value); save() }

    /** Each side's playmat in the lobby, by file name; none when absent. */
    var matMine: String?
        get() = props.getProperty("lobby.matMine")
        set(value) { if (value == null) props.remove("lobby.matMine") else props.setProperty("lobby.matMine", value); save() }
    var matTheirs: String?
        get() = props.getProperty("lobby.matTheirs")
        set(value) { if (value == null) props.remove("lobby.matTheirs") else props.setProperty("lobby.matTheirs", value); save() }

    /** A playmat's dim in tenths (four, until set). */
    fun matDim(name: String): Int = props.getProperty("playmat.$name.dim")?.toIntOrNull()?.coerceIn(0, 9) ?: 4
    fun setMatDim(name: String, tenths: Int) { props.setProperty("playmat.$name.dim", tenths.coerceIn(0, 9).toString()); save() }

    /**
     * Where a playmat's picture sits in its crop (x, y: 0 to 1) and its zoom (1 to 3); the middle at 100 % until
     * set. A mat placed before (top, middle, bottom) starts where it was.
     */
    fun matFrame(name: String): Triple<Float, Float, Float> {
        val old = when (props.getProperty("playmat.$name.anchor")) { "TOP" -> 0f; "BOTTOM" -> 1f; else -> 0.5f }
        fun read(key: String, default: Float) = props.getProperty("playmat.$name.$key")?.toFloatOrNull() ?: default
        return Triple(read("x", 0.5f).coerceIn(0f, 1f), read("y", old).coerceIn(0f, 1f), read("zoom", 1f).coerceIn(1f, 3f))
    }
    fun setMatFrame(name: String, x: Float, y: Float, zoom: Float) {
        props.setProperty("playmat.$name.x", "%.3f".format(java.util.Locale.ROOT, x.coerceIn(0f, 1f)))
        props.setProperty("playmat.$name.y", "%.3f".format(java.util.Locale.ROOT, y.coerceIn(0f, 1f)))
        props.setProperty("playmat.$name.zoom", "%.2f".format(java.util.Locale.ROOT, zoom.coerceIn(1f, 3f)))
        props.remove("playmat.$name.anchor")
        save()
    }

    /** The getting-started checklist was put away (Hide); the library's tour was seen; a first game's tips were read. */
    var guideDone: Boolean
        get() = props.getProperty("guide.done") == "true"
        set(value) { props.setProperty("guide.done", value.toString()); save() }
    var tourDone: Boolean
        get() = props.getProperty("guide.tour") == "true"
        set(value) { props.setProperty("guide.tour", value.toString()); save() }
    var tipsDone: Boolean
        get() = props.getProperty("guide.tips") == "true"
        set(value) { props.setProperty("guide.tips", value.toString()); save() }

    /** How long Forge took to start last time, for the lobby to say how long it may take. */
    var forgeStartMillis: Long?
        get() = props.getProperty("forge.startMillis")?.toLongOrNull()
        set(value) { if (value == null) props.remove("forge.startMillis") else props.setProperty("forge.startMillis", value.toString()); save() }

    /** How large everything is drawn, 1.0 as the app is designed (Ctrl+= / Ctrl+-, Ctrl+0 back). */
    var textScale: Float
        get() = props.getProperty("ui.textScale")?.toFloatOrNull()?.takeIf { it in TEXT_SCALES.first()..TEXT_SCALES.last() } ?: 1f
        set(value) { props.setProperty("ui.textScale", value.toString()); save() }

    /** The lobby's last pairing, chosen again when it opens: your deck and the AI's. */
    var lobbyMe: Int?
        get() = props.getProperty("lobby.me")?.toIntOrNull()
        set(value) { if (value == null) props.remove("lobby.me") else props.setProperty("lobby.me", value.toString()); save() }
    var lobbyOpponent: Int?
        get() = props.getProperty("lobby.opponent")?.toIntOrNull()
        set(value) { if (value == null) props.remove("lobby.opponent") else props.setProperty("lobby.opponent", value.toString()); save() }

    /**
     * The name you go by at a network table, sent to the other side: "Player" until you set one. Never the
     * computer's user name, which may be a work account and is nobody else's business.
     */
    var playerName: String
        get() = props.getProperty("net.name")?.takeIf { it.isNotBlank() } ?: "Player"
        set(value) { props.setProperty("net.name", value); save() }
    val playerNamed: Boolean get() = !props.getProperty("net.name").isNullOrBlank()

    /** Your playmat goes to the other side of a network table (as pixels). */
    /** The lobby's tab: limited (true) or constructed. */
    var lobbyLimited: Boolean
        get() = props.getProperty("lobby.tab") == "limited"
        set(value) { props.setProperty("lobby.tab", if (value) "limited" else "constructed"); save() }
    /** The set last chosen to open packs of (Forge's code). */
    var limitedSet: String?
        get() = props.getProperty("limited.set")
        set(value) { if (value == null) props.remove("limited.set") else props.setProperty("limited.set", value); save() }

    /** How a limited deck's pool is laid out in the workspace: `colour type mv`, `-` for an empty slot. */
    var limitedSort: mtgoracle.core.lookup.CardSort
        get() = props.getProperty("limited.sort")?.let { mtgoracle.core.lookup.CardSort.parse(it.split(' ')) } ?: mtgoracle.core.lookup.CardSort.DEFAULT
        set(value) { props.setProperty("limited.sort", value.command.removePrefix("sort ")); save() }

    /** How any other search is laid out, kept apart from the pool's: `colour type mv`, `-` for an empty slot. */
    var searchSort: mtgoracle.core.lookup.CardSort
        get() = props.getProperty("search.sort")?.let { mtgoracle.core.lookup.CardSort.parse(it.split(' ')) } ?: mtgoracle.core.lookup.CardSort.DEFAULT
        set(value) { props.setProperty("search.sort", value.command.removePrefix("sort ")); save() }

    var shareMat: Boolean
        get() = props.getProperty("net.shareMat") != "false"
        set(value) { props.setProperty("net.shareMat", value.toString()); save() }

    /** The other side's playmat is shown: off until you choose it, since you can't know what a stranger's is. */
    var showTheirMat: Boolean
        get() = props.getProperty("net.showTheirMat") == "true"
        set(value) { props.setProperty("net.showTheirMat", value.toString()); save() }

    /** When a release last asked GitHub for a newer one (Updates): once a day, and at each start. */
    var updateLastCheck: java.time.Instant?
        get() = instant("update.lastCheck")
        set(value) { setInstant("update.lastCheck", value) }

    /** Whether the app syncs by itself once a day (AutoSync); on unless turned off with `autosync off`. */
    var autoSync: Boolean
        get() = props.getProperty("sync.auto") != "false"
        set(value) { props.setProperty("sync.auto", value.toString()); save() }

    /** When the last sync finished, manual or automatic. */
    var syncLast: java.time.Instant?
        get() = instant("sync.last")
        set(value) { setInstant("sync.last", value) }

    /** When Commander Spellbook was last synced without failing: the automatic sync takes it once a week. */
    var syncLastCombos: java.time.Instant?
        get() = instant("sync.lastCombos")
        set(value) { setInstant("sync.lastCombos", value) }

    /** The sources whose last sync failed, and since when: said in the status line until a later sync of them succeeds. */
    var syncFailed: Map<mtgoracle.core.sync.Source, java.time.Instant>
        get() = props.getProperty("sync.failed").orEmpty().split(';').filter { it.isNotBlank() }.mapNotNull { entry ->
            val source = mtgoracle.core.sync.Source.of(entry.substringBefore('@')) ?: return@mapNotNull null
            runCatching { java.time.Instant.parse(entry.substringAfter('@')) }.getOrNull()?.let { source to it }
        }.toMap()
        set(value) {
            if (value.isEmpty()) props.remove("sync.failed")
            else props.setProperty("sync.failed", value.entries.joinToString(";") { (s, at) -> "${s.key}@$at" })
            save()
        }

    private fun instant(key: String): java.time.Instant? = props.getProperty(key)?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
    private fun setInstant(key: String, value: java.time.Instant?) {
        if (value == null) props.remove(key) else props.setProperty(key, value.toString())
        save()
    }

    private fun save() {
        file.parentFile.mkdirs()
        file.writer(Charsets.UTF_8).use { props.store(it, "MTG Oracle app settings") }
    }
}

/** The sizes Ctrl+= and Ctrl+- step through: the house grid at 13 px reads small on a laptop or a 4K screen. */
val TEXT_SCALES = listOf(0.8f, 0.9f, 1f, 1.1f, 1.25f, 1.4f, 1.6f, 1.8f)
