package mtgoracle.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.sql.Connection
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.regex.Pattern
import java.util.zip.GZIPInputStream

/*
 * What every source's ingest shares: JSON Lines, Python's `dict.get`, and
 * the `sync_state` row each source keeps (scripts/sync_*.py).
 */

/** Python's `re` semantics for str patterns: `\w`, `\b`, `\s` and case are Unicode-aware. */
internal fun pyRegex(pattern: String, ignoreCase: Boolean = false): Regex =
    Pattern.compile(pattern, Pattern.UNICODE_CHARACTER_CLASS or (if (ignoreCase) Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE else 0)).toRegex()

/** One object per line of a gzipped JSON Lines export, tolerating a stray `[` `]` wrapper and trailing commas. */
internal fun jsonLines(file: File): Sequence<JsonObject> = sequence {
    GZIPInputStream(file.inputStream().buffered(1 shl 16)).bufferedReader(Charsets.UTF_8).use { reader ->
        for (raw in reader.lineSequence()) {
            val line = raw.trim().trimEnd(',')
            if (line.isEmpty() || line == "[" || line == "]") continue
            yield(Json.parseToJsonElement(line) as JsonObject)
        }
    }
}

/**
 * `d.get(key, default)` as Python reads it: a missing key is [default], a
 * JSON null is null (Python's None), a string is its text, anything else
 * stays an element. The difference between missing and null is stored.
 */
internal fun JsonObject.py(key: String, default: Any? = null): Any? = when (val v = this[key]) {
    null -> default
    is JsonNull -> null
    is JsonPrimitive -> if (v.isString) v.content else v
    else -> v
}

/** `d.get(key) or ""`: falsy (missing, null, empty) is the empty string. */
internal fun JsonObject.text(key: String): String = (py(key) as? String).orEmpty()

internal fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

/** Python's truthiness, for the values these exports hold. */
internal fun truthy(v: Any?): Boolean = when (v) {
    null -> false
    is String -> v.isNotEmpty()
    is JsonPrimitive -> v.content.let { it != "0" && it != "0.0" && it != "false" && it.isNotEmpty() }
    is JsonArray -> v.isNotEmpty()
    is JsonObject -> v.isNotEmpty()
    else -> true
}

/** The text of a primitive element (a number, a string). */
internal fun JsonElement.asText(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

/** UTC now as Python's sync scripts write it: `2026-09-30T12:00:00Z`. */
internal fun nowStamp(): String = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()

internal object SyncState {
    fun get(conn: Connection, source: String): String? =
        conn.prepareStatement("SELECT updated_at FROM sync_state WHERE source = ?").use { st ->
            st.setString(1, source)
            st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    fun set(conn: Connection, source: String, updatedAt: String, rowCount: Int) {
        conn.prepareStatement(
            """
            INSERT INTO sync_state (source, updated_at, last_sync, row_count) VALUES (?, ?, ?, ?)
            ON CONFLICT(source) DO UPDATE SET updated_at = excluded.updated_at, last_sync = excluded.last_sync, row_count = excluded.row_count
            """.trimIndent(),
        ).use { st ->
            st.setString(1, source); st.setString(2, updatedAt); st.setString(3, nowStamp()); st.setInt(4, rowCount)
            st.executeUpdate()
        }
    }
}

/** Binds Python values: null, String, Int, Long, Boolean (as 0/1), a JSON number. */
internal fun java.sql.PreparedStatement.bind(vararg values: Any?) {
    values.forEachIndexed { i, v ->
        val at = i + 1
        when (v) {
            null -> setObject(at, null)
            is String -> setString(at, v)
            is Int -> setInt(at, v)
            is Long -> setLong(at, v)
            is Boolean -> setInt(at, if (v) 1 else 0)
            is JsonPrimitive -> v.content.toLongOrNull()?.let { setLong(at, it) } ?: v.content.toDoubleOrNull()?.let { setDouble(at, it) } ?: setString(at, v.content)
            else -> setString(at, v.toString())
        }
    }
}
