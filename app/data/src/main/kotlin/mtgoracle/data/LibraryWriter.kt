package mtgoracle.data

import mtgoracle.core.lookup.FormatCatalog
import mtgoracle.core.deck.DeckRefusal
import mtgoracle.core.deck.DeckRefusal.Kind
import java.sql.Connection
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Folders, decks and formats: the library's shape, not a deck's contents,
 * so none of it is in the history (rename, move and format never were). A
 * port of mtg_oracle/decks.py's folder and deck functions; DeckParityTest
 * holds it to them.
 */
class LibraryWriter(private val db: MtgDb) {

    /** A new folder; its name is unique ignoring case, and never `(unsorted)`. Returns its id. */
    fun createFolder(name: String): Int = db.write { conn ->
        val n = name.trim()
        if (n.isEmpty()) throw DeckRefusal(Kind.BAD_NAME, "folder name required")
        assertValidName(n, "folder")
        if (n.equals(UNSORTED, ignoreCase = true)) throw DeckRefusal(Kind.BAD_NAME, "'$UNSORTED' is reserved for decks outside any folder")
        if (conn.query("SELECT 1 FROM deck_folders WHERE name = ? COLLATE NOCASE", n) { 1 }.isNotEmpty()) {
            throw DeckRefusal(Kind.NAME_TAKEN, "folder already exists: '$n'")
        }
        conn.insert("INSERT INTO deck_folders (name, created_at) VALUES (?, ?)", n, now()).toInt()
    }

    /**
     * Deletes a folder. One with decks is refused unless [force], which moves
     * them out of every folder, and refused even then when a deck there would
     * clash with an unsorted deck's name (decks.delete_folder).
     */
    fun deleteFolder(folderId: Int, force: Boolean = false) = db.write { conn ->
        val name = folderName(conn, folderId)
        val decks = conn.query("SELECT COUNT(*) FROM decks WHERE folder_id = ?", folderId) { getInt(1) }.single()
        if (decks > 0 && !force) throw DeckRefusal(Kind.FOLDER_NOT_EMPTY, "folder '$name' contains $decks deck(s); move them first, or delete it with its decks moved out")
        if (decks > 0) {
            val clashes = conn.query(
                "SELECT d.name FROM decks d WHERE d.folder_id = ? AND EXISTS (SELECT 1 FROM decks u WHERE u.folder_id IS NULL AND u.name = d.name COLLATE NOCASE) ORDER BY d.name COLLATE NOCASE",
                folderId,
            ) { getString(1) }
            if (clashes.isNotEmpty()) throw DeckRefusal(Kind.NAME_TAKEN, "cannot move the decks in '$name' to $UNSORTED: a deck there already has the name ${clashes.joinToString(", ") { "'$it'" }}. Rename first.")
            conn.update("UPDATE decks SET folder_id = NULL WHERE folder_id = ?", folderId)
        }
        conn.update("DELETE FROM deck_folders WHERE id = ?", folderId)
    }

    /**
     * A folder's default format (null clears it): what new decks there
     * inherit. With [applyToDecks] also stamped on its decks that have none;
     * a deck's own format is never overwritten. Returns the decks updated.
     */
    fun setFolderFormat(folderId: Int, format: String?, applyToDecks: Boolean = false): Int = db.write { conn ->
        folderName(conn, folderId)
        val fmt = conn.canonicalFormat(format)
        conn.update("UPDATE deck_folders SET format = ? WHERE id = ?", fmt, folderId)
        if (applyToDecks && fmt != null) conn.update("UPDATE decks SET format = ?, updated_at = ? WHERE folder_id = ? AND (format IS NULL OR format = '')", fmt, now(), folderId)
        else 0
    }

    /** An empty deck in [folderId] (null: outside any folder); a blank format takes the folder's default. Returns its id. */
    fun createDeck(name: String, folderId: Int? = null, format: String? = null): Int = db.write { conn -> createDeckRow(conn, name, folderId, format) }

