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
        assertNull(suggester.suggest("search light"), "search is its own language")
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
}
