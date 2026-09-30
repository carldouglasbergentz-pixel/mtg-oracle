package mtgoracle.data

import mtgoracle.core.deck.DeckRefusal
import mtgoracle.core.deck.DeckSection
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The deck engine against its original: one sequence of changes runs
 * through mtg_oracle/decks.py on one copy of the database and through
 * DeckWriter on another, and every outcome (done, or refused for which
 * reason), every deck row, the considering lists and the whole history
 * must come out the same. The sequence walks each rule: identity,
 * singleton (basics, any number, up to N), banned, points, restricted,
 * banned as commander, promote and demote, moves, removals, undo and redo.
 */
class DeckParityTest {

    private val decks = mapOf(
        "__parity_cmd__" to "commander", "__parity_none__" to null, "__parity_can__" to "canlander",
        "__parity_vin__" to "vintage", "__parity_duel__" to "duel",
    )

    /** op, deck, then the op's arguments; `-` is "not given". */
    private val ops = """
        add __parity_cmd__ Sol Ring|1|0|0
        commander __parity_cmd__ Savra, Queen of the Golgari|0|0
        add __parity_cmd__ Sol Ring|1|0|0
        add __parity_cmd__ Counterspell|1|0|0
        add __parity_cmd__ Counterspell|1|0|1
        add __parity_cmd__ Forest|10|0|0
        add __parity_cmd__ Relentless Rats|5|0|0
        add __parity_cmd__ Nazgûl|9|0|0
        add __parity_cmd__ nazgul|1|0|0
        add __parity_cmd__ Mana Crypt|1|0|0
        add __parity_cmd__ Mana Crypt|1|0|1
        add __parity_cmd__ Sol Ring|1|1|0
        add __parity_cmd__ Lightning Bolt|0|0|0
        add __parity_cmd__ Zzyzx the Unreal|1|0|0
        consider __parity_cmd__ Counterspell|1
        consider __parity_cmd__ arcane signet|2
        move __parity_cmd__ Arcane Signet|considering|main|1|0
        move __parity_cmd__ Arcane Signet|considering|main|1|0
        move __parity_cmd__ Arcane Signet|considering|sideboard|1|0
        move __parity_cmd__ Sol Ring|main|considering|1|0
        move __parity_cmd__ Sol Ring|main|main|1|0
        move __parity_cmd__ Sol Ring|main|sideboard|1|0
        move __parity_cmd__ Counterspell|considering|main|1|0
        remove __parity_cmd__ Forest|3|-
        remove __parity_cmd__ Forest|-|-
        remove __parity_cmd__ Sol Ring|1|considering
        remove __parity_cmd__ Sol Ring|-|considering
        remove __parity_cmd__ Island|-|-
        commander __parity_cmd__ Savra, Queen of the Golgari|1|0
        commander __parity_cmd__ Savra, Queen of the Golgari|1|0
        commander __parity_cmd__ Savra, Queen of the Golgari|0|0
        commander __parity_cmd__ Savra, Queen of the Golgari|0|0
        add __parity_cmd__ Relentless Rats|2|0|0
        undo __parity_cmd__
        undo __parity_cmd__
        undo __parity_cmd__
        commander __parity_cmd__ Tymna the Weaver|0|0
        add __parity_cmd__ Swords to Plowshares|1|0|0
        commander __parity_none__ Nazgûl|0|0
        add __parity_none__ Sol Ring|4|0|0
        add __parity_none__ Forest|3|0|0
        commander __parity_none__ Forest|0|0
        add __parity_can__ Ancestral Recall|1|0|0
        add __parity_can__ Mana Drain|1|0|0
        add __parity_can__ Sol Ring|1|0|0
        add __parity_can__ Sol Ring|1|1|0
        move __parity_can__ Sol Ring|sideboard|main|1|0
        add __parity_can__ Strip Mine|1|0|0
        add __parity_can__ Mana Drain|1|0|0
        consider __parity_can__ Black Lotus|1
        move __parity_can__ Black Lotus|considering|main|1|0
        move __parity_can__ Black Lotus|considering|main|1|1
        undo __parity_can__
        add __parity_vin__ Ancestral Recall|1|0|0
        add __parity_vin__ Ancestral Recall|1|0|0
        add __parity_vin__ Ancestral Recall|1|1|0
        add __parity_vin__ Lightning Bolt|4|0|0
        add __parity_vin__ Lightning Bolt|4|1|0
        add __parity_vin__ Mana Vault|1|0|0
        remove __parity_vin__ Lightning Bolt|5|-
        add __parity_vin__ Black Lotus|1|0|0
        commander __parity_vin__ Black Lotus|0|0
        commander __parity_duel__ Krark, the Thumbless|0|0
        add __parity_duel__ Krark, the Thumbless|1|0|0
        commander __parity_duel__ Krark, the Thumbless|0|1
        add __parity_duel__ Krark, the Thumbless|2|0|1
        undo __parity_duel__
        undo __parity_none__
    """.trimIndent().lines()

