package mtgoracle.data

import mtgoracle.core.library.DeckAction
import mtgoracle.core.library.DeckPlan
import mtgoracle.core.library.ImportChoice
import mtgoracle.core.library.ImportPlan
import mtgoracle.core.library.ImportResult
import mtgoracle.core.library.LibraryPackage
import mtgoracle.core.library.PackageManifest
import mtgoracle.core.library.PackageScope
import mtgoracle.core.library.PackagedCard
import mtgoracle.core.library.PackagedChange
import mtgoracle.core.library.PackagedCombo
import mtgoracle.core.library.PackagedConsidering
import mtgoracle.core.library.PackagedDeck
import mtgoracle.core.library.PackagedGame
import mtgoracle.core.library.PackagedRevision
import mtgoracle.core.library.PackagedSubstitution
import java.io.File
import java.sql.Connection

/**
 * A library's decks, games and own combos as a package, and a package into
 * a library. The export reads rows as they are (dates, printings, history);
 * the import plans first ([plan]: what is new, what is already here, what
 * clashes, which cards the database lacks) and then writes in one
 * transaction after a backup ([import]), so a failure leaves nothing behind.
 *
 * A deck already here with the same contents is left alone; one of the same
 * name with other contents comes in as `Name (2)`. Nothing is overwritten.
 * Games and combos already here (the same game, the same cards and text) are
 * not taken twice. Card names are kept as the package has them, an unknown
 * one too: an import is verbatim, as a pasted list is.
 */
class PackageStore(private val db: MtgDb, private val names: CardNames, private val writer: DeckWriter) {

    fun export(scope: PackageScope, app: String, at: String): LibraryPackage = db.read { conn ->
        val ids = when (scope) {
            PackageScope.Library -> conn.query("SELECT id FROM decks ORDER BY id") { getInt(1) }
            is PackageScope.Folder -> conn.query("SELECT id FROM decks WHERE folder_id = ? ORDER BY id", scope.id) { getInt(1) }
            is PackageScope.Deck -> conn.query("SELECT id FROM decks WHERE id = ?", scope.id) { getInt(1) }
        }
        val decks = ids.map { deck(conn, it) }
        val keys = ids.zip(decks).associate { (id, d) -> id to d.key }
        val games = conn.query("SELECT * FROM games ORDER BY id") { game(this, keys) }
            .filter { (g, mine) -> scope == PackageScope.Library || mine }.map { it.first }
        val label = when (scope) {
            PackageScope.Library -> "the library"
            is PackageScope.Folder -> "folder " + (conn.query("SELECT name FROM deck_folders WHERE id = ?", scope.id) { getString(1) }.firstOrNull() ?: "#${scope.id}")
            is PackageScope.Deck -> "deck " + (decks.firstOrNull()?.name ?: "#${scope.id}")
        }
        LibraryPackage(PackageManifest(PackageManifest.VERSION, app, conn.userVersion(), at, label), decks, games, combos(conn))
    }

    /** What [import] would do with [pkg], and what it would want first (cards a sync may bring). Writes nothing. */
    fun plan(pkg: LibraryPackage): ImportPlan = db.read { conn -> plan(conn, pkg) }

