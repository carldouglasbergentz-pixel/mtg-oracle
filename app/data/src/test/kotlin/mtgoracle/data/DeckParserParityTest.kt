package mtgoracle.data

import mtgoracle.core.deck.DeckParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The deck-list parser against its original: every reference list in the
 * fixture, and the shapes that once lost cards, parse row for row to the
 * name, count, section and printing Python's deck_parser gave
 * (`expected/deck-parser.txt`).
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

    private fun kotlin(texts: List<String>): List<String> = texts.flatMap { t ->
        DeckParser.parse(t).map { r -> listOf(r.name, r.quantity.let { if (it == Int.MAX_VALUE) "99999999999999999999" else "$it" }, r.section, r.setCode.orEmpty(), r.collectorNumber.orEmpty()).joinToString("|") } + "---"
    }

    @Test
    fun `every reference list and every tricky shape parses as Python parses it`() {
        val lists = FixtureDb.decklists.walkTopDown().filter { it.isFile && it.extension == "txt" }.sortedBy { it.path }.toList()
        assertTrue(lists.size >= 10, "the reference lists are where the test expects them: ${lists.size}")
        val texts = lists.map { it.readText(Charsets.UTF_8) } + tricky
        val expected = FixtureDb.expected("deck-parser.txt")
        val actual = kotlin(texts)
        assertEquals(expected.size, actual.size, "row count")
        expected.indices.forEach { i -> assertEquals(expected[i], actual[i], "row ${i + 1}") }
    }
}