    /** The first word is the op, the second the deck; the rest, split on `|`, its arguments. */
    private data class Op(val op: String, val deck: String, val args: List<String>)

    private fun parse(line: String): Op {
        val (op, deck) = line.split(' ', limit = 3)
        return Op(op, deck, line.split(' ', limit = 3).getOrNull(2)?.split('|').orEmpty())
    }

    private val python = """
        import sys
        from pathlib import Path
        from mtg_oracle import decks as d, queries as q, scryfall_search as ss
        db, ops_path, decks_path = Path(sys.argv[1]), sys.argv[2], sys.argv[3]
        for m in (q, d, ss):
            m.DB_PATH = db
        q.clear_format_cache()
        KINDS = [('quantity must be', 'QUANTITY'), ('card not found', 'CARD_NOT_FOUND'), ('outside the deck', 'COLOR_IDENTITY'),
                 ('banned as a commander', 'BANNED_AS_COMMANDER'), ('is banned in', 'BANNED'), ('is not legal in', 'NOT_IN_POOL'),
                 ('is restricted in', 'RESTRICTED'), ('singleton format', 'SINGLETON'), ('point(s) in', 'POINTS'),
                 ('a commander is a single card', 'COMMANDER_COPIES'), ('already a commander', 'ALREADY_COMMANDER'),
                 ('already in the main deck', 'ALREADY_IN_MAIN'), ('card not in', 'NOT_IN_DECK'), ('cannot move', 'NOT_IN_DECK'),
                 ('not currently a commander', 'NOT_A_COMMANDER'), ('nothing to undo', 'NOTHING_TO_UNDO'), ('cannot undo', 'UNDO_DRIFT'),
                 ('move is between', 'BAD_MOVE'), ('is already in the', 'BAD_MOVE')]
        def kind(e):
            return next((k for p, k in KINDS if p in str(e)), 'UNKNOWN: ' + str(e))
        if sys.argv[4] == 'create':
            for line in open(decks_path, encoding='utf-8').read().splitlines():
                name, fmt = line.split('|')
                d.create_deck(name, format=fmt or None)
            sys.exit(0)
        for line in open(ops_path, encoding='utf-8').read().splitlines():
            op, deck, rest = (line.split(' ', 2) + [''])[:3]
            a = rest.split('|')
            opt = lambda v: None if v == '-' else v
            try:
                if op == 'add': d.add_card_to_deck(deck, a[0], quantity=int(a[1]), is_sideboard=a[2] == '1', force=a[3] == '1')
                elif op == 'consider': d.consider_card(deck, a[0], quantity=int(a[1]))
                elif op == 'remove': d.remove_card_from_deck(deck, a[0], quantity=None if a[1] == '-' else int(a[1]), section=opt(a[2]))
                elif op == 'move': d.move_card(deck, a[0], to_section=a[2], from_section=a[1], quantity=int(a[3]), force=a[4] == '1')
                elif op == 'commander': d.set_commander(deck, a[0], unset=a[1] == '1', force=a[2] == '1')
                elif op == 'undo': d.undo_last_change(deck)
                print('OK')
            except d.DeckError as e:
                print('ERR ' + kind(e))
    """.trimIndent()