    private fun plan(conn: Connection, pkg: LibraryPackage): ImportPlan {
        val taken = HashSet<String>() // folder/name of every deck here or planned, lower-cased
        conn.query("SELECT COALESCE(f.name, ''), d.name FROM decks d LEFT JOIN deck_folders f ON f.id = d.folder_id") { "${getString(1)}/${getString(2)}".lowercase() }.forEach { taken += it }
        val decks = pkg.decks.map { d ->
            val here = existingDeck(conn, d.folder, d.name)
            when {
                here == null -> DeckPlan(d, DeckAction.NEW, d.name).also { taken += d.key.lowercase() }
                deck(conn, here).content == d.content -> DeckPlan(d, DeckAction.SAME, d.name)
                else -> {
                    val name = generateSequence(2) { it + 1 }.map { "${d.name} ($it)" }.first { "${d.folder.orEmpty()}/$it".lowercase() !in taken }
                    taken += "${d.folder.orEmpty()}/$name".lowercase()
                    DeckPlan(d, DeckAction.RENAMED, name)
                }
            }
        }
        val knownGames = conn.query("SELECT * FROM games") { game(this, emptyMap()).first.identity }.toSet()
        val knownCombos = combos(conn).map { it.identity }.toSet()
        val newGames = pkg.games.count { it.identity !in knownGames }
        val newCombos = pkg.combos.count { it.identity !in knownCombos }
        val named = pkg.decks.flatMap { d -> d.cards.map { it.name } + d.considering.map { it.name } + d.substitutions.map { it.substitute } } +
            pkg.combos.flatMap { c -> c.cards.map { it.first } }
        val missing = named.distinctBy { it.lowercase() }.filter { names.resolve(it) == null }.sortedWith(String.CASE_INSENSITIVE_ORDER)
        return ImportPlan(decks, newGames, pkg.games.size - newGames, newCombos, pkg.combos.size - newCombos, missing, pkg.decks.sumOf { it.history.size })
    }

    /**
     * [pkg] into the library, as [plan] had it and as [choice] takes it: after a copy of the
     * database in [backups] (`mtg-…-pre-import.db`, the newest three kept), in one transaction.
     */
    fun import(pkg: LibraryPackage, choice: ImportChoice, backups: File?): ImportResult {
        val backup = backups?.let { dir -> db.read { conn -> Backups.take(conn, dir, "import") } }
        return db.write { conn ->
            val plan = plan(conn, pkg)
            val deckIds = HashMap<String, Int>() // package key -> the deck here, imported or already here
            var revisions = 0
            for (p in plan.decks) {
                if (p.action == DeckAction.SAME) { existingDeck(conn, p.deck.folder, p.deck.name)?.let { deckIds[p.deck.key] = it }; continue }
                val id = writeDeck(conn, p, folderFor(conn, p.deck))
                deckIds[p.deck.key] = id
                // Its history as it was (none, too); without it, the deck as one import revision, so it can be undone.
                revisions += if (choice.history) writeHistory(conn, id, p.deck.history)
                    else if (writer.recordImported(conn, id, "from a package (${pkg.manifest.scope})") != null) 1 else 0
            }
            val games = if (!choice.games) 0 else {
                val known = conn.query("SELECT * FROM games") { game(this, emptyMap()).first.identity }.toMutableSet()
                pkg.games.filter { known.add(it.identity) }.onEach { writeGame(conn, it, deckIds) }.size
            }
            val combos = if (!choice.combos) 0 else {
                val known = combos(conn).map { it.identity }.toMutableSet()
                pkg.combos.filter { known.add(it.identity) }.onEach { writeCombo(conn, it) }.size
            }
            ImportResult(plan.decksToImport.map { it.name }, plan.decks.count { it.action == DeckAction.SAME }, games, combos, revisions, backup?.name)
        }
    }

    // --- reading ---

    private fun existingDeck(conn: Connection, folder: String?, name: String): Int? = conn.query(
        "SELECT d.id FROM decks d LEFT JOIN deck_folders f ON f.id = d.folder_id " +
            "WHERE d.name = ? COLLATE NOCASE AND ((? IS NULL AND d.folder_id IS NULL) OR f.name = ? COLLATE NOCASE)",
        name, folder, folder,
    ) { getInt(1) }.firstOrNull()

