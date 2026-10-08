package mtgoracle.data

import mtgoracle.core.lookup.FormatCatalog
import mtgoracle.core.lookup.SearchError
import mtgoracle.core.lookup.SearchLanguage
import mtgoracle.core.lookup.SearchNode
import mtgoracle.core.lookup.SearchPage
import mtgoracle.core.lookup.SearchQuery
import mtgoracle.core.lookup.SearchRow

/** The search language over `cards`, a page at a time. (scryfall_search.run_query / count_query) */
class CardSearch(private val db: MtgDb, private val formats: FormatCatalog, private val names: CardNames, private val likes: LikeIndex) {
    private val known = db.read { conn ->
        fun distinct(column: String) = conn.prepareStatement("SELECT DISTINCT $column FROM cards WHERE $column IS NOT NULL").use { st ->
            st.executeQuery().use { rs -> rs.rows { getString(1).lowercase() }.toSet() }
        }
        distinct("rarity") to distinct("layout")
    }

    /** The card `like:` names, as `cards` names it, or the error a mistyped one is. */
    private fun likeCard(raw: String): String = names.resolve(raw) ?: throw SearchError("like: no card named '$raw'")

    // Read with the lookup, as the formats are, so a sync that brings a new layout is searchable once the lookup is rebuilt.
    private val sql = SearchSql(formats, rarities = known.first, layouts = known.second, like = { likes.similar(likeCard(it)) })

    /** The live hint's compiler: `like:` only checks the card is one, since its cards cost the index's first read. */
    private val checking = SearchSql(formats, rarities = known.first, layouts = known.second, like = { likeCard(it); listOf(it) })

    /**
     * [where]'s compiler. A `like:` among other filters ranks the cards they
     * find (SearchLanguage.likeAmong), so `like:counterspell f:premodern` is
     * premodern's likest, not the likest of all that happen to be premodern.
     */
    private fun compiler(where: SearchNode?): SearchSql {
        val among = SearchLanguage.likeAmong(where) ?: return sql
        val rest = sql.where(among)
        val pool = db.read { conn ->
            conn.prepareStatement("SELECT c.name FROM cards c WHERE ${rest.text}").use { st ->
                st.bind(rest.params)
                st.executeQuery().use { rs -> rs.rows { getString(1) }.toSet() }
            }
        }
        return SearchSql(formats, rarities = known.first, layouts = known.second, like = { likes.similar(likeCard(it), pool) })
    }

    /** One page of [query]. Throws SearchError for anything the user can fix. */
    fun page(query: SearchQuery, page: Int = 1, pageSize: Int = 50, filters: List<String> = emptyList()): SearchPage {
        val sql = compiler(query.where)
        val where = sql.where(query.where)
        // Before any SQL runs: an unknown sort field is the user's to fix.
        val orderBy = sql.orderBy(query.order, SearchLanguage.freeWords(query.where), SearchLanguage.likeTarget(query.where))
        val size = pageSize.coerceIn(1, 1000)
        val at = maxOf(1, page)
        return db.read { conn ->
            val total = conn.prepareStatement("SELECT COUNT(*) FROM cards c WHERE ${where.text}").use { st ->
                st.bind(where.params)
                st.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
            }
            val rows = conn.prepareStatement(
                "SELECT c.name, c.type_line, c.mana_cost, c.colors, c.mana_value FROM cards c WHERE ${where.text} ORDER BY ${orderBy.text} LIMIT ? OFFSET ?",
            ).use { st ->
                st.bind(where.params + orderBy.params + listOf(size, (at - 1) * size))
                st.executeQuery().use { rs ->
                    rs.rows { SearchRow(getString("name"), getString("type_line"), getString("mana_cost"), getString("colors"), getDouble("mana_value").takeIf { !wasNull() }) }
                }
            }
            SearchPage(query, rows, total, at, size, filters)
        }
    }

    /** Throws SearchError for anything wrong with [query], without touching the database (the live hint). */
    fun check(query: SearchQuery) {
        checking.where(query.where)
        checking.orderBy(query.order, SearchLanguage.freeWords(query.where))
    }

    /** How many cards [query] finds. */
    fun count(query: SearchQuery): Int {
        val where = compiler(query.where).where(query.where)
        return db.read { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM cards c WHERE ${where.text}").use { st ->
                st.bind(where.params)
                st.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
            }
        }
    }

    private fun java.sql.PreparedStatement.bind(params: List<Any>) = params.forEachIndexed { i, p ->
        when (p) {
            is Int -> setInt(i + 1, p)
            else -> setString(i + 1, p.toString())
        }
    }
}
