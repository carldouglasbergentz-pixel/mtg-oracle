package mtgoracle.data

import mtgoracle.core.deck.DeckChange
import mtgoracle.core.deck.ParsedRow
import mtgoracle.core.deck.DeckRefusal
import mtgoracle.core.deck.DeckRefusal.Kind
import mtgoracle.core.deck.DeckRevision
import mtgoracle.core.deck.DeckRules
import mtgoracle.core.deck.DeckSection
import mtgoracle.core.deck.Printing
import mtgoracle.core.limited.OpenedPool
import mtgoracle.core.limited.PoolRule
import mtgoracle.core.lookup.FormatCatalog
import mtgoracle.core.lookup.FormatInfo
import mtgoracle.core.lookup.Formats
import java.sql.Connection
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Changes to a deck's contents, held to the deck's rules and recorded in its
 * history: one call is one revision (`deck_revisions` + `deck_changes`),
 * written in the same transaction as the change, so the TUI's `history`
 * and `undo` see the app's changes and the other way round. A port of
 * mtg_oracle/decks.py's writes; DeckParityTest runs the same sequence
 * through both and compares rows, history and refusals.
 *
 * Every rule check runs before the first write, and a refusal rolls the
 * transaction back ([MtgDb.write]), so a refused change changes nothing.
 */
class DeckWriter(private val db: MtgDb, private val names: CardNames, private val formats: FormatCatalog) {

    /** Adds [quantity] copies to the main deck or the sideboard (decks.add_card_to_deck). Returns the canonical name. */
    fun add(deckId: Int, card: String, quantity: Int = 1, sideboard: Boolean = false, force: Boolean = false): String = db.write { conn ->
        requireDeck(conn, deckId)
        val before = snapshot(conn, deckId)
        val canonical = addCard(conn, deckId, card, quantity, sideboard, force)
        recordRevision(conn, deckId, "add", before, canonical)
        canonical
    }

    /**
     * Into the main deck, as the workspace's `+` does. A deck built from a
     * pool takes the copies out of its sideboard (the pool) while it holds
     * them, as one `move`; anything else is [add], under every rule.
     */
    fun addToMain(deckId: Int, card: String, quantity: Int = 1, force: Boolean = false): String {
        val canonical = names.resolve(card) ?: card
        val inPool = db.read { conn ->
            if (poolOf(conn, deckId) == null) 0 else conn.query(
                "SELECT COALESCE(SUM(quantity), 0) FROM deck_cards WHERE deck_id = ? AND card_name = ? COLLATE NOCASE AND is_sideboard = 1", deckId, canonical,
            ) { getInt(1) }.single()
        }
        return if (inPool >= quantity) move(deckId, canonical, DeckSection.SIDEBOARD, DeckSection.MAIN, quantity, force)
        else add(deckId, card, quantity, force = force)
    }

    /**
     * Out of the main deck, as the workspace's `-` does. A deck built from a
     * pool puts the copies back in its sideboard (the pool), except basic
     * lands beyond what the pool opened, which go: one revision either way.
     * Anything else is [remove]. Returns (name, taken out, left in the main deck).
     */
    fun removeFromMain(deckId: Int, card: String, quantity: Int? = null): Triple<String, Int, Int> {
        val canonical = names.resolve(card) ?: card
        val poolId = db.read { conn -> poolOf(conn, deckId) } ?: return remove(deckId, card, quantity, DeckSection.MAIN)
        return db.write { conn ->
            quantity?.let(DeckRules::checkQuantity)
            val before = snapshot(conn, deckId)
            val name = before.keys.firstOrNull { it.second == DeckSection.MAIN && it.first.equals(canonical, ignoreCase = true) }?.first
                ?: throw DeckRefusal(Kind.NOT_IN_DECK, "card not in the main deck: '$canonical'")
            val main = before.getValue(name to DeckSection.MAIN)
            val side = before[name to DeckSection.SIDEBOARD]
            val take = minOf(quantity ?: main.quantity, main.quantity)
            val opened = conn.query("SELECT COUNT(*) FROM limited_pool_cards WHERE pool_id = ? AND card_name = ? COLLATE NOCASE", poolId, name) { getInt(1) }.single()
            val basic = DeckRules.isBasicLand(conn.query("SELECT type_line FROM cards WHERE name = ? COLLATE NOCASE", name) { getString(1) }.firstOrNull())
            // A basic land the pool never opened came free, and goes; one it did open goes back with the rest.
            val back = if (basic) minOf(take, maxOf(0, opened - (side?.quantity ?: 0))) else take
            setSectionQuantity(conn, deckId, name, DeckSection.MAIN, main.quantity - take)
            if (back > 0) setSectionQuantity(conn, deckId, name, DeckSection.SIDEBOARD, (side?.quantity ?: 0) + back, printing = side?.printing ?: main.printing, setPrinting = true)
            touch(conn, deckId)
            recordRevision(conn, deckId, if (back == take) "move" else "remove", before, "$name: main -> " + if (back == take) "sideboard" else "sideboard $back, out ${take - back}")
            Triple(name, take, main.quantity - take)
        }
    }

    private fun poolOf(conn: Connection, deckId: Int): Int? =
        conn.query("SELECT pool_id FROM decks WHERE id = ?", deckId) { getObject(1)?.let { (it as Number).toInt() } }.firstOrNull()

    /** Puts a card on the considering list; no deck rule applies there (decks.consider_card). */
    fun consider(deckId: Int, card: String, quantity: Int = 1): String = db.write { conn ->
        DeckRules.checkQuantity(quantity)
        val canonical = names.resolve(card) ?: throw DeckRefusal(Kind.CARD_NOT_FOUND, "card not found: '$card'")
        requireDeck(conn, deckId)
        val before = snapshot(conn, deckId)
        val have = before[canonical to DeckSection.CONSIDERING]?.quantity ?: 0
        DeckRules.checkQuantity(have + quantity)
        setSectionQuantity(conn, deckId, canonical, DeckSection.CONSIDERING, have + quantity)
        touch(conn, deckId)
        recordRevision(conn, deckId, "consider", before, canonical)
        canonical
    }

