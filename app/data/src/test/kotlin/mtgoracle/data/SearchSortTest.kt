package mtgoracle.data

import mtgoracle.core.lookup.CardSort
import mtgoracle.core.lookup.SearchLanguage
import mtgoracle.core.lookup.SearchRow
import mtgoracle.core.lookup.SortLayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A search laid out by a CardSort is ordered in SQL, page by page, and its
 * groups are named in Kotlin from the rows: the two must rank every card
 * alike, or a group splits in two across a page.
 */
class SearchSortTest {
    private val db = MtgDb(FixtureDb.file)
    private val lookup = Lookup(db)

    @Test
    fun `the database ranks every card as its row does, in every layer`() {
        val sql = SearchSql(lookup.formats)
        val ranks = SortLayer.entries.joinToString(", ") { sql.rankOf(it) }
        val rows = db.read { c ->
            c.query("SELECT c.name, c.type_line, c.mana_cost, c.colors, c.mana_value, $ranks FROM cards c") {
                val row = SearchRow(getString(1), getString(2), getString(3), getString(4), getDouble(5).takeIf { !wasNull() })
                row to SortLayer.entries.indices.map { getInt(6 + it) }
            }
        }
        assertTrue(rows.size > 2000, "the fixture's cards")
        for ((row, inSql) in rows) assertEquals(SortLayer.entries.map { it.rank(row) }, inSql, row.toString())
    }

    @Test
    fun `a laid-out search keeps its order across pages, and each group counts the whole search`() {
        val query = SearchLanguage.parse("t:creature mv<=2")
        val first = lookup.search.page(query, page = 1, pageSize = 25, sort = CardSort.DEFAULT)
        val second = lookup.search.page(query, page = 2, pageSize = 25, sort = CardSort.DEFAULT)
        val arranged = assertNotNull(first.arrangement)
        assertTrue(!arranged.pool)
        assertEquals(first.total, arranged.totals.values.sum(), "the colour groups add up to the search: ${arranged.totals}")
        assertEquals("White", arranged.groups.first().first, "white first: ${arranged.groups}")
        val whole = lookup.search.page(query, pageSize = 1000)
        assertTrue(whole.total in 51..1000, "the whole search in one page: ${whole.total}")
        val all = whole.rows.let { CardSort.DEFAULT.arrange(it).first }.map { it.name }
        assertEquals(all.take(50), (first.rows + second.rows).map { it.name }, "the pages are the whole search laid out, in turn")
    }

    @Test
    fun `a search that orders itself keeps its own order`() {
        for (q in listOf("bolt", "t:creature order:asc_name", "like:\"lightning bolt\"")) {
            assertNull(lookup.search.page(SearchLanguage.parse(q), pageSize = 25, sort = CardSort.DEFAULT).arrangement, q)
        }
        val byName = lookup.search.page(SearchLanguage.parse("t:creature"), pageSize = 25)
        assertNull(byName.arrangement, "no sort, name order")
        val none = lookup.search.page(SearchLanguage.parse("t:creature"), pageSize = 25, sort = CardSort.parse(listOf("-"))!!)
        assertEquals(byName.rows, none.rows, "a sort of no layers is name order")
        assertEquals(listOf("" to 25), none.arrangement!!.groups, "one group, unnamed")
    }
}
