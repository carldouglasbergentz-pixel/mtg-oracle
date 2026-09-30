package mtgoracle.ui.lookup

import mtgoracle.core.lookup.Ability
import mtgoracle.core.lookup.CardProfile
import mtgoracle.core.lookup.ComboCard
import mtgoracle.core.lookup.ComboDetail
import mtgoracle.core.lookup.ComboSummary
import mtgoracle.core.lookup.Correction
import mtgoracle.core.lookup.Rule
import mtgoracle.core.lookup.Ruling
import mtgoracle.core.lookup.SearchLanguage
import mtgoracle.core.lookup.SearchPage
import mtgoracle.core.lookup.SearchRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The lookup renderers as text: what each shows, that nothing runs past the
 * width it is given, and that every link covers exactly what it names.
 * The Python renderers had no tests for any of these.
 */
class RenderTest {

    private val altar = ComboSummary("2028-2034--5", "C", null, 2, "Ashnod's Altar + Nim Deathmantle", "spellbook", hasTemplateVars = false)
    private val long = ComboSummary("1001-2034-5003", "B", null, 4,
        "Ashnod's Altar + Gravecrawler + Phyrexian Altar + Blood Artist, Knight of the Ebon Legion", "spellbook", hasTemplateVars = true)

    private val card = CardProfile(
        name = "Ashnod's Altar", manaCost = "{3}", typeLine = "Artifact",
        oracleText = "Sacrifice a creature: Add {C}{C}.",
        games = "arena,mtgo,paper", reserved = false, edhrecRank = 1234,
        tags = mapOf("type" to listOf("artifact"), "subtype" to emptyList()),
        abilities = listOf(Ability("activated", "Sacrifice a creature", "Add {C}{C}.", false, true, true)),
        rulings = listOf(Ruling("2004-10-04", "You can sacrifice the creature at any time you could activate a mana ability.")),
        legalities = mapOf("legal" to listOf("commander", "legacy", "vintage"), "banned" to listOf("duel")),
        combos = listOf(altar, long), combosFilteredByCi = null,
        corrections = listOf(Correction(4, "altar_is_colourless", "card_interaction", "wrong", "Ashnod's Altar makes {C}{C}.", null, listOf("Ashnod's Altar"), "user_correction")),
    )

    private fun text(r: Rendering, width: Int) = r.lines(width).joinToString("\n") { it.text }

    /** Every line fits and every span sits inside its line; a card link covers the name it opens. */
    private fun assertWellFormed(r: Rendering, width: Int) {
        for (line in r.lines(width)) {
            assertTrue(line.text.length <= width, "over $width: '${line.text}'")
            for (s in line.spans) {
                assertTrue(s.start in 0..s.end && s.end <= line.text.length, "span $s outside '${line.text}'")
                val shown = line.text.substring(s.start, s.end)
                when (val link = s.link) {
                    is OutputLink.Card -> assertTrue(shown == link.name || (shown.endsWith("…") && link.name.startsWith(shown.dropLast(1))), "'$shown' for $link")
                    is OutputLink.Run -> assertEquals(link.command, shown)
                    is OutputLink.Rule -> assertEquals("[${link.number}]", shown)
                    is OutputLink.Combo -> assertTrue(shown.startsWith("[") && shown.endsWith("]"), shown)
                }
            }
        }
    }

    @Test
    fun `a card profile has every section, and fits any pane`() {
        val out = text(renderCard(card), 70)
        for (part in listOf("Ashnod's Altar", "Artifact", "Tags:", "type       artifact", "Parsed abilities:", "IS MANA ABILITY",
            "cost:   Sacrifice a creature", "Legality:", "legal        commander, legacy, vintage", "banned       duel",
            "EDHREC #1,234", "arena/mtgo/paper", "Rulings (1):", "[2004-10-04]", "Top combos featuring this card (2):",
            "(4+ cards)", "NOTE - 1 correction(s)", "-> Ashnod's Altar makes {C}{C}.")) {
            assertTrue(part in out, "missing '$part' in\n$out")
        }
        assertTrue(out.lines()[1].endsWith("{3}") && out.lines()[1].length == 70, "the cost sits at the right edge")
        // From 50: a single card name longer than the line (39 here) is clipped when drawn, never split from its link.
        for (w in listOf(50, 70, 120, 200)) assertWellFormed(renderCard(card), w)
    }

