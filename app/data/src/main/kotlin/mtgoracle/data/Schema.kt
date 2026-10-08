package mtgoracle.data

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/*
 * The app owns the schema (ADR 0001, step 6a). `PRAGMA user_version` is the
 * database's version: 1 is scripts/init_db.py's schema as the last Python
 * migration left it (resources/schema-v1.sql, verbatim), and each later
 * version is one [Migration]. The Python migrations (git tag
 * `python-final`) leave a database that has a version alone.
 */

/**
 * A schema as sets, so a table whose columns came in different orders (an
 * ALTER TABLE long ago) is still the same table: columns with their type,
 * NOT NULL, default and key; indexes by what they cover; foreign keys.
 */
data class Shape(val tables: Set<String>, val columns: Map<String, String>, val indexes: Set<String>, val foreignKeys: Set<String>) {
    /** What [actual] lacks of this shape, one line each (`table games`, `column games.conceded`). Extras are no gap. */
    fun missingIn(actual: Shape): List<String> =
        (tables - actual.tables).sorted().map { "table $it" } +
            columns.filter { (key, sig) -> key.substringBefore('.') in actual.tables && actual.columns[key] != sig }.keys.sorted()
                .map { key -> if (key in actual.columns) "column $key as ${columns[key]} (it is ${actual.columns[key]})" else "column $key" } +
            (indexes - actual.indexes).filter { it.substringAfter(" on ").substringBefore('(') in actual.tables }.sorted().map { "index $it" } +
            (foreignKeys - actual.foreignKeys).filter { it.substringBefore('.') in actual.tables }.sorted().map { "foreign key $it" }

    companion object {
        fun of(conn: Connection): Shape {
            fun rows(sql: String, vararg params: String): List<List<String?>> = conn.prepareStatement(sql).use { st ->
                params.forEachIndexed { i, p -> st.setString(i + 1, p) }
                st.executeQuery().use { rs -> buildList { while (rs.next()) add((1..rs.metaData.columnCount).map { rs.getString(it) }) } }
            }
            val tables = rows("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'").map { it[0]!! }.toSet()
            val columns = tables.flatMap { t ->
                rows("SELECT name, type, \"notnull\", dflt_value, pk FROM pragma_table_info(?)", t).map { c -> "$t.${c[0]}" to "${c[1]} notnull=${c[2]} default=${c[3]} pk=${c[4]}" }
            }.toMap()
            val indexes = tables.flatMap { t ->
                rows("SELECT name, \"unique\", partial FROM pragma_index_list(?)", t).map { i ->
                    val cols = rows("SELECT name FROM pragma_index_info(?) ORDER BY seqno", i[0]!!).joinToString(",") { it[0] ?: "expr" }
                    // An automatic index (a UNIQUE or PRIMARY KEY) is named by its constraint's position: compare what it covers.
                    val name = if (i[0]!!.startsWith("sqlite_autoindex_")) "(automatic)" else i[0]
                    "$name on $t($cols) unique=${i[1]} partial=${i[2]}"
                }
            }.toSet()
            val foreignKeys = tables.flatMap { t ->
                rows("SELECT \"from\", \"table\", \"to\", on_delete FROM pragma_foreign_key_list(?)", t).map { f -> "$t.${f[0]} -> ${f[1]}.${f[2]} on delete ${f[3]}" }
            }.toSet()
            return Shape(tables, columns, indexes, foreignKeys)
        }
    }
}

/** One step of the schema after version 1: [apply] runs in a transaction and says what it did. */
internal data class Migration(val version: Int, val summary: String, val apply: (Connection) -> String)

object Schema {
    /** The version this app brings a database to: version 1, and each migration after it. */
    val VERSION: Int get() = MIGRATIONS.maxOfOrNull { it.version } ?: 1

    /** Version 1, as SQL: every table, index and default. */
    val baselineSql: String by lazy {
        Schema::class.java.getResourceAsStream("/mtgoracle/data/schema-v1.sql")!!.bufferedReader(Charsets.UTF_8).readText()
    }

