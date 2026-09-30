package mtgoracle.ui.lookup

/** Every command the app's command line knows, in the order a prefix completes to (`c` is `card`). */
val COMMANDS = listOf(
    "card", "ruling", "combo", "combos", "combo-info", "rule", "search-rules", "search",
    "next", "prev", "page", "correction", "cd", "copy", "help", "clear", "quit",
)

/**
 * Autofill for the command line: the whole line it would become, or null.
 * Which list it completes from follows the command, as the TUI's suggester
 * did (mtg_oracle/tui/suggester.py):
 *
 *  - no space yet: the command itself;
 *  - `card` / `ruling` / `combo` / `correction` + text: a card name;
 *  - `combos a; b; <text>`: a card name for the last segment only;
 *  - `rule` + text: a rule number;
 *  - `cd` + text: a deck name; `help` + text: a topic.
 *
 * Prefix matches, ignoring case; the first in list order wins, and what is
 * already typed in full is never offered again.
 */
class Suggester(
    private val cardNames: List<String>,
    private val ruleNumbers: List<String>,
    private val deckNames: () -> List<String> = { emptyList() },
    private val helpTopics: List<String> = emptyList(),
) {
    private val cardNamesLower = cardNames.map { it.lowercase() }

    fun suggest(value: String): String? {
        if (value.isEmpty()) return null
        if (' ' !in value) return COMMANDS.firstOrNull { it.startsWith(value.lowercase()) }?.takeIf { it != value }
        val command = value.substringBefore(' ')
        val rest = value.substringAfter(' ')
        return when (command.lowercase()) {
            "card", "ruling", "rulings", "combo", "correction", "corrections" -> complete(command, rest, cardNames, cardNamesLower)
            "combos" -> combos(command, rest)
            "rule" -> complete(command, rest, ruleNumbers)
            "cd" -> complete(command, rest, deckNames())
            "help" -> complete(command, rest, helpTopics)
            else -> null
        }
    }

    private fun complete(command: String, rest: String, names: List<String>, lower: List<String> = names.map { it.lowercase() }): String? {
        if (rest.isEmpty()) return null
        val want = rest.lowercase()
        val at = lower.indexOfFirst { it.startsWith(want) }.takeIf { it >= 0 } ?: return null
        return "$command ${names[at]}".takeIf { !it.equals("$command $rest", ignoreCase = true) }
    }

    /** Only the text after the last `;` completes; what came before is kept as typed. */
    private fun combos(command: String, rest: String): String? {
        if (';' !in rest) return complete(command, rest, cardNames, cardNamesLower)
        val head = rest.substringBeforeLast(';')
        val tail = rest.substringAfterLast(';').trimStart()
        if (tail.isEmpty()) return null
        val at = cardNamesLower.indexOfFirst { it.startsWith(tail.lowercase()) }.takeIf { it >= 0 } ?: return null
        return "$command $head; ${cardNames[at]}".takeIf { !it.equals("$command $rest", ignoreCase = true) }
    }
}
