package mtgoracle.data

import mtgoracle.core.lookup.Rule
import mtgoracle.core.lookup.RuleOrder

/** The Comprehensive Rules. (queries.get_rule / search_rules) */
class Rules(private val db: MtgDb) {

    /** A rule by number, the letter suffix in any case, with its immediate children in natural order. */
    fun rule(number: String): Rule? {
        if (number.isBlank()) return null
        return db.read { conn ->
            val rule = conn.prepareStatement("SELECT rule_number, section_title, text FROM rules WHERE rule_number = ? COLLATE NOCASE").use { st ->
                st.setString(1, number.trim())
                st.executeQuery().use { rs -> if (rs.next()) Rule(rs.getString("rule_number"), rs.getString("section_title"), rs.getString("text").orEmpty()) else null }
            } ?: return@read null
            val children = conn.prepareStatement("SELECT rule_number, text FROM rules WHERE parent_rule = ?").use { st ->
                st.setString(1, rule.number)
                st.executeQuery().use { rs -> rs.rows { Rule(getString("rule_number"), null, getString("text").orEmpty()) } }
            }
            rule.copy(children = children.sortedWith(compareBy(RuleOrder) { it.number }))
        }
    }

    /** Rules whose text contains [text], in natural order, the first [limit]. */
    fun search(text: String, limit: Int = 25): List<Rule> {
        if (text.isEmpty()) return emptyList()
        // Sorted before the limit: SQL's text order puts 702.10 before 702.2.
        return db.read { conn ->
            conn.prepareStatement("SELECT rule_number, section_title, text FROM rules WHERE text LIKE ? ESCAPE '!' COLLATE NOCASE").use { st ->
                st.setString(1, SearchSql.contains(text))
                st.executeQuery().use { rs -> rs.rows { Rule(getString("rule_number"), getString("section_title"), getString("text").orEmpty()) } }
            }
        }.sortedWith(compareBy(RuleOrder) { it.number }).take(limit.coerceIn(1, 200))
    }

    /** Every rule number, for completion (text order, as the TUI's suggester has them). */
    fun numbers(): List<String> = db.read { conn ->
        conn.prepareStatement("SELECT rule_number FROM rules ORDER BY rule_number").use { st ->
            st.executeQuery().use { rs -> rs.rows { getString(1) } }
        }
    }
}
