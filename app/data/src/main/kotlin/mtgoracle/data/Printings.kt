package mtgoracle.data

import mtgoracle.core.deck.CardPrinting

/**
 * Every paper printing Scryfall has of a card (the `printings` table, filled
 * by the sync's printings source): what the art chooser lists, and what the
 * art layer fetches when Forge lacks a printing.
 */
class Printings(private val db: MtgDb, private val names: CardNames) {

    /** One stored printing: Scryfall's id is what its images are named by. */
    data class Stored(val scryfallId: String, val printing: CardPrinting, val artist: String?, val imageFaces: Int)

    private val columns = "scryfall_id, set_code, collector_number, set_name, released_at, lang, labels, artist, image_faces"

    private fun stored(rs: java.sql.ResultSet) = Stored(
        rs.getString(1),
        CardPrinting(rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5).orEmpty(), lang = rs.getString(6), labels = rs.getString(7).orEmpty()),
        rs.getString(8), rs.getInt(9),
    )

    /** [name]'s printings, newest first; empty before the first printings sync. */
    fun forCard(name: String): List<Stored> {
        val canonical = names.resolve(name) ?: name
        return db.read { conn ->
            conn.prepareStatement("SELECT $columns FROM printings WHERE card_name = ? COLLATE NOCASE ORDER BY released_at DESC, set_code, collector_number").use { st ->
                st.setString(1, canonical)
                st.executeQuery().use { rs -> buildList { while (rs.next()) add(stored(rs)) } }
            }
        }
    }

    /** One printing of [name] by Scryfall's set code and collector number, ignoring case; null when Scryfall has no such one. */
    fun find(name: String, setCode: String, collectorNumber: String?): Stored? {
        val canonical = names.resolve(name) ?: name
        return db.read { conn ->
            val sql = "SELECT $columns FROM printings WHERE card_name = ? COLLATE NOCASE AND set_code = ? COLLATE NOCASE" +
                (if (collectorNumber != null) " AND collector_number = ? COLLATE NOCASE" else "") + " ORDER BY lang = 'en' DESC, collector_number LIMIT 1"
            conn.prepareStatement(sql).use { st ->
                st.setString(1, canonical); st.setString(2, setCode)
                collectorNumber?.let { st.setString(3, it) }
                st.executeQuery().use { rs -> if (rs.next()) stored(rs) else null }
            }
        }
    }

    companion object {
        /** Scryfall's image of a printing: [kind] is `art_crop`, `normal` or `large`; face 1 is the back of a two-faced card. */
        fun imageUrl(scryfallId: String, kind: String, face: Int = 0): String =
            "https://cards.scryfall.io/$kind/${if (face == 0) "front" else "back"}/${scryfallId[0]}/${scryfallId[1]}/$scryfallId.jpg"
    }
}
