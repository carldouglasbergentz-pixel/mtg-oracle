package mtgoracle.data

import mtgoracle.core.lookup.FormatCatalog
import mtgoracle.core.lookup.SearchLanguage
import mtgoracle.core.lookup.SearchPage
import mtgoracle.core.lookup.SearchQuery
import mtgoracle.core.lookup.SearchRow

/** The search language over `cards`, a page at a time. (scryfall_search.run_query / count_query) */
class CardSearch(private val db: MtgDb, formats: FormatCatalog) {
    private val sql = SearchSql(formats)

    /** One page of [query]. Throws SearchError for anything the user can fix. */
    fun page(query: SearchQuery, page: Int = 1, pageSize: Int = 50, filters: List<String> = emptyList()): SearchPage {
        val where = sql.where(query.where)
        // Before any SQL runs: an unknown sort field is the user's to fix.
        val orderBy = sql.orderBy(query.order, SearchLanguage.freeWords(query.where))
        val size = pageSize.coerceIn(1, 1000)
        val at = maxOf(1, page)
        return db.read { conn ->
            val total = conn.prepareStatement("SELECT COUNT(*) FROM cards c WHERE ${where.text}").use { st ->
                st.bind(where.params)
                st.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
            }
            val rows = conn.prepareStatement(
                "SELECT c.name, c.type_line, c.mana_cost FROM cards c WHERE ${where.text} ORDER BY ${orderBy.text} LIMIT ? OFFSET ?",
            ).use { st ->
                st.bind(where.params + orderBy.params + listOf(size, (at - 1) * size))
                st.executeQuery().use { rs -> rs.rows { SearchRow(getString("name"), getString("type_line"), getString("mana_cost")) } }
            }
            SearchPage(query, rows, total, at, size, filters)
        }
    }

    private fun java.sql.PreparedStatement.bind(params: List<Any>) = params.forEachIndexed { i, p ->
        when (p) {
            is Int -> setInt(i + 1, p)
            else -> setString(i + 1, p.toString())
        }
    }
}