    /**
     * Takes [quantity] copies (all when null) out of [section], or, with no
     * section, out of every section but the considering list, main deck
     * first (decks.remove_card_from_deck). Returns (name, removed, remaining).
     */
    fun remove(deckId: Int, card: String, quantity: Int? = null, section: DeckSection? = null): Triple<String, Int, Int> = db.write { conn ->
        quantity?.let(DeckRules::checkQuantity)
        val name = names.resolve(card) ?: card
        requireDeck(conn, deckId)
        val before = snapshot(conn, deckId)
        if (section != null) {
            val have = before[name to section]?.quantity
                ?: before.entries.firstOrNull { (k, _) -> k.second == section && k.first.equals(name, ignoreCase = true) }?.value?.quantity ?: 0
            if (have == 0) throw DeckRefusal(Kind.NOT_IN_DECK, "card not in the ${section.key}: '$name'")
            val removed = if (quantity == null) have else minOf(quantity, have)
            setSectionQuantity(conn, deckId, name, section, have - removed)
            touch(conn, deckId)
            recordRevision(conn, deckId, "remove", before, name)
            return@write Triple(name, removed, have - removed)
        }
        val rows = conn.query(
            "SELECT id, quantity FROM deck_cards WHERE deck_id = ? AND card_name = ? COLLATE NOCASE ORDER BY is_sideboard ASC, is_commander ASC",
            deckId, name,
        ) { getLong("id") to getInt("quantity") }
        if (rows.isEmpty()) throw DeckRefusal(Kind.NOT_IN_DECK, "card not in deck: '$name'")
        val total = rows.sumOf { it.second }
        val removed: Int
        if (quantity == null) {
            conn.update("DELETE FROM deck_cards WHERE deck_id = ? AND card_name = ? COLLATE NOCASE", deckId, name)
            removed = total
        } else {
            removed = minOf(quantity, total)
            var left = removed
            for ((id, qty) in rows) {
                if (left <= 0) break
                if (left >= qty) { conn.update("DELETE FROM deck_cards WHERE id = ?", id); left -= qty }
                else { conn.update("UPDATE deck_cards SET quantity = ? WHERE id = ?", qty - left, id); left = 0 }
            }
        }
        touch(conn, deckId)
        recordRevision(conn, deckId, "remove", before, name)
        Triple(name, removed, total - removed)
    }

    /**
     * Moves copies between the main deck, the sideboard and the considering
     * list, as one `move` revision (decks.move_card). Into the deck or the
     * sideboard the card meets that section's rules; the list takes anything.
     */
    fun move(deckId: Int, card: String, from: DeckSection, to: DeckSection, quantity: Int = 1, force: Boolean = false): String = db.write { conn ->
        val movable = setOf(DeckSection.MAIN, DeckSection.SIDEBOARD, DeckSection.CONSIDERING)
        if (to !in movable || from !in movable) throw DeckRefusal(Kind.BAD_MOVE, "move is between main, sideboard, considering; the command zone is `commander`")
        if (to == from) throw DeckRefusal(Kind.BAD_MOVE, "'$card' is already in the ${to.key}")
        DeckRules.checkQuantity(quantity)
        val canonical = names.resolve(card) ?: card
        requireDeck(conn, deckId)
        val before = snapshot(conn, deckId)
        val have = before[canonical to from]?.quantity ?: 0
        if (have < quantity) throw DeckRefusal(Kind.NOT_IN_DECK, "the ${from.key} has $have '$canonical'; cannot move $quantity")
        setSectionQuantity(conn, deckId, canonical, from, have - quantity)
        if (to == DeckSection.CONSIDERING) {
            setSectionQuantity(conn, deckId, canonical, to, (before[canonical to to]?.quantity ?: 0) + quantity)
        } else {
            // The printing goes with the copies, unless the row they join has one of its own (a row without one leaves it alone).
            val printing = before[canonical to from]?.printing?.takeIf { before[canonical to to]?.printing == null }
            addCard(conn, deckId, canonical, quantity, sideboard = to == DeckSection.SIDEBOARD, force = force, printing = printing)
        }
        touch(conn, deckId)
        recordRevision(conn, deckId, "move", before, "$canonical: ${from.key} -> ${to.key}")
        canonical
    }

    /** What [promote] did: `promoted`, `added`, `unchanged`, `demoted`; [formatSet] when the deck had none and became `commander`. */
    data class CommanderChange(val card: String, val action: String, val formatSet: String?)