    fun renameDeck(deckId: Int, newName: String) = db.write { conn ->
        val n = newName.trim()
        if (n.isEmpty()) throw DeckRefusal(Kind.BAD_NAME, "new deck name required")
        assertValidName(n, "deck")
        val folder = deckFolder(conn, deckId)
        // A case change of its own name is no clash.
        assertDeckNameFree(conn, n, folder, exceptId = deckId)
        conn.update("UPDATE decks SET name = ?, updated_at = ? WHERE id = ?", n, now(), deckId)
    }

    /** Into folder [folderId], or out of every folder with null. */
    fun moveDeck(deckId: Int, folderId: Int?) = db.write { conn ->
        deckFolder(conn, deckId)
        folderId?.let { folderName(conn, it) }
        val name = conn.query("SELECT name FROM decks WHERE id = ?", deckId) { getString(1) }.single()
        assertDeckNameFree(conn, name, folderId, exceptId = deckId)
        conn.update("UPDATE decks SET folder_id = ?, updated_at = ? WHERE id = ?", folderId, now(), deckId)
    }

    /** Deletes a deck; its rows, list and history go with it (the foreign keys cascade). */
    fun deleteDeck(deckId: Int) = db.write { conn ->
        deckFolder(conn, deckId)
        val poolId = conn.query("SELECT pool_id FROM decks WHERE id = ?", deckId) { getObject(1)?.let { (it as Number).toInt() } }.firstOrNull()
        conn.update("DELETE FROM deck_cards WHERE deck_id = ?", deckId)
        conn.update("DELETE FROM decks WHERE id = ?", deckId)
        // A limited deck's pool goes with it, and the pool it was opened against (the AI's, the friend's), unless another deck holds one of them.
        poolId?.let { deletePoolIfUnheld(conn, it) }
    }

    private fun deletePoolIfUnheld(conn: java.sql.Connection, poolId: Int) {
        if (conn.query("SELECT 1 FROM decks WHERE pool_id = ?", poolId) { 1 }.isNotEmpty()) return
        val rival = conn.query("SELECT rival_pool_id FROM limited_pools WHERE id = ?", poolId) { getObject(1)?.let { (it as Number).toInt() } }.firstOrNull()
        conn.update("DELETE FROM limited_pools WHERE id = ?", poolId)
        rival?.let { r ->
            val held = conn.query("SELECT 1 FROM decks WHERE pool_id = ? UNION ALL SELECT 1 FROM limited_pools WHERE rival_pool_id = ?", r, r) { 1 }.isNotEmpty()
            if (!held) conn.update("DELETE FROM limited_pools WHERE id = ?", r)
        }
    }

    /**
     * A deck's format (null or blank clears it): a format the catalog knows
     * by its key, any other as typed. Free text on purpose: a name no rule
     * knows is still a label (decks.set_deck_format).
     */
    fun setDeckFormat(deckId: Int, format: String?) = db.write { conn ->
        deckFolder(conn, deckId)
        conn.update("UPDATE decks SET format = ?, updated_at = ? WHERE id = ?", conn.canonicalFormat(format), now(), deckId)
    }

