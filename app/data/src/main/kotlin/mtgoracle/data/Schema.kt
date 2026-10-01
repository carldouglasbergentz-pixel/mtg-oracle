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
    private val NAME = Regex("""mtg-.*-pre-v\d+\.db""")
    private const val KEEP = 3

    /** A copy of the database behind [conn] in [dir], by SQLite's backup, named for the version it precedes; older ones beyond [KEEP] go. */
    fun take(conn: Connection, dir: File, beforeVersion: Int): File {
        dir.mkdirs()
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss"))
        val file = File(dir, "mtg-$stamp-pre-v$beforeVersion.db")
        conn.createStatement().use { it.executeUpdate("backup to '${file.absolutePath.replace("'", "''")}'") }
        check(file.isFile && file.length() > 0) { "the backup $file was not written" }
        // Only the app's own backups are pruned: a copy someone made by hand stays.
        dir.listFiles { f -> f.isFile && NAME.matches(f.name) }.orEmpty().sortedByDescending { it.name }.drop(KEEP).forEach { it.delete() }
        return file
    }
}