    @Test
    fun `combo rows wrap between cards and link every card`() {
        val lines = renderCard(card).lines(60)
        val row = lines.indexOfFirst { "Gravecrawler" in it.text }
        assertTrue(lines[row + 1].text.startsWith("    + "), lines.joinToString("\n") { it.text })
        val links = lines.flatMap { it.spans }.map { it.link }
        assertTrue(OutputLink.Combo("1001-2034-5003") in links)
        assertTrue(OutputLink.Card("Blood Artist, Knight of the Ebon Legion") in links, "a name with a comma stays one link")
    }

    @Test
    fun `a deck identity filter is named, and so is an empty result`() {
        assertTrue("filtered to deck CI B" in text(renderCard(card.copy(combosFilteredByCi = "B")), 80))
        assertTrue("0 applicable to deck CI G" in text(renderCard(card.copy(combos = emptyList(), combosFilteredByCi = "G")), 100))
    }

    @Test
    fun `a search page numbers its rows, links the names and pages with links`() {
        val rows = listOf(SearchRow("Lightning Bolt", "Instant", "{R}"), SearchRow("Emeritus of Conflict // Lightning Bolt — a very long name indeed", "Creature — Human Wizard // Instant", "{1}{R} // {R}"))
        val page = SearchPage(SearchLanguage.parse("n:bolt"), rows, total = 120, page = 2, pageSize = 50)
        val lines = renderSearch(page).lines(100)
        assertEquals("120 card(s) — showing 51-52 (page 2 of 3)", lines[0].text)
        assertTrue(lines[1].text.startsWith("  [  1] Lightning Bolt"))
        assertEquals(OutputLink.Card(rows[1].name), lines[2].spans.single().link, "a cut name still opens the whole name")
        assertEquals(listOf(OutputLink.Run("next"), OutputLink.Run("prev")), lines.last().spans.map { it.link })
        for (w in listOf(50, 70, 100, 180)) assertWellFormed(renderSearch(page), w)
        assertTrue(renderSearch(page).lines(180)[2].text.contains("Creature — Human Wizard // Instant"), "a wide pane shows the whole type line")
        assertEquals(emptyList(), renderSearch(page.copy(page = 1, total = 2)).lines(80).last().spans, "one page: nothing to page to")
    }

    @Test
    fun `the deck filter notice names every filter, and nothing without one`() {
        assertEquals("[deck filter: ci<=BG  f:commander  (`cd ..` to search the full pool)]", text(renderDeckFilterNotice(listOf("ci<=BG", "f:commander")), 120))
        assertEquals(emptyList(), renderDeckFilterNotice(emptyList()).lines(80))
    }

    @Test
    fun `a combo in full, and a numbered list that opens by id`() {
        val detail = ComboDetail("1001-2034-5003", "Infinite drain", "B", "Loop the Gravecrawler.", "spellbook",
            listOf(ComboCard("Gravecrawler", 1), ComboCard("Phyrexian Altar", 2)), emptyList(), listOf("Sacrifice Gravecrawler.", "Cast it again."), listOf("Infinite death triggers."))
        val out = text(renderCombo(detail), 70)
        for (part in listOf("Combo 1001-2034-5003  B", "Infinite drain", "Cards:", "- Phyrexian Altar x2", "Steps:", "  1. Sacrifice Gravecrawler.", "  2. Cast it again.", "Results:")) {
            assertTrue(part in out, "missing '$part' in\n$out")
        }
        assertWellFormed(renderCombo(detail), 30)
        val list = renderComboList(listOf(altar, long), "2 combo(s) featuring Ashnod's Altar:").lines(70)
        assertEquals("2 combo(s) featuring Ashnod's Altar:", list[0].text)
        assertTrue(list[1].text.startsWith("  [  1] C     (2 cards) "))
        assertEquals(OutputLink.Combo("2028-2034--5"), list[1].spans.first().link)
        assertEquals("(no matching combos)", text(renderComboList(emptyList(), "x"), 70))
    }

