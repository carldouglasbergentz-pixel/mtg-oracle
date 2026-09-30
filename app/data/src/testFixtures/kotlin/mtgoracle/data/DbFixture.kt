package mtgoracle.data

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.DriverManager

/**
 * A throwaway copy of data/mtg.db — tests never open the real file for
 * writing (CLAUDE.md, tests/db_sandbox.py on the Python side).
 *
 * The schema is Python's (scripts/self_heal.py owns every migration), so the
 * copy is healed by running that very script against it: the tests exercise
 * the schema the user will have once they run it, not a hand-written guess.
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

    /** A fresh copy in a new temp dir, migrated by self_heal. */
    fun copy(): File {
        check(available) { "no $realDb to copy" }
        val dir = Files.createTempDirectory("mtg-oracle-kt-test-").toFile()
        val copy = File(dir, "mtg.db")
        Files.copy(realDb.toPath(), copy.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
        check(copy.canonicalFile != realDb) { "refusing to use the real database" }
        heal(copy)
        // The user's own recorded games are theirs, not the tests': a test counts the rows it wrote.
        // (MatchTest broke the day the real DB got its first row.)
        DriverManager.getConnection("jdbc:sqlite:${copy.path}").use { it.createStatement().execute("DELETE FROM games") }
        return copy
    }

    /** Runs scripts/self_heal.py's migrations on [db] (never the real one); fails loudly if any fails. */
    fun heal(db: File) {
        check(db.canonicalFile != realDb) { "refusing to migrate the real database" }
        val code = "import sys; from pathlib import Path; sys.path.insert(0, 'scripts'); import self_heal; " +
            "r, f = self_heal.run(db_path=Path(sys.argv[1])); print(chr(10).join(r)); sys.exit(1 if f else 0)"
        val process = ProcessBuilder("python", "-c", code, db.absolutePath)
            .directory(repoRoot).redirectErrorStream(true)
            .apply { environment()["PYTHONIOENCODING"] = "utf-8" }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "self_heal failed on the test copy: $output" }
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