    /** Makes a card a commander, or ([unset]) puts one back in the main deck (decks.set_commander). */
    fun promote(deckId: Int, card: String, unset: Boolean = false, force: Boolean = false): CommanderChange {
        val canonical = names.resolve(card) ?: throw DeckRefusal(Kind.CARD_NOT_FOUND, "card not found: '$card'")
        return db.write { conn ->
            requireDeck(conn, deckId)
            val before = snapshot(conn, deckId)
            if (unset) {
                val row = conn.query("SELECT id FROM deck_cards WHERE deck_id = ? AND card_name = ? COLLATE NOCASE AND is_commander = 1", deckId, canonical) { getLong(1) }.firstOrNull()
                    ?: throw DeckRefusal(Kind.NOT_A_COMMANDER, "'$canonical' is not currently a commander in this deck")
                conn.update("UPDATE deck_cards SET is_commander = 0 WHERE id = ?", row)
                touch(conn, deckId)
                recordRevision(conn, deckId, "demote", before, canonical)
                return@write CommanderChange(canonical, "demoted", null)
            }
            data class Row(val id: Long, val quantity: Int, val commander: Boolean, val sideboard: Boolean)
            val rows = conn.query(
                "SELECT id, quantity, is_commander, is_sideboard FROM deck_cards WHERE deck_id = ? AND card_name = ? COLLATE NOCASE ORDER BY is_sideboard ASC, is_commander DESC",
                deckId, canonical,
            ) { Row(getLong("id"), getInt("quantity"), getInt("is_commander") == 1, getInt("is_sideboard") == 1) }
            val primary = rows.firstOrNull()
            // Checked against the format the deck will have: an unset one becomes `commander` below.
            val fmt = deckFormat(conn, deckId) ?: "commander"
            if (!force) {
                val info = formats.resolve(fmt)
                // quantity is the delta: promoting moves a copy and adds none.
                assertLegal(conn, deckId, canonical, info, commander = true, quantity = if (rows.isEmpty()) 1 else 0, singleton = formats.isSingleton(fmt))
                // Only a copy entering the main deck changes the points total.
                if (primary == null || primary.sideboard) assertPointsFit(conn, deckId, canonical, info, 1, sideboard = false)
            }
            if (primary == null) {
                conn.update("INSERT INTO deck_cards (deck_id, card_name, quantity, is_commander, is_sideboard, added_at) VALUES (?, ?, 1, 1, 0, ?)", deckId, canonical, now())
                val formatSet = autoSetCommanderFormat(conn, deckId)
                touch(conn, deckId)
                recordRevision(conn, deckId, "promote", before, canonical)
                return@write CommanderChange(canonical, "added", formatSet)
            }
            if (primary.commander && !primary.sideboard) {
                return@write CommanderChange(canonical, "unchanged", autoSetCommanderFormat(conn, deckId))
            }
            if (primary.quantity == 1) {
                conn.update("UPDATE deck_cards SET is_commander = 1, is_sideboard = 0 WHERE id = ?", primary.id)
            } else {
                // One copy becomes the commander; the rest stay where they were.
                conn.update("UPDATE deck_cards SET quantity = quantity - 1 WHERE id = ?", primary.id)
                // In the printing of the copies it came from: dropped, the commander lost the art the user chose.
                conn.update(
                    "INSERT INTO deck_cards (deck_id, card_name, quantity, is_commander, is_sideboard, added_at, set_code, collector_number) " +
                        "SELECT deck_id, card_name, 1, 1, 0, ?, set_code, collector_number FROM deck_cards WHERE id = ?",
                    now(), primary.id,
                )
            }
            val formatSet = autoSetCommanderFormat(conn, deckId)
            touch(conn, deckId)
            recordRevision(conn, deckId, "promote", before, canonical)
            CommanderChange(canonical, "promoted", formatSet)
        }
    }

    /**
     * Reverts the deck's newest revision and records that as an `undo`
     * revision, so undo of undo is redo (decks.undo_last_change). Refused
     * when the deck no longer matches what that revision recorded.
     * Returns the new revision.
     */
    fun undo(deckId: Int): DeckRevision = db.write { conn ->
        val name = requireDeck(conn, deckId)
        val latest = conn.query("SELECT id, action FROM deck_revisions WHERE deck_id = ? ORDER BY id DESC LIMIT 1", deckId) { getLong("id") to getString("action") }.firstOrNull()
            ?: throw DeckRefusal(Kind.NOTHING_TO_UNDO, "nothing to undo: deck '$name' has no recorded changes")
        val changes = changesOf(conn, latest.first)
        val before = snapshot(conn, deckId)
        val drifted = changes.filter { c -> differs(before[c.card to c.section] ?: ABSENT, Held(c.after, c.printingAfter)) }.map { it.card }
        if (drifted.isNotEmpty()) throw DeckRefusal(Kind.UNDO_DRIFT, "cannot undo revision #${latest.first}: the deck no longer matches it (${drifted.joinToString(", ")} changed since)")
        for (c in changes) setSectionQuantity(conn, deckId, c.card, c.section, c.before, printing = c.printingBefore, setPrinting = true)
        val id = recordRevision(conn, deckId, "undo", before, "undo of #${latest.first} (${latest.second})")
        touch(conn, deckId)
        revision(conn, id!!)
    }

    /**
     * What adding one copy to [section] (main or sideboard) would be refused
     * for, without adding it: the considering list's `!`. Null when it would go in.
     */
    fun wouldRefuse(deckId: Int, card: String, section: DeckSection = DeckSection.MAIN): DeckRefusal? = db.dryRun { conn ->
        try {
            addCard(conn, deckId, card, 1, sideboard = section == DeckSection.SIDEBOARD, force = false)
            null
        } catch (e: DeckRefusal) {
            e
        }
    }

    /**
     * Would deck [deckId] pass `add`'s rules with these cards swapped out
     * (decks.check_swaps)? [swaps] is (card in the deck, substitute); they come
     * back as canonical names, or the first one that breaks a rule is refused.
     * A dry run through [addCard], never a second copy of its rules: each
     * swapped-out card is deleted, each substitute goes back in with the same
     * quantities and sections, and the transaction is rolled back. All the
     * swaps together, since two substitutes can break singleton or the points
     * budget only jointly. A substitution has no "anyway": it isn't the deck.
     */
    fun checkSwaps(deckId: Int, swaps: List<Pair<String, String>>): List<Pair<String, String>> = db.dryRun { conn ->
        requireDeck(conn, deckId)
        data class Row(val name: String, val quantity: Int, val commander: Boolean, val sideboard: Boolean)
        data class Planned(val card: String, val substitute: String, val rows: List<Row>)
        val planned = swaps.map { (card, substitute) ->
            val inDeck = names.resolve(card) ?: card
            val rows = conn.query(
                "SELECT card_name, quantity, is_commander, is_sideboard FROM deck_cards WHERE deck_id = ? AND card_name = ? COLLATE NOCASE",
                deckId, inDeck,
            ) { Row(getString(1), getInt(2), getBoolean(3), getBoolean(4)) }
            if (rows.isEmpty()) throw DeckRefusal(Kind.NOT_IN_DECK, "card not in deck: '$card'")
            val canonical = names.resolve(substitute) ?: throw DeckRefusal(Kind.CARD_NOT_FOUND, "card not found: '$substitute'")
            if (canonical.equals(rows[0].name, ignoreCase = true)) throw DeckRefusal(Kind.BAD_SUBSTITUTE, "'$canonical' cannot substitute for itself")
            Planned(rows[0].name, canonical, rows)
        }
        planned.forEach { conn.update("DELETE FROM deck_cards WHERE deck_id = ? AND card_name = ? COLLATE NOCASE", deckId, it.card) }
        for (p in planned) for (row in p.rows) {
            try {
                addCard(conn, deckId, p.substitute, row.quantity, sideboard = row.sideboard, force = false, commander = row.commander)
            } catch (e: DeckRefusal) {
                throw DeckRefusal(e.kind, "${p.substitute} for ${p.card}: ${e.message}")
            }
        }
        planned.map { it.card to it.substitute }
    }

