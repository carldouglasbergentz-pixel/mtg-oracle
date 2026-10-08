package mtgoracle.data

import java.io.File
import java.sql.DriverManager
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The app's schema: version 1 is Python's, table for table; a database the
 * Python migrations made is adopted unchanged and migrated after a backup;
 * an empty file becomes the latest version; a gap or a newer version stops it.
 * Python's database is its schema as init_db and every self_heal migration
 * left it on 2026-10-01 (`fixture/python-v0-schema.sql`, version 0).
 */
class SchemaTest {
    private val dir: File = createTempDirectory("mtg-oracle-schema-").toFile()

    @AfterTest fun clean() { dir.deleteRecursively() }

    /** A database as Python built one: init_db's schema, then every self_heal migration. Version 0. */
    private fun python(name: String = "mtg.db"): File {
        val file = File(dir, name)
        val script = File(FixtureDb.dir, "python-v0-schema.sql").readText(Charsets.UTF_8)
        DriverManager.getConnection("jdbc:sqlite:${file.path}").use { c -> c.createStatement().use { it.executeUpdate(script) } } // every statement; execute() runs the first
        return file
    }

    private fun shape(file: File) = DriverManager.getConnection("jdbc:sqlite:${file.path}").use { Shape.of(it) }
    private fun sql(file: File, statement: String) = DriverManager.getConnection("jdbc:sqlite:${file.path}").use { c -> c.createStatement().use { it.execute(statement) } }
    private fun version(file: File) = DriverManager.getConnection("jdbc:sqlite:${file.path}").use { it.userVersion() }

    @Test
    fun `version 1 is Python's schema, and the user's database has all of it`() {
        val py = shape(python())
        val ours = Schema.shapeAt(1)
        assertEquals(emptyList(), ours.missingIn(py), "Python's lacks nothing of ours")
        assertEquals(emptyList(), py.missingIn(ours), "ours lacks nothing of Python's")
        assertTrue("forge_matches" in ours.tables && "forge_matches" !in Schema.shapeAt(2).tables)
        if (DbFixture.available) {
            DriverManager.getConnection("jdbc:sqlite:file:${DbFixture.realDb.path}?mode=ro").use { conn ->
                assertEquals(emptyList(), Schema.shapeAt(maxOf(conn.userVersion(), 1)).missingIn(Shape.of(conn)), "the user's database")
            }
        }
    }

    @Test
    fun `an empty file becomes the latest version`() {
        val file = File(dir, "empty.db").apply { createNewFile() }
        val report = MtgDb(file).migrate(File(dir, "backups"))
        assertEquals(0 to Schema.VERSION, report.from to report.to)
        assertEquals("created schema version 1", report.lines.first())
        assertNull(report.backup, "nothing to back up")
        assertEquals(emptyList(), Schema.shapeAt(Schema.VERSION).missingIn(shape(file)))
        assertEquals(emptyList(), shape(file).missingIn(Schema.shapeAt(Schema.VERSION)), "and nothing more")
    }

    @Test
    fun `a database the Python migrations made is adopted unchanged, backed up, then migrated once`() {
        val file = python()
        val before = shape(file)
        val backups = File(dir, "backups")
        val report = MtgDb(file).migrate(backups)
        assertEquals(0 to Schema.VERSION, report.from to report.to)
        assertTrue(report.lines.any { it.startsWith("adopted") } && report.lines.any { it == "v2: forge_matches dropped (it was empty; simulations are rows in games now)" }, report.lines.toString())
        assertTrue(report.lines.any { it.startsWith("v3: printings") }, report.lines.toString())
        val after = shape(file)
        val added = setOf("printings", "printing_sets", "limited_pools", "limited_pool_cards")
        assertEquals(before.tables - "forge_matches" + added, after.tables, "nothing but the migrations changed")
        assertEquals(before.columns.filterKeys { !it.startsWith("forge_matches.") }, after.columns.filterKeys { it.substringBefore('.') !in added && it != "decks.pool_id" })

        val backup = report.backup!!
        assertTrue(backup.parentFile == backups && backup.name.matches(Regex("""mtg-.*-pre-v2\.db""")), backup.name)
        assertEquals(0, version(backup), "the backup is the database as it was, before the stamp")
        assertEquals(before, shape(backup))

        val again = MtgDb(file).migrate(backups)
        assertEquals(Schema.VERSION to Schema.VERSION, again.from to again.to)
        assertEquals(emptyList(), again.lines)
        assertNull(again.backup)
        MtgDb(file).checkSchema()
    }

