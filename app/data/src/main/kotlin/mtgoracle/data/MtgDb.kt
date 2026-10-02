package mtgoracle.data

import org.sqlite.SQLiteConfig
import java.io.File
import java.sql.Connection

/**
 * data/mtg.db. The app owns its schema ([Schema], [migrate]) and fills it
 * with its own sync (`data.sync`).
 *
 * Reads go through a read-only connection, so a bug here cannot touch the
 * user's decks; every write opens its own short-lived read-write one.
 */
class MtgDb(val file: File) {

    init {
        if (!file.isFile) throw MissingDatabaseException(file)
    }

    companion object {
        /** An empty database at [file], schema and all, for a first start: [migrate] creates the schema, a sync the rows. */
        fun create(file: File): MtgDb {
            check(!file.exists()) { "$file already exists" }
            file.parentFile?.mkdirs()
            file.createNewFile()
            return MtgDb(file).also { it.migrate(backups = null) }
        }
    }

    fun <T> read(block: (Connection) -> T): T = connect(readOnly = true).use(block)

    /**
     * [block] in one transaction. [foreignKeys] off only for the sync, which
     * replaces whole card tables, as the Python pipeline it was ported from did (never with them
     * enforced): `INSERT OR REPLACE` on a combo id seen twice would otherwise
     * trip over the first one's cards.
     */
    internal fun <T> write(foreignKeys: Boolean = true, block: (Connection) -> T): T = connect(readOnly = false, foreignKeys = foreignKeys).use { conn ->
        conn.autoCommit = false
        try {
            block(conn).also { conn.commit() }
        } catch (e: Exception) {
            conn.rollback()
            throw e
        }
    }

    /** [block] on a read-write connection that is always rolled back: what a write would do, without doing it. */
    internal fun <T> dryRun(block: (Connection) -> T): T = connect(readOnly = false).use { conn ->
        conn.autoCommit = false
        try {
            block(conn)
        } finally {
            conn.rollback()
        }
    }

    private fun connect(readOnly: Boolean, foreignKeys: Boolean = true): Connection {
        val config = SQLiteConfig().apply {
            setReadOnly(readOnly)
            enforceForeignKeys(foreignKeys)
            busyTimeout = 5_000 // the TUI may be writing at the same moment
        }
        return config.createConnection("jdbc:sqlite:${file.path}").also(::addFold)
    }

    /**
     * `fold(text)` in SQL: [NameFold] (case and accents off, ligatures spelled
     * out), which SQLite's NOCASE can't do past ASCII — `eowyn` found no
     * Éowyn. One instance per connection: a function holds its call's state.
     */
    private fun addFold(conn: Connection) = org.sqlite.Function.create(conn, "fold", object : org.sqlite.Function() {
        override fun xFunc() { result(value_text(0)?.let(mtgoracle.core.lookup.NameFold::fold)) }
    })

    /**
     * Fails unless the database has every table, column, index and foreign
     * key its version should (read-only: for the tools and tests that must
     * not write). The app itself calls [migrate].
     */
    fun checkSchema() {
        read { conn ->
            val version = conn.userVersion()
            if (version > Schema.VERSION) throw SchemaTooNewException(file, version)
            val missing = Schema.shapeAt(maxOf(version, 1)).missingIn(Shape.of(conn))
            if (missing.isNotEmpty()) throw SchemaTooOldException(file, missing)
        }
    }

    /**
     * Brings the database to [Schema.VERSION]. An empty file gets version 1
     * and every migration. A database without a version (the Python
     * migrations made it, before 2026-10) is checked against version 1 and, when it has all
     * of it, stamped 1; nothing else changes. Then each pending migration runs
     * in its own transaction, after one backup in [backups] (none when null:
     * tests on copies). Afterwards the database must have its version's shape.
     */
    fun migrate(backups: File?): MigrationReport = connect(readOnly = false).use { conn ->
        val from = conn.userVersion()
        if (from > Schema.VERSION) throw SchemaTooNewException(file, from)
        val lines = mutableListOf<String>()
        val fresh = from == 0 && Shape.of(conn).tables.isEmpty()
        if (from == 0 && !fresh) {
            val missing = Schema.shapeAt(1).missingIn(Shape.of(conn))
            if (missing.isNotEmpty()) throw SchemaTooOldException(file, missing)
        }
        val pending = Schema.MIGRATIONS.filter { it.version > maxOf(from, 1) }
        // Before any write, the version stamp included: a restored backup is exactly the database as it was.
        // A database just created holds nothing to lose.
        val backup = if (!fresh && pending.isNotEmpty()) backups?.let { Backups.take(conn, it, pending.first().version) } else null
        backup?.let { lines += "backup: $it" }
        if (fresh) {
            conn.transaction { conn.createStatement().use { it.executeUpdate(Schema.baselineSql) }; conn.setUserVersion(1) }
            lines += "created schema version 1"
        } else if (from == 0) {
            conn.setUserVersion(1)
            lines += "adopted: the database has all of schema version 1"
        }
        var version = maxOf(from, 1)
        for (m in pending) {
            conn.transaction { lines += "v${m.version}: ${m.apply(conn)}"; conn.setUserVersion(m.version) }
            version = m.version
        }
        val missing = Schema.shapeAt(version).missingIn(Shape.of(conn))
        if (missing.isNotEmpty()) throw SchemaTooOldException(file, missing)
        MigrationReport(from, version, lines, backup)
    }

    private fun Connection.setUserVersion(version: Int) = createStatement().use { it.executeUpdate("PRAGMA user_version = $version") }

    private fun <T> Connection.transaction(block: () -> T): T {
        autoCommit = false
        try {
            return block().also { commit() }
        } catch (e: Exception) {
            rollback()
            throw e
        } finally {
            autoCommit = true
        }
    }
}

class MissingDatabaseException(val file: File) :
    IllegalStateException("No database at $file. Start the app and press [ Sync ], or run `mtg.cmd sync`: either creates it.")

/**
 * A database without a schema version that lacks part of version 1: made by
 * an older Python build, whose migrations can still complete it. They live
 * at the git tag `python-final`. (The app migrates from version 1 on, and a
 * versioned database is never short.)
 */
class SchemaTooOldException(val file: File, val missing: List<String>) : IllegalStateException(
    "$file lacks part of the schema: ${missing.joinToString(", ")}. " +
        "It predates the app: check out the git tag `python-final`, run `python scripts/self_heal.py` once, then start the app again.",
)
