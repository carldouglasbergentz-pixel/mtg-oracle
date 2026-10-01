package mtgoracle.data.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.sql.Connection

/**
 * Scryfall's oracle cards and rulings into `cards`, `card_legalities` and
 * `rulings` (sync_cards.ingest_cards / ingest_rulings). Cards are upserted on
 * name and never deleted; legalities and rulings are a full snapshot.
 */
internal object CardsIngest {
    /**
     * Not game cards. `front_card` is a memorabilia product's display face,
     * which shares names with real cards (Blink) and used to overwrite them.
     * Planes, schemes and vanguards stay.
     */
    val SKIPPED_LAYOUTS = setOf("art_series", "emblem", "token", "double_faced_token", "front_card")

    /** Set types that lose a name collision against any other printing. */
    private val NOVELTY_SET_TYPES = setOf("funny", "memorabilia")

    data class Result(val cards: Int, val collisions: Int, val skipped: Int)

    /** (legal anywhere, not a novelty set): the entry that holds a shared name. */
    private fun rank(card: JsonObject): Pair<Boolean, Boolean> {
        val legal = card.obj("legalities")?.values?.any { it.asText() != "not_legal" } ?: false
        return legal to (card.py("set_type") !in NOVELTY_SET_TYPES)
    }

    private fun Pair<Boolean, Boolean>.atMost(other: Pair<Boolean, Boolean>) =
        first < other.first || (first == other.first && second <= other.second)

    private fun faces(card: JsonObject): JsonArray = card.array("card_faces") ?: JsonArray(emptyList())

    /** Both faces' text for a two-faced card: `name\ntext`, joined by `\n\n// \n\n`. */
    private fun oracleText(card: JsonObject): String {
        val top = card.text("oracle_text")
        val faces = faces(card)
        if (faces.isEmpty()) return top
        val parts = faces.mapNotNull { f ->
            f as JsonObject
            val name = f.py("name", "")?.toString().orEmpty()
            val text = f.text("oracle_text")
            if (name.isNotEmpty() || text.isNotEmpty()) "$name\n$text".trim() else null
        }
        return parts.joinToString("\n\n// \n\n").ifEmpty { top }
    }

    private fun typeLine(card: JsonObject): String {
        val top = card.text("type_line")
        if (top.isNotEmpty()) return top
        return faces(card).map { (it as JsonObject).text("type_line") }.filter { it.isNotEmpty() }.joinToString(" // ")
    }

    /** Per-face costs joined with ` // ` when the card has none of its own; a land's empty cost stays "". */
    private fun manaCost(card: JsonObject): String {
        val top = card.text("mana_cost")
        val faces = faces(card)
        if (top.isNotEmpty() || faces.isEmpty()) return top
        return faces.joinToString(" // ") { (it as JsonObject).text("mana_cost") }
    }

    private fun letters(v: Any?): List<String> = (v as? JsonArray)?.mapNotNull { it.asText() }.orEmpty()

    private fun colors(card: JsonObject): String {
        val all = letters(card["colors"]).toMutableSet()
        faces(card).forEach { all += letters((it as JsonObject)["colors"]) }
        return all.sorted().joinToString(",")
    }

    private fun sortedCsv(v: Any?): String = letters(v).sorted().joinToString(",")