    /** In order, one version each, from 2. A new one goes last, and never edits an old one. */
    internal val MIGRATIONS: List<Migration> = listOf(
        Migration(2, "drop forge_matches, the Python simulator's table, when it is empty") { conn ->
            if (!conn.hasTable("forge_matches")) return@Migration "forge_matches: already gone"
            val rows = conn.count("SELECT COUNT(*) FROM forge_matches")
            if (rows > 0) return@Migration "forge_matches kept: it holds $rows game(s) from the Python simulator"
            conn.createStatement().use { it.executeUpdate("DROP TABLE forge_matches") }
            "forge_matches dropped (it was empty; simulations are rows in games now)"
        },
        Migration(3, "printings: every paper printing from Scryfall, for a card's art") { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    """
                    CREATE TABLE printings (
                        scryfall_id      TEXT PRIMARY KEY,
                        card_name        TEXT NOT NULL,
                        oracle_id        TEXT,
                        set_code         TEXT NOT NULL,
                        set_name         TEXT NOT NULL,
                        collector_number TEXT NOT NULL,
                        lang             TEXT NOT NULL,
                        released_at      TEXT,
                        artist           TEXT,
                        image_faces      INTEGER NOT NULL DEFAULT 1,
                        finishes         TEXT,
                        labels           TEXT
                    )
                    """.trimIndent(),
                )
                st.executeUpdate("CREATE INDEX idx_printings_card_name_nocase ON printings(card_name COLLATE NOCASE)")
                st.executeUpdate("CREATE UNIQUE INDEX idx_printings_set_number ON printings(set_code, collector_number, lang)")
                st.executeUpdate("CREATE TABLE printing_sets (set_code TEXT PRIMARY KEY, card_count INTEGER NOT NULL, synced_at TEXT NOT NULL)")
            }
            "printings and printing_sets created (empty until a sync fetches them)"
        },
        Migration(4, "games.mode takes human_vs_human: two people's games are recorded") { conn ->
            // SQLite can't change a CHECK: the table is built again with the same columns, rows and indexes.
            val columns = "id, played_at, mode, deck_id, deck_name, opponent_deck_id, opponent_name, opponent_ai_variant, seed, winner, turns, " +
                "duration_ms, forge_version, log_path, match_id, game_no, match_format, conceded, deck_ai_variant"
            val kept = conn.count("SELECT COUNT(*) FROM games")
            conn.createStatement().use { st ->
                st.executeUpdate(
                    """
                    CREATE TABLE games_v4 (
                        id INTEGER PRIMARY KEY,
                        played_at TEXT NOT NULL,
                        mode TEXT NOT NULL CHECK (mode IN ('human_vs_ai', 'ai_vs_ai', 'human_vs_human')),
                        deck_id INTEGER REFERENCES decks(id) ON DELETE SET NULL,
                        deck_name TEXT NOT NULL,
                        opponent_deck_id INTEGER REFERENCES decks(id) ON DELETE SET NULL,
                        opponent_name TEXT NOT NULL,
                        opponent_ai_variant INTEGER NOT NULL DEFAULT 0,
                        seed INTEGER,
                        winner TEXT CHECK (winner IN ('me', 'opponent', 'draw')),
                        turns INTEGER,
                        duration_ms INTEGER,
                        forge_version TEXT,
                        log_path TEXT,
                        match_id TEXT,
                        game_no INTEGER,
                        match_format TEXT CHECK (match_format IN ('bo1', 'bo3', 'bo5')),
                        conceded INTEGER NOT NULL DEFAULT 0,
                        deck_ai_variant INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                st.executeUpdate("INSERT INTO games_v4 ($columns) SELECT $columns FROM games")
                st.executeUpdate("DROP TABLE games")
                st.executeUpdate("ALTER TABLE games_v4 RENAME TO games")
                st.executeUpdate("CREATE INDEX idx_games_deck ON games(deck_id)")
                st.executeUpdate("CREATE INDEX idx_games_opponent_deck ON games(opponent_deck_id)")
                st.executeUpdate("CREATE INDEX idx_games_match ON games(match_id)")
            }
            check(conn.count("SELECT COUNT(*) FROM games") == kept) { "games lost rows in the rebuild: nothing was changed" }
            "games rebuilt to take two people's games ($kept kept)"
        },
        Migration(5, "limited_pools: the packs a limited deck is built from") { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    """
                    CREATE TABLE limited_pools (
                        id            INTEGER PRIMARY KEY,
                        set_code      TEXT NOT NULL,
                        scryfall_code TEXT NOT NULL,
                        set_name      TEXT NOT NULL,
                        product       TEXT NOT NULL CHECK (product IN ('sealed')),
                        packs         INTEGER NOT NULL,
                        seed          INTEGER NOT NULL,
                        opened_by     TEXT NOT NULL,
                        rival_pool_id INTEGER REFERENCES limited_pools(id) ON DELETE SET NULL,
                        forge_version TEXT,
                        created_at    TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                st.executeUpdate(
                    """
                    CREATE TABLE limited_pool_cards (
                        id               INTEGER PRIMARY KEY,
                        pool_id          INTEGER NOT NULL REFERENCES limited_pools(id) ON DELETE CASCADE,
                        pack_no          INTEGER NOT NULL,
                        card_name        TEXT NOT NULL REFERENCES cards(name),
                        set_code         TEXT,
                        collector_number TEXT,
                        foil             INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                st.executeUpdate("CREATE INDEX idx_limited_pool_cards_pool ON limited_pool_cards(pool_id)")
                st.executeUpdate("CREATE INDEX idx_limited_pool_cards_card_nocase ON limited_pool_cards(card_name COLLATE NOCASE)")
                st.executeUpdate("ALTER TABLE decks ADD COLUMN pool_id INTEGER REFERENCES limited_pools(id) ON DELETE SET NULL")
            }
            "limited_pools and limited_pool_cards created, decks.pool_id added"
        },
    )

    /** The shape a database at [version] must have: version 1 built in memory, and the migrations up to it applied. */
    fun shapeAt(version: Int): Shape = DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
        conn.createStatement().use { it.executeUpdate(baselineSql) }
        MIGRATIONS.filter { it.version <= version }.forEach { it.apply(conn) }
        Shape.of(conn)
    }
}

/** What [MtgDb.migrate] did. [backup] is the copy taken before the first migration, if one was needed. */
data class MigrationReport(val from: Int, val to: Int, val lines: List<String>, val backup: File?) {
    val changed: Boolean get() = from != to
}

class SchemaTooNewException(val file: File, val version: Int) : IllegalStateException(
    "$file is at schema version $version, newer than this app (${Schema.VERSION}): a newer build made it. Run that build, or restore a backup from data/backups/.",
)

internal fun Connection.hasTable(name: String): Boolean =
    prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?").use { st ->
        st.setString(1, name)
        st.executeQuery().use { it.next() }
    }

internal fun Connection.count(sql: String): Int = createStatement().use { st -> st.executeQuery(sql).use { rs -> rs.next(); rs.getInt(1) } }

internal fun Connection.userVersion(): Int = count("PRAGMA user_version")

internal object Backups {
    private const val KEEP = 3

    /** A copy of the database behind [conn] in [dir], by SQLite's backup, named for the version it precedes; older ones beyond [KEEP] go. */
    fun take(conn: Connection, dir: File, beforeVersion: Int): File = take(conn, dir, "v$beforeVersion", Regex("""mtg-.*-pre-v\d+\.db"""))

    /** The same before [what] (`import`): `mtg-<time>-pre-import.db`, the newest [KEEP] of that kind kept. */
    fun take(conn: Connection, dir: File, what: String): File = take(conn, dir, what, Regex("""mtg-.*-pre-${Regex.escape(what)}\.db"""))

    private fun take(conn: Connection, dir: File, label: String, kind: Regex): File {
        dir.mkdirs()
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss"))
        val file = File(dir, "mtg-$stamp-pre-$label.db")
        conn.createStatement().use { it.executeUpdate("backup to '${file.absolutePath.replace("'", "''")}'") }
        check(file.isFile && file.length() > 0) { "the backup $file was not written" }
        // Only the app's own backups are pruned, each kind by itself: a copy someone made by hand stays.
        dir.listFiles { f -> f.isFile && kind.matches(f.name) }.orEmpty().sortedByDescending { it.name }.drop(KEEP).forEach { it.delete() }
        return file
    }
}
