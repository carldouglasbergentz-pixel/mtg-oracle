package mtgoracle.app

import mtgoracle.core.deck.DeckSummary
import mtgoracle.core.lookup.ComboSummary
import mtgoracle.core.lookup.DeckScope
import mtgoracle.core.lookup.SearchError
import mtgoracle.core.lookup.SearchLanguage
import mtgoracle.core.lookup.SearchPage
import mtgoracle.core.lookup.SearchQuery
import mtgoracle.data.Lookup
import mtgoracle.forge.Log
import mtgoracle.ui.kit.CardFace
import mtgoracle.ui.lookup.HELP_TEXT
import mtgoracle.ui.lookup.HELP_TOPICS
import mtgoracle.ui.lookup.LookupUi
import mtgoracle.ui.lookup.OutputLink
import mtgoracle.ui.lookup.OutputLog
import mtgoracle.ui.lookup.Rendering
import mtgoracle.ui.lookup.Suggester
import mtgoracle.ui.lookup.Tone
import mtgoracle.ui.lookup.message
import mtgoracle.ui.lookup.preformatted
import mtgoracle.ui.lookup.renderCard
import mtgoracle.ui.lookup.renderCombo
import mtgoracle.ui.lookup.renderComboList
import mtgoracle.ui.lookup.renderCorrections
import mtgoracle.ui.lookup.renderDeckFilterNotice
import mtgoracle.ui.lookup.renderRule
import mtgoracle.ui.lookup.renderRulesSearch
import mtgoracle.ui.lookup.renderRulings
import mtgoracle.ui.lookup.renderSearch
import java.sql.SQLException

/**
 * The command line's commands: argument syntax, which lookup answers, and
 * what is remembered between commands (the last search page, the last combo
 * list, the deck `cd` entered). The TUI's lookup handlers (tui/app.py), with
 * the same messages, so the two interfaces read alike while both exist.
 */
