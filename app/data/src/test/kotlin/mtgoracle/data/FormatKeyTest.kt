package mtgoracle.data

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A deck's and a folder's format are stored by the format's key when the
 * catalog knows it: a folder of `canlander` and a deck of
 * `canadianhighlander` were one format written two ways.
 */
class FormatKeyTest {
    private fun formats(db: MtgDb, table: String) = db.read { c -> c.query("SELECT name, format FROM $table WHERE format IS NOT NULL") { getString(1) to getString(2) } }

    @Test
    fun `the library's formats are stored by their key once, after a backup, and a second pass finds nothing`() {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        val file = DbFixture.copy()
        val db = MtgDb(file)
        val backups = File(file.parentFile, "backups")
        val catalog = Lookup(db).formats
        val before = formats(db, "decks").toMap()
        val changed = LibraryWriter(db).canonicalFormats(backups)
        println("changed: $changed")
        val after = formats(db, "decks").toMap()
        assertEquals(before.keys, after.keys, "no deck gained or lost a format")
        for ((name, format) in after) assertEquals(catalog.canonical(format), format, "$name is stored by its key")
        for ((name, format) in formats(db, "deck_folders")) assertEquals(catalog.canonical(format), format, "folder $name")
        for ((name, format) in before) assertEquals(catalog.resolve(format)?.key, catalog.resolve(after.getValue(name))?.key, "$name names the same format")
        if (changed.isNotEmpty()) assertTrue(backups.listFiles().orEmpty().any { it.name.endsWith("-pre-formats.db") }, "a backup came first")
        assertEquals(emptyList(), LibraryWriter(db).canonicalFormats(backups), "a second pass has nothing to do")
    }

    @Test
    fun `a format set, a deck made in a folder, and a label no rule knows`() {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        val db = MtgDb(DbFixture.copy())
        val writer = LibraryWriter(db)
        val folder = writer.createFolder("Format key test")
        writer.setFolderFormat(folder, "CHL")
        assertEquals("canadianhighlander", formats(db, "deck_folders").toMap()["Format key test"])
        val deck = writer.createDeck("In the folder", folder)
        assertEquals("canadianhighlander", formats(db, "decks").toMap()["In the folder"], "the folder's default, by its key")
        writer.setDeckFormat(deck, "EDH")
        assertEquals("commander", formats(db, "decks").toMap()["In the folder"])
        writer.setDeckFormat(deck, " Kitchen Table ")
        assertEquals("Kitchen Table", formats(db, "decks").toMap()["In the folder"], "a label is kept as typed")
    }
}
