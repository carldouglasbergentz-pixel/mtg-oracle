package mtgoracle.core.lookup

import mtgoracle.core.lookup.SearchNode.And
import mtgoracle.core.lookup.SearchNode.Not
import mtgoracle.core.lookup.SearchNode.Or
import mtgoracle.core.lookup.SearchNode.Term
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The grammar, without a database: tests/test_search_language.py's TestTokenizer and more. */
class SearchLanguageTest {

    private fun where(q: String) = SearchLanguage.parse(q).where

    @Test
    fun `a dangling minus is an error, not a search for a hyphen`() {
        assertFailsWith<SearchError> { SearchLanguage.parse("t:goblin -") }
        assertFailsWith<SearchError> { SearchLanguage.parse("(t:goblin -)") }
    }

    @Test
    fun `an order token inside quotes is text`() {
        assertEquals("o:\"x order:asc_mv y\"" to emptyList(), SearchLanguage.extractOrder("o:\"x order:asc_mv y\""))
    }

    @Test
    fun `an order token outside quotes is extracted`() {
        assertEquals("t:goblin" to listOf(SortKey("ci", descending = true)), SearchLanguage.extractOrder("t:goblin order:desc_ci"))
        assertEquals(listOf(SortKey("mv", false), SortKey("name", true)), SearchLanguage.parse("sort=asc_mv t:elf ORDER:desc_name").order)
    }

    @Test
    fun `an order token needs a direction and a field`() {
        assertFailsWith<SearchError> { SearchLanguage.parse("order:mv") }
        assertFailsWith<SearchError> { SearchLanguage.parse("order:up_mv") }
        assertFailsWith<SearchError> { SearchLanguage.parse("order:asc_") }
    }

    @Test
    fun `only sort tokens match everything`() {
        val q = SearchLanguage.parse("order:asc_edhrec")
        assertNull(q.where)
        assertEquals(listOf(SortKey("edhrec", false)), q.order)
    }

    @Test
    fun `and binds tighter than or, negation tighter than and`() {
        assertEquals(Or(listOf(Term("o", ":", "a"), And(listOf(Term("o", ":", "b"), Term("o", ":", "c"))))), where("a or b c"))
        assertEquals(And(listOf(Not(Term("t", ":", "a")), Term("t", ":", "b"))), where("-t:a t:b"))
        assertEquals(And(listOf(Or(listOf(Term("c", ":", "w"), Term("c", ":", "u"))), Term("mv", "<=", "3"))), where("(c:w or c:u) mv<=3"))
    }

    @Test
    fun `explicit and is dropped, not searched for`() {
        assertEquals(where("t:creature c:u"), where("t:creature AND c:u"))
    }

    @Test
    fun `quoted values and barewords`() {
        assertEquals(Term("o", ":", "draw a card"), where("o:\"draw a card\""))
        assertEquals(Term("o", ":", "enters the battlefield"), where("\"enters the battlefield\""))
        assertEquals(Not(Term("o", ":", "flash")), where("not flash"))
        assertEquals(Term("pow", ">=", "4"), where("pow>=4"))
        assertEquals(Term("pow", "!", "3"), where("pow!3")) // the compiler rejects the op, not the parser
    }

    @Test
    fun `syntax errors are search errors`() {
        for (bad in listOf("", "(t:x", "t:x)", "()", "o:\"unterminated", "\"open", "a or")) {
            assertFailsWith<SearchError>(bad) { SearchLanguage.parseExpression(bad) }
        }
    }

    @Test
    fun `a deck's filters keep the sort order`() {
        // services.deck_search_scope wrapped the text in parentheses, which hid `order:` from its extractor.
        val scope = DeckScope(1, "Golgari", listOf("B", "G"), FormatInfo("commander", "commander", "commander", null, true, false))
        val (q, labels) = scope.restrict(SearchLanguage.parse("t:creature order:asc_mv"))
        assertEquals(listOf(SortKey("mv", false)), q.order)
        assertEquals(And(listOf(And(listOf(Term("t", ":", "creature"), Term("ci", "<=", "BG"))), Term("f", ":", "commander"))), q.where)
        assertEquals(listOf("ci<=BG", "f:commander"), labels)
    }

    @Test
    fun `a sort alone inside a deck is the deck's filters, sorted`() {
        val scope = DeckScope(1, "Colourless", emptyList(), null)
        val (q, labels) = scope.restrict(SearchLanguage.parse("order:desc_edhrec"))
        assertEquals(Term("ci", "<=", "c"), q.where)
        assertEquals(listOf("ci<=C"), labels)
        assertEquals(listOf(SortKey("edhrec", true)), q.order)
    }

    @Test
    fun `a custom format names its inherited pool`() {
        val canlander = FormatInfo("canadianhighlander", "Canadian Highlander", "vintage", 10, true, custom = true)
        assertEquals(listOf("f:Canadian Highlander (=vintage pool)"), DeckScope(2, "UW", null, canlander).restrict(SearchLanguage.parse("t:x")).second)
        assertEquals(emptyList(), DeckScope(3, "Kitchen table", null, null).filters)
    }
}