    private fun deck(conn: Connection, id: Int): PackagedDeck {
        data class Head(val folder: String?, val folderFormat: String?, val name: String, val format: String?, val description: String?, val created: String?, val updated: String?)
        val head = conn.query(
            "SELECT f.name, f.format, d.name, d.format, d.description, d.created_at, d.updated_at FROM decks d LEFT JOIN deck_folders f ON f.id = d.folder_id WHERE d.id = ?", id,
        ) { Head(getString(1), getString(2), getString(3), getString(4), getString(5), getString(6), getString(7)) }.single()
        val cards = conn.query(
            "SELECT card_name, quantity, category, is_commander, is_sideboard, added_at, set_code, collector_number FROM deck_cards WHERE deck_id = ? ORDER BY id", id,
        ) { PackagedCard(getString(1), getInt(2), getString(3), getInt(4) != 0, getInt(5) != 0, getString(6), getString(7), getString(8)) }
        val considering = conn.query("SELECT card_name, quantity, added_at FROM deck_considering WHERE deck_id = ? ORDER BY id", id) {
            PackagedConsidering(getString(1), getInt(2), getString(3))
        }
        val substitutions = conn.query("SELECT card_name, substitute, added_at FROM forge_substitutions WHERE deck_id = ? ORDER BY id", id) {
            PackagedSubstitution(getString(1), getString(2), getString(3))
        }
        val history = conn.query("SELECT id, at, action, note FROM deck_revisions WHERE deck_id = ? ORDER BY id", id) { listOf(getLong(1), getString(2), getString(3), getString(4)) }
            .map { (rid, at, action, note) ->
                val changes = conn.query(
                    "SELECT card_name, section, qty_before, qty_after, set_code_before, collector_number_before, set_code_after, collector_number_after " +
                        "FROM deck_changes WHERE revision_id = ? ORDER BY id", rid,
                ) { PackagedChange(getString(1), getString(2), getInt(3), getInt(4), getString(5), getString(6), getString(7), getString(8)) }
                PackagedRevision(at as String, action as String, note as String?, changes)
            }
        return PackagedDeck(head.folder, head.folderFormat, head.name, head.format, head.description, head.created, head.updated, cards, considering, substitutions, history)
    }

    /** A games row, its decks by [keys]; and whether a deck of its is among them. */
    private fun game(rs: java.sql.ResultSet, keys: Map<Int, String>): Pair<PackagedGame, Boolean> = with(rs) {
        fun intOrNull(c: String) = getObject(c)?.let { (it as Number).toInt() }
        val deckId = intOrNull("deck_id")
        val opponentId = intOrNull("opponent_deck_id")
        PackagedGame(
            playedAt = getString("played_at"), mode = getString("mode"), deck = deckId?.let(keys::get), deckName = getString("deck_name"),
            opponentDeck = opponentId?.let(keys::get), opponentName = getString("opponent_name"), opponentAiVariant = intOrNull("opponent_ai_variant"),
            seed = getObject("seed")?.let { (it as Number).toLong() }, winner = getString("winner"), turns = intOrNull("turns"),
            durationMs = getObject("duration_ms")?.let { (it as Number).toLong() }, forgeVersion = getString("forge_version"),
            matchId = getString("match_id"), gameNo = intOrNull("game_no"), matchFormat = getString("match_format"),
            conceded = intOrNull("conceded"), deckAiVariant = intOrNull("deck_ai_variant"),
        ) to (deckId in keys || opponentId in keys)
    }

    private fun combos(conn: Connection): List<PackagedCombo> =
        conn.query("SELECT id, name, color_identity, description, added_at FROM user_combos ORDER BY id") { listOf(getString(1), getString(2), getString(3), getString(4), getString(5)) }
            .map { (id, name, ci, description, at) ->
                val cards = conn.query("SELECT card_name, quantity FROM user_combo_cards WHERE combo_id = ? ORDER BY rowid", id) { getString(1) to (getObject(2)?.let { (it as Number).toInt() } ?: 1) }
                PackagedCombo(name, ci, description.orEmpty(), at, cards)
            }

    // --- writing ---

    /** The deck's folder here, by name: made (with the package's default format) when there is none. */
    private fun folderFor(conn: Connection, deck: PackagedDeck): Int? {
        val name = deck.folder ?: return null
        conn.query("SELECT id FROM deck_folders WHERE name = ? COLLATE NOCASE", name) { getInt(1) }.firstOrNull()?.let { return it }
        LibraryWriter.assertValidName(name, "folder")
        return conn.insert("INSERT INTO deck_folders (name, created_at, format) VALUES (?, ?, ?)", name, LibraryWriter.now(), conn.canonicalFormat(deck.folderFormat)).toInt()
    }

