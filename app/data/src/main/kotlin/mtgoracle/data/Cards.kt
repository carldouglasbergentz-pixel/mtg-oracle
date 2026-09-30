package mtgoracle.data

import mtgoracle.core.lookup.Ability
import mtgoracle.core.lookup.CardProfile
import mtgoracle.core.lookup.Formats
import mtgoracle.core.lookup.Ruling

/** A card's profile and its rulings, by any name [CardNames] resolves. (queries.get_card / get_rulings) */
class Cards(private val db: MtgDb, private val names: CardNames, private val combos: Combos, private val corrections: Corrections) {

    /**
     * Everything `card <name>` shows, or null for a name that resolves to
     * nothing. [restrictToCi] filters the embedded combos to a deck's
     * commander identity (empty = colourless only); null leaves them all.
     */
    fun profile(name: String, restrictToCi: List<String>? = null): CardProfile? {
        val canonical = names.resolve(name) ?: return null
        return db.read { conn ->
            val base = conn.prepareStatement(
                "SELECT name, mana_cost, type_line, oracle_text, games, reserved, edhrec_rank FROM cards WHERE name = ?",
            ).use { st ->
                st.setString(1, canonical)
                st.executeQuery().use { rs -> if (rs.next()) rs.rowValues() else null }
            } ?: return@read null
            val tags = conn.prepareStatement("SELECT tag, category FROM card_tags WHERE card_name = ? ORDER BY category, tag").use { st ->
                st.setString(1, canonical)
                st.executeQuery().use { rs -> rs.rows { getString("category") to getString("tag") } }
            }.groupBy({ it.first }, { it.second })
            val abilities = conn.prepareStatement(
                "SELECT ability_type, cost, effect, has_target, produces_mana, is_mana_ability FROM card_abilities WHERE card_name = ? ORDER BY ability_index",
            ).use { st ->
                st.setString(1, canonical)
                st.executeQuery().use { rs ->
                    rs.rows {
                        Ability(getString("ability_type"), getString("cost"), getString("effect"),
                            getInt("has_target") != 0, getInt("produces_mana") != 0, getInt("is_mana_ability") != 0)
                    }
                }
            }
            // Absence of a row is "not legal", so only the formats worth naming come back. `restricted`
            // is split again: in Duel Commander and Tiny Leaders it is "not as your commander".
            val legalities = conn.prepareStatement("SELECT format, status FROM card_legalities WHERE card_name = ? ORDER BY format").use { st ->
                st.setString(1, canonical)
                st.executeQuery().use { rs ->
                    rs.rows {
                        val format = getString("format")
                        val status = getString("status")
                        (if (status == "restricted" && format in Formats.RESTRICTED_MEANS_NO_COMMANDER) "no_commander" else status) to format
                    }
                }
            }.groupBy({ it.first }, { it.second })
            CardProfile(
                name = base.getValue("name") as String,
                manaCost = base["mana_cost"] as String?,
                typeLine = base["type_line"] as String?,
                oracleText = base["oracle_text"] as String?,
                games = base["games"] as String?,
                reserved = (base["reserved"] as Number?)?.toInt() == 1,
                edhrecRank = (base["edhrec_rank"] as Number?)?.toInt(),
                tags = tags,
                abilities = abilities,
                rulings = rulingsOf(conn, canonical),
                legalities = legalities,
                combos = combos.withCard(conn, canonical, limit = 10, restrictToCi = restrictToCi),
                combosFilteredByCi = restrictToCi?.let { it.joinToString("").ifEmpty { "C" } },
                // A correction names a card the way people say it — often one face — so each face counts too.
                corrections = corrections.about(conn, listOf(canonical) + if (" // " in canonical) canonical.split(" // ") else emptyList()),
            )
        }
    }

    /** A card's rulings, oldest first; empty for a name nothing resolves to. */
    fun rulings(name: String): List<Ruling> {
        val canonical = names.resolve(name) ?: return emptyList()
        return db.read { rulingsOf(it, canonical) }
    }

    private fun rulingsOf(conn: java.sql.Connection, canonical: String): List<Ruling> =
        conn.prepareStatement("SELECT date, text FROM rulings WHERE card_name = ? ORDER BY date").use { st ->
            st.setString(1, canonical)
            st.executeQuery().use { rs -> rs.rows { Ruling(getString("date").orEmpty(), getString("text").orEmpty()) } }
        }
}

/** The current row as column label -> value (nulls kept). */
internal fun java.sql.ResultSet.rowValues(): Map<String, Any?> {
    val meta = metaData
    return (1..meta.columnCount).associate { meta.getColumnLabel(it) to getObject(it) }
}