    /** What a pasted list did to a deck (decks._load_rows' summary). */
    data class LoadResult(
        val revisionId: Long?, val added: Int, val copies: Int, val unresolved: List<String>,
        val rejected: List<Pair<String, String>>, val considering: Int, val formatSet: String?, val totalInput: Int,
    )

    /**
     * Adds a pasted list to deck [deckId], all or nothing, as one `load`
     * revision (decks.load_parsed_into_deck). [force], the default, loads it
     * verbatim: a paste is the deck as the user has it. The maybeboard goes
     * onto the considering list.
     */
    fun load(deckId: Int, rows: List<ParsedRow>, force: Boolean = true): LoadResult = db.write { conn ->
        requireDeck(conn, deckId)
        loadRows(conn, deckId, rows, force, "load")
    }

    /** A new deck made from a pasted list, created and loaded in one transaction (decks.import_deck). Returns its id. */
    fun importDeck(name: String, folderId: Int?, format: String?, rows: List<ParsedRow>): Pair<Int, LoadResult> = db.write { conn ->
        val id = LibraryWriter.createDeckRow(conn, name, folderId, format)
        id to loadRows(conn, id, rows, force = true, action = "import")
    }

    /**
     * A new limited deck and the pool it is built from, in one transaction:
     * [mine] stored as the user's, [rival] (the AI's, or the other person's)
     * as opened by [rivalOpenedBy] and linked to it, and the deck holding the
     * whole pool in its sideboard, each card in the printing it came in, as
     * one `import` revision. Building it is moving cards to the main deck.
     * Returns the deck's id.
     */
    fun createFromPool(name: String, folderId: Int?, format: String, product: String, mine: OpenedPool, rival: OpenedPool?, rivalOpenedBy: String, forgeVersion: String?): Int = db.write { conn ->
        val rivalId = rival?.let { PoolStore.insert(conn, it, product, rivalOpenedBy, forgeVersion, rivalPoolId = null) }
        val poolId = PoolStore.insert(conn, mine, product, "me", forgeVersion, rivalId)
        val id = LibraryWriter.createDeckRow(conn, name, folderId, format)
        conn.update("UPDATE decks SET pool_id = ? WHERE id = ?", poolId, id)
        val rows = mine.cards.map { ParsedRow(it.name, 1, "sideboard", it.setCode, it.collectorNumber) }
        val loaded = loadRows(conn, id, rows, force = true, action = "import")
        if (loaded.unresolved.isNotEmpty()) throw DeckRefusal(Kind.UNRESOLVED_CARDS, "the pool holds cards the database lacks: ${loaded.unresolved.distinct().joinToString(", ")}")
        id
    }

    private fun loadRows(conn: Connection, deckId: Int, rows: List<ParsedRow>, force: Boolean, action: String): LoadResult {
        val before = snapshot(conn, deckId)
        var added = 0
        var copies = 0
        var considering = 0
        var commanderAdded = false
        val unresolved = mutableListOf<String>()
        val rejected = mutableListOf<Pair<String, String>>()
        for (row in rows) {
            if (row.section == "maybeboard") {
                val canonical = names.resolve(row.name) ?: run { unresolved += row.name; null } ?: continue
                val have = conn.query("SELECT COALESCE(SUM(quantity), 0) FROM deck_considering WHERE deck_id = ? AND card_name = ? COLLATE NOCASE", deckId, canonical) { getInt(1) }.single()
                try {
                    DeckRules.checkQuantity(row.quantity)
                    DeckRules.checkQuantity(have + row.quantity)
                } catch (e: DeckRefusal) {
                    rejected += row.name to (e.message ?: ""); continue
                }
                setSectionQuantity(conn, deckId, canonical, DeckSection.CONSIDERING, have + row.quantity)
                considering += row.quantity
                continue
            }
            val commander = row.section == "commander"
            try {
                addCard(conn, deckId, row.name, row.quantity, sideboard = row.section == "sideboard", force = force,
                    commander = commander, printing = Printing.of(row.setCode, row.collectorNumber))
            } catch (e: DeckRefusal) {
                when {
                    e.kind == Kind.CARD_NOT_FOUND -> unresolved += row.name
                    // The row's fault, not a structural one: force does not waive it.
                    e.kind == Kind.QUANTITY -> rejected += row.name to (e.message ?: "")
                    // With force every rule is skipped, so a refusal here is structural.
                    force -> throw e
                    else -> rejected += row.name to (e.message ?: "")
                }
                continue
            }
            added++
            copies += row.quantity
            commanderAdded = commanderAdded || commander
        }
        // As set_commander: naming a commander is what makes a deck a Commander deck.
        val formatSet = if (commanderAdded) autoSetCommanderFormat(conn, deckId) else null
        val revision = recordRevision(conn, deckId, action, before, null)
        return LoadResult(revision, added, copies, unresolved, rejected, considering, formatSet, rows.size)
    }

    /** What a replace did, or ([dryRun]) would do: the changes, and the rows it could not take. */
    data class ReplaceResult(
        val revisionId: Long?, val changes: List<DeckChange>, val unresolved: List<String>,
        val rejected: List<Pair<String, String>>, val considering: Boolean, val formatSet: String?,
        /** The deck's commanders a list without a commander section left in place. */
        val commandersKept: List<String> = emptyList(),
    )

