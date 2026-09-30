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
    fun `mana symbols read braced or as shorthand`() {
        assertEquals(listOf("{2}", "{U}", "{U}"), SearchSql.manaSymbols("2uu"))
        assertEquals(listOf("{10}", "{G/W}"), SearchSql.manaSymbols("{10}{g/w}"))
        assertFailsWith<SearchError> { SearchSql.manaSymbols("2uq") }
        assertFailsWith<SearchError> { SearchSql.manaSymbols("{U}x{U}") }
    }
}