    /**
     * Every deck's and folder's format as [setDeckFormat] stores it, after a
     * backup in [backups] when one changes (`mtg-…-pre-formats.db`). Formats
     * were stored as typed before 0.6.0, so a folder's `canlander` and a
     * deck's `canadianhighlander` named one format two ways. Returns what changed.
     */
    fun canonicalFormats(backups: java.io.File?): List<String> {
        data class Row(val table: String, val id: Int, val name: String, val format: String, val canonical: String)
        fun pending(conn: Connection): List<Row> {
            val catalog = FormatCatalog(conn.customFormats())
            return listOf("deck_folders" to "folder", "decks" to "deck").flatMap { (table, kind) ->
                conn.query("SELECT id, name, format FROM $table WHERE format IS NOT NULL") { Triple(getInt(1), getString(2), getString(3)) }
                    .mapNotNull { (id, name, format) -> catalog.canonical(format)?.takeIf { it != format }?.let { Row(table, id, "$kind '$name'", format, it) } }
            }
        }
        if (db.read(::pending).isEmpty()) return emptyList()
        backups?.let { dir -> db.read { conn -> Backups.take(conn, dir, "formats") } }
        return db.write { conn ->
            pending(conn).map { r ->
                conn.update("UPDATE ${r.table} SET format = ? WHERE id = ?", r.canonical, r.id)
                "${r.name}: ${r.format} -> ${r.canonical}"
            }
        }
    }

    private fun folderName(conn: Connection, folderId: Int): String =
        conn.query("SELECT name FROM deck_folders WHERE id = ?", folderId) { getString(1) }.firstOrNull()
            ?: throw DeckRefusal(Kind.NO_SUCH_DECK, "folder not found: #$folderId")

    /** The deck's folder (null: none); refused when there is no such deck. */
    private fun deckFolder(conn: Connection, deckId: Int): Int? {
        val rows = conn.query("SELECT folder_id FROM decks WHERE id = ?", deckId) { getObject(1)?.let { (it as Number).toInt() } }
        if (rows.isEmpty()) throw DeckRefusal(Kind.NO_SUCH_DECK, "no deck #$deckId")
        return rows.single()
    }

    companion object {
        /** How decks outside any folder are named, as the TUI names them. */
        const val UNSORTED = "(unsorted)"

        internal fun now(): String = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()

        /** `/` separates folder and deck in every path (`cd F/D`), so a name with one could never be addressed. */
        internal fun assertValidName(name: String, kind: String) {
            if ('/' in name) throw DeckRefusal(Kind.BAD_NAME, "$kind name '$name' contains '/', which separates folder and deck in a path (<folder>/<deck>) — pick another name")
        }

        /** Refused unless no other deck in the folder has this name, ignoring case. */
        internal fun assertDeckNameFree(conn: Connection, name: String, folderId: Int?, exceptId: Int? = null) {
            val taken = conn.query("SELECT 1 FROM decks WHERE folder_id IS ? AND name = ? COLLATE NOCASE AND id IS NOT ?", folderId, name, exceptId) { 1 }
            if (taken.isNotEmpty()) {
                val where = folderId?.let { id -> conn.query("SELECT name FROM deck_folders WHERE id = ?", id) { "folder '${getString(1)}'" }.firstOrNull() } ?: UNSORTED
                throw DeckRefusal(Kind.NAME_TAKEN, "a deck named '$name' already exists in $where")
            }
        }

        /** decks._create_deck on an open connection (an import creates and loads in one transaction). */
        internal fun createDeckRow(conn: Connection, name: String, folderId: Int?, format: String?): Int {
            val n = name.trim()
            if (n.isEmpty()) throw DeckRefusal(Kind.BAD_NAME, "deck name required")
            assertValidName(n, "deck")
            if (folderId != null && conn.query("SELECT 1 FROM deck_folders WHERE id = ?", folderId) { 1 }.isEmpty()) throw DeckRefusal(Kind.NO_SUCH_DECK, "folder not found: #$folderId")
            assertDeckNameFree(conn, n, folderId)
            // A deck dropped into a folder gets its default: the whole point of the folder's format.
            val fmt = conn.canonicalFormat(format
                ?.takeIf { it.isNotBlank() } ?: folderId?.let { conn.query("SELECT format FROM deck_folders WHERE id = ?", it) { getString(1) }.firstOrNull() })
            return conn.insert("INSERT INTO decks (folder_id, name, format, description, created_at, updated_at) VALUES (?, ?, ?, NULL, ?, ?)", folderId, n, fmt, now(), now()).toInt()
        }
    }
}
