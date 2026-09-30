package mtgoracle.data

import mtgoracle.core.deck.DeckParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The deck-list parser against its original: every reference list in
 * docs/reports/decklists, and the shapes that once lost cards, through
 * mtg_oracle/deck_parser.py and through DeckParser. Row for row the same
 * name, count, section and printing.
 */
class DeckParserParityTest {

    /** The shapes deck_parser.py documents, each from an export that went wrong once. */
    private val tricky = listOf(
        "4 Lightning Bolt\n4x Lightning Bolt\nLightning Bolt\nLightning Bolt x4\n1 Jace, Vryn's Prodigy\n1 Fire/Ice",
        "4 Lightning Bolt (CLB) 146\n4 Lightning Bolt *F*\n1 Sol Ring (c21) 263 [Ramp] ^Have,#37d67a^\n1 Mana Leak (PLST) DDN-64\n1 Opt (7ED) 76*",
        "1 Unearth (Theme)\n1 Hazmat Suit (Used)\n2 Island (15)\n1 Sol Ring *E*",
        "# comment\n// comment\n\nDeck\n1 Sol Ring\nSideboard (15)\n2 Duress\nSB: 1 Pithing Needle\nCommander\n1 Savra, Queen of the Golgari",
        "COMMANDER\n1 Elminster\n40 LANDS (42)\n1 Island\n28 INSTANTS and SORC.\n1 Opt\n7 OTHER SPELLS\n40 Lands",
        "Maybeboard\n1 Opt\nConsidering\n1 Brainstorm\nTokens\n1 Treasure\nMain\n1 Sol Ring",
        "﻿1 Sol Ring\r\n2 Island\r3 Mountain  4 Forest",
        "99999999999999999999 Mountain\n0 Island\n4 Lightning Bolt",
        "Companion\n1 Lurrus of the Dream-Den\nmaybe board\n1 Opt",
    )

    private val python = """
        import sys
        from mtg_oracle.deck_parser import parse_deckstring
        texts = open(sys.argv[1], encoding='utf-8').read().split('\x1e')
        for t in texts:
            for r in parse_deckstring(t):
                print('|'.join([r['name'], str(r['quantity']), r['section'], r.get('set_code') or '', r.get('collector_number') or '']))
            print('---')
    """.trimIndent()

    private fun kotlin(texts: List<String>): List<String> = texts.flatMap { t ->
        DeckParser.parse(t).map { r -> listOf(r.name, r.quantity.let { if (it == Int.MAX_VALUE) "99999999999999999999" else "$it" }, r.section, r.setCode.orEmpty(), r.collectorNumber.orEmpty()).joinToString("|") } + "---"
    }

    @Test
    fun `every reference list and every tricky shape parses as Python parses it`() {
        val lists = File(DbFixture.repoRoot, "docs/reports/decklists").walkTopDown().filter { it.isFile && it.extension == "txt" }.sortedBy { it.path }.toList()
        assertTrue(lists.size >= 10, "the reference lists are where the test expects them: ${lists.size}")
        val texts = lists.map { it.readText(Charsets.UTF_8) } + tricky
        val input = File.createTempFile("parser-parity-", ".txt").apply { writeText(texts.joinToString("\u001E"), Charsets.UTF_8); deleteOnExit() }
        val expected = DbFixture.python(python, input.path).replace("\r\n", "\n").trimEnd('\n').split('\n')
        val actual = kotlin(texts)
        assertEquals(expected.size, actual.size, "row count")
        expected.indices.forEach { i -> assertEquals(expected[i], actual[i], "row ${i + 1}") }
    }
}
