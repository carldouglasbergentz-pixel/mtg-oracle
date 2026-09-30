package mtgoracle.ui.theme

import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps the grid a grid.
 *
 * A character the house font lacks is drawn from a fallback font with a
 * different advance width, and every column after it on that line shifts
 * (the spike's `▶` did exactly that). So every string drawn on the grid goes
 * through [safe]: characters the font has pass unchanged (box drawing, `û`,
 * `—`), and the rest become a one-character stand-in, or `?` — never
 * longer, so a width computed before guarding still holds.
 */
object GlyphGuard {
    private val known = ConcurrentHashMap<Int, Boolean>()

    private val STAND_INS = mapOf(
        '▶'.code to ">", '►'.code to ">", '◀'.code to "<", '★'.code to "*", '☆'.code to "*",
        '•'.code to "·", '→'.code to ">", '←'.code to "<", '✓'.code to "v", '✗'.code to "x",
        '↑'.code to "^", '↓'.code to "v", '▲'.code to "^", '▼'.code to "v", '◄'.code to "<", '▸'.code to ">", '▾'.code to "v",
        '“'.code to "\"", '”'.code to "\"", '‘'.code to "'", '’'.code to "'",
    )

    /** Whether the house font has its own glyph for [codePoint]. */
    fun hasGlyph(codePoint: Int): Boolean = known.getOrPut(codePoint) {
        codePoint < 0x20 || HouseFont.typeface.getUTF32Glyph(codePoint) != 0.toShort()
    }

    fun safe(text: String): String {
        if (text.all { it.code < 0x7f }) return text // ASCII: always there
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            when {
                hasGlyph(cp) -> out.appendCodePoint(cp)
                else -> out.append(STAND_INS[cp]?.takeIf { s -> s.all { hasGlyph(it.code) } } ?: "?")
            }
            i += Character.charCount(cp)
        }
        return out.toString()
    }
}
