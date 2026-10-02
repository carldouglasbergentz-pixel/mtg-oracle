package mtgoracle.ui.lookup

import mtgoracle.core.lookup.SearchFields
import mtgoracle.core.lookup.SearchVocabulary

/** Every command the app's command line knows, in the order a prefix completes to (`c` is `card`). */
val COMMANDS = listOf(
    "card", "ruling", "combo", "combos", "combo-info", "rule", "search-rules", "search",
    "next", "prev", "page", "correction", "cd", "copy", "help", "clear", "quit",
    // In the deck workspace.
    "add", "remove", "consider", "commander", "undo", "history",
    // Analysis.
    "profile", "compare",
    // Games.
    "results",
    // The data.
    "sync", "prune",
)

/** What a line's first word must be to run as a command; anything else is a search. */
val COMMAND_WORDS: Set<String> = COMMANDS.toSet() + setOf("rulings", "corrections", "?", "exit")

/** One line per command, for the hint under the command line while it is typed. */
val COMMAND_HINTS: Map<String, String> = mapOf(
    "card" to "card <name>: the full profile · card <N>: row N of the last search",
    "ruling" to "ruling <name>: the card's rulings", "rulings" to "rulings <name>: the card's rulings",
    "combo" to "combo <card>: combos with that card · combo add <card>; <card>: your own · combo remove <user-NNN>",
    "combos" to "combos <card>; <card>[; ...]: combos with all of them · combos: the ones the deck holds whole",
    "combo-info" to "combo-info <id|N>: one combo in full",
    "rule" to "rule <number>: the rule and its sub-rules",
    "search-rules" to "search-rules <text>: rules whose text has it",
    "search" to "search <query>: or just type the query — anything that isn't a command is a search",
    "next" to "next: the next page of the last search", "prev" to "prev: the page before",
    "page" to "page <N>: that page of the last search",
    "correction" to "correction [text]: corrections from the feedback loop", "corrections" to "corrections [text]",
    "cd" to "cd <deck>: open it to edit · cd ..: back to the library",
    "copy" to "copy [last|all]: output to the clipboard",
    "help" to "help [search]", "?" to "? [search]: help", "clear" to "clear: empty the output (Ctrl+L)",
    "quit" to "quit: close the app", "exit" to "exit: close the app",
    "add" to "add [--sb] [--force] <card> [N]: into the open deck (or its sideboard)",
    "remove" to "remove [--sb|--considering] <card> [N]: out of the open deck; no N takes every copy",
    "consider" to "consider <card> [N]: onto the open deck's considering list",
    "commander" to "commander [--unset] [--force] <card>: make it (or no longer) the open deck's commander",
    "undo" to "undo: revert the open deck's newest change (undo again redoes it)",
    "history" to "history: the open deck's changes (the History tab)",
    "profile" to "profile [<deck>|<folder>]: what the cards do and when each role is castable",
    "compare" to "compare <deck>|<folder>: this deck head to head, or against every deck in the folder",
    "results" to "results [<deck>]: wins–losses–draws against each opponent, your games and the simulated ones",
    "prune" to "prune [--yes]: the card rows no export writes any more (a dry run without --yes; decks keep theirs)",
    "sync" to "sync [--force] [cards|rules|combos|tags|oracletags|formats|printings ...]: fetch what moved upstream (decks are never touched)",
)

/**
 * Autofill for the command line: the whole line it would become, or null.
 * Which list completes follows the line, as the TUI's suggester did
 * (mtg_oracle/tui/suggester.py), plus the search language:
 *
 *  - no space yet: a command, else a card name (a one-word search);
 *  - `card` / `ruling` / `combo` / `correction` + text: a card name;
 *  - `combos a; b; <text>`: a card name for the last segment only;
 *  - `rule` + text: a rule number; `cd`: a deck; `help`: a topic;
 *  - a search (`search ...`, or any line not starting with a command):
 *    after `field:` the field's values (`t:` types, `kw:` keywords,
 *    `otag:` functions, `f:` formats, `is:`, `order:` ...), and free
 *    words complete to a card name.
 *
 * Prefix matches, ignoring case; the first in list order wins (the
 * vocabulary is most-used first), and what is typed in full is never
 * offered again.
 */