    @Test
    fun `version 4 takes two people's games, every game before it kept as it was`() {
        val file = python()
        sql(file, "INSERT INTO games (played_at, mode, deck_name, opponent_name, winner, turns, match_id, game_no, match_format, conceded) " +
            "VALUES ('2026-10-01T12:00:00Z', 'human_vs_ai', 'Jori En', 'Phelia Doggo (AI)', 'me', 9, 'm1', 1, 'bo3', 0)")
        sql(file, "INSERT INTO games (played_at, mode, deck_name, opponent_name, winner) VALUES ('2026-10-02T12:00:00Z', 'ai_vs_ai', 'A', 'B', 'draw')")
        fun rows() = DriverManager.getConnection("jdbc:sqlite:${file.path}").use { c ->
            c.createStatement().use { st -> st.executeQuery("SELECT * FROM games ORDER BY id").use { rs -> buildList { while (rs.next()) add((1..rs.metaData.columnCount).map { rs.getString(it) }) } } }
        }
        val before = rows()
        assertFailsWith<java.sql.SQLException>("the old CHECK refuses it") {
            sql(file, "INSERT INTO games (played_at, mode, deck_name, opponent_name) VALUES ('x', 'human_vs_human', 'A', 'Bob')")
        }
        val report = MtgDb(file).migrate(File(dir, "backups"))
        assertContains(report.lines, "v4: games rebuilt to take two people's games (2 kept)")
        assertEquals(before, rows(), "every row as it was")
        sql(file, "INSERT INTO games (played_at, mode, deck_name, opponent_name) VALUES ('x', 'human_vs_human', 'A', 'Bob')")
        val indexes = DriverManager.getConnection("jdbc:sqlite:${file.path}").use { c ->
            c.createStatement().use { st -> st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'games' ORDER BY name").use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }
        }
        assertEquals(listOf("idx_games_deck", "idx_games_match", "idx_games_opponent_deck"), indexes)
        MtgDb(file).checkSchema()
    }

    @Test
    fun `version 5 adds limited pools, and a deck keeps its rows and gains no pool`() {
        val file = python()
        sql(file, "INSERT INTO decks (name, created_at, updated_at) VALUES ('Jori En', 'x', 'x')")
        val report = MtgDb(file).migrate(File(dir, "backups"))
        assertContains(report.lines, "v5: limited_pools and limited_pool_cards created, decks.pool_id added")
        val pool = DriverManager.getConnection("jdbc:sqlite:${file.path}").use { c ->
            c.createStatement().use { st -> st.executeQuery("SELECT name, pool_id FROM decks").use { rs -> rs.next(); rs.getString(1) to rs.getObject(2) } }
        }
        assertEquals("Jori En" to null, pool)
        assertFailsWith<java.sql.SQLException>("only sealed, until another product is built") {
            sql(file, "INSERT INTO limited_pools (set_code, scryfall_code, set_name, product, packs, seed, opened_by, created_at) VALUES ('BLB', 'blb', 'Bloomburrow', 'draft', 3, 1, 'me', 'x')")
        }
        MtgDb(file).checkSchema()
    }

    @Test
    fun `forge_matches with games in it is kept`() {
        val file = python()
        sql(file, "INSERT INTO forge_matches (match_id, played_at, deck_a, deck_b, game_type, game_no, winner) VALUES ('m', 'x', 'A', 'B', 'constructed', 1, 'a')")
        val report = MtgDb(file).migrate(null)
        assertContains(report.lines, "v2: forge_matches kept: it holds 1 game(s) from the Python simulator")
        assertTrue("forge_matches" in shape(file).tables)
        MtgDb(file).checkSchema()
    }

    @Test
    fun `a gap is named and nothing is written, a newer version is refused`() {
        val short = python()
        sql(short, "ALTER TABLE games DROP COLUMN deck_ai_variant")
        val error = assertFailsWith<SchemaTooOldException> { MtgDb(short).migrate(File(dir, "backups")) }
        assertEquals(listOf("column games.deck_ai_variant"), error.missing)
        assertContains(error.message!!, "python-final")
        assertEquals(0, version(short), "not stamped")
        assertTrue(!File(dir, "backups").exists(), "no backup either: nothing was going to change")

        val newer = python("newer.db")
        sql(newer, "PRAGMA user_version = 99")
        assertFailsWith<SchemaTooNewException> { MtgDb(newer).migrate(null) }
        assertFailsWith<SchemaTooNewException> { MtgDb(newer).checkSchema() }
    }

    @Test
    fun `three of the app's backups are kept, and one made by hand stays`() {
        val backups = File(dir, "backups").apply { mkdirs() }
        (1..4).forEach { File(backups, "mtg-2026-01-0$it-000000-pre-v2.db").writeText("old") }
        File(backups, "mtg-2026-09-30-pre-considering.db").writeText("mine")
        MtgDb(python()).migrate(backups)
        val names = backups.list()!!.sorted()
        assertEquals(4, names.size, names.toString())
        assertTrue("mtg-2026-09-30-pre-considering.db" in names)
        assertTrue("mtg-2026-01-01-000000-pre-v2.db" !in names && "mtg-2026-01-02-000000-pre-v2.db" !in names, "the oldest go")
    }

    @Test
    fun `a missing database says how to build one, and create makes it the latest version`() {
        val file = File(dir, "new/mtg.db")
        val error = assertFailsWith<MissingDatabaseException> { MtgDb(file) }
        assertContains(error.message!!, "[ Sync ]")
        MtgDb.create(file).checkSchema()
        assertEquals(Schema.VERSION, version(file))
        assertFailsWith<IllegalStateException> { MtgDb.create(file) }
    }
}