    @Test
    fun `a combo without card rows shows its name, not a card link`() {
        val user = ComboSummary("user-3", "UB", "Doomsday pile", 0, null, "user", false)
        val line = renderComboList(listOf(user), "1 combo").lines(70)[1]
        assertTrue(line.text.endsWith("Doomsday pile"))
        assertEquals(listOf(OutputLink.Combo("user-3")), line.spans.map { it.link })
    }

    @Test
    fun `rules and rule searches link each number`() {
        val rule = Rule("702.2", "Keyword Abilities", "Deathtouch", listOf(Rule("702.2a", null, "Deathtouch is a static ability."), Rule("702.2b", null, "A creature with toughness greater than 0 that's been dealt damage by a source with deathtouch...")))
        val lines = renderRule(rule).lines(60)
        assertEquals("[702.2] (Keyword Abilities)", lines[0].text)
        assertEquals(listOf(OutputLink.Rule("702.2a"), OutputLink.Rule("702.2b")), lines.flatMap { it.spans }.map { it.link })
        assertWellFormed(renderRule(rule), 30)
        val hits = renderRulesSearch("deathtouch", listOf(rule.copy(text = "x".repeat(500)))).lines(80)
        assertEquals("1 rule(s) matching 'deathtouch':", hits[0].text)
        assertTrue(hits.drop(2).sumOf { it.text.trim().length } <= 300, "a hit shows at most 300 characters")
        assertEquals("(no rules matching 'zzz')", text(renderRulesSearch("zzz", emptyList()), 80))
    }

    @Test
    fun `corrections show all four fields and link the cards`() {
        val c = Correction(11, "emeritus_face", "card_interaction", "Treated it as one card.", "It is a two-faced card.", "Prepare is a keyword.",
            listOf("Emeritus of Ideation", "Ancestral Recall", "Lórien Revealed"), "user_correction")
        val out = text(renderCorrections(listOf(c)), 70)
        for (part in listOf("1 correction(s):", "#11 [card_interaction/user_correction] emeritus_face", "wrong:   Treated", "correct: It is", "why:     Prepare", "re:      Emeritus of Ideation, Ancestral Recall, Lórien Revealed")) {
            assertTrue(part in out, "missing '$part' in\n$out")
        }
        assertWellFormed(renderCorrections(listOf(c)), 40)
        assertEquals(3, renderCorrections(listOf(c)).lines(40).flatMap { it.spans }.size)
        assertEquals("(no corrections)", text(renderCorrections(emptyList()), 70))
    }

    @Test
    fun `rulings name the card and date each ruling`() {
        val out = text(renderRulings("Delver of Secrets // Insectile Aberration", listOf(Ruling("2021-09-24", "It transforms."))), 70)
        assertEquals("Delver of Secrets // Insectile Aberration - 1 ruling(s):\n  [2021-09-24]\n    It transforms.", out)
        assertEquals("(no rulings found for Sol Ring)", text(renderRulings("Sol Ring", emptyList()), 70))
    }

    @Test
    fun `wrapping breaks a word longer than the line, keeps indents, never runs over`() {
        assertEquals(listOf("  aaaa", "  bbbb", "  cc"), wrapWords("aaaabbbbcc", 6, "  "))
        assertEquals(listOf("- one two", "  three"), wrapWords("one two three", 9, "- ", "  "))
        assertEquals(listOf(""), wrapWords("", 10))
        val help = preformatted("  cmd    a line that is far too long for a narrow pane to show").lines(30)
        assertTrue(help.all { it.text.length <= 30 })
        assertTrue(help.drop(1).all { it.text.startsWith("    ") }, "continuations hang under the line's indent")
    }
}
