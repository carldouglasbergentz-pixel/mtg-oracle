package mtgoracle.core.deck

/**
 * One card line of a pasted list. [section] is `main`, `sideboard`,
 * `commander` or `maybeboard` (the deck's considering list); the printing is
 * there only when the line names one (`1 Sol Ring (C18) 263`).
 */
data class ParsedRow(
    val name: String,
    val quantity: Int,
    val section: String,
    val setCode: String? = null,
    val collectorNumber: String? = null,
)

/**
 * Plain-text deck lists as Moxfield, Archidekt, Arena, MTGO, mtgtop8 and
 * card shops write them: a port of mtg_oracle/deck_parser.py, line for line,
 * held to it by DeckParserParityTest over every reference list in
 * the test fixture's decklists. Every shape below came out of a real export that
 * lost cards before it was handled; the comments in deck_parser.py say which.
 */
object DeckParser {
    private val SECTION_ALIASES = mapOf(
        "deck" to "main", "maindeck" to "main", "main deck" to "main", "main" to "main", "mainboard" to "main",
        "sideboard" to "sideboard", "sb" to "sideboard", "sb:" to "sideboard", "side board" to "sideboard", "companion" to "sideboard",
        "commander" to "commander", "commanders" to "commander",
        "maybeboard" to "maybeboard", "maybe" to "maybeboard", "maybe board" to "maybeboard", "considering" to "maybeboard",
        // Token lists are not deck contents.
        "tokens" to "tokens", "token" to "tokens",
    )
    private val HEADER_COUNT = Regex("(?U)\\s*\\(\\d+\\)\\s*$")
    private val INLINE_SB = Regex("(?U)^SB:\\s*(.+)$", RegexOption.IGNORE_CASE)
    // Archidekt's `[Ramp] ^Have,#37d67a^` after the set block; no card name has `[` or `^`.
    private val CATEGORY_TAIL = Regex("(?U)\\s*\\[[^\\]]*\\](?:\\s*\\^[^^]*\\^)?\\s*$")
    // ` (SET) number`, the set code all upper or all lower (Title Case is part of a name: `Unearth (Theme)`).
    // A number is as Scryfall writes it, which export writes back: `280†`, `130★s`, `DDN-64`, `Φ1` (every character the printings hold).
    private val SET_TAIL = Regex("(?U)\\s*\\((?<set>[A-Z0-9]{2,6}|[a-z0-9]{2,6})\\)(?:\\s*(?<number>[A-Za-z0-9Φ][A-Za-z0-9_†Φ★-]*))?\\s*(?<star>[★*])?\\s*$")
    private val FOIL_TAIL = Regex("(?U)\\s*\\*(?:[A-Za-z]|foil|etched|showcase|borderless)\\*\\s*$", RegexOption.IGNORE_CASE)
    // mtgtop8's `40 LANDS (42)`: a grouping inside the main deck, shouted, from a closed vocabulary.
    private val TYPE_GROUP = Regex("(?U)^\\d+\\s+(?<label>[A-Za-z .&]+?)\\s*(?:\\(\\d+\\))?\\s*:?\\s*$")
    private val TYPE_GROUP_WORDS = setOf(
        "lands", "creatures", "instants", "sorceries", "instants and sorc.", "instants and sorceries",
        "other spells", "spells", "artifacts", "enchantments", "planeswalkers", "battles",
    )
    private val GROUP_CONNECTIVES = setOf("and", "or")
    private val LEAD_COUNT = Regex("(?U)^(\\d+)[xX]?\\s+(.+)$")
    // The one card whose name reads as a count and a name (every card name checked): uncounted, it was 1996 × World Champion.
    private val NAMES_LIKE_COUNTS = setOf("1996 world champion")
    private val TRAIL_COUNT =Regex("(?U)^(.+?)\\s+[xX](\\d+)\\s*$")
    /** What Python's str.splitlines() splits on, so a list reads into the same lines. */
    private val LINE_BREAKS = Regex("\r\n|[\n\r\u000B\u000C\u001C\u001D\u001E\u0085\u2028\u2029]")

