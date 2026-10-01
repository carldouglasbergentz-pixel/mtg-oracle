package mtgoracle.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import mtgoracle.core.lookup.Formats
import mtgoracle.data.CardNames
import java.io.File
import java.sql.Connection

/** A format file the load refuses: malformed, or naming a card the database can't resolve. */
class FormatDefinitionError(message: String) : IllegalArgumentException(message)

/**
 * The curated community formats in `data/formats/` (one JSON file each) into
 * `custom_formats` and `custom_format_points` (load_custom_formats.py). Every
 * file is checked before anything is written; a card name that doesn't
 * resolve is a hard failure, not a silently missing point.
 */
internal object FormatsIngest {
    data class Definition(val file: File, val spec: JsonObject, val points: LinkedHashMap<String, Int>) {
        val format: String get() = spec.text("format")
    }

    private val REQUIRED = listOf("format", "name", "points")

    fun load(file: File, names: CardNames): Definition {
        val spec = try {
            Json.parseToJsonElement(file.readText(Charsets.UTF_8)) as? JsonObject ?: throw FormatDefinitionError("${file.name}: not a JSON object")
        } catch (e: kotlinx.serialization.SerializationException) {
            throw FormatDefinitionError("${file.name}: invalid JSON: ${e.message}")
        }
        val missing = REQUIRED.filter { it !in spec }.sorted()
        if (missing.isNotEmpty()) throw FormatDefinitionError("${file.name}: missing required key(s): ${missing.joinToString(", ")}")
        val points = spec["points"] as? JsonObject ?: throw FormatDefinitionError("${file.name}: 'points' must be an object")
        // A typo here becomes a legality key with no rows, which refuses every card "not in the format's pool".
        (spec.py("derives_from") as? String)?.let { derives ->
            if (derives !in Formats.LEGALITY) {
                throw FormatDefinitionError("${file.name}: derives_from='$derives' is not a Scryfall legality format. Valid: ${Formats.LEGALITY.sorted().joinToString(", ")}")
            }
        }
        val resolved = LinkedHashMap<String, Int>()
        val unresolved = mutableListOf<String>()
        for ((raw, value) in points) {
            val n = (value as? JsonPrimitive)?.takeIf { !it.isString }?.let { if (it.content == "true") 1L else it.longOrNull }
            if (n == null || n < 1) throw FormatDefinitionError("${file.name}: '$raw' has non-positive-integer points $value")
            val canonical = names.resolve(raw)
            if (canonical == null) unresolved += raw else resolved[canonical] = n.toInt()
        }
        if (unresolved.isNotEmpty()) {
            throw FormatDefinitionError(
                "${file.name}: ${unresolved.size} card name(s) don't resolve against the cards table: " +
                    unresolved.joinToString(", ") { "'$it'" } + ". Fix the spelling, or sync the cards first.",
            )
        }
        return Definition(file, spec, resolved)
    }

    /** Every definition in [dir], all checked first; null when the directory is missing (a broken checkout retires nothing). */
    fun loadAll(dir: File, names: CardNames): List<Definition>? {
        if (!dir.isDirectory) return null
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }.map { load(it, names) }
    }

    /** Writes [definitions] and removes the formats whose file is gone; returns (points written, (removed format, decks naming it)). */
    fun write(conn: Connection, definitions: List<Definition>): Pair<Int, List<Pair<String, Int>>> {
        val keep = definitions.map { it.format }.toSet()
        val removed = mutableListOf<Pair<String, Int>>()
        val retired = conn.createStatement().use { st ->
            st.executeQuery("SELECT format, aliases FROM custom_formats").use { rs -> buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) } }
        }.filter { it.first !in keep }.sortedBy { it.first }
        for ((format, aliases) in retired) {
            // A deck may have been given any alias, not the key.
            val names = listOf(format) + (runCatching { Json.parseToJsonElement(aliases ?: "[]") as JsonArray }.getOrNull()?.mapNotNull { it.asText() }.orEmpty())
            val decks = conn.prepareStatement("SELECT COUNT(*) FROM decks WHERE LOWER(TRIM(format)) IN (${names.joinToString(", ") { "?" }})").use { st ->
                names.forEachIndexed { i, n -> st.setString(i + 1, n.lowercase()) }
                st.executeQuery().use { it.next(); it.getInt(1) }
            }
            removed += format to decks
            conn.prepareStatement("DELETE FROM custom_format_points WHERE format = ?").use { it.bind(format); it.executeUpdate() }
            conn.prepareStatement("DELETE FROM custom_formats WHERE format = ?").use { it.bind(format); it.executeUpdate() }
        }
        var total = 0
        for (d in definitions) {
            val s = d.spec
            conn.prepareStatement(
                """
                INSERT INTO custom_formats (format, name, aliases, derives_from, points_budget, singleton, source_url, list_current_as_of, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(format) DO UPDATE SET name = excluded.name, aliases = excluded.aliases, derives_from = excluded.derives_from,
                    points_budget = excluded.points_budget, singleton = excluded.singleton, source_url = excluded.source_url,
                    list_current_as_of = excluded.list_current_as_of, updated_at = excluded.updated_at
                """.trimIndent(),
            ).use { st ->
                val aliases = (s["aliases"] as? JsonArray)?.takeIf { it.isNotEmpty() } ?: JsonArray(emptyList())
                st.bind(d.format, s.py("name"), PyJson.dumps(aliases), s.py("derives_from"), s.py("points_budget"), truthy(s.py("singleton")),
                    s.py("source_url"), s.py("list_current_as_of"), nowStamp())
                st.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM custom_format_points WHERE format = ?").use { it.bind(d.format); it.executeUpdate() }
            conn.prepareStatement("INSERT INTO custom_format_points (format, card_name, points) VALUES (?, ?, ?)").use { st ->
                d.points.forEach { (name, pts) -> st.bind(d.format, name, pts); st.addBatch() }
                st.executeBatch()
            }
            total += d.points.size
        }
        return total to removed
    }
}