class LookupCommands(
    private val lookup: Lookup,
    private val decks: () -> List<DeckSummary>,
    faceOf: (String) -> CardFace?,
    /** `cd <deck>` also selects it in the library's list. */
    private val onEnterDeck: (Int) -> Unit = {},
    private val copyToClipboard: (String) -> Unit = {},
    private val onQuit: () -> Unit = {},
) {
    val output = OutputLog()
    private val suggester = Suggester(lookup.names.sorted, lookup.rules.numbers(), deckNames = { decks().map { it.name } + ".." }, helpTopics = HELP_TOPICS.keys.toList())
    val ui = LookupUi(output, suggester::suggest, ::submit, ::open, faceOf)

    /** The deck `cd` entered: `search` and card profiles follow it. Null at the root. */
    var scope: DeckScope? = null
        private set
    private var lastSearch: SearchPage? = null
    private var lastCombos: List<ComboSummary> = emptyList()

    private fun say(rendering: Rendering) = output.add(rendering)
    private fun say(text: String, tone: Tone = Tone.PLAIN) = output.add(message(text, tone))

    /** A typed line: echoed, then run; anything that fails is one line in the output, never a crash. */
    fun submit(line: String) {
        ui.showOutput = true
        output.echo(line)
        guarded { dispatch(line) }
    }

    /**
     * A click: echoed as the command it stands for, run on the value itself —
     * a card name never goes back through the parser (`;`, `//` and digits
     * would change what it means).
     */
    fun open(link: OutputLink) {
        ui.showOutput = true
        when (link) {
            is OutputLink.Card -> { output.echo("card ${link.name}"); guarded { card(link.name) } }
            is OutputLink.Combo -> { output.echo("combo-info ${link.id}"); guarded { showCombo(link.id) } }
            is OutputLink.Rule -> { output.echo("rule ${link.number}"); guarded { rule(link.number) } }
            is OutputLink.Run -> { output.echo(link.command); guarded { dispatch(link.command) } }
        }
    }

    private fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: SQLException) {
            Log.error("lookup failed", e)
            // A gap in the schema means the database predates this build; the raw message says nothing actionable.
            val hint = if ("no such" in e.message.orEmpty()) " — your database predates this app: run `python scripts/self_heal.py`" else ""
            say("ERR database: ${e.message}$hint", Tone.ERROR)
        } catch (e: Exception) {
            Log.error("lookup failed", e)
            say("ERR ${e::class.simpleName}: ${e.message}", Tone.ERROR)
        }
    }

    private fun dispatch(line: String) {
        val head = line.substringBefore(' ').lowercase()
        val arg = line.substringAfter(' ', "").trim()
        when (head) {
            "help", "?" -> help(arg)
            "card" -> cardOrRow(arg)
            "ruling", "rulings" -> rulings(arg)
            "combo" -> combo(arg)
            "combos" -> combos(arg)
            "combo-info" -> comboInfo(arg)
            "rule" -> if (arg.isEmpty()) say("usage: rule <rule_number>") else rule(arg)
            "search-rules" -> if (arg.isEmpty()) say("usage: search-rules <text>") else say(renderRulesSearch(arg, lookup.rules.search(arg, limit = 25)))
            "search" -> search(arg)
            "next" -> turnPage { if (it.hasNext) it.page + 1 else { say("(already on last page ${it.lastPage})"); null } }
            "prev" -> turnPage { if (it.hasPrev) it.page - 1 else { say("(already on first page)"); null } }
            "page" -> turnPage { p ->
                val n = arg.toIntOrNull()
                when {
                    n == null -> { say("usage: page <N>"); null }
                    n !in 1..p.lastPage -> { say("(valid pages are 1..${p.lastPage})"); null }
                    else -> n
                }
            }
            "correction", "corrections" -> say(renderCorrections(lookup.corrections.list(arg.ifEmpty { null }, limit = 50)))
            "cd" -> cd(arg)
            "copy" -> copy(arg)
            "clear" -> output.clear()
            "quit", "exit", "q" -> onQuit()
            else -> say("(unknown command: '$head'; type 'help')", Tone.DIM)
        }
    }

    private fun help(topic: String) {
        if (topic.isEmpty()) return say(preformatted(HELP_TEXT))
        val body = HELP_TOPICS[topic.lowercase()] ?: return say("(no help topic '$topic'; try: ${HELP_TOPICS.keys.joinToString(", ")} — or bare `help`)")
        say(preformatted(body))
    }

    /** `card <N>` is the N-th row of the last search page; anything else is a name. */
    private fun cardOrRow(arg: String) {
        if (arg.isEmpty()) return say("usage: card <name>  |  or `card <N>` for the N-th row of last search")
        val rows = lastSearch?.rows.orEmpty()
        if (arg.all { it.isDigit() } && rows.isNotEmpty()) {
            val row = rows.getOrNull(arg.toInt() - 1) ?: return say("(no row #$arg in last search; valid range is 1..${rows.size})")
            return card(row.name)
        }
        card(arg)
    }

    private fun card(name: String) {
        val profile = lookup.cards.profile(name, restrictToCi = scope?.commanderCi) ?: return say("(card not found: $name)")
        say(renderCard(profile))
    }

    private fun rulings(arg: String) {
        if (arg.isEmpty()) return say("usage: ruling <name>")
        say(renderRulings(lookup.names.resolve(arg) ?: arg, lookup.cards.rulings(arg)))
    }

    private fun rule(number: String) {
        val rule = lookup.rules.rule(number) ?: return say("(rule not found: $number)")
        say(renderRule(rule))
    }

    private fun combo(arg: String) {
        if (arg.isEmpty()) return say("usage: combo <card>")
        showCombos(lookup.combos.withCard(arg, limit = 50), "combo(s) featuring ${lookup.names.resolve(arg) ?: arg}:")
    }

    private fun combos(arg: String) {
        val cards = arg.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        if (cards.size < 2) return say(if (arg.isEmpty()) "usage: combos <card1>; <card2>[; ...]" else "need at least 2 cards separated by ';'")
        showCombos(lookup.combos.withAll(cards, limit = 50), "combo(s) containing ALL of: ${cards.joinToString(" + ")}")
    }

    /** The list `combo-info <N>` counts in; a single combo opens at once — that is clearly what was wanted. */
    private fun showCombos(combos: List<ComboSummary>, header: String) {
        lastCombos = combos
        if (combos.size == 1) lookup.combos.detail(combos.single().id)?.let { return say(renderCombo(it)) }
        say(renderComboList(combos, "${combos.size} $header"))
    }

    private fun comboInfo(arg: String) {
        if (arg.isEmpty()) return say("usage: combo-info <id-or-number>")
        if (arg.all { it.isDigit() } && lastCombos.isNotEmpty()) {
            val combo = lastCombos.getOrNull(arg.toInt() - 1) ?: return say("(no combo #$arg in last list; valid range is 1..${lastCombos.size})")
            return showCombo(combo.id)
        }
        showCombo(arg)
    }

    private fun showCombo(id: String) {
        val combo = lookup.combos.detail(id) ?: return say("(combo not found: $id)")
        say(renderCombo(combo))
    }

    private fun search(arg: String) {
        if (arg.isEmpty() || arg.lowercase() in setOf("help", "?")) return help("search")
        val parsed = try {
            SearchLanguage.parse(arg)
        } catch (e: SearchError) {
            return searchError(e)
        }
        // After `cd`, only what the deck can play; the filters are named, never applied silently.
        val (query, filters) = scope?.restrict(parsed) ?: (parsed to emptyList())
        showPage(query, 1, filters, announce = true)
    }

    /** Paging re-runs the query that ran, filters included: leaving the deck mid-paging can't change the results. */
    private fun turnPage(target: (SearchPage) -> Int?) {
        val current = lastSearch ?: return say("(no prior search — run `search <query>` first)")
        val page = target(current) ?: return
        showPage(current.query, page, current.filters)
    }

    /** Runs one page; [announce] names the deck's filters above it (a new search, not a page turn). */
    private fun showPage(query: SearchQuery, page: Int, filters: List<String>, announce: Boolean = false) {
        val result = try {
            lookup.search.page(query, page = page, pageSize = PAGE_SIZE, filters = filters)
        } catch (e: SearchError) {
            return searchError(e)
        }
        lastSearch = result
        if (announce && filters.isNotEmpty()) say(renderDeckFilterNotice(filters))
        say(renderSearch(result))
    }

    private fun searchError(e: SearchError) {
        say("search error: ${e.message}", Tone.ERROR)
        say("(type `search help` for syntax)", Tone.DIM)
    }

    /**
     * `cd <deck>` (or `<folder>/<deck>`): search and card profiles follow the
     * deck. `cd ..` and `cd /` go back to the whole pool. Navigation only:
     * folders and decks are still made and changed in the TUI.
     */
    private fun cd(arg: String) {
        if (arg.isEmpty() || arg == ".." || arg == "/") {
            scope = null
            ui.prompt = "> "
            return say("(search and card profiles cover the whole card pool)", Tone.DIM)
        }
        val folder = if ('/' in arg) arg.substringBefore('/').trim() else null
        val name = arg.substringAfter('/').trim()
        val matches = decks().filter { d ->
            d.name.equals(name, ignoreCase = true) &&
                (folder == null || (d.folderName ?: UNSORTED).equals(folder, ignoreCase = true) || (d.folderName == null && folder.equals("(no folder)", ignoreCase = true)))
        }
        val deck = when (matches.size) {
            0 -> return say("(no deck named '$arg'; the decks are in the list on the left)")
            1 -> matches.single()
            else -> return say("('$name' is in several folders: ${matches.joinToString(", ") { it.folderName ?: UNSORTED }} — use `cd <folder>/$name`)")
        }
        val entered = lookup.deckScope(deck.id) ?: return say("(no deck named '$arg')")
        scope = entered
        ui.prompt = "${entered.deckName}> "
        onEnterDeck(deck.id)
        val filters = entered.filters.map { it.second }
        say(if (filters.isEmpty()) "in ${entered.deckName}: no commander and no format, so search is not filtered"
            else "in ${entered.deckName}: search is limited to ${filters.joinToString("  ")}  (`cd ..` for the full pool)", Tone.DIM)
    }

    private fun copy(arg: String) {
        val (text, what) = when (arg.lowercase().ifEmpty { "last" }) {
            "last" -> output.lastOutputText() to "the last output"
            "all" -> output.allText() to "all the output"
            else -> return say("usage: copy [last|all]  — last: the previous command's output; all: the whole pane")
        }
        if (text.isBlank()) return say("(nothing to copy)")
        copyToClipboard(text)
        say("copied $what to the clipboard (${text.length} chars)", Tone.DIM)
    }

    companion object {
        const val PAGE_SIZE = 50
        /** How `cd` names the decks outside every folder (the TUI's path for them). */
        const val UNSORTED = "(unsorted)"
    }
}
