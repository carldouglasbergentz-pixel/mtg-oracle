package mtgoracle.app

import mtgoracle.core.model.PhaseStops
import mtgoracle.core.play.MatchFormat
import mtgoracle.forge.ForgeSetup
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
 *   mtg.db        the shared database (read-only, plus `games` rows)
 *   game_logs/    one full log per game
 *   app/forge/    Forge's user data and image cache (never %APPDATA%\Forge)
 *   app/settings.properties   phase stops, text or art mode
 */
data class AppPaths(
    val data: File,
    val forgeAssets: File,
    /** Forge's home, when not `app/forge/` under [data]: the tests share one Forge per JVM. */
    val forgeHome: File? = null,
) {
    val db: File get() = data.resolve("mtg.db")
    val gameLogs: File get() = data.resolve("game_logs")
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
        fun fromSystemProperties() = AppPaths(
            data = File(required("mtgoracle.data")).canonicalFile,
            forgeAssets = File(required("mtgoracle.forgeAssets")).canonicalFile,
        )

        private fun required(key: String) = System.getProperty(key)
            ?: throw IllegalStateException("system property $key is not set; start the app through Gradle (app/app/build.gradle.kts)")
    }
}

/** The few preferences the app keeps; a missing or broken file means the defaults. */
class Settings(private val file: File) {
    private val props = Properties().apply {
        if (file.isFile) runCatching { file.reader(Charsets.UTF_8).use(::load) }
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
            )
        }
        set(value) {
            fun put(key: String, v: Any?) { if (v == null) props.remove(key) else props.setProperty(key, v.toString()) }
            put("stackBox.col", value.stackCol); put("stackBox.row", value.stackRow); put("stackBox.folded", value.stackCollapsed)
            put("pane.zoneCols", value.zoneCols); put("pane.sideCols", value.sideCols); put("board.rotateTapped", value.rotateTapped)
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

    private fun save() {
        file.parentFile.mkdirs()
        file.writer(Charsets.UTF_8).use { props.store(it, "MTG Oracle app settings") }
    }
}
