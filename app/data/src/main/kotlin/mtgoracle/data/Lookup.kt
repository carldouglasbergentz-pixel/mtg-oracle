package mtgoracle.data

import mtgoracle.core.lookup.CustomFormat
import mtgoracle.core.lookup.DeckScope
import mtgoracle.core.lookup.FormatCatalog

/**
 * Cards, rules, combos and corrections, read-only: everything the lookup
 * commands ask. Reads the card names and the formats once, at construction.
 */
class Lookup(private val db: MtgDb) {
    val names: CardNames = CardNames(db.read { conn ->
        // Table order, not sorted: the fold fallback picks the first card in it, as Python's scan does.
        conn.prepareStatement("SELECT name FROM cards").use { st -> st.executeQuery().use { rs -> rs.rows { getString(1) } } }
    })
    val formats: FormatCatalog = FormatCatalog(readCustomFormats())
    val corrections = Corrections(db)
    val combos = Combos(db, names)
    val cards = Cards(db, names, combos, corrections)
    val rules = Rules(db)
    val search = CardSearch(db, formats)

    /** What deck [deckId] can play, for `search` and the card profile's combos after `cd`; null if it is gone. */
    fun deckScope(deckId: Int): DeckScope? = db.read { conn ->
        val (name, format) = conn.prepareStatement("SELECT name, format FROM decks WHERE id = ?").use { st ->
            st.setInt(1, deckId)
            st.executeQuery().use { rs -> if (rs.next()) rs.getString("name") to rs.getString("format") else null }
        } ?: return@read null
        val identities = conn.prepareStatement(
            "SELECT c.color_identity FROM deck_cards dc LEFT JOIN cards c ON c.name = dc.card_name COLLATE NOCASE " +
                "WHERE dc.deck_id = ? AND dc.is_commander = 1",
        ).use { st ->
            st.setInt(1, deckId)
            st.executeQuery().use { rs -> rs.rows { getString(1) } }
        }
        val ci = if (identities.isEmpty()) null
        else identities.flatMap { it.orEmpty().split(",") }.map { it.trim() }.filter { it.isNotEmpty() }.toSortedSet().toList()
        DeckScope(deckId, name, ci, formats.resolve(format))
    }

    private fun readCustomFormats(): List<CustomFormat> = db.read { conn ->
        conn.prepareStatement(
            """
            SELECT format, name, derives_from, points_budget, singleton,
                   CASE WHEN json_valid(aliases) AND json_type(aliases) = 'array'
                        THEN (SELECT GROUP_CONCAT(value, char(31)) FROM json_each(custom_formats.aliases)) END AS alias_list
            FROM custom_formats
            """.trimIndent(),
        ).use { st ->
            st.executeQuery().use { rs ->
                rs.rows {
                    CustomFormat(
                        key = getString("format"), name = getString("name"),
                        aliases = getString("alias_list")?.split('\u001F').orEmpty(),
                        derivesFrom = getString("derives_from"),
                        pointsBudget = getInt("points_budget").takeIf { !wasNull() },
                        singleton = getInt("singleton") != 0,
                    )
                }
            }
        }
    }
}
