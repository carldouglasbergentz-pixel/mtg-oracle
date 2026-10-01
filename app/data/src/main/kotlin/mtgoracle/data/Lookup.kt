package mtgoracle.data

import mtgoracle.core.lookup.CustomFormat
import mtgoracle.core.lookup.DeckScope
import mtgoracle.core.lookup.FormatCatalog
import mtgoracle.core.lookup.Formats
import mtgoracle.core.lookup.SearchFields
import mtgoracle.core.lookup.SearchVocabulary

/**
 * Cards, rules, combos and corrections, read-only: everything the lookup
 * commands ask. Reads the card names and the formats once, at construction.
 */
class Lookup(val db: MtgDb) {
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
    val analysis = Analysis(db, names, combos)
    /** The games played and simulated, for `results`. */
    val games = GameStore(db)
    /** The user's own combos, beside Spellbook's. */
    val userCombos = UserCombos(db, names)

    /**
     * What autofill offers after each field, the most used first: types and
     * keywords from `card_tags`, Tagger tags (plus the parents their children
     * imply: `removal` from `removal-creature`), formats, rarities, layouts.
     */
    val vocabulary: SearchVocabulary by lazy {
        db.read { conn ->
            fun column(sql: String): List<String> = conn.prepareStatement(sql).use { st -> st.executeQuery().use { rs -> rs.rows { getString(1) } } }.filterNotNull()
            val tags = column("SELECT tag FROM card_oracle_tags GROUP BY tag ORDER BY COUNT(*) DESC")
            SearchVocabulary(mapOf(
                "t" to column("SELECT tag FROM card_tags WHERE category IN ('type', 'subtype', 'supertype') GROUP BY tag ORDER BY COUNT(*) DESC"),
                "kw" to column("SELECT tag FROM card_tags WHERE category = 'keyword' GROUP BY tag ORDER BY COUNT(*) DESC"),
                "otag" to (tags + tags.filter { '-' in it }.map { it.substringBefore('-') }).distinct(),
                "f" to (Formats.LEGALITY.toList() + readCustomFormats().map { it.key }),
                "r" to listOf("common", "uncommon", "rare", "mythic", "special", "bonus"),
                "layout" to column("SELECT layout FROM cards WHERE layout IS NOT NULL GROUP BY layout ORDER BY COUNT(*) DESC"),
                "game" to SearchFields.GAMES,
                "is" to SearchFields.IS_FLAGS,
                "order" to SearchFields.SORT_FIELDS.flatMap { listOf("asc_$it", "desc_$it") },
            ))
        }
    }

    /** A card's layout (`split`, `transform`, `modal_dfc`...), or null: what the export's front-face rule needs. */
    fun layout(card: String): String? =
        db.read { conn -> conn.query("SELECT layout FROM cards WHERE name = ? COLLATE NOCASE", card) { getString(1) }.firstOrNull() }

    /** The names on deck [deckId]'s considering list. */
    fun deckConsidering(deckId: Int): List<String> =
        db.read { conn -> conn.query("SELECT card_name FROM deck_considering WHERE deck_id = ? ORDER BY card_name COLLATE NOCASE", deckId) { getString(1) } }

    /** A points format's list, lower-cased card name -> points; empty for a format without one. */
    fun points(formatKey: String?): Map<String, Int> {
        if (formatKey == null) return emptyMap()
        return db.read { conn -> conn.query("SELECT card_name, points FROM custom_format_points WHERE format = ?", formatKey) { getString(1).lowercase() to getInt(2) } }.toMap()
    }

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