    /**
     * Makes deck [deckId] hold exactly the pasted list, as one `replace`
     * revision (decks.replace_deck_contents): verbatim, no deck rule. A list
     * with a maybeboard sets the considering list; one without leaves it, and
     * one without a commander section keeps the deck's commanders. A
     * name that resolves to nothing stops it, unless [force]. [dryRun] reports
     * the changes and writes nothing: the preview before the user says yes.
     */
    fun replace(deckId: Int, rows: List<ParsedRow>, force: Boolean = false, dryRun: Boolean = false): ReplaceResult {
        val target = LinkedHashMap<Pair<String, DeckSection>, Int>()
        val targetPrinting = HashMap<Pair<String, DeckSection>, Printing>()
        val keepCurrent = HashSet<Pair<String, DeckSection>>()
        val unresolved = mutableListOf<String>()
        val rejected = mutableListOf<Pair<String, String>>()
        val hasConsidering = rows.any { it.section == "maybeboard" }
        for (row in rows) {
            val section = when (row.section) {
                "maybeboard" -> DeckSection.CONSIDERING
                "sideboard" -> DeckSection.SIDEBOARD
                "commander" -> DeckSection.COMMANDER
                else -> DeckSection.MAIN
            }
            val canonical = names.resolve(row.name) ?: run { unresolved += row.name; null } ?: continue
            val key = canonical to section
            try {
                DeckRules.checkQuantity(row.quantity)
                DeckRules.checkQuantity((target[key] ?: 0) + row.quantity)
            } catch (e: DeckRefusal) {
                rejected += row.name to (e.message ?: ""); keepCurrent += key; continue
            }
            target[key] = (target[key] ?: 0) + row.quantity
            Printing.of(row.setCode, row.collectorNumber)?.takeIf { section != DeckSection.CONSIDERING }?.let { targetPrinting[key] = it }
        }
        if (unresolved.isNotEmpty() && !force) {
            throw DeckRefusal(Kind.UNRESOLVED_CARDS, "${unresolved.size} card name(s) not found, so nothing was replaced: ${unresolved.joinToString(", ")}. Fix them, or replace without them.")
        }
        val work: (Connection) -> ReplaceResult = { conn ->
            requireDeck(conn, deckId)
            val before = snapshot(conn, deckId)
            // Deck rows spell names as stored; match the list to them ignoring case, so a spelling never reads as a swap.
            val stored = before.keys.associate { it.first.lowercase() to it.first }
            fun asStored(key: Pair<String, DeckSection>) = (stored[key.first.lowercase()] ?: key.first) to key.second
            val want = target.entries.associate { asStored(it.key) to it.value }.toMutableMap()
            val wantPrinting = targetPrinting.entries.associate { asStored(it.key) to it.value }.toMutableMap()
            val keep = keepCurrent.map(::asStored).toSet()
            val namesCommander = want.keys.any { it.second == DeckSection.COMMANDER }
            // A list with no commander section says nothing about the command zone (an export without a
            // `Commander` header once left a Duel Commander deck without Elminster): the deck's commanders
            // stay, and a copy the list puts in the main deck is that commander, not a second card.
            val kept = mutableListOf<String>()
            if (!namesCommander) {
                for ((key, row) in before) {
                    if (key.second != DeckSection.COMMANDER || row.quantity == 0) continue
                    want[key] = row.quantity
                    val main = key.first to DeckSection.MAIN
                    want[main]?.let { qty ->
                        want[main] = maxOf(0, qty - row.quantity)
                        if (want[main] == 0) wantPrinting.remove(main)?.let { wantPrinting[key] = it }
                    }
                    kept += key.first
                }
            }
            for (key in before.keys + want.keys) {
                if (key in keep || (key.second == DeckSection.CONSIDERING && !hasConsidering)) continue
                val now = before[key] ?: ABSENT
                val qty = want[key] ?: 0
                val printing = wantPrinting[key] ?: now.printing
                if (now.quantity != qty || (qty > 0 && printing != now.printing)) {
                    setSectionQuantity(conn, deckId, key.first, key.second, qty, printing = printing, setPrinting = true)
                }
            }
            val formatSet = if (namesCommander) autoSetCommanderFormat(conn, deckId) else null
            val id = recordRevision(conn, deckId, "replace", before, null)
            if (id != null) touch(conn, deckId)
            ReplaceResult(id, id?.let { changesOf(conn, it) }.orEmpty(), unresolved, rejected, hasConsidering, formatSet, kept.sorted())
        }
        return if (dryRun) db.dryRun(work) else db.write(block = work)
    }

    /**
     * Gives the card in [section] (main, sideboard, commander) this printing,
     * or none: the art the app and Forge show. A change of its own, the
     * `printing` revision, undoable (decks.set_printing).
     */
    fun setPrinting(deckId: Int, card: String, section: DeckSection, printing: Printing?): String = db.write { conn ->
        if (section == DeckSection.CONSIDERING) throw DeckRefusal(Kind.BAD_MOVE, "a printing is chosen in the deck, not the ${section.key}")
        val canonical = names.resolve(card) ?: card
        requireDeck(conn, deckId)
        val before = snapshot(conn, deckId)
        val have = before[canonical to section]?.quantity ?: 0
        if (have == 0) throw DeckRefusal(Kind.NOT_IN_DECK, "card not in the ${section.key}: '$canonical'")
        setSectionQuantity(conn, deckId, canonical, section, have, printing = printing, setPrinting = true)
        if (recordRevision(conn, deckId, "printing", before, canonical) != null) touch(conn, deckId)
        canonical
    }

    /** The deck's revisions, newest first (decks.deck_history). */
    fun history(deckId: Int, limit: Int = 50): List<DeckRevision> = db.read { conn ->
        conn.query("SELECT id FROM deck_revisions WHERE deck_id = ? ORDER BY id DESC LIMIT ?", deckId, limit.coerceIn(1, 500)) { getLong(1) }
            .map { revision(conn, it) }
    }

    // --- the rules, on the caller's connection (decks._add_card and its asserts) ---

