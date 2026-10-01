package mtgoracle.data.sync

import java.sql.Connection

/** The Comprehensive Rules text into `rules` (sync_rules.parse_rules_text). */
internal object RulesIngest {
    /** `https://media.wizards.com/<year>/downloads/MagicCompRules YYYYMMDD.txt`, in HTML or Nuxt-escaped JSON. */
    private val CR_URL = pyRegex(
        """https?[:\\u003A][/\\u002F]+media\.wizards\.com[/\\u002F]+\d{4}[/\\u002F]+downloads[/\\u002F]+MagicCompRules[ %20]+(\d{8})\.txt""",
        ignoreCase = true,
    )

    /**
     * Most specific first. Every rule number starts with three digits, which
     * keeps the glossary's numbered senses out; the optional period and the
     * two-letter suffix are upstream's own (`119.1d.`, `606.5 If`, `704.5aa`).
     */
    private val RULE_PATTERNS = listOf(
        pyRegex("""^(\d{3}\.\d+[a-z]{1,2})\.?\s+(.*)"""),
        pyRegex("""^(\d{3}\.\d+)\.?\s+(.*)"""),
        pyRegex("""^(\d{3})\.\s+(.*)"""),
    )
    private val SECTION = pyRegex("""^(\d)\.\s+([A-Z][A-Za-z ,'\-]+?)\s*$""")
    private val SUBRULE = pyRegex("""^(\d+\.\d+)[a-z]{1,2}$""")
    private val RULE = pyRegex("""^\d+\.\d+$""")
    private const val EXAMPLE = "Example:"

    data class Rule(val number: String, val parent: String?, val section: String?, val text: String)

    /** (download URL, release date) of the newest rules text the page links, or null when it links none. */
    fun discover(html: String): Pair<String, String>? =
        CR_URL.findAll(html).map { m ->
            m.value.replace("\\u002F", "/").replace("\\u003A", ":").replace(" ", "%20") to m.groupValues[1]
        }.sortedByDescending { it.second }.firstOrNull()

    /** 704.5aa belongs to 704.5, 704.5 to 704, 704 to nothing. */
    private fun parent(number: String): String? {
        SUBRULE.matchEntire(number)?.let { return it.groupValues[1] }
        return if (RULE.matchEntire(number) != null) number.substringBefore('.') else null
    }

    /**
     * The rules in order. The table of contents matches the same patterns and
     * is overwritten later by the real rule (same key). `Example:` lines and
     * indented paragraphs join the rule above, only while the run of rule
     * lines is unbroken: a glossary line ends it.
     */
    fun parse(text: String): List<Rule> {
        val rules = mutableListOf<Rule>()
        var section: String? = null
        var attachTo: Int? = null
        for (raw in pySplitLines(text)) {
            val line = raw.pyStrip()
            if (line.isEmpty()) continue
            val heading = SECTION.find(line)
            if (heading != null) {
                section = heading.groupValues[2].pyStrip()
                attachTo = null
                continue
            }
            val rule = RULE_PATTERNS.firstNotNullOfOrNull { it.find(line) }
            if (rule != null) {
                val number = rule.groupValues[1]
                rules += Rule(number, parent(number), section, rule.groupValues[2].pyStrip())
                attachTo = rules.lastIndex
                continue
            }
            val continues = line.startsWith(EXAMPLE) || (raw.isNotEmpty() && raw[0].isPyWhitespace())
            val at = attachTo
            if (continues && at != null) rules[at] = rules[at].copy(text = rules[at].text + "\n" + line)
            else attachTo = null
        }
        return rules
    }

    /** Replaces `rules`; returns how many landed (the table of contents collapses into the rules). */
    fun write(conn: Connection, rules: List<Rule>): Int {
        conn.createStatement().use { it.executeUpdate("DELETE FROM rules") }
        conn.prepareStatement("INSERT OR REPLACE INTO rules (rule_number, parent_rule, section_title, text) VALUES (?, ?, ?, ?)").use { st ->
            rules.forEach { st.bind(it.number, it.parent, it.section, it.text); st.addBatch() }
            st.executeBatch()
        }
        return conn.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM rules").use { it.next(); it.getInt(1) } }
    }
}

/** Python's `str.isspace()` for one character: Unicode whitespace, and the separators 0x1C-0x1F. */
internal fun Char.isPyWhitespace(): Boolean = isWhitespace() || this in '\u001C'..'\u001F' || this == '\u0085'

/** Python's `str.strip()`. */
internal fun String.pyStrip(): String = trim { it.isPyWhitespace() }

/** Python's `str.splitlines()`: `\n`, `\r\n`, `\r`, and the other line boundaries it knows. */
internal fun pySplitLines(text: String): List<String> {
    val out = mutableListOf<String>()
    val line = StringBuilder()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when (c) {
            '\r' -> { out += line.toString(); line.setLength(0); if (i + 1 < text.length && text[i + 1] == '\n') i++ }
            '\n', '\u000B', '\u000C', '\u001C', '\u001D', '\u001E', '\u0085', ' ', ' ' -> { out += line.toString(); line.setLength(0) }
            else -> line.append(c)
        }
        i++
    }
    if (line.isNotEmpty()) out += line.toString()
    return out
}