    /** A count as written; one too big for an Int is still a count, and the quantity check refuses it. */
    private fun count(digits: String): Int = digits.toIntOrNull() ?: Int.MAX_VALUE

    /** Python's str.strip(): Unicode whitespace, U+0085 included. */
    private fun pyStrip(s: String) = s.trim { it.isWhitespace() || it == '\u0085' }

    private fun sectionHeader(line: String): String? {
        val s = HEADER_COUNT.replace(pyStrip(line).lowercase(), "").trimEnd(':').trim()
        if (s.isEmpty() || s.any { it.isDigit() }) return null
        return SECTION_ALIASES[s]
    }

    private fun isTypeGroup(line: String): Boolean {
        val m = TYPE_GROUP.matchEntire(pyStrip(line)) ?: return false
        val label = m.groups["label"]!!.value.trim()
        if (label.lowercase() !in TYPE_GROUP_WORDS) return false
        // Shouted, ignoring connectives: `40 LANDS` is a heading, `40 Lands` the Jumpstart card.
        val words = label.split(Regex("\\s+")).filter { it.isNotEmpty() && it.lowercase() !in GROUP_CONNECTIVES }
        return words.isNotEmpty() && words.all { it == it.uppercase() }
    }

    /** (quantity, name, set code, collector number) of a card line, or null when it is none. */
    private fun parseLine(raw: String): ParsedRow? {
        var line = pyStrip(raw)
        if (line.isEmpty()) return null
        line = CATEGORY_TAIL.replace(line, "")
        line = FOIL_TAIL.replace(line, "")
        var setCode: String? = null
        var number: String? = null
        SET_TAIL.find(line)?.let { tail ->
            val set = tail.groups["set"]!!.value.lowercase()
            // `(15)` is a count some exporters append, never a set code.
            if (!set.all { it.isDigit() }) {
                setCode = set
                number = tail.groups["number"]?.value?.let { n -> if (tail.groups["star"] != null) "$n★" else n }
            }
            line = line.substring(0, tail.range.first)
        }
        line = pyStrip(line)
        if (line.isEmpty()) return null
        if (line.lowercase() in NAMES_LIKE_COUNTS) return ParsedRow(line, 1, "main", setCode, number)
        LEAD_COUNT.matchEntire(line)?.let { m ->
            val name = pyStrip(m.groupValues[2])
            if (name.isNotEmpty()) return ParsedRow(name, count(m.groupValues[1]), "main", setCode, number)
        }
        TRAIL_COUNT.matchEntire(line)?.let { m -> return ParsedRow(pyStrip(m.groupValues[1]), count(m.groupValues[2]), "main", setCode, number) }
        return ParsedRow(line, 1, "main", setCode, number)
    }

    /** Every card line of [text], in order, each with its section. */
    fun parse(text: String): List<ParsedRow> {
        val rows = mutableListOf<ParsedRow>()
        var section = "main"
        // A UTF-8 BOM (PowerShell's Out-File writes one) survives strip() and hid the first card.
        for (raw in text.trimStart('﻿').split(LINE_BREAKS)) {
            var line = pyStrip(raw)
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue
            sectionHeader(line)?.let { section = it; continue }
            // A type-group heading is no section, but it ends a commander block (mtgtop8 writes nothing else to say so).
            if (isTypeGroup(line)) {
                if (section == "commander") section = "main"
                continue
            }
            if (section == "tokens") continue
            var rowSection = section
            INLINE_SB.matchEntire(line)?.let { line = it.groupValues[1]; rowSection = "sideboard" }
            val parsed = parseLine(line) ?: continue
            rows += parsed.copy(section = rowSection)
        }
        return rows
    }
}