    /** Python's `int(cmc or 0)`: Who/What/When's 0.5 is 0. */
    private fun manaValue(card: JsonObject): Int = (card["cmc"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() ?: 0

    /** The top-level value, else the first face's that has one. */
    private fun faceField(card: JsonObject, key: String): Any? {
        card.py(key)?.let { return it }
        for (f in faces(card)) (f as JsonObject).py(key)?.let { return it }
        return null
    }

    fun cards(conn: Connection, file: File): Result {
        conn.createStatement().use { it.executeUpdate("DELETE FROM card_legalities") }
        val upsert = conn.prepareStatement(
            """
            INSERT INTO cards (name, oracle_id, oracle_text, mana_cost, mana_value, colors, color_identity, power, toughness, rarity,
                               type_line, layout, card_faces, games, reserved, edhrec_rank)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(name) DO UPDATE SET
                oracle_id = excluded.oracle_id, oracle_text = excluded.oracle_text, mana_cost = excluded.mana_cost,
                mana_value = excluded.mana_value, colors = excluded.colors, color_identity = excluded.color_identity,
                power = excluded.power, toughness = excluded.toughness, rarity = excluded.rarity, type_line = excluded.type_line,
                layout = excluded.layout, card_faces = excluded.card_faces, games = excluded.games, reserved = excluded.reserved,
                edhrec_rank = excluded.edhrec_rank
            """.trimIndent(),
        )
        val legality = conn.prepareStatement("INSERT OR REPLACE INTO card_legalities (card_name, format, status) VALUES (?, ?, ?)")
        val dropLegalities = conn.prepareStatement("DELETE FROM card_legalities WHERE card_name = ?")
        var pending = 0
        fun flush() { if (pending > 0) { legality.executeBatch(); pending = 0 } }

        val written = HashMap<String, Pair<Boolean, Boolean>>()
        var count = 0
        var collisions = 0
        var skipped = 0
        try {
            for (card in jsonLines(file)) {
                val layout = card.py("layout", "")
                if (layout in SKIPPED_LAYOUTS) { skipped++; continue }
                val name = card.py("name") as? String
                if (name.isNullOrEmpty()) continue
                val rank = rank(card)
                val held = written[name]
                if (held != null) {
                    collisions++
                    if (rank.atMost(held)) continue
                    // The better entry came second: it takes the row, and the loser's legalities go too.
                    flush()
                    dropLegalities.bind(name); dropLegalities.executeUpdate()
                } else count++
                written[name] = rank

                val faces = card.array("card_faces")?.takeIf { it.isNotEmpty() }
                upsert.bind(
                    name, card.py("oracle_id"), oracleText(card), manaCost(card), manaValue(card), colors(card),
                    sortedCsv(card["color_identity"]), faceField(card, "power"), faceField(card, "toughness"), card.py("rarity"),
                    typeLine(card), layout, faces?.let(PyJson::dumps), letters(card["games"]).sorted().joinToString(","),
                    truthy(card.py("reserved")), card.py("edhrec_rank"),
                )
                upsert.executeUpdate()
                card.obj("legalities")?.forEach { (format, status) ->
                    val s = status.asText()
                    if (!s.isNullOrEmpty() && s != "not_legal") {
                        legality.bind(name, format, s); legality.addBatch(); pending++
                    }
                }
                if (pending >= 20_000) flush()
            }
            flush()
        } finally {
            upsert.close(); legality.close(); dropLegalities.close()
        }
        return Result(count, collisions, skipped)
    }

    /** Rulings by oracle id onto the cards that have it; a ruling for a card this database lacks is skipped. */
    fun rulings(conn: Connection, file: File): Int {
        val nameByOracle = HashMap<String, String>()
        conn.createStatement().use { st ->
            st.executeQuery("SELECT oracle_id, name FROM cards WHERE oracle_id IS NOT NULL").use { rs -> while (rs.next()) nameByOracle[rs.getString(1)] = rs.getString(2) }
        }
        conn.createStatement().use { it.executeUpdate("DELETE FROM rulings") }
        var count = 0
        conn.prepareStatement("INSERT INTO rulings (card_name, oracle_id, date, text) VALUES (?, ?, ?, ?)").use { insert ->
            for (r in jsonLines(file)) {
                val oracleId = r.py("oracle_id") as? String
                val name = nameByOracle[oracleId] ?: continue
                insert.bind(name, oracleId, r.py("published_at", ""), r.py("comment", ""))
                insert.addBatch()
                if (++count % 5_000 == 0) insert.executeBatch()
            }
            insert.executeBatch()
        }
        return count
    }
}
