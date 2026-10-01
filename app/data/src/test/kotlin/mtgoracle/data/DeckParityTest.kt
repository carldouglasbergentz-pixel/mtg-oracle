package mtgoracle.data

import mtgoracle.core.deck.DeckExport
import mtgoracle.core.deck.DeckParser
import mtgoracle.core.deck.DeckRefusal
import mtgoracle.core.deck.DeckSection
import mtgoracle.core.deck.Printing
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The deck engine against its original: one sequence of changes ran
 * through mtg_oracle/decks.py on the fixture, and through DeckWriter every
 * outcome (done, or refused for which reason) must be the same
 * (`expected/deck-ops.txt`), and so must every deck row, the considering
 * lists and the whole history (`deck-state.txt`). A new rule adds its ops
 * here and its expected lines with them. The sequence walks each rule: identity,
 * singleton (basics, any number, up to N), banned, points, restricted,
 * banned as commander, promote and demote, moves, removals, undo and redo.
 */
class DeckParityTest {

    private val decks = mapOf(
        "__parity_cmd__" to "commander", "__parity_none__" to null, "__parity_can__" to "canlander",
        "__parity_vin__" to "vintage", "__parity_duel__" to "duel",
    )

    /** The lists the load / import / replace ops paste, by index. */
    private val pastes = listOf(
        "Commander\n1 Tymna the Weaver\nDeck\n1 Sol Ring (C18) 263\n4 Plains\nSideboard\n1 Duress\nMaybeboard\n2 Opt\n1 Not A Real Card",
        "1 Sol Ring\n3 Plains\n1 Swords to Plowshares (EMA) 25\nConsidering\n1 Brainstorm",
        "1 Sol Ring\n2 Plains",
        "1 Sol Ring\n1 Zzyzx the Unreal",
        "999 Plains\n1000 Island\n1 Sol Ring",
        // No commander section, the commander in the main deck: it stays the commander, not a second copy.
        "1 Tymna the Weaver (CMR) 51\n1 Sol Ring\n2 Plains",
        // A commander section replaces the commander.
        "Commander\n1 Thrasios, Triton Hero\nDeck\n1 Sol Ring\n2 Plains",
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
        swaps __parity_can__ Strip Mine>Wasteland
        swaps __parity_can__ Sol Ring>Mana Crypt
        swaps __parity_can__ Sol Ring>Mana Drain
        swaps __parity_can__ Sol Ring>Wasteland;Strip Mine>Wasteland
        newdeck __p_swap__ -|canlander
        add __p_swap__ Sol Ring|1|0|0
        add __p_swap__ Strip Mine|1|0|0
        add __p_swap__ Counterspell|1|0|0
        swaps __p_swap__ Sol Ring>Counterspell
        swaps __p_swap__ Sol Ring>Brainstorm;Strip Mine>Brainstorm
        swaps __p_swap__ Sol Ring>Brainstorm
        delete __p_swap__
        swaps __parity_can__ Sol Ring>sol ring
        swaps __parity_can__ Black Lotus>Island
        swaps __parity_can__ Sol Ring>Zzyzx the Unreal
        swaps __parity_cmd__ Swords to Plowshares>Lightning Bolt
        swaps __parity_cmd__ Swords to Plowshares>Path to Exile
        swaps __parity_cmd__ Tymna the Weaver>Thrasios, Triton Hero
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
        mkdir __pf__
        mkdir __PF__
        mkdir a/b
        mkdir (unsorted)
        folderformat __pf__ canlander|0
        newdeck __p_new__ __pf__|-
        newdeck __P_NEW__ __pf__|-
        newdeck __p_other__ -|vintage
        newdeck bad/name -|-
        rename __p_new__ __P_New__
        movedeck __P_New__ -
        rename __P_New__ __p_other__
        movedeck __P_New__ __pf__
        rmdir __pf__ 0
        deckformat __P_New__ Commander
        deckformat __P_New__ -
        load __P_New__ 0
        replace __P_New__ 1|0
        replace __P_New__ 5|0
        replace __P_New__ 6|0
        replace __P_New__ 1|0
        replace __P_New__ 2|0
        replace __P_New__ 3|0
        replace __P_New__ 3|1
        replace __P_New__ 4|0
        printing __P_New__ Sol Ring|main|c21|263
        printing __P_New__ Sol Ring|considering|c21|-
        printing __P_New__ Island|main|c21|-
        import __p_imp__ __pf__|-|0
        import __p_imp__ __pf__|-|1
        export __p_imp__ 0
        export __P_New__ 1
        export __parity_cmd__ 0
        export __parity_cmd__ 0|1
        export __P_New__ 1|1
        undo __P_New__
        folderformat __pf__ duel|1
        rmdir __pf__ 1
        delete __p_imp__
        delete __p_imp__
    """.trimIndent().lines()

    /** The first word is the op, the second the deck; the rest, split on `|`, its arguments. */
    private data class Op(val op: String, val deck: String, val args: List<String>)

    private fun parse(line: String): Op {
        val (op, deck) = line.split(' ', limit = 3)
        return Op(op, deck, line.split(' ', limit = 3).getOrNull(2)?.split('|').orEmpty())
    }

    /** A deck's or folder's id by name, looked up at each op: ops create, rename and delete them. */
    private fun MtgDb.id(table: String, name: String): Int? = read { c -> c.query("SELECT id FROM $table WHERE name = ? COLLATE NOCASE", name) { getInt(1) }.firstOrNull() }

    private fun runKotlin(db: MtgDb, lookup: Lookup, writer: DeckWriter, library: LibraryWriter, op: Op): String = try {
        val a = op.args
        fun deck() = db.id("decks", op.deck) ?: throw DeckRefusal(DeckRefusal.Kind.NO_SUCH_DECK, "deck not found")
        fun folder(name: String?) = name?.takeIf { it != "-" }?.let { db.id("deck_folders", it) ?: throw DeckRefusal(DeckRefusal.Kind.NO_SUCH_DECK, "folder not found") }
        fun opt(v: String) = v.takeIf { it != "-" }
        when (op.op) {
            "mkdir" -> library.createFolder(op.deck)
            "rmdir" -> library.deleteFolder(folder(op.deck)!!, force = a[0] == "1")
            "folderformat" -> library.setFolderFormat(folder(op.deck)!!, opt(a[0]), applyToDecks = a[1] == "1")
            "newdeck" -> library.createDeck(op.deck, folder(a[0]), opt(a[1]))
            "rename" -> library.renameDeck(deck(), a[0])
            "movedeck" -> library.moveDeck(deck(), folder(a[0]))
            "deckformat" -> library.setDeckFormat(deck(), opt(a[0]))
            "delete" -> library.deleteDeck(deck())
            "load" -> writer.load(deck(), DeckParser.parse(pastes[a[0].toInt()]))
            "import" -> writer.importDeck(op.deck, folder(a[0]), opt(a[1]), DeckParser.parse(pastes[a[2].toInt()]))
            "replace" -> writer.replace(deck(), DeckParser.parse(pastes[a[0].toInt()]), force = a[1] == "1")
            "printing" -> writer.setPrinting(deck(), a[0], DeckSection.of(a[1]), Printing.of(opt(a[2]), opt(a[3])))
            "swaps" -> {
                val pairs = op.args.joinToString("|").split(';').map { it.substringBefore('>') to it.substringAfter('>') }
                return "SWAPS " + writer.checkSwaps(deck(), pairs).joinToString(";") { "${it.first}>${it.second}" }
            }
            "export" -> {
                val d = Library(db).deck(deck())!!
                val pool = lookup.analysis.pool(d.cards.map { it.name })
                val grouped = a.getOrNull(1) == "1"
                return "EXPORT " + DeckExport.text(d, frontFace = a[0] == "1", layoutOf = lookup::layout,
                    primaryOf = if (grouped) { n -> pool.classify(n)?.primary } else null).replace("\n", "~")
            }
            else -> runContent(writer, deck(), op)
        }
        "OK"
    } catch (e: DeckRefusal) {
        "ERR ${e.kind}"
    }

    private fun runContent(writer: DeckWriter, id: Int, op: Op) {
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
    }

    /** Everything the engine writes about [names], ids and times aside, as comparable text. */
    private fun state(db: File): String = DriverManager.getConnection("jdbc:sqlite:${db.path}").use { c ->
        val folders = c.query("SELECT name, COALESCE(format, '') FROM deck_folders WHERE name LIKE '!_!_p%' ESCAPE '!' ORDER BY name") { "${getString(1)}|${getString(2)}" }
        val names = c.query("SELECT name FROM decks WHERE name LIKE '!_!_p%' ESCAPE '!' ORDER BY name") { getString(1) }
        "folders: $folders\n\n" + names.joinToString("\n\n") { name ->
            val id = c.query("SELECT id FROM decks WHERE name = ?", name) { getInt(1) }.single()
            val format = c.query("SELECT d.format || ' in ' || COALESCE(f.name, '(unsorted)') FROM decks d LEFT JOIN deck_folders f ON f.id = d.folder_id WHERE d.id = ?", id) { getString(1) }.single()
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
        val copy = FixtureDb.copy()
        try {
            val kotlinDb = MtgDb(copy)
            val library = LibraryWriter(kotlinDb)
            decks.forEach { (name, format) -> library.createDeck(name, null, format) }
            val lookup = Lookup(kotlinDb)
            val writer = DeckWriter(kotlinDb, lookup.names, lookup.formats)
            val actual = ops.map { runKotlin(kotlinDb, lookup, writer, library, parse(it)) }

            val expected = FixtureDb.expected("deck-ops.txt")
            ops.indices.forEach { i -> assertEquals(expected[i], actual[i], "op ${i + 1}: ${ops[i]}") }
            assertEquals(FixtureDb.expected("deck-state.txt").joinToString("\n"), state(copy), "the folders, the decks and their history")
        } finally {
            copy.parentFile.deleteRecursively()
        }
    }
}
