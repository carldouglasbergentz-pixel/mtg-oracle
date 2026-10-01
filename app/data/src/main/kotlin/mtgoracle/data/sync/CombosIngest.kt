package mtgoracle.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.Reader
import java.sql.Connection

/**
 * Commander Spellbook's variants into `combos` and its four child tables
 * (sync_combos.normalize + sync). The export is ~600 MB, so it is read one
 * variant at a time ([Variants]) rather than parsed whole as Python does.
 */
internal object CombosIngest {
    data class Combo(
        val id: String, val name: String, val colorIdentity: String, val description: String,
        val cards: List<Pair<String, Any?>>, val results: List<String>, val prerequisites: List<String>, val steps: List<String>,
    )

    /** Non-blank lines, stripped. */
    private fun lines(text: String): List<String> = text.split("\n").map { it.pyStrip() }.filter { it.isNotEmpty() }

    /** One variant, as normalize() reads both the current schema and the older one. */
    fun normalize(v: JsonObject): Combo {
        val uses = (v["uses"] ?: v["cards"]) as? JsonArray ?: JsonArray(emptyList())
        val cards = uses.mapNotNull { entry ->
            val e = entry as? JsonObject ?: return@mapNotNull null
            val name = (e.obj("card")?.py("name") as? String)?.takeIf { it.isNotEmpty() } ?: (e.py("name") as? String)
            if (name.isNullOrEmpty()) null else name to e.py("quantity", 1)
        }
        val produces = (v["produces"] ?: v["results"]) as? JsonArray ?: JsonArray(emptyList())
        val results = produces.mapNotNull { f ->
            val o = f as? JsonObject ?: return@mapNotNull null
            ((o.obj("feature")?.py("name") as? String)?.takeIf { it.isNotEmpty() } ?: (o.py("name") as? String))?.takeIf { it.isNotEmpty() }
        }
        val prerequisites = v.text("other_prerequisites").ifEmpty { v.text("prerequisites") }
        val id = when (val raw = v["id"]) {
            null -> ""
            is JsonPrimitive -> if (raw.isString) raw.content else raw.content.takeIf { it != "null" } ?: "None"
            else -> raw.toString()
        }
        return Combo(
            id = id, name = v.text("name"),
            colorIdentity = v.text("identity").ifEmpty { v.text("color_identity") },
            description = v.text("notes"),
            cards = cards, results = results, prerequisites = lines(prerequisites), steps = lines(v.text("description")),
        )
    }

    /** Replaces every combo table with [variants]; returns how many combos went in. */
    fun write(conn: Connection, variants: Sequence<JsonObject>): Int {
        conn.createStatement().use { st ->
            listOf("combo_cards", "combo_results", "combo_prerequisites", "combo_steps", "combos").forEach { st.executeUpdate("DELETE FROM $it") }
        }
        val combo = conn.prepareStatement("INSERT OR REPLACE INTO combos (id, name, color_identity, description) VALUES (?, ?, ?, ?)")
        val card = conn.prepareStatement("INSERT OR IGNORE INTO combo_cards (combo_id, card_name, quantity) VALUES (?, ?, ?)")
        val result = conn.prepareStatement("INSERT INTO combo_results (combo_id, text) VALUES (?, ?)")
        val prereq = conn.prepareStatement("INSERT INTO combo_prerequisites (combo_id, text) VALUES (?, ?)")
        val step = conn.prepareStatement("INSERT INTO combo_steps (combo_id, step_order, text) VALUES (?, ?, ?)")
        val all = listOf(combo, card, result, prereq, step)
        var count = 0
        try {
            for (v in variants) {
                val c = normalize(v)
                if (c.id.isEmpty()) continue
                // Each table's rows in the order Python inserts them, so their ids and their duplicates resolve alike.
                combo.bind(c.id, c.name, c.colorIdentity, c.description); combo.executeUpdate()
                c.cards.forEach { (name, qty) -> card.bind(c.id, name, qty); card.executeUpdate() }
                c.results.forEach { result.bind(c.id, it); result.executeUpdate() }
                c.prerequisites.forEach { prereq.bind(c.id, it); prereq.executeUpdate() }
                c.steps.forEachIndexed { i, s -> step.bind(c.id, i, s); step.executeUpdate() }
                count++
            }
        } finally {
            all.forEach { it.close() }
        }
        return count
    }
}

