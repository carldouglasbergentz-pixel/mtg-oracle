package mtgoracle.data

import mtgoracle.core.lookup.FormatCatalog
import mtgoracle.core.lookup.SearchError
import mtgoracle.core.lookup.SearchFields
import mtgoracle.core.lookup.SearchLanguage
import mtgoracle.core.lookup.SortKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The compiler without a database: its lists agree with core's, and its errors name the likely fix. */
class SearchSqlTest {
    private val sql = SearchSql(FormatCatalog(emptyList()))

    private fun error(q: String) = assertFailsWith<SearchError>(q) {
        val parsed = SearchLanguage.parse(q)
        sql.where(parsed.where)
        sql.orderBy(parsed.order)
    }.message!!

    @Test
    fun `core's field lists are the compiler's`() {
        assertEquals(SearchFields.IS_FLAGS.toSet(), SearchSql.IS_FLAGS)
        SearchFields.SORT_FIELDS.forEach { sql.orderBy(listOf(SortKey(it, descending = false))) }
        SearchFields.ALIAS.keys.forEach { field -> runCatching { sql.where(SearchLanguage.parse("$field:1").where) }
            .exceptionOrNull()?.let { assertTrue("unknown field" !in it.message!!, "$field: ${it.message}") } }
    }

    @Test
    fun `a typo in a field, a flag, a sort or a format says what was meant`() {
        assertTrue("did you mean type:?" in error("typ:instant"), error("typ:instant"))
        assertTrue("did you mean is:commander?" in error("is:comander"))
        assertTrue("did you mean edhrec?" in error("order:asc_edhre"))
        assertTrue("did you mean commander?" in error("f:comander"))
        assertTrue("did you mean" !in error("zzzzzz:1"), "nothing close: no guess")
    }

    @Test
    fun `a rarity or a layout must be one the cards have`() {
        val known = SearchSql(FormatCatalog(emptyList()), rarities = setOf("common", "mythic"), layouts = setOf("normal", "modal_dfc"))
        fun error(q: String) = assertFailsWith<SearchError>(q) { known.where(SearchLanguage.parse(q).where) }.message!!
        assertTrue("did you mean r:mythic?" in error("r:mythc"), error("r:mythc"))
        assertTrue("did you mean layout:modal_dfc?" in error("layout:modal_dcf"), error("layout:modal_dcf"))
        assertEquals(listOf<Any>("mythic"), known.where(SearchLanguage.parse("r:Mythic").where).params)
        // No cards yet: nothing to check against, so nothing is refused.
        assertEquals(listOf<Any>("whatever"), sql.where(SearchLanguage.parse("layout:whatever").where).params)
    }

    @Test
    fun `a field with no value yet is an error, not every card or the colourless ones`() {
        for (q in listOf("t:", "o:", "n:", "ci:", "c=", "r:", "t:\"\"", "t:\" \"")) assertTrue("needs a value" in error(q), "$q: ${error(q)}")
        assertTrue("empty quotes" in error("\"\""))
        assertEquals(listOf<Any>("%sol %"), sql.where(SearchLanguage.parse("name:\"sol \"").where).params.take(1), "a value of a space and more is a value")
    }

    @Test
    fun `mana symbols read braced or as shorthand`() {
        assertEquals(listOf("{2}", "{U}", "{U}"), SearchSql.manaSymbols("2uu"))
        assertEquals(listOf("{10}", "{G/W}"), SearchSql.manaSymbols("{10}{g/w}"))
        assertFailsWith<SearchError> { SearchSql.manaSymbols("2uq") }
        assertFailsWith<SearchError> { SearchSql.manaSymbols("{U}x{U}") }
    }
}
