package mtgoracle.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.sql.Connection

/**
 * Scryfall's printings into `printings` and `printing_sets`: every paper
 * printing of every card, for choosing its art. The first sync reads the
 * `default_cards` bulk export (English, or the printed language where no
 * English printing exists); later ones fetch only the sets whose card count
 * moved, through the search API. An image URL is not stored: it follows from
 * the Scryfall id (Printings.imageUrl).
 */
internal object PrintingsIngest {
    data class Row(
        val scryfallId: String, val cardName: String, val oracleId: String?, val setCode: String, val setName: String,
        val collectorNumber: String, val lang: String, val releasedAt: String?, val artist: String?, val imageFaces: Int,
        val finishes: String, val labels: String,
    )

    data class SetInfo(val code: String, val cardCount: Int, val digital: Boolean)

    /** A paper printing of a card worth showing, or null (digital only, a token, an art card). */
    fun row(card: JsonObject): Row? {
        if (card.py("layout") in CardsIngest.SKIPPED_LAYOUTS) return null
        if (truthy(card.py("digital"))) return null
        val games = (card["games"] as? JsonArray)?.mapNotNull { it.asText() }.orEmpty()
        if ("paper" !in games) return null
        val id = card.py("id") as? String ?: return null
        val name = card.py("name") as? String ?: return null
        val set = card.py("set") as? String ?: return null
        val number = card.py("collector_number") as? String ?: return null
        val faces = card.array("card_faces")?.mapNotNull { it as? JsonObject }.orEmpty()
        val imageFaces = if (card.obj("image_uris") != null) 1 else faces.count { it.obj("image_uris") != null }.coerceAtLeast(1)
        return Row(
            scryfallId = id, cardName = name,
            oracleId = card.py("oracle_id") as? String ?: faces.firstNotNullOfOrNull { it.py("oracle_id") as? String },
            setCode = set, setName = card.text("set_name"), collectorNumber = number, lang = card.text("lang").ifEmpty { "en" },
            releasedAt = card.py("released_at") as? String,
            artist = card.py("artist") as? String ?: faces.firstNotNullOfOrNull { it.py("artist") as? String },
            imageFaces = imageFaces,
            finishes = (card["finishes"] as? JsonArray)?.mapNotNull { it.asText() }.orEmpty().joinToString(","),
            labels = labels(card).joinToString(","),
        )
    }

    /** What sets a printing apart in the chooser: borderless, showcase, extended art, full art, textless, promo. */
    private fun labels(card: JsonObject): List<String> {
        val effects = (card["frame_effects"] as? JsonArray)?.mapNotNull { it.asText() }.orEmpty()
        return buildList {
            if (card.py("border_color") == "borderless") add("borderless")
            effects.filter { it in SHOWN_FRAME_EFFECTS }.forEach { add(if (it == "extendedart") "extended art" else it) }
            if (truthy(card.py("full_art"))) add("full art")
            if (truthy(card.py("textless"))) add("textless")
            if (truthy(card.py("promo"))) add("promo")
        }
    }

    private val SHOWN_FRAME_EFFECTS = setOf("showcase", "extendedart", "inverted", "etched")

    private const val INSERT = """
        INSERT OR REPLACE INTO printings (scryfall_id, card_name, oracle_id, set_code, set_name, collector_number, lang,
                                          released_at, artist, image_faces, finishes, labels)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """

    private fun insertAll(conn: Connection, rows: Sequence<Row>): Int {
        var count = 0
        conn.prepareStatement(INSERT.trimIndent()).use { insert ->
            for (r in rows) {
                insert.bind(r.scryfallId, r.cardName, r.oracleId, r.setCode, r.setName, r.collectorNumber, r.lang, r.releasedAt,
                    r.artist, r.imageFaces, r.finishes, r.labels)
                insert.addBatch()
                if (++count % 5_000 == 0) insert.executeBatch()
            }
            insert.executeBatch()
        }
        return count
    }

    /** Every printing from the bulk export, replacing what was there. */
    fun replaceAll(conn: Connection, cards: Sequence<JsonObject>): Int {
        conn.createStatement().use { it.executeUpdate("DELETE FROM printings") }
        return insertAll(conn, cards.mapNotNull(::row))
    }

    /** One set's printings, replacing that set's. */
    fun replaceSet(conn: Connection, setCode: String, cards: List<JsonObject>): Int {
        conn.prepareStatement("DELETE FROM printings WHERE set_code = ?").use { it.bind(setCode); it.executeUpdate() }
        return insertAll(conn, cards.asSequence().mapNotNull(::row))
    }

    /** The sets' card counts as Scryfall gave them: the next sync fetches only a set whose count moved. */
    fun saveSets(conn: Connection, sets: List<SetInfo>, now: String) {
        conn.prepareStatement("INSERT OR REPLACE INTO printing_sets (set_code, card_count, synced_at) VALUES (?, ?, ?)").use { st ->
            for (s in sets) { st.bind(s.code, s.cardCount, now); st.addBatch() }
            st.executeBatch()
        }
    }

    fun knownSets(conn: Connection): Map<String, Int> = conn.createStatement().use { st ->
        st.executeQuery("SELECT set_code, card_count FROM printing_sets").use { rs -> buildMap { while (rs.next()) put(rs.getString(1), rs.getInt(2)) } }
    }

    /** Scryfall's `/sets` answer. */
    fun parseSets(json: String): List<SetInfo> =
        ((Json.parseToJsonElement(json) as JsonObject)["data"] as? JsonArray).orEmpty().mapNotNull { e ->
            val s = e as? JsonObject ?: return@mapNotNull null
            val code = s.py("code") as? String ?: return@mapNotNull null
            SetInfo(code, (s["card_count"] as? JsonPrimitive)?.intOrNull ?: 0, (s["digital"] as? JsonPrimitive)?.booleanOrNull ?: false)
        }

    /**
     * [cards] as `default_cards` holds a set: each collector number in English, or in
     * the one language it was printed in when there is no English printing of it.
     */
    fun preferEnglish(cards: List<JsonObject>): List<JsonObject> =
        cards.groupBy { it.text("collector_number") }.values.map { prints -> prints.firstOrNull { it.text("lang") == "en" } ?: prints.first() }

    /** One page of a search: its cards, and the next page's URL when there is one. */
    fun parsePage(json: String): Pair<List<JsonObject>, String?> {
        val page = Json.parseToJsonElement(json) as JsonObject
        val cards = (page["data"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val next = if (truthy(page.py("has_more"))) page.py("next_page") as? String else null
        return cards to next
    }
}
