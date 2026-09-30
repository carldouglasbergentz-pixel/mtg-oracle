package mtgoracle.data

import mtgoracle.core.lookup.Correction
import java.sql.Connection

/** The corrections table: the project's memory of factual mistakes. (queries.get_corrections) */
class Corrections(private val db: MtgDb) {

    /**
     * The newest first; [text], when given, has to appear in the card names,
     * the topic or the wrong claim — one box, OR across the three, so a
     * correction whose topic doesn't repeat the card name is still found.
     */
    fun list(text: String? = null, limit: Int = 50): List<Correction> = db.read { conn ->
        val where = if (text.isNullOrBlank()) "" else
            "WHERE relates_to LIKE ? ESCAPE '!' OR topic LIKE ? ESCAPE '!' OR incorrect_claim LIKE ? ESCAPE '!'"
        conn.prepareStatement("$SELECT $where ORDER BY added_at DESC LIMIT ?").use { st ->
            var i = 0
            if (!text.isNullOrBlank()) repeat(3) { st.setString(++i, SearchSql.contains(text.trim())) }
            st.setInt(++i, limit.coerceIn(1, 200))
            st.executeQuery().use { rs -> rs.rows { correction() } }
        }
    }

    /**
     * Those naming any of [names], matched as whole JSON elements: a bare
     * substring had 'Strangle' pick up Strangleroot Geist's correction.
     */
    internal fun about(conn: Connection, names: List<String>): List<Correction> =
        conn.prepareStatement("$SELECT WHERE ${names.joinToString(" OR ") { "relates_to LIKE ? ESCAPE '!' COLLATE NOCASE" }} ORDER BY added_at DESC").use { st ->
            names.forEachIndexed { i, name -> st.setString(i + 1, SearchSql.contains(jsonString(name))) }
            st.executeQuery().use { rs -> rs.rows { correction() } }
        }

    private fun java.sql.ResultSet.correction(): Correction {
        val raw = getString("relates_to")
        val names = getString("names")
        return Correction(
            id = getInt("id"), topic = getString("topic").orEmpty(), category = getString("category"),
            incorrectClaim = getString("incorrect_claim"), correctClaim = getString("correct_claim").orEmpty(),
            explanation = getString("explanation"),
            // A hand-edited row may hold free text rather than a JSON array: kept, not dropped.
            relatesTo = when {
                names != null -> names.split(UNIT_SEPARATOR)
                raw.isNullOrBlank() || raw == "[]" -> emptyList()
                else -> listOf(raw)
            },
            source = getString("source"),
        )
    }

    private companion object {
        const val UNIT_SEPARATOR = '\u001F'
        // relates_to is a JSON array of names; SQLite's JSON1 reads it, so no JSON library is needed.
        // CASE evaluates lazily, so json_each never sees a row that isn't an array.
        val SELECT = """
            SELECT id, topic, category, incorrect_claim, correct_claim, explanation, relates_to, source,
                   CASE WHEN json_valid(relates_to) AND json_type(relates_to) = 'array' AND json_array_length(relates_to) > 0
                        THEN (SELECT GROUP_CONCAT(value, char(31)) FROM json_each(corrections.relates_to)) END AS names
            FROM corrections
        """.trimIndent()

        /** [s] as a JSON string literal, as Python's json.dumps(ensure_ascii=False) writes it into relates_to. */
        fun jsonString(s: String): String = buildString {
            append('"')
            for (ch in s) when {
                ch == '"' -> append("\\\"")
                ch == '\\' -> append("\\\\")
                ch == '\n' -> append("\\n")
                ch == '\t' -> append("\\t")
                ch < ' ' -> append("\\u%04x".format(ch.code))
                else -> append(ch)
            }
            append('"')
        }
    }
}
