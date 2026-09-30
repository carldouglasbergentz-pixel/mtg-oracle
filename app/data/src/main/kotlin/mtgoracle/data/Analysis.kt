package mtgoracle.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mtgoracle.core.analysis.AnalysisRow
import mtgoracle.core.analysis.Archetype
import mtgoracle.core.analysis.DeckAnalysis
import mtgoracle.core.analysis.DeckInsight
import mtgoracle.core.analysis.CardFace
import mtgoracle.core.analysis.CardFacts
import mtgoracle.core.analysis.CardPool
import mtgoracle.core.analysis.DeckList
import java.sql.Connection
import java.sql.ResultSet

/**
 * What the analysis reads: card facts and Tagger labels for any spelling of
 * a name (queries.get_card_facts / get_oracle_tags), and a deck's rows with
 * their facts. `roles` and friends are pure, so the data comes from here.
 */
class Analysis(private val db: MtgDb, private val names: CardNames, private val combos: Combos) {
    /** Every pool's classifications: the cards change only with a sync, which the app does not survive. */
    private val classified = java.util.concurrent.ConcurrentHashMap<Pair<String, Int>, mtgoracle.core.analysis.Classification>()

    /** Everything the analysis block above deck [deckId] shows. */
    fun insight(deckId: Int, name: String): DeckInsight {
        val list = deckList(deckId, name)
        val pool = pool(list.cards.keys)
        return DeckInsight(
            deckId = deckId,
            analytics = DeckAnalysis.compute(rows(deckId)),
            profile = Archetype.profile(list, pool),
            combos = combos.inDeck(deckId, limit = 500).size,
            unsure = Archetype.rank(listOf(list), pool).lowConfidence.sorted(),
        )
    }

    /** Facts and tags for [asked], keyed by each name as asked; names that don't resolve are absent. */
    fun pool(asked: Collection<String>): CardPool {
        val resolved = asked.distinct().associateWith { names.resolve(it) }
        val canonical = resolved.values.filterNotNull().distinct()
        return db.read { conn ->
            val facts = chunked(conn, canonical, "SELECT $FACT_COLUMNS FROM cards WHERE name COLLATE NOCASE IN") { readFacts(it) }
                .associateBy { it.name.lowercase() }
            val tags = chunked(conn, canonical, "SELECT card_name, tag FROM card_oracle_tags WHERE card_name COLLATE NOCASE IN") {
                it.getString(1).lowercase() to it.getString(2)
            }.groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.toSet() }
            CardPool(
                resolved.mapNotNull { (name, canon) -> canon?.let { facts[it.lowercase()] }?.let { name to it } }.toMap(),
                resolved.mapNotNull { (name, canon) -> canon?.let { tags[it.lowercase()] }?.let { name to it } }.toMap(),
                classified,
            )
        }
    }

    /** Deck [deckId]'s rows with their facts, sideboard included (the analytics skip it themselves). */
    fun rows(deckId: Int): List<AnalysisRow> = db.read { conn ->
        conn.prepareStatement(
            "SELECT dc.card_name AS deck_name, dc.quantity, dc.is_commander, dc.is_sideboard, " +
                "c.name IS NOT NULL AS known, ${FACT_COLUMNS.split(", ").joinToString(", ") { "c.$it" }} " +
                "FROM deck_cards dc LEFT JOIN cards c ON c.name = dc.card_name COLLATE NOCASE WHERE dc.deck_id = ?",
        ).use { st ->
            st.setInt(1, deckId)
            st.executeQuery().use { rs ->
                rs.rows {
                    AnalysisRow(
                        name = getString("deck_name"),
                        quantity = getInt("quantity"),
                        facts = if (getBoolean("known")) readFacts(this) else null,
                        isCommander = getBoolean("is_commander"),
                        isSideboard = getBoolean("is_sideboard"),
                    )
                }
            }
        }
    }

    /**
     * Deck [deckId] as the archetype analysis takes it: the main deck and
     * commanders, sideboard dropped, since the draw maths is about the cards
     * you shuffle (services.deck_cards_for_analysis).
     */
    fun deckList(deckId: Int, name: String): DeckList {
        val cards = linkedMapOf<String, Int>()
        db.read { conn ->
            conn.query("SELECT card_name, quantity FROM deck_cards WHERE deck_id = ? AND is_sideboard = 0", deckId) {
                getString(1) to getInt(2)
            }
        }.forEach { (card, qty) -> cards.merge(card, qty, Int::plus) }
        return DeckList(name, cards)
    }

    /** Every card in the database, for the classifier's parity test. */
    internal fun everyCard(): List<Pair<CardFacts, Set<String>>> = db.read { conn ->
        val tags = conn.query("SELECT card_name, tag FROM card_oracle_tags") { getString(1).lowercase() to getString(2) }
            .groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.toSet() }
        conn.query("SELECT $FACT_COLUMNS FROM cards") { readFacts(this) }.map { it to (tags[it.name.lowercase()] ?: emptySet()) }
    }

    private fun <T> chunked(conn: Connection, keys: List<String>, sql: String, read: (ResultSet) -> T): List<T> =
        keys.chunked(400).flatMap { chunk -> conn.query("$sql (${chunk.joinToString(", ") { "?" }})", *chunk.toTypedArray()) { read(this) } }

    internal companion object {
        const val FACT_COLUMNS = "name, mana_cost, mana_value, type_line, oracle_text, color_identity, layout, card_faces, power, toughness"

        fun readFacts(rs: ResultSet) = CardFacts(
            name = rs.getString("name"),
            manaCost = rs.getString("mana_cost"),
            manaValue = rs.getInt("mana_value"),
            typeLine = rs.getString("type_line"),
            oracleText = rs.getString("oracle_text"),
            colorIdentity = rs.getString("color_identity"),
            layout = rs.getString("layout"),
            faces = parseFaces(rs.getString("card_faces")),
            power = rs.getString("power"),
            toughness = rs.getString("toughness"),
        )

        /** Scryfall's `card_faces` JSON; anything malformed is a single-faced card, as in Python. */
        fun parseFaces(raw: String?): List<CardFace> {
            if (raw.isNullOrEmpty()) return emptyList()
            val array = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonArray ?: return emptyList()
            return array.mapNotNull { element ->
                val face = element as? JsonObject ?: return@mapNotNull null
                fun field(key: String) = (face[key] as? JsonPrimitive)?.takeIf { !it.isNullLiteral() }?.content
                CardFace(field("name"), field("mana_cost"), field("type_line"), field("oracle_text"), field("power"), field("toughness"))
            }
        }

        private fun JsonPrimitive.isNullLiteral() = this is kotlinx.serialization.json.JsonNull
    }
}