    private fun writeDeck(conn: Connection, p: DeckPlan, folderId: Int?): Int {
        val d = p.deck
        val id = LibraryWriter.createDeckRow(conn, p.name, folderId, d.format)
        // As it was: its own format (none too, not the folder's default), its description and dates.
        val now = LibraryWriter.now()
        conn.update("UPDATE decks SET format = ?, description = ?, created_at = ?, updated_at = ? WHERE id = ?", conn.canonicalFormat(d.format), d.description, d.createdAt ?: now, d.updatedAt ?: now, id)
        for (c in d.cards) {
            conn.update(
                "INSERT INTO deck_cards (deck_id, card_name, quantity, category, is_commander, is_sideboard, added_at, set_code, collector_number) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, names.resolve(c.name) ?: c.name, c.quantity, c.category, if (c.commander) 1 else 0, if (c.sideboard) 1 else 0, c.addedAt ?: now, c.setCode, c.collectorNumber,
            )
        }
        for (c in d.considering) conn.update("INSERT INTO deck_considering (deck_id, card_name, quantity, added_at) VALUES (?, ?, ?, ?)", id, names.resolve(c.name) ?: c.name, c.quantity, c.addedAt ?: now)
        for (s in d.substitutions) conn.update("INSERT INTO forge_substitutions (deck_id, card_name, substitute, added_at) VALUES (?, ?, ?, ?)",
            id, names.resolve(s.card) ?: s.card, names.resolve(s.substitute) ?: s.substitute, s.addedAt ?: now)
        return id
    }

    private fun writeHistory(conn: Connection, deckId: Int, history: List<PackagedRevision>): Int {
        for (r in history) {
            val rid = conn.insert("INSERT INTO deck_revisions (deck_id, at, action, note) VALUES (?, ?, ?, ?)", deckId, r.at, r.action, r.note)
            for (c in r.changes) conn.update(
                "INSERT INTO deck_changes (revision_id, card_name, section, qty_before, qty_after, set_code_before, collector_number_before, set_code_after, collector_number_after) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                rid, c.card, c.section, c.before, c.after, c.setBefore, c.numberBefore, c.setAfter, c.numberAfter,
            )
        }
        return history.size
    }

    private fun writeGame(conn: Connection, g: PackagedGame, deckIds: Map<String, Int>) {
        conn.update(
            "INSERT INTO games (played_at, mode, deck_id, deck_name, opponent_deck_id, opponent_name, opponent_ai_variant, seed, winner, turns, duration_ms, " +
                "forge_version, log_path, match_id, game_no, match_format, conceded, deck_ai_variant) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?)",
            g.playedAt, g.mode, g.deck?.let(deckIds::get), g.deckName, g.opponentDeck?.let(deckIds::get), g.opponentName, g.opponentAiVariant ?: 0,
            g.seed, g.winner, g.turns, g.durationMs, g.forgeVersion, g.matchId, g.gameNo, g.matchFormat, g.conceded ?: 0, g.deckAiVariant ?: 0,
        )
    }

    private fun writeCombo(conn: Connection, c: PackagedCombo) {
        val used = conn.query("SELECT id FROM user_combos WHERE id LIKE 'user-%'") { getString(1) }.mapNotNull { it.substringAfter('-').toIntOrNull() }.toSet()
        val id = "user-%03d".format(generateSequence(1) { it + 1 }.first { it !in used })
        conn.update("INSERT INTO user_combos (id, name, color_identity, description, added_at, added_by) VALUES (?, ?, ?, ?, ?, 'user')",
            id, c.name, c.colorIdentity, c.description, c.addedAt ?: LibraryWriter.now())
        c.cards.forEach { (name, qty) -> conn.update("INSERT INTO user_combo_cards (combo_id, card_name, quantity) VALUES (?, ?, ?)", id, names.resolve(name) ?: name, qty) }
    }
}