/**
 * The variants of a Spellbook export, one at a time: the `variants` array of
 * the `{"timestamp", "version", "variants": [...], ...}` envelope, or a bare
 * top-level array. Each element is cut out by matching brackets (strings and
 * escapes respected) and parsed alone, so memory holds one variant, not 600 MB.
 */
internal object Variants {
    fun read(file: File): Sequence<JsonObject> = sequence {
        file.bufferedReader(Charsets.UTF_8, 1 shl 16).use { reader ->
            val r = Scanner(reader)
            r.skipWhitespace()
            when (r.peek()) {
                '[' -> { r.next(); yieldAll(elements(r)) }
                '{' -> {
                    r.next()
                    while (true) {
                        r.skipWhitespace()
                        if (r.peek() == '}') break
                        val key = r.string()
                        r.skipWhitespace(); r.expect(':'); r.skipWhitespace()
                        if (key == "variants") { r.expect('['); yieldAll(elements(r)); break }
                        r.skipValue()
                        r.skipWhitespace()
                        if (r.peek() == ',') r.next()
                    }
                }
                else -> error("unexpected Spellbook export: it starts with '${r.peek()}'")
            }
        }
    }

    private fun elements(r: Scanner): Sequence<JsonObject> = sequence {
        while (true) {
            r.skipWhitespace()
            when (r.peek()) {
                ']' -> { r.next(); return@sequence }
                ',' -> r.next()
                else -> {
                    val text = r.captureValue()
                    yield(Json.parseToJsonElement(text) as JsonObject)
                }
            }
        }
    }

    /** A character reader with one character of lookahead, enough to walk JSON's brackets and strings. */
    private class Scanner(private val reader: Reader) {
        private var ahead = -2

        fun peek(): Char {
            if (ahead == -2) ahead = reader.read()
            if (ahead == -1) error("the Spellbook export ended early")
            return ahead.toChar()
        }

        fun next(): Char = peek().also { ahead = -2 }

        fun expect(c: Char) { val got = next(); check(got == c) { "expected '$c' in the Spellbook export, got '$got'" } }

        fun skipWhitespace() { while (peek().isWhitespace()) next() }

        /** A JSON string's text, its escapes kept raw (only keys are read this way, and they have none). */
        fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                val c = next()
                if (c == '"') return out.toString()
                if (c == '\\') { out.append(c); out.append(next()) } else out.append(c)
            }
        }

        fun skipValue() { captureValue(StringBuilder(), keep = false) }

        fun captureValue(): String = StringBuilder().also { captureValue(it, keep = true) }.toString()

        /** One value (object, array, string or literal) appended to [out]. */
        private fun captureValue(out: StringBuilder, keep: Boolean) {
            var depth = 0
            var inString = false
            while (true) {
                val c = peek()
                if (inString) {
                    next(); if (keep) out.append(c)
                    if (c == '\\') { val e = next(); if (keep) out.append(e) }
                    else if (c == '"') { inString = false; if (depth == 0) return }
                    continue
                }
                when (c) {
                    '"' -> { inString = true; next(); if (keep) out.append(c) }
                    '{', '[' -> { depth++; next(); if (keep) out.append(c) }
                    '}', ']' -> {
                        if (depth == 0) return // the end of the container this value sits in
                        depth--; next(); if (keep) out.append(c)
                        if (depth == 0) return
                    }
                    ',' -> if (depth == 0) return else { next(); if (keep) out.append(c) }
                    else -> { next(); if (keep) out.append(c) }
                }
            }
        }
    }
}
