package mtgoracle.core.lookup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A query read back in plain words, and the typo guess behind "did you mean". */
class ExplainTest {

    private fun explain(q: String) = Explain.query(SearchLanguage.parse(q))

    @Test
    fun `terms read as words, joined by dots at the top`() {
        assertEquals("type has \"instant\" · colours include U · mana value ≤ 2 · \"counter\" in name, type or text",
            explain("t:instant c:u mv<=2 counter"))
        assertEquals("identity within BG · legal in commander · sorted by popularity ↑", explain("ci<=bg f:commander order:asc_edhrec"))
        assertEquals("function: removal · cost has {U}{U} · multicoloured · can be a commander", explain("otag:removal m:{U}{U} c:m is:commander"))
        assertEquals("sorted by mana value ↓", explain("order:desc_mv"))
        assertEquals("every card", Explain.query(SearchQuery(null)))
    }

    @Test
    fun `or, not and grouping keep their shape`() {
        assertEquals("(keyword flying or keyword trample) · colours include G", explain("(kw:flying or kw:trample) c:g"))
        assertEquals("not type has \"land\"", explain("-t:land"))
        assertEquals("not (type has \"land\" or type has \"artifact\")", explain("-(t:land or t:artifact)"))
        assertEquals("named \"Lightning Bolt\"", explain("n=\"Lightning Bolt\""))
    }

    @Test
    fun `a typo is close, a different word is not`() {
        assertEquals("type", closest("typ", SearchFields.ALIAS.keys))
        assertEquals("card", closest("crad", listOf("card", "combo", "rule")))
        assertEquals("commander", closest("comander", SearchFields.IS_FLAGS))
        assertNull(closest("goblin", listOf("card", "combo", "rule")))
        assertNull(closest("card", listOf("card")), "the word itself is no typo")
    }
}