    /**
     * decks._add_card on an open connection: every check before the first
     * write. [commander] adds a command-zone row (not checked against the
     * identity it defines); [printing] sets the row's art, null leaves it.
     */
    private fun addCard(
        conn: Connection, deckId: Int, card: String, quantity: Int, sideboard: Boolean, force: Boolean,
        commander: Boolean = false, printing: Printing? = null,
    ): String {
        DeckRules.checkQuantity(quantity)
        val canonical = names.resolve(card) ?: throw DeckRefusal(Kind.CARD_NOT_FOUND, "card not found: '$card'")
        val meta = conn.query("SELECT color_identity, type_line, oracle_text FROM cards WHERE name = ? COLLATE NOCASE", canonical) {
            Triple(getString("color_identity"), getString("type_line"), getString("oracle_text"))
        }.firstOrNull()
        if (!force && !commander) {
            commanderIdentity(conn, deckId)?.let { deckCi ->
                val letters = meta?.first.orEmpty().split(",").filter { it.isNotEmpty() }.toSet()
                val outside = (letters - deckCi.toSet()).sorted()
                if (outside.isNotEmpty()) {
                    val deckLabel = if (deckCi.isEmpty()) "{colorless}" else deckCi.joinToString(",", "{", "}")
                    throw DeckRefusal(Kind.COLOR_IDENTITY, "'$canonical' has color identity ${letters.sorted().joinToString(",", "{", "}")} which is outside the deck's CI $deckLabel (offending: ${outside.joinToString(",")})")
                }
            }
        }
        val fmt = deckFormat(conn, deckId)
        val info = formats.resolve(fmt)
        val singleton = formats.isSingleton(fmt)
        if (!force) assertLegal(conn, deckId, canonical, info, commander = commander, quantity = quantity, singleton = singleton)
        // A commander is one card, and not also a card in the 99; before singleton, so the message names the problem.
        if (!force && commander && !sideboard) assertCanBeAddedAsCommander(conn, deckId, canonical, quantity)
        val limit = DeckRules.singletonLimit(meta?.second, meta?.third)
        if (!force && singleton && limit != null) {
            val current = conn.query(
                "SELECT COALESCE(SUM(quantity), 0) FROM deck_cards WHERE deck_id = ? AND card_name = ? COLLATE NOCASE AND is_sideboard = ?",
                deckId, canonical, if (sideboard) 1 else 0,
            ) { getInt(1) }.single()
            if (current + quantity > limit) {
                throw DeckRefusal(Kind.SINGLETON, "singleton format ('$fmt'): '$canonical' would have ${current + quantity} copies in the ${if (sideboard) "sideboard" else "main deck"} (limit is $limit; basics and 'any number' cards are exempt)")
            }
        }
        if (!force) assertPointsFit(conn, deckId, canonical, info, quantity, sideboard)
        if (!force) assertOpened(conn, deckId, canonical, quantity, meta?.second)
        val existing = conn.query(
            "SELECT id, quantity FROM deck_cards WHERE deck_id = ? AND card_name = ? COLLATE NOCASE AND is_commander = ? AND is_sideboard = ?",
            deckId, canonical, if (commander) 1 else 0, if (sideboard) 1 else 0,
        ) { getLong("id") to getInt("quantity") }.firstOrNull()
        if (existing != null) {
            DeckRules.checkQuantity(existing.second + quantity)
            conn.update("UPDATE deck_cards SET quantity = ? WHERE id = ?", existing.second + quantity, existing.first)
            // One printing per row: the latest one named wins.
            if (printing != null) conn.update("UPDATE deck_cards SET set_code = ?, collector_number = ? WHERE id = ?", printing.setCode, printing.collectorNumber, existing.first)
        } else {
            conn.update(
                "INSERT INTO deck_cards (deck_id, card_name, quantity, category, is_commander, is_sideboard, added_at, set_code, collector_number) VALUES (?, ?, ?, NULL, ?, ?, ?, ?, ?)",
                deckId, canonical, quantity, if (commander) 1 else 0, if (sideboard) 1 else 0, now(), printing?.setCode, printing?.collectorNumber,
            )
        }
        touch(conn, deckId)
        return canonical
    }

    /** decks._assert_can_be_added_as_commander: one fresh copy, not already a commander or in the main deck. */
    private fun assertCanBeAddedAsCommander(conn: Connection, deckId: Int, canonical: String, quantity: Int) {
        if (quantity != 1) throw DeckRefusal(Kind.COMMANDER_COPIES, "a commander is a single card; cannot add ${quantity}x '$canonical' as commander")
        val already = conn.query(
            "SELECT MAX(is_commander) FROM deck_cards WHERE deck_id = ? AND card_name = ? COLLATE NOCASE AND is_sideboard = 0", deckId, canonical,
        ) { getObject(1)?.let { (it as Number).toInt() } }.single()
        if (already == 1) throw DeckRefusal(Kind.ALREADY_COMMANDER, "'$canonical' is already a commander of this deck")
        if (already == 0) throw DeckRefusal(Kind.ALREADY_IN_MAIN, "'$canonical' is already in the main deck; promote that copy to commander instead")
    }

    /** decks._assert_legal_in_format: banned, out of the pool, banned as commander, restricted to one copy. */
    private fun assertLegal(conn: Connection, deckId: Int, canonical: String, info: FormatInfo?, commander: Boolean, quantity: Int, singleton: Boolean) {
        val key = info?.legalityKey ?: return
        val pool = if (info.custom) "${info.label} (inherits $key's card pool)" else info.label
        val status = conn.query("SELECT status FROM card_legalities WHERE card_name = ? COLLATE NOCASE AND format = ?", canonical, key) { getString(1) }.firstOrNull()
        when (status) {
            "banned" -> throw DeckRefusal(Kind.BANNED, "'$canonical' is banned in $pool")
            null -> throw DeckRefusal(Kind.NOT_IN_POOL, "'$canonical' is not legal in $pool (not in the format's card pool)")
            "restricted" -> if (key in Formats.RESTRICTED_MEANS_NO_COMMANDER) {
                if (commander) throw DeckRefusal(Kind.BANNED_AS_COMMANDER, "'$canonical' is banned as a commander in ${info.label} (it may still be in the deck)")
            } else if (!singleton) {
                // One copy across main deck and sideboard together, unlike singleton, which is per section.
                val have = conn.query("SELECT COALESCE(SUM(quantity), 0) FROM deck_cards WHERE deck_id = ? AND card_name = ? COLLATE NOCASE", deckId, canonical) { getInt(1) }.single()
                if (have + quantity > 1) throw DeckRefusal(Kind.RESTRICTED, "'$canonical' is restricted in ${info.label} (limit 1 copy across main deck and sideboard)")
            }
        }
    }

