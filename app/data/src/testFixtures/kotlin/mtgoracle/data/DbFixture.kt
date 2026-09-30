package mtgoracle.data

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.DriverManager

/**
 * A throwaway copy of data/mtg.db — tests never open the real file for
 * writing (CLAUDE.md, tests/db_sandbox.py on the Python side).
 *
 * The copy is migrated by the app's own migrations (MtgDb.migrate), so the
 * tests run on the schema the user's database will have once the app starts.
 */
object DbFixture {
    val repoRoot: File = File(System.getProperty("mtgoracle.repoRoot") ?: "..").canonicalFile
    val realDb: File = File(repoRoot, "data/mtg.db").canonicalFile

    val available: Boolean get() = realDb.isFile

    /**
     * The real database through [MtgDb]'s read-only connection, for tests
     * that only read cards, rules and combos: a 330 MB copy per class buys
     * nothing there. Tests that write anything use [copy].
     */
    fun readOnly(): MtgDb = MtgDb(realDb)

    /** Runs [code] with Python from the repo root (scripts see `mtg_oracle`), returning stdout; fails loudly on a non-zero exit. */
    fun python(code: String, vararg args: String): String {
        val process = ProcessBuilder(listOf("python", "-c", code) + args)
            .directory(repoRoot)
            .apply { environment()["PYTHONIOENCODING"] = "utf-8" }
            .start()
        val out = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        val err = process.errorStream.bufferedReader(Charsets.UTF_8).readText()
        check(process.waitFor() == 0) { "python failed: $err" }
        return out
    }

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
