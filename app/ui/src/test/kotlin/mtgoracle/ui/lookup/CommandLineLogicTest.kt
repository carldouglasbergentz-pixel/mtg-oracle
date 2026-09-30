package mtgoracle.ui.lookup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** History and autofill: the TUI had neither under test. */
class CommandLineLogicTest {

    @Test
    fun `up walks back and stops at the oldest, down comes forward to the draft`() {
        val h = History()
        assertNull(h.older("typing"), "no history: nothing")
        h.submit("card sol ring")
        h.submit("rule 702")
        h.submit("rule 702") // an immediate repeat is stored once
        assertEquals("rule 702", h.older("half-typ"))
        assertEquals("card sol ring", h.older("ignored"))
        assertEquals("card sol ring", h.older("ignored"), "the oldest stays put")
        assertEquals("rule 702", h.newer())
        assertEquals("half-typ", h.newer(), "past the newest: what was being typed")
        assertNull(h.newer(), "not browsing any more")
        h.submit("search t:elf")
        assertEquals("search t:elf", h.older(""))
    }

    private val suggester = Suggester(
        cardNames = listOf("Fire // Ice", "Lightning Bolt", "Lightning Greaves", "Sol Ring", "Thassa's Oracle"),
        ruleNumbers = listOf("100", "702", "702.1", "702.2"),
        deckNames = { listOf("UW Draw Go", "Rakdos Midrange", "..") },
        helpTopics = listOf("search"),
        vocabulary = mtgoracle.core.lookup.SearchVocabulary(mapOf(
            "t" to listOf("creature", "instant", "enchantment", "eldrazi"),
            "kw" to listOf("flying", "first strike", "flash"),
            "otag" to listOf("spot removal", "mana rock", "removal-creature", "removal"),
            "is" to listOf("commander", "permanent"),
            "order" to listOf("asc_mv", "desc_mv", "asc_edhrec", "desc_edhrec"),
            "f" to listOf("commander", "canadianhighlander"),
        )),
    )

    @Test
    fun `a command completes first, and never to what is typed`() {
        assertEquals("card", suggester.suggest("ca"))
        assertEquals("combo", suggester.suggest("com"))
        assertNull(suggester.suggest("card"), "already whole")
        assertEquals("search-rules", suggester.suggest("search-"))
        assertNull(suggester.suggest("xyz"))
        assertNull(suggester.suggest(""))
    }

    @Test
    fun `card commands complete names, ignoring case`() {
        assertEquals("card Lightning Bolt", suggester.suggest("card light"))
        assertEquals("ruling Sol Ring", suggester.suggest("ruling SOL"))
        assertEquals("combo Thassa's Oracle", suggester.suggest("combo thas"))
        assertNull(suggester.suggest("card lightning bolt"), "typed in full, in another case")
        assertNull(suggester.suggest("card "))
        assertNull(suggester.suggest("next light"), "a command without arguments to complete")
    }

    @Test
    fun `combos completes only the segment after the last semicolon`() {
        assertEquals("combos Thassa's Oracle; Sol Ring", suggester.suggest("combos Thassa's Oracle; so"))
        assertEquals("combos fire; Lightning Bolt; Sol Ring", suggester.suggest("combos fire; Lightning Bolt;sol"))
        assertNull(suggester.suggest("combos Sol Ring; "))
        assertEquals("combos Fire // Ice", suggester.suggest("combos fi"))
    }

    @Test
    fun `rules, decks and help topics complete`() {
        assertEquals("rule 702", suggester.suggest("rule 70"))
        assertEquals("rule 702.1", suggester.suggest("rule 702."))
        assertEquals("cd UW Draw Go", suggester.suggest("cd uw"))
        assertEquals("help search", suggester.suggest("help s"))
    }

    @Test
    fun `a search completes the value after a field, most used first`() {
        assertEquals("t:enchantment", suggester.suggest("t:ench"))
        assertEquals("t:instant kw:flying", suggester.suggest("t:instant kw:fl"))
        assertEquals("-kw:flying", suggester.suggest("-kw:fly"))
        assertEquals("(type:creature", suggester.suggest("(type:cr"))
        assertEquals("search is:commander", suggester.suggest("search is:com"))
        assertEquals("order:asc_edhrec", suggester.suggest("order:asc_e"))
        assertEquals("f:canadianhighlander", suggester.suggest("f:can"))
        assertNull(suggester.suggest("t:"), "nothing typed after the field yet")
        assertNull(suggester.suggest("xyz:foo"), "no such field")
        assertNull(suggester.suggest("t:creature "), "a finished token")
    }

    @Test
    fun `tags take Scryfall's hyphens, and a value with a space comes quoted`() {
        assertEquals("otag:mana-rock", suggester.suggest("otag:mana-r"))
        assertEquals("function:spot-removal", suggester.suggest("function:spot"))
        assertEquals("otag:removal-creature", suggester.suggest("otag:removal-c"))
        assertEquals("kw:\"first strike\"", suggester.suggest("kw:fir"), "offered as a Tab hint: it rewrites the text")
        assertEquals("kw:\"first strike\"", suggester.suggest("kw:\"fir"))
        assertNull(suggester.suggest("kw:\"first strike\""), "a closed quote is finished")
    }

    @Test
    fun `free words complete to a card name, commands still come first`() {
        assertEquals("Lightning Bolt", suggester.suggest("lightning b"))
        assertEquals("t:instant c:r Lightning Bolt", suggester.suggest("t:instant c:r lightning b"))
        assertEquals("search Sol Ring", suggester.suggest("search sol"))
        assertEquals("Thassa's Oracle", suggester.suggest("thas"), "a one-word search")
        assertEquals("card", suggester.suggest("car"), "a command before a card")
        assertNull(suggester.suggest("-lightning b"), "a negated word is not a name to finish")
        assertNull(suggester.suggest("l"), "one letter is too little")
    }
}
