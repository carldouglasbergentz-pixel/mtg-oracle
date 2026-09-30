package mtgoracle.data

import mtgoracle.core.lookup.ComboCard
import mtgoracle.core.lookup.ComboDetail
import mtgoracle.core.lookup.ComboSummary
import java.sql.Connection

/**
 * Commander Spellbook's combos and the user's own (`user_combos`), always
 * together: smallest first, then by id. (queries.find_combos_with_card /
 * find_combos_with_all / get_combo)
 */
class Combos(private val db: MtgDb, private val names: CardNames) {

    /** Combos with [card] in them. */
    fun withCard(card: String, limit: Int = 50): List<ComboSummary> =
        db.read { withCard(it, names.resolve(card) ?: card, limit.coerceIn(1, 500), restrictToCi = null) }

    /** Combos with every one of [cards] in them; a name given twice counts once. */
    fun withAll(cards: List<String>, limit: Int = 50): List<ComboSummary> {
        // HAVING compares against the number of names, so a repeat made every combo unreachable.
        val unique = cards.map { names.resolve(it) ?: it }.distinctBy { it.lowercase() }
        if (unique.isEmpty()) return emptyList()
        val marks = unique.joinToString(",") { "?" }
        fun branch(combos: String, cardsTable: String, source: String) = """
            SELECT c.id, c.color_identity, c.name,
                   (SELECT COUNT(*) FROM $cardsTable WHERE combo_id = c.id) AS card_count,
                   (SELECT GROUP_CONCAT(card_name, ' + ') FROM $cardsTable WHERE combo_id = c.id) AS cards,
                   '$source' AS source
            FROM $combos c
            WHERE c.id IN (SELECT combo_id FROM $cardsTable WHERE card_name COLLATE NOCASE IN ($marks)
                           GROUP BY combo_id HAVING COUNT(DISTINCT card_name) = ?)
        """.trimIndent()
        val sql = branch("combos", "combo_cards", "spellbook") + "\nUNION ALL\n" + branch("user_combos", "user_combo_cards", "user") +
            "\nORDER BY card_count ASC, id LIMIT ?"
        return db.read { conn ->
            val rows = conn.prepareStatement(sql).use { st ->
                var i = 0
                repeat(2) {
                    unique.forEach { st.setString(++i, it) }
                    st.setInt(++i, unique.size)
                }
                st.setInt(++i, limit.coerceIn(1, 500))
                st.executeQuery().use { rs -> rs.rows { summaryRow() } }
            }
            flagTemplateVars(conn, rows)
        }
    }

    /** One combo in full; Spellbook first, then the user's. Null for an unknown id. */
    fun detail(id: String): ComboDetail? = db.read { conn ->
        fun texts(sql: String): List<String> = conn.prepareStatement(sql).use { st ->
            st.setString(1, id)
            st.executeQuery().use { rs -> rs.rows { getString("text").orEmpty() } }
        }
        fun cards(table: String): List<ComboCard> = conn.prepareStatement("SELECT card_name, quantity FROM $table WHERE combo_id = ?").use { st ->
            st.setString(1, id)
            st.executeQuery().use { rs -> rs.rows { ComboCard(getString("card_name"), getInt("quantity").takeIf { !wasNull() } ?: 1) } }
        }
        fun head(table: String): Triple<String?, String?, String?>? =
            conn.prepareStatement("SELECT name, color_identity, description FROM $table WHERE id = ?").use { st ->
                st.setString(1, id)
                st.executeQuery().use { rs -> if (rs.next()) Triple(rs.getString("name"), rs.getString("color_identity"), rs.getString("description")) else null }
            }
        head("combos")?.let { (name, ci, description) ->
            return@read ComboDetail(
                id, name, ci, description, "spellbook", cards("combo_cards"),
                prerequisites = texts("SELECT text FROM combo_prerequisites WHERE combo_id = ? ORDER BY id"),
                steps = texts("SELECT text FROM combo_steps WHERE combo_id = ? ORDER BY step_order"),
                results = texts("SELECT text FROM combo_results WHERE combo_id = ? ORDER BY id"),
            )
        }
        // A user combo keeps its whole story in the description; it has no step tables.
        head("user_combos")?.let { (name, ci, description) ->
            ComboDetail(id, name, ci, description, "user", cards("user_combo_cards"), emptyList(), emptyList(), emptyList())
        }
    }

