package mtgoracle.data

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.DriverManager

/**
 * A throwaway copy of data/mtg.db — tests never open the real file for
 * writing (CLAUDE.md). Tests that need known data rather than the user's
 * use [FixtureDb], which needs no data/mtg.db at all.
 *
 * The copy is migrated by the app's own migrations (MtgDb.migrate), so the
 * tests run on the schema the user's database will have once the app starts.
 */
object DbFixture {
    val repoRoot: File = File(System.getProperty("mtgoracle.repoRoot") ?: "..").canonicalFile
    val realDb: File = File(repoRoot, "data/mtg.db").canonicalFile

    val available: Boolean get() = realDb.isFile

    /**
     * The real database, every connection read-only, for tests that only read
     * cards, rules and combos: a 330 MB copy per class buys nothing there. A
     * write through it fails rather than reaching the user's decks; tests that
     * write anything use [copy].
     */
    fun readOnly(): MtgDb = MtgDb(realDb, writable = false)

    /** The real database was never opened for writing by these tests (its file time is no proof: the user's app writes it). */
    fun assertUntouched() = check(!MtgDb.openedForWriting(realDb)) { "a test opened the real database $realDb for writing" }

    /** A fresh copy in a new temp dir, migrated to this build's schema as the app would (without a backup). */
    fun copy(): File {
        check(available) { "no $realDb to copy" }
        val dir = Files.createTempDirectory("mtg-oracle-kt-test-").toFile()
        val copy = File(dir, "mtg.db")
        Files.copy(realDb.toPath(), copy.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
        check(copy.canonicalFile != realDb) { "refusing to use the real database" }
        MtgDb(copy).migrate(backups = null)
        // The user's own recorded games are theirs, not the tests': a test counts the rows it wrote.
        // (MatchTest broke the day the real DB got its first row.)
        DriverManager.getConnection("jdbc:sqlite:${copy.path}").use { it.createStatement().execute("DELETE FROM games") }
        return copy
    }

    /** A card's printing, set on the copy only (for printing tests). */
    fun setPrinting(db: File, deckId: Int, cardName: String, setCode: String, collectorNumber: String) {
        DriverManager.getConnection("jdbc:sqlite:${db.path}").use { conn ->
            conn.prepareStatement("UPDATE deck_cards SET set_code = ?, collector_number = ? WHERE deck_id = ? AND card_name = ?").use {
                it.setString(1, setCode); it.setString(2, collectorNumber); it.setInt(3, deckId); it.setString(4, cardName)
                check(it.executeUpdate() == 1) { "no $cardName in deck $deckId" }
            }
        }
    }
}
