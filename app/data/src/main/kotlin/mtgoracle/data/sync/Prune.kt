package mtgoracle.data.sync

import mtgoracle.data.MtgDb

/**
 * Stale `cards` rows: names no export writes any more (prune_stale_cards.py).
 * The ingest upserts on name and never deletes, so a renamed card, a retired
 * Alchemy rebalance or a memorabilia face lingers with NULL Scryfall columns,
 * and a NULL colour identity reads as colourless in every `ci<=` filter. A
 * row a deck names is never touched: the deck the user built wins.
 */
object Prune {
    /** Every table keyed on a card name, cleaned before the card itself. */
    private val DERIVED = listOf("rulings", "card_tags", "card_abilities", "card_legalities", "card_oracle_tags", "custom_format_points")

    /**
     * NULL in a column the ingest always fills ('' for a colourless card),
     * or a layout the ingest now skips as no card. A column counts only once
     * some row has it: a column just added is NULL everywhere, and trusting
     * it would mark the whole table stale.
     */
    private val CLAUSES = listOf(
        "oracle_id" to "oracle_id IS NULL",
        "color_identity" to "color_identity IS NULL",
        "games" to "games IS NULL",
        "layout" to "layout IN (${CardsIngest.SKIPPED_LAYOUTS.sorted().joinToString(", ") { "'$it'" }})",
    )

    /** Anything near the whole table means the premise is wrong, not that the table is junk. */
    private const val MAX_FRACTION = 0.05
    private const val MAX_FLOOR = 250

    data class Report(
        val total: Int,
        /** Stale rows no deck names: what [run] with `delete` removes. */
        val prunable: List<String>,
        /** Stale rows a deck names: kept. */
        val kept: List<String>,
        /** Columns not yet filled anywhere, so not trusted. */
        val ignored: List<String>,
        /** Too many look stale to believe: nothing is deleted. */
        val refused: Boolean,
        /** Rows deleted per table; empty for a dry run. */
        val deleted: Map<String, Int>,
    ) {
        val limit: Int get() = maxOf(MAX_FLOOR, (total * MAX_FRACTION).toInt())
    }

    /** What is stale and, with [delete], removes it, in one transaction. A dry run by default. */
    fun run(db: MtgDb, delete: Boolean = false): Report {
        val block: (java.sql.Connection) -> Report = { conn ->
            fun exists(sql: String) = conn.createStatement().use { st -> st.executeQuery(sql).use { it.next(); it.getInt(1) == 1 } }
            val total = conn.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM cards").use { it.next(); it.getInt(1) } }
            val usable = CLAUSES.filter { (column, _) -> exists("SELECT EXISTS (SELECT 1 FROM cards WHERE $column IS NOT NULL)") }
            val ignored = (CLAUSES - usable.toSet()).map { it.first }
            val rows = if (usable.isEmpty()) emptyList() else conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT name, EXISTS (SELECT 1 FROM deck_cards dc WHERE dc.card_name = cards.name COLLATE NOCASE) " +
                        "FROM cards WHERE (${usable.joinToString(" OR ") { it.second }}) ORDER BY name",
                ).use { rs -> buildList { while (rs.next()) add(rs.getString(1) to (rs.getInt(2) == 1)) } }
            }
            val prunable = rows.filter { !it.second }.map { it.first }
            val kept = rows.filter { it.second }.map { it.first }
            val limit = maxOf(MAX_FLOOR, (total * MAX_FRACTION).toInt())
            val refused = rows.size > limit
            val deleted = linkedMapOf<String, Int>()
            if (delete && !refused && prunable.isNotEmpty()) {
                for (table in DERIVED + "cards") {
                    val column = if (table == "cards") "name" else "card_name"
                    conn.prepareStatement("DELETE FROM $table WHERE $column = ?").use { st ->
                        prunable.forEach { st.setString(1, it); st.addBatch() }
                        deleted[table] = st.executeBatch().sum()
                    }
                }
            }
            Report(total, prunable, kept, ignored, refused, deleted)
        }
        // The rulings and tags of a stale card point at it by name, so the derived rows go first, in one transaction.
        return if (delete) db.write(foreignKeys = false, block = block) else db.read(block)
    }
}
