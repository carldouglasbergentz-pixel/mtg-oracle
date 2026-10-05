package mtgoracle.data

import mtgoracle.core.library.DeckAction
import mtgoracle.core.library.ImportChoice
import mtgoracle.core.library.PackageManifest
import mtgoracle.core.library.PackageScope
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.sql.DriverManager
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A library out as a package and back in: on copies of the user's database,
 * the export taken whole, an emptied copy filled from it, and the import's
 * rules (nothing twice, nothing overwritten, a clash beside the deck it meets).
 */
class PackageStoreTest {
    private val dirs = mutableListOf<File>()
    @AfterTest fun clean() = dirs.forEach { it.deleteRecursively() }

    /** A copy with games of its own (the fixture's copy has none: the user's are theirs): two between two decks, one against a deck since gone. */
    private fun copy(): MtgDb {
        val f = DbFixture.copy(); dirs += f.parentFile
        val db = MtgDb(f)
        val (a, b) = Library(db).decks().take(2).map { it.id to it.name }
        sql(db,
            "INSERT INTO games (played_at, mode, deck_id, deck_name, opponent_deck_id, opponent_name, seed, winner, turns, match_id, game_no, match_format) " +
                "VALUES ('2026-10-01T10:00:00Z', 'human_vs_ai', ${a.first}, '${a.second.replace("'", "''")}', ${b.first}, '${b.second.replace("'", "''")}', 1, 'me', 7, 'm1', 1, 'bo3')",
            "INSERT INTO games (played_at, mode, deck_id, deck_name, opponent_deck_id, opponent_name, seed, winner, turns, match_id, game_no, match_format) " +
                "VALUES ('2026-10-01T10:20:00Z', 'human_vs_ai', ${a.first}, '${a.second.replace("'", "''")}', ${b.first}, '${b.second.replace("'", "''")}', 2, 'opponent', 9, 'm1', 2, 'bo3')",
            "INSERT INTO games (played_at, mode, deck_id, deck_name, opponent_deck_id, opponent_name, seed, winner, turns) " +
                "VALUES ('2026-09-01T09:00:00Z', 'ai_vs_ai', NULL, 'A deck since deleted', ${b.first}, '${b.second.replace("'", "''")}', 3, 'draw', 30)",
        )
        return db
    }
    private fun store(db: MtgDb): PackageStore { val lookup = Lookup(db); return PackageStore(db, lookup.names, DeckWriter(db, lookup.names, lookup.formats)) }
    private fun sql(db: MtgDb, vararg statements: String) = DriverManager.getConnection("jdbc:sqlite:${db.file.path}").use { c -> c.createStatement().use { st -> statements.forEach(st::executeUpdate) } }
    private fun count(db: MtgDb, table: String): Int = DriverManager.getConnection("jdbc:sqlite:${db.file.path}").use { c -> c.createStatement().use { it.executeQuery("SELECT COUNT(*) FROM $table").use { rs -> rs.next(); rs.getInt(1) } } }
    /** A library with no decks, games or own combos, the cards all there. */
    private fun emptied(): MtgDb = copy().also { sql(it, "PRAGMA foreign_keys = ON", "DELETE FROM decks", "DELETE FROM deck_folders", "DELETE FROM games", "DELETE FROM user_combo_cards", "DELETE FROM user_combos") }

    @Test
    fun `the whole library goes out and comes back the same, history, games and combos with it`() {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        val source = copy()
        val pkg = store(source).export(PackageScope.Library, "test", "2026-10-05T12:00:00Z")
        assertTrue(pkg.decks.isNotEmpty())
        val file = File(source.file.parentFile, "library" + PackageManifest.FILE_SUFFIX)
        PackageFile.write(file, pkg)
        val read = PackageFile.read(file)
        assertEquals(pkg, read, "the file holds the package as it was")

        val target = emptied()
        val backups = File(target.file.parentFile, "backups")
        val plan = store(target).plan(read)
        assertTrue(plan.decks.all { it.action == DeckAction.NEW })
        val result = store(target).import(read, ImportChoice(), backups)
        assertEquals(pkg.decks.size, result.decks.size)
        assertTrue(backups.listFiles()!!.single().name.matches(Regex("""mtg-.*-pre-import\.db""")), "a backup first")
        val again = store(target).export(PackageScope.Library, "test", "2026-10-05T12:00:00Z")
        assertEquals(pkg.decks, again.decks, "every deck as it was: cards, printings, considering, substitutes, history")
        assertEquals(3, pkg.games.size)
        assertEquals(pkg.games.size, again.games.size)
        assertEquals(pkg.games.map { it.identity to (it.deck != null) }, again.games.map { it.identity to (it.deck != null) }, "each game on its deck")
        assertEquals(pkg.combos.map { it.identity }, again.combos.map { it.identity })
        for (t in listOf("decks", "deck_cards", "deck_revisions", "deck_changes", "forge_substitutions", "games", "user_combos")) assertEquals(count(source, t), count(target, t), t)
    }

    @Test
    fun `imported again it adds nothing, and a deck changed since comes in beside it`() {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        val db = copy()
        val pkg = store(db).export(PackageScope.Library, "test", "now")
        val before = listOf("decks", "deck_cards", "games", "user_combos").associateWith { count(db, it) }
        val same = store(db).import(pkg, ImportChoice(), backups = null)
        assertEquals(emptyList(), same.decks)
        assertEquals(0, same.games + same.combos)
        assertEquals(before, before.keys.associateWith { count(db, it) }, "nothing twice")

        val changed = pkg.decks.first()
        sql(db, "UPDATE deck_cards SET quantity = quantity + 1 WHERE id = (SELECT MIN(dc.id) FROM deck_cards dc JOIN decks d ON d.id = dc.deck_id WHERE d.name = '${changed.name.replace("'", "''")}')")
        val plan = store(db).plan(pkg)
        val p = plan.decks.first { it.deck.key == changed.key }
        assertEquals(DeckAction.RENAMED, p.action)
        assertEquals("${changed.name} (2)", p.name)
        assertEquals(1, plan.decksToImport.size, "the rest are the same")
        assertEquals(listOf("${changed.name} (2)"), store(db).import(pkg, ImportChoice(), null).decks)
    }

    @Test
    fun `without its history a deck has one import revision, and without games or combos none come`() {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        val pkg = store(copy()).export(PackageScope.Library, "test", "now")
        val target = emptied()
        store(target).import(pkg, ImportChoice(history = false, games = false, combos = false), null)
        val actions = DriverManager.getConnection("jdbc:sqlite:${target.file.path}").use { c ->
            c.createStatement().use { it.executeQuery("SELECT deck_id, action FROM deck_revisions").use { rs -> buildList { while (rs.next()) add(rs.getInt(1) to rs.getString(2)) } } }
        }
        assertEquals(pkg.decks.count { it.cards.isNotEmpty() || it.considering.isNotEmpty() }, actions.size)
        assertTrue(actions.all { it.second == "import" })
        assertEquals(0, count(target, "games"))
        assertEquals(0, count(target, "user_combos"))
    }

    @Test
    fun `a card the database lacks is named before the import, and a file that is no package is refused`() {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        val db = copy()
        val pkg = store(db).export(PackageScope.Library, "test", "now")
        val deck = pkg.decks.first()
        val odd = pkg.copy(decks = listOf(deck.copy(name = "Odd", cards = deck.cards + deck.cards.first().copy(name = "Not A Real Card"))))
        assertEquals(listOf("Not A Real Card"), store(db).plan(odd).missingCards)

        val dir = db.file.parentFile
        val junk = File(dir, "junk.mtgoracle").apply { writeBytes(ByteArray(100) { it.toByte() }) }
        assertFailsWith<PackageRefused> { PackageFile.read(junk) }
        val noManifest = File(dir, "empty.mtgoracle").apply { ZipOutputStream(outputStream()).use { it.putNextEntry(ZipEntry("decks.json")); it.write("[]".toByteArray()); it.closeEntry() } }
        assertTrue(assertFailsWith<PackageRefused> { PackageFile.read(noManifest) }.message!!.contains("no manifest"))
        val newer = File(dir, "newer.mtgoracle").also { PackageFile.write(it, pkg.copy(manifest = pkg.manifest.copy(version = PackageManifest.VERSION + 1))) }
        assertTrue(assertFailsWith<PackageRefused> { PackageFile.read(newer) }.message!!.contains("newer"))
    }

    @Test
    fun `a deck or a folder alone takes its own games only`() {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        val db = copy()
        val all = store(db).export(PackageScope.Library, "test", "now")
        val played = all.games.mapNotNull { it.deck }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        assumeTrue(played != null, "a deck with games")
        val id = Library(db).decks().first { "${it.folderName.orEmpty()}/${it.name}" == played }.id
        val one = store(db).export(PackageScope.Deck(id), "test", "now")
        assertEquals(1, one.decks.size)
        assertTrue(one.games.isNotEmpty() && one.games.all { it.deck == played || it.opponentDeck == played }, "only its games")
        assertTrue(one.manifest.scope.startsWith("deck "))
    }
}