    /** decks._assert_points_fit: the main deck's points after the add must fit the budget; the sideboard is never charged. */
    private fun assertPointsFit(conn: Connection, deckId: Int, canonical: String, info: FormatInfo?, quantity: Int, sideboard: Boolean) {
        if (sideboard) return
        val budget = info?.pointsBudget ?: return
        val cost = conn.query("SELECT points FROM custom_format_points WHERE format = ? AND card_name = ?", info.key, canonical) { getInt(1) }.firstOrNull() ?: return
        if (cost == 0) return
        val already = pointsSpent(conn, deckId, info.key)
        if (already + cost * quantity > budget) {
            throw DeckRefusal(Kind.POINTS, "'$canonical' costs $cost point(s) in ${info.label}; the deck is at $already/$budget and would go to ${already + cost * quantity}")
        }
    }

    /** A deck built from a pool holds no more of a card, main and sideboard together, than the pool opened (PoolRule). */
    private fun assertOpened(conn: Connection, deckId: Int, canonical: String, quantity: Int, typeLine: String?) {
        val poolId = conn.query("SELECT pool_id FROM decks WHERE id = ?", deckId) { getObject(1)?.let { (it as Number).toInt() } }.single() ?: return
        val opened = conn.query("SELECT COUNT(*) FROM limited_pool_cards WHERE pool_id = ? AND card_name = ? COLLATE NOCASE", poolId, canonical) { getInt(1) }.single()
        val held = conn.query("SELECT COALESCE(SUM(quantity), 0) FROM deck_cards WHERE deck_id = ? AND card_name = ? COLLATE NOCASE", deckId, canonical) { getInt(1) }.single()
        val excess = PoolRule.excess(mapOf(canonical to opened), mapOf(canonical to held + quantity)) { DeckRules.isBasicLand(typeLine) }
        if (excess.isNotEmpty()) {
            throw DeckRefusal(Kind.NOT_OPENED, "'$canonical': the pool opened $opened and the deck would hold ${held + quantity} (basic lands are free)")
        }
    }

    /** The main deck's points (commanders included, the sideboard not), as decks._deck_points_inner totals them. */
    internal fun pointsSpent(conn: Connection, deckId: Int, formatKey: String): Int = conn.query(
        "SELECT COALESCE(SUM(p.points * dc.quantity), 0) FROM deck_cards dc JOIN custom_format_points p " +
            "ON p.card_name = dc.card_name COLLATE NOCASE AND p.format = ? WHERE dc.deck_id = ? AND dc.is_sideboard = 0",
        formatKey, deckId,
    ) { getInt(1) }.single()

    private fun autoSetCommanderFormat(conn: Connection, deckId: Int): String? {
        if (deckFormat(conn, deckId) != null) return null
        conn.update("UPDATE decks SET format = 'commander' WHERE id = ?", deckId)
        return "commander"
    }

    private fun deckFormat(conn: Connection, deckId: Int): String? =
        conn.query("SELECT format FROM decks WHERE id = ?", deckId) { getString(1) }.firstOrNull()?.takeIf { it.isNotEmpty() }

    private fun requireDeck(conn: Connection, deckId: Int): String =
        conn.query("SELECT name FROM decks WHERE id = ?", deckId) { getString(1) }.firstOrNull()
            ?: throw DeckRefusal(Kind.NO_SUCH_DECK, "no deck #$deckId")

    private fun touch(conn: Connection, deckId: Int) = conn.update("UPDATE decks SET updated_at = ? WHERE id = ?", now(), deckId)

    // --- the history: snapshots, diffs, revisions (decks._snapshot / _record_revision) ---

    /** Copies and printing of one (card, section). */
    private data class Held(val quantity: Int, val printing: Printing?)

    /** (card, section) -> what it holds, for every row of the deck and its considering list. */
    private fun snapshot(conn: Connection, deckId: Int): Map<Pair<String, DeckSection>, Held> {
        val state = LinkedHashMap<Pair<String, DeckSection>, Held>()
        conn.query(
            "SELECT card_name, is_commander, is_sideboard, quantity, set_code, collector_number FROM deck_cards WHERE deck_id = ? ORDER BY id",
            deckId,
        ) {
            val section = when {
                getInt("is_sideboard") != 0 -> DeckSection.SIDEBOARD // sideboard wins, as in the export
                getInt("is_commander") != 0 -> DeckSection.COMMANDER
                else -> DeckSection.MAIN
            }
            Triple(getString("card_name") to section, getInt("quantity"), Printing.of(getString("set_code"), getString("collector_number")))
        }.forEach { (key, qty, printing) ->
            val held = state[key]
            state[key] = Held((held?.quantity ?: 0) + qty, held?.printing ?: if (held == null) printing else null)
        }
        conn.query("SELECT card_name, quantity FROM deck_considering WHERE deck_id = ? ORDER BY id", deckId) { getString(1) to getInt(2) }
            .forEach { (card, qty) -> val key = card to DeckSection.CONSIDERING; state[key] = Held((state[key]?.quantity ?: 0) + qty, null) }
        return state
    }

    /** A quantity change, or a new printing on a card present on both sides. */
    private fun differs(before: Held, after: Held): Boolean =
        before.quantity != after.quantity || (before.quantity > 0 && before.printing != after.printing)

    /** A deck written row by row (a package's, without its history): its contents as one `import` revision, from nothing. */
    internal fun recordImported(conn: Connection, deckId: Int, note: String?): Long? = recordRevision(conn, deckId, "import", emptyMap(), note)

    /** Diffs the deck against [before] and stores it as one revision; nothing changed is nothing recorded (null). */
    private fun recordRevision(conn: Connection, deckId: Int, action: String, before: Map<Pair<String, DeckSection>, Held>, note: String?): Long? {
        val after = snapshot(conn, deckId)
        val changes = (before.keys + after.keys).sortedWith(DeckRules.CHANGE_ORDER).mapNotNull { key ->
            val b = before[key] ?: ABSENT
            val a = after[key] ?: ABSENT
            if (!differs(b, a)) null
            else DeckChange(key.first, key.second, b.quantity, a.quantity, b.printing.takeIf { b.quantity > 0 }, a.printing.takeIf { a.quantity > 0 })
        }
        if (changes.isEmpty()) return null
        val id = conn.insert("INSERT INTO deck_revisions (deck_id, at, action, note) VALUES (?, ?, ?, ?)", deckId, now(), action, note)
        for (c in changes) {
            conn.update(
                "INSERT INTO deck_changes (revision_id, card_name, section, qty_before, qty_after, set_code_before, collector_number_before, set_code_after, collector_number_after) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, c.card, c.section.key, c.before, c.after,
                c.printingBefore?.setCode, c.printingBefore?.collectorNumber, c.printingAfter?.setCode, c.printingAfter?.collectorNumber,
            )
        }
        return id
    }