    /**
     * Combos with the canonical [card], on an open connection (the card
     * profile embeds ten). [restrictToCi] keeps those whose identity fits
     * those letters; `combos.color_identity` is contiguous (`WBG`).
     */
    internal fun withCard(conn: Connection, card: String, limit: Int, restrictToCi: List<String>?): List<ComboSummary> {
        val excluded = restrictToCi?.let { allowed -> listOf("W", "U", "B", "R", "G").filter { it !in allowed } }.orEmpty()
        val ciWhere = excluded.joinToString("") { " AND (c.color_identity IS NULL OR c.color_identity NOT LIKE ?)" }
        fun branch(combos: String, cardsTable: String, source: String) = """
            SELECT c.id, c.color_identity, c.name,
                   (SELECT COUNT(*) FROM $cardsTable WHERE combo_id = c.id) AS card_count,
                   (SELECT GROUP_CONCAT(card_name, ' + ') FROM $cardsTable WHERE combo_id = c.id) AS cards,
                   '$source' AS source
            FROM $combos c JOIN $cardsTable cc ON cc.combo_id = c.id
            WHERE cc.card_name = ? COLLATE NOCASE$ciWhere
        """.trimIndent()
        val sql = branch("combos", "combo_cards", "spellbook") + "\nUNION ALL\n" + branch("user_combos", "user_combo_cards", "user") +
            "\nORDER BY card_count ASC, id LIMIT ?"
        val rows = conn.prepareStatement(sql).use { st ->
            var i = 0
            repeat(2) {
                st.setString(++i, card)
                excluded.forEach { st.setString(++i, "%$it%") }
            }
            st.setInt(++i, limit)
            st.executeQuery().use { rs -> rs.rows { summaryRow() } }
        }
        return flagTemplateVars(conn, rows)
    }

    private fun java.sql.ResultSet.summaryRow() = ComboSummary(
        id = getString("id"), colorIdentity = getString("color_identity"), name = getString("name"),
        cardCount = getInt("card_count"), cards = getString("cards"), source = getString("source"), hasTemplateVars = false,
    )

    /** Marks the Spellbook combos whose steps name a card slot the card list doesn't. User combos have no steps. */
    private fun flagTemplateVars(conn: Connection, rows: List<ComboSummary>): List<ComboSummary> {
        val ids = rows.filter { it.source == "spellbook" }.map { it.id }
        if (ids.isEmpty()) return rows
        val flagged = conn.prepareStatement("SELECT combo_id, text FROM combo_steps WHERE combo_id IN (${ids.joinToString(",") { "?" }})").use { st ->
            ids.forEachIndexed { i, id -> st.setString(i + 1, id) }
            st.executeQuery().use { rs -> rs.rows { getString("combo_id") to getString("text").orEmpty() } }
        }.filter { (_, text) -> TEMPLATE_VAR.containsMatchIn(text) }.map { it.first }.toSet()
        return rows.map { if (it.id in flagged) it.copy(hasTemplateVars = true) else it }
    }

    private companion object {
        /**
         * Step text naming a slot `combo_cards` doesn't enumerate. A vetted
         * list, not "any \w+": ordinary English matched that too often.
         */
        val TEMPLATE_VAR = Regex(
            "\\bthe affinity\\b|\\byour commander\\b|\\bany \\w+\\s+(creature|permanent|spell)\\b|\\bnoncreature spell\\b" +
                "|\\ba \\w+ (creature|permanent|spell) (you control|in your hand|in your graveyard|on the battlefield)\\b",
            RegexOption.IGNORE_CASE,
        )
    }
}