    private fun runKotlin(writer: DeckWriter, ids: Map<String, Int>, op: Op): String = try {
        val id = ids.getValue(op.deck)
        val a = op.args
        when (op.op) {
            "add" -> writer.add(id, a[0], a[1].toInt(), sideboard = a[2] == "1", force = a[3] == "1")
            "consider" -> writer.consider(id, a[0], a[1].toInt())
            "remove" -> writer.remove(id, a[0], a[1].takeIf { it != "-" }?.toInt(), a[2].takeIf { it != "-" }?.let(DeckSection::of))
            "move" -> writer.move(id, a[0], DeckSection.of(a[1]), DeckSection.of(a[2]), a[3].toInt(), force = a[4] == "1")
            "commander" -> writer.promote(id, a[0], unset = a[1] == "1", force = a[2] == "1")
            "undo" -> writer.undo(id)
            else -> error("unknown op ${op.op}")
        }
        "OK"
    } catch (e: DeckRefusal) {
        "ERR ${e.kind}"
    }

    /** Everything the engine writes about [names], ids and times aside, as comparable text. */
    private fun state(db: File, names: Collection<String>): String = DriverManager.getConnection("jdbc:sqlite:${db.path}").use { c ->
        names.sorted().joinToString("\n\n") { name ->
            val id = c.query("SELECT id FROM decks WHERE name = ?", name) { getInt(1) }.single()
            val format = c.query("SELECT format FROM decks WHERE id = ?", id) { getString(1) }.single()
            val cards = c.query(
                "SELECT card_name, quantity, is_commander, is_sideboard, category, set_code, collector_number FROM deck_cards WHERE deck_id = ? ORDER BY card_name, is_commander, is_sideboard, quantity",
                id,
            ) { (1..7).joinToString("|") { getString(it).orEmpty() } }
            val considering = c.query("SELECT card_name, quantity FROM deck_considering WHERE deck_id = ? ORDER BY card_name", id) { "${getString(1)}|${getInt(2)}" }
            val history = c.query(
                "SELECT r.action, r.note, ch.card_name, ch.section, ch.qty_before, ch.qty_after, ch.set_code_before, ch.set_code_after " +
                    "FROM deck_revisions r JOIN deck_changes ch ON ch.revision_id = r.id WHERE r.deck_id = ? ORDER BY r.id, ch.id",
                id,
            ) { (1..8).joinToString("|") { getString(it).orEmpty() } }
            listOf("# $name ($format)", *cards.toTypedArray(), "considering:", *considering.toTypedArray(), "history:", *history.toTypedArray()).joinToString("\n")
        }
    }

    @Test
    fun `the same changes give the same decks, history and refusals as Python`() {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        val first = DbFixture.copy() // healed: it has deck_considering
        val tmp = Files.createTempDirectory("mtg-oracle-parity-").toFile()
        try {
            val opsFile = File(tmp, "ops.txt").apply { writeText(ops.joinToString("\n"), Charsets.UTF_8) }
            val decksFile = File(tmp, "decks.txt").apply { writeText(decks.entries.joinToString("\n") { "${it.key}|${it.value.orEmpty()}" }, Charsets.UTF_8) }
            DbFixture.python(python, first.path, opsFile.path, decksFile.path, "create")
            val second = File(tmp, "mtg.db").also { Files.copy(first.toPath(), it.toPath()) }

            val expected = DbFixture.python(python, first.path, opsFile.path, decksFile.path, "run").trimEnd().lines().map { it.trimEnd('\r') }

            val kotlinDb = MtgDb(second)
            val lookup = Lookup(kotlinDb)
            val writer = DeckWriter(kotlinDb, lookup.names, lookup.formats)
            val ids = decks.keys.associateWith { name -> kotlinDb.read { c -> c.query("SELECT id FROM decks WHERE name = ?", name) { getInt(1) }.single() } }
            val actual = ops.map { runKotlin(writer, ids, parse(it)) }

            ops.indices.forEach { i -> assertEquals(expected[i], actual[i], "op ${i + 1}: ${ops[i]}") }
            assertEquals(state(first, decks.keys), state(second, decks.keys), "the decks and their history")
        } finally {
            tmp.deleteRecursively()
            first.parentFile.deleteRecursively()
        }
    }
}