    /**
     * Makes (card, section) hold exactly [quantity] copies (decks._set_section_quantity):
     * duplicate rows collapse into the first, 0 deletes. [setPrinting] replaces the row's printing with [printing].
     */
    private fun setSectionQuantity(conn: Connection, deckId: Int, card: String, section: DeckSection, quantity: Int, printing: Printing? = null, setPrinting: Boolean = false) {
        if (section == DeckSection.CONSIDERING) {
            val ids = conn.query("SELECT id FROM deck_considering WHERE deck_id = ? AND card_name = ? COLLATE NOCASE ORDER BY id", deckId, card) { getLong(1) }
            (if (quantity > 0) ids.drop(1) else ids).forEach { conn.update("DELETE FROM deck_considering WHERE id = ?", it) }
            when {
                ids.isNotEmpty() && quantity > 0 -> conn.update("UPDATE deck_considering SET quantity = ? WHERE id = ?", quantity, ids.first())
                quantity > 0 -> conn.update("INSERT INTO deck_considering (deck_id, card_name, quantity, added_at) VALUES (?, ?, ?, ?)", deckId, card, quantity, now())
            }
            return
        }
        val where = when (section) {
            DeckSection.SIDEBOARD -> "is_sideboard = 1"
            DeckSection.COMMANDER -> "is_commander = 1 AND is_sideboard = 0"
            else -> "is_commander = 0 AND is_sideboard = 0"
        }
        val ids = conn.query("SELECT id FROM deck_cards WHERE deck_id = ? AND card_name = ? COLLATE NOCASE AND $where ORDER BY id", deckId, card) { getLong(1) }
        val keep = if (quantity > 0) ids.take(1) else emptyList()
        ids.filter { it !in keep }.forEach { conn.update("DELETE FROM deck_cards WHERE id = ?", it) }
        if (keep.isNotEmpty()) {
            conn.update("UPDATE deck_cards SET quantity = ? WHERE id = ?", quantity, keep.first())
            if (setPrinting) conn.update("UPDATE deck_cards SET set_code = ?, collector_number = ? WHERE id = ?", printing?.setCode, printing?.collectorNumber, keep.first())
        } else if (quantity > 0) {
            conn.update(
                "INSERT INTO deck_cards (deck_id, card_name, quantity, is_commander, is_sideboard, added_at, set_code, collector_number) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                deckId, card, quantity, if (section == DeckSection.COMMANDER) 1 else 0, if (section == DeckSection.SIDEBOARD) 1 else 0, now(),
                if (setPrinting) printing?.setCode else null, if (setPrinting) printing?.collectorNumber else null,
            )
        }
    }

    private fun changesOf(conn: Connection, revisionId: Long): List<DeckChange> = conn.query(
        "SELECT card_name, section, qty_before, qty_after, set_code_before, collector_number_before, set_code_after, collector_number_after FROM deck_changes WHERE revision_id = ?",
        revisionId,
    ) {
        DeckChange(
            getString("card_name"), DeckSection.of(getString("section")), getInt("qty_before"), getInt("qty_after"),
            Printing.of(getString("set_code_before"), getString("collector_number_before")),
            Printing.of(getString("set_code_after"), getString("collector_number_after")),
        )
    }.sortedWith(compareBy(DeckRules.CHANGE_ORDER) { it.card to it.section })

    private fun revision(conn: Connection, id: Long): DeckRevision =
        conn.query("SELECT id, at, action, note FROM deck_revisions WHERE id = ?", id) {
            DeckRevision(getLong("id"), getString("at"), getString("action"), getString("note"), emptyList())
        }.single().let { it.copy(changes = changesOf(conn, id)) }

    private companion object {
        val ABSENT = Held(0, null)
        /** decks._now: UTC, to the second, `Z`. */
        fun now(): String = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
    }
}

// --- small JDBC helpers: parameters in order, as Python's sqlite3 takes them ---

private fun java.sql.PreparedStatement.bindAll(params: Array<out Any?>) = params.forEachIndexed { i, p ->
    when (p) {
        null -> setObject(i + 1, null)
        is Int -> setInt(i + 1, p)
        is Long -> setLong(i + 1, p)
        else -> setString(i + 1, p.toString())
    }
}

internal fun <T> Connection.query(sql: String, vararg params: Any?, read: java.sql.ResultSet.() -> T): List<T> =
    prepareStatement(sql).use { st -> st.bindAll(params); st.executeQuery().use { rs -> rs.rows(read) } }

internal fun Connection.update(sql: String, vararg params: Any?): Int =
    prepareStatement(sql).use { st -> st.bindAll(params); st.executeUpdate() }

/** An INSERT, returning the new row's id. */
internal fun Connection.insert(sql: String, vararg params: Any?): Long =
    prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS).use { st ->
        st.bindAll(params); st.executeUpdate()
        st.generatedKeys.use { rs -> rs.next(); rs.getLong(1) }
    }

/**
 * The union of deck [deckId]'s commanders' identities, letters sorted; empty =
 * colourless; null = no commander. A row flagged sideboard is a sideboard card
 * even when flagged commander too, as everywhere else (the export, the sections).
 */
internal fun commanderIdentity(conn: Connection, deckId: Int): List<String>? {
    val identities = conn.query(
        "SELECT c.color_identity FROM deck_cards dc LEFT JOIN cards c ON c.name = dc.card_name COLLATE NOCASE " +
            "WHERE dc.deck_id = ? AND dc.is_commander = 1 AND dc.is_sideboard = 0",
        deckId,
    ) { getString(1) }
    if (identities.isEmpty()) return null
    return identities.flatMap { it.orEmpty().split(",") }.map { it.trim() }.filter { it.isNotEmpty() }.toSortedSet().toList()
}
