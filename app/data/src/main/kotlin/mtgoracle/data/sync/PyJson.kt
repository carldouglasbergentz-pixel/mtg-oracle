package mtgoracle.data.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * JSON as Python's `json.dumps(value, ensure_ascii=False)` writes it: `", "`
 * and `": "` between items, keys in their order, non-ASCII as itself, and
 * only `"`, `\` and control characters escaped. `cards.card_faces` and
 * `custom_formats.aliases` were stored that way, and a sync must not rewrite
 * every row in another spelling of the same JSON.
 */
internal object PyJson {
    fun dumps(element: JsonElement): String = StringBuilder().also { write(element, it) }.toString()

    private fun write(element: JsonElement, out: StringBuilder) {
        when (element) {
            is JsonNull -> out.append("null")
            is JsonObject -> {
                out.append('{')
                element.entries.forEachIndexed { i, (key, value) ->
                    if (i > 0) out.append(", ")
                    string(key, out)
                    out.append(": ")
                    write(value, out)
                }
                out.append('}')
            }
            is JsonArray -> {
                out.append('[')
                element.forEachIndexed { i, value ->
                    if (i > 0) out.append(", ")
                    write(value, out)
                }
                out.append(']')
            }
            is JsonPrimitive -> if (element.isString) string(element.content, out) else out.append(number(element.content))
        }
    }

    /** A number or a literal as Python prints what it parsed: `1.0` stays `1.0`, `1e5` becomes `100000.0`. */
    private fun number(literal: String): String = when {
        literal == "true" || literal == "false" -> literal
        literal.any { it == '.' || it == 'e' || it == 'E' } -> pyFloat(literal.toDouble())
        else -> literal
    }

    /** Python's `repr(float)`: the shortest text that reads back to the same double. */
    private fun pyFloat(x: Double): String {
        if (x.isNaN()) return "NaN"
        if (x.isInfinite()) return if (x > 0) "Infinity" else "-Infinity"
        val shortest = java.math.BigDecimal(x.toString()).stripTrailingZeros()
        val exponent = shortest.precision() - shortest.scale() - 1
        return if (exponent < -4 || exponent >= 16) {
            val digits = shortest.unscaledValue().abs().toString()
            val mantissa = if (digits.length > 1) digits[0] + "." + digits.substring(1) else digits
            (if (x < 0) "-" else "") + mantissa + "e" + (if (exponent < 0) "-" else "+") + "%02d".format(kotlin.math.abs(exponent))
        } else {
            val plain = shortest.toPlainString()
            if ('.' in plain) plain else "$plain.0"
        }
    }

    private fun string(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        out.append('"')
    }
}