class Suggester(
    private val cardNames: List<String>,
    private val ruleNumbers: List<String>,
    private val deckNames: () -> List<String> = { emptyList() },
    private val helpTopics: List<String> = emptyList(),
    private val vocabulary: SearchVocabulary = SearchVocabulary(),
) {
    private val cardNamesLower = cardNames.map { it.lowercase() }

    fun suggest(value: String): String? {
        if (value.isEmpty()) return null
        if (' ' !in value) {
            COMMANDS.firstOrNull { it.startsWith(value.lowercase()) }?.let { return it.takeIf { c -> c != value } }
            return query("", value)
        }
        val command = value.substringBefore(' ')
        val rest = value.substringAfter(' ')
        return when (command.lowercase()) {
            "card", "ruling", "rulings", "combo", "correction", "corrections", "add", "remove", "consider", "commander" ->
                complete(command, rest, cardNames, cardNamesLower)
            "combos" -> combos(command, rest)
            "rule" -> complete(command, rest, ruleNumbers)
            "cd", "profile", "compare", "results" -> complete(command, rest, deckNames())
            "help", "?" -> complete(command, rest, helpTopics)
            "search" -> query("$command ", rest)
            in COMMAND_WORDS -> null
            else -> query("", value)
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

    /** A search: the token being typed completes, the rest stays as typed. [prefix] is `search ` or empty. */
    private fun query(prefix: String, query: String): String? {
        val tokens = tokenStarts(query)
        if (tokens.isEmpty() || query.last().isWhitespace()) return null
        val start = tokens.last()
        val token = query.substring(start)
        val lead = token.takeWhile { it == '-' || it == '(' }
        val body = token.drop(lead.length)
        val opAt = body.indexOfFirst { it in ":=<>!" }
        if (opAt > 0) {
            val field = body.substring(0, opAt)
            val op = body.drop(opAt).takeWhile { it in ":=<>!" }
            val raw = body.drop(opAt + op.length)
            val quoted = raw.startsWith('"')
            val typed = raw.removePrefix("\"")
            if (typed.isEmpty() || typed.endsWith('"') && quoted) return null
            val lower = field.lowercase()
            val canonical = if (lower == "order" || lower == "sort") "order" else SearchFields.ALIAS[lower] ?: return null
            val candidates = if (canonical == "n") cardNames else vocabulary[canonical]
            // Tagger tags are stored with spaces and written with hyphens (`mana-rock`), as Scryfall does.
            val tag = canonical == "otag"
            val want = typed.lowercase()
            val wantSpaced = want.replace('-', ' ')
            val match = candidates.firstOrNull { c ->
                val cl = c.lowercase()
                (cl.startsWith(want) || tag && cl.startsWith(wantSpaced)) && cl != want && cl != wantSpaced
            } ?: return null
            // Otherwise a value with a space has to be quoted (then it is offered as a Tab hint, not a ghost).
            val written = when {
                quoted -> "\"$match\""
                tag -> match.replace(' ', '-')
                ' ' in match -> "\"$match\""
                else -> match
            }
            return prefix + query.substring(0, start) + lead + field + op + written
        }
        if (opAt == 0) return null
        // Free words: the bare words at the end make a phrase, and a card name can finish it.
        val phraseStart = tokens.reversed().takeWhile { at -> isBare(query.substring(at).substringBefore(' ')) }.lastOrNull() ?: return null
        val phrase = query.substring(phraseStart)
        if (phrase.length < 2 || phrase.startsWith('-') || phrase.startsWith('"')) return null
        val at = cardNamesLower.indexOfFirst { it.startsWith(phrase.lowercase()) && it != phrase.lowercase() }.takeIf { it >= 0 } ?: return null
        val name = cardNames[at]
        // Only a name that reads the same as free text: a quote, a bracket or an operator would change the query.
        if (name.any { it in "\"():=<>!" }) return null
        return prefix + query.substring(0, phraseStart) + name
    }

    private fun isBare(token: String): Boolean =
        token.isNotEmpty() && token.none { it in ":=<>!\"()" } && token.lowercase() !in setOf("or", "not", "and") && !token.startsWith('-')

    /** Where each whitespace-separated token starts, a quoted string counting as one. */
    private fun tokenStarts(s: String): List<Int> = buildList {
        var i = 0
        var quoted = false
        var inToken = false
        while (i < s.length) {
            val ch = s[i]
            if (ch == '"') quoted = !quoted
            if (ch.isWhitespace() && !quoted) inToken = false
            else if (!inToken) { add(i); inToken = true }
            i++
        }
    }
}
