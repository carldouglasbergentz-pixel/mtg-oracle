package mtgoracle.app

import mtgoracle.core.analysis.Archetype
import mtgoracle.core.analysis.DeckList
import mtgoracle.core.analysis.Roles
import mtgoracle.core.deck.DeckSummary
import mtgoracle.core.sync.Source
import mtgoracle.ui.lookup.CommandLineState
import mtgoracle.core.play.DeckKey
import mtgoracle.core.play.Records
import mtgoracle.core.lookup.ComboSummary
import mtgoracle.core.lookup.DeckScope
import mtgoracle.core.lookup.Explain
import mtgoracle.core.lookup.closest
import mtgoracle.core.lookup.SearchError
import mtgoracle.core.lookup.SearchLanguage
import mtgoracle.core.lookup.SearchPage
import mtgoracle.core.lookup.SearchQuery
import mtgoracle.data.DeckWriter
import mtgoracle.data.Lookup
import mtgoracle.core.deck.DeckSection
import mtgoracle.ui.lookup.EditAction
import mtgoracle.forge.Log
import mtgoracle.ui.kit.CardFace
import mtgoracle.ui.lookup.COMMAND_HINTS
import mtgoracle.ui.lookup.COMMAND_WORDS
import mtgoracle.ui.lookup.HELP_TEXT
import mtgoracle.ui.lookup.Preview
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
import mtgoracle.ui.lookup.renderComparison
import mtgoracle.ui.lookup.renderCorrections
import mtgoracle.ui.lookup.renderProfile
import mtgoracle.ui.lookup.renderRanking
import mtgoracle.ui.lookup.renderResults
import mtgoracle.ui.lookup.renderDeckFilterNotice
import mtgoracle.ui.lookup.renderRule
import mtgoracle.ui.lookup.renderRulesSearch
import mtgoracle.ui.lookup.renderRulings
import mtgoracle.ui.lookup.renderSearch
import java.sql.SQLException
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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
    /** The deck engine; null leaves the workspace read-only (tests of lookup alone). */
    writer: DeckWriter? = null,
    /** Deck [id] changed: re-read it for the screen. */
    private val onDeckChanged: (Int) -> Unit = {},
    /** A line for the status bar. */
    private val notify: (String) -> Unit = {},
    /** The deck selected in the library: what `profile`, `compare` and `combos` mean without `cd`. */
    private val selectedDeck: () -> Int? = { null },
    /** `sync`: runs the data pipeline (in the background) with these options; null where there is none (tests of lookup alone). */
    private val sync: ((force: Boolean, only: Set<Source>) -> Unit)? = null,
    /** The scrollback and the command line, carried over when the lookup is rebuilt after a sync. */
    val output: OutputLog = OutputLog(),
    command: CommandLineState = CommandLineState(),
) {
    private val writerOrNull = writer
    private val suggester = Suggester(
        lookup.names.sorted, lookup.rules.numbers(), deckNames = { decks().map { it.name } + ".." },
        helpTopics = HELP_TOPICS.keys.toList(), vocabulary = lookup.vocabulary,
    )
    val ui = LookupUi(output, suggester::suggest, ::submit, ::open, faceOf, ::preview, ::count, edit = { editing?.perform(it) }, command = command)
    private val editing: DeckEditing? = writer?.let { DeckEditing(it, lookup, ui, openDeck = { scope?.deckId }, reload = ::deckChanged, say = notify) }

    /**
     * The deck being worked on (the deck workspace, entered by Enter or `cd`):
     * `search` and card profiles follow it. Null in the library. Compose
     * state, so the window switches screens with it.
     */
    var scope by mutableStateOf<DeckScope?>(null)
        private set

    /** Opens deck [id] to work on: search follows it from now on. False when it is gone. */
    fun enterDeck(id: Int): Boolean {
        val entered = lookup.deckScope(id) ?: return false
        // Another deck starts with an empty output: the last deck's searches and reports only crowd it.
        // The same deck again keeps what it had; a `cd` keeps its own echo.
        if (id != lastDeck) {
            val echo = output.entries.lastOrNull()?.takeIf { it.isEcho }
            output.clear()
            echo?.let { output.entries += it }
            lastSearch = null
            lastCombos = emptyList()
            ui.selected = null
        }
        lastDeck = id
        scope = entered
        ui.prompt = "${entered.deckName}> "
        ui.refusal = null
        ui.deckTab = mtgoracle.ui.lookup.DeckTab.DECK
        onEnterDeck(id)
        editing?.refresh(id)
        return true
    }

    /** After a change to deck [id]: its scope again (a new commander is a new identity, a rename a new prompt), then the screen. */
    private fun deckChanged(id: Int) {
        if (scope?.deckId == id) lookup.deckScope(id)?.let { scope = it; ui.prompt = "${it.deckName}> " }
        onDeckChanged(id)
    }

    /** Deck [id] was changed from outside the workspace's edits (a rename, a format, an import): read everything again. */
    fun refreshDeck(id: Int) {
        if (scope?.deckId == id && editing != null) editing.refresh(id) else deckChanged(id)
    }

    /** Back to the library: search covers the whole pool again. */
    fun leaveDeck() {
        scope = null
        ui.prompt = "> "
        ui.refusal = null
        ui.points = emptyMap()
        ui.pointsBudget = null
    }
    /** The deck last opened: entering another one empties the output. */
    private var lastDeck: Int? = null
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
        val showing = ui.showOutput
        ui.showOutput = true
        when (link) {
            is OutputLink.Card -> { output.echo("card ${link.name}"); guarded { card(link.name) } }
            is OutputLink.Combo -> { output.echo("combo-info ${link.id}"); guarded { showCombo(link.id) } }
            is OutputLink.Rule -> { output.echo("rule ${link.number}"); guarded { rule(link.number) } }
            is OutputLink.Run -> { output.echo(link.command); guarded { dispatch(link.command) } }
            is OutputLink.Cards -> { output.echo(link.title); say(mtgoracle.ui.lookup.renderCardList(link.title, link.names)) }
            // A result's `+ sb ?`: the deck changes, the output doesn't.
            is OutputLink.Edit -> { ui.showOutput = showing; guarded { edit(link.action) } }
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
            "combos" -> if (arg.isEmpty() && currentDeck() != null) deckCombos() else combos(arg)
            "profile" -> profile(arg)
            "compare" -> compare(arg)
            "results" -> results(arg)
            "sync" -> sync(arg)
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
            "quit", "exit" -> onQuit()
            "add" -> deckCommand(arg, "add [--sb] [--force] <card> [N]") { card, n, flags ->
                EditAction.Add(card, if ("--sb" in flags || "--sideboard" in flags) DeckSection.SIDEBOARD else DeckSection.MAIN, n ?: 1)
            }
            "remove" -> deckCommand(arg, "remove [--sb|--considering] <card> [N]") { card, n, flags ->
                val section = when {
                    "--sb" in flags || "--sideboard" in flags -> DeckSection.SIDEBOARD
                    "--considering" in flags -> DeckSection.CONSIDERING
                    else -> null
                }
                if (section == null) null else EditAction.Remove(card, section, all = n == null, quantity = n ?: 1)
            }
            "consider" -> deckCommand(arg, "consider <card> [N]") { card, n, _ -> EditAction.Add(card, DeckSection.CONSIDERING, n ?: 1) }
            "commander" -> deckCommand(arg, "commander [--unset] [--force] <card>") { card, _, flags ->
                if ("--unset" in flags) EditAction.Demote(card) else EditAction.Promote(card)
            }
            "undo" -> say(editing?.takeIf { scope != null }?.undo() ?: "open a deck first (Enter on it in the library, or `cd <deck>`)")
            "history" -> if (scope == null) say("open a deck first") else { ui.deckTab = mtgoracle.ui.lookup.DeckTab.HISTORY; say("(the History tab of the deck pane)", Tone.DIM) }
            // Anything that isn't a command is a search, as in a browser's address bar.
            else -> search(line, typo = closest(head, COMMAND_WORDS))
        }
    }

    /** A result's `+ sb ?` or a deck command: through the engine, and whether it went in (or why not) under the output. */
    private fun edit(action: EditAction, forced: Boolean = false): String? {
        val e = editing ?: return null.also { say("(deck editing is not available here)", Tone.DIM) }
        return e.perform(action, forced)
    }

    /**
     * `add` / `remove` / `consider` / `commander`: `--` flags anywhere, a
     * trailing number as the count (a card whose name ends in one wins, as
     * in the TUI: `add Pip-Boy 3000`), then [make] turns them into the edit.
     * A null edit is the remove without a section: through the engine's own
     * every-section path.
     */
    private fun deckCommand(arg: String, usage: String, make: (card: String, n: Int?, flags: Set<String>) -> EditAction?) {
        if (scope == null) return say("open a deck first (Enter on it in the library, or `cd <deck>`)")
        val words = arg.split(' ').filter { it.isNotEmpty() }
        val flags = words.filter { it.startsWith("--") }.map { it.lowercase() }.toSet()
        var rest = words.filter { !it.startsWith("--") }.joinToString(" ")
        if (rest.isEmpty()) return say("usage: $usage")
        var n: Int? = null
        val last = rest.substringAfterLast(' ', "")
        if (last.all { it.isDigit() } && last.isNotEmpty() && lookup.names.resolve(rest) == null) {
            n = last.toInt()
            rest = rest.substringBeforeLast(' ')
        }
        val action = make(rest, n, flags)
        val result = if (action == null) {
            // `remove <card>` with no section: every section but the list, as the TUI's `remove`.
            val id = scope!!.deckId
            try {
                val (card, removed, left) = requireNotNull(writerOrNull).remove(id, rest, n)
                deckChanged(id); editing?.refresh(id)
                "-$removed $card ($left left)"
            } catch (e: mtgoracle.core.deck.DeckRefusal) { "refused: ${e.message}" }
        } else edit(action, forced = "--force" in flags)
        result?.let { say(it, if (it.startsWith("refused")) Tone.ERROR else Tone.DIM) }
    }

    /** The search a line stands for (`search <q>`, or a line that is no command), or null. */
    private fun searchText(line: String): String? {
        val head = line.substringBefore(' ').lowercase()
        return when {
            head == "search" -> line.substringAfter(' ', "").trim().takeIf { it.isNotEmpty() && it.lowercase() !in setOf("help", "?") }
            head in COMMAND_WORDS -> null
            else -> line.trim().takeIf { it.isNotEmpty() }
        }
    }

    /** [text] parsed and, after `cd`, restricted to the deck, with the labels of what the deck added. */
    private fun scoped(text: String): Pair<SearchQuery, List<String>> {
        val parsed = SearchLanguage.parse(text)
        return scope?.restrict(parsed) ?: (parsed to emptyList())
    }

    /** The hint under the command line: a command's usage, or the query read back (or what is wrong with it). */
    fun preview(line: String): Preview? {
        if (line.isBlank()) return null
        val text = searchText(line) ?: return COMMAND_HINTS[line.substringBefore(' ').lowercase()]?.let { Preview(it) }
        return try {
            val parsed = SearchLanguage.parse(text)
            val (query, filters) = scope?.restrict(parsed) ?: (parsed to emptyList())
            lookup.search.check(query)
            Preview(Explain.query(parsed) + if (filters.isEmpty()) "" else "  ·  in ${scope?.deckName}: ${filters.joinToString(" ")}", isSearch = true)
        } catch (e: SearchError) {
            Preview("✗ ${e.message}", error = true)
        }
    }

    /** How many cards a search line finds; null for a command or a query that doesn't compile. */
    fun count(line: String): Int? {
        val text = searchText(line) ?: return null
        return try { lookup.search.count(scoped(text).first) } catch (e: SearchError) { null }
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

    /** [typo]: the command the first word may have meant, named when the search finds nothing. */
    private fun search(arg: String, typo: String? = null) {
        if (arg.isEmpty() || arg.lowercase() in setOf("help", "?")) return help("search")
        // After `cd`, only what the deck can play; the filters are named, never applied silently.
        val (query, filters) = try {
            scoped(arg)
        } catch (e: SearchError) {
            return searchError(e)
        }
        showPage(query, 1, filters, announce = true)
        if (typo != null && lastSearch?.query == query && lastSearch?.total == 0) {
            say("(no card matches, and '${arg.substringBefore(' ')}' is no command — did you mean `$typo`?)", Tone.DIM)
        }
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
        ui.selected = null
        // In the workspace a row carries `+ sb ?`, and a pointed card its points.
        val points = ui.points
        output.addSearch(result, renderSearch(result, actions = scope != null && editing != null, points = { points[it.lowercase()] }))
    }

    private fun searchError(e: SearchError) {
        say("search error: ${e.message}", Tone.ERROR)
        say("(type `search help` for syntax)", Tone.DIM)
    }

    /**
     * `cd <deck>` (or `<folder>/<deck>`) opens the deck workspace, as Enter
     * does; search and card profiles follow the deck. `cd ..` and `cd /` go
     * back to the library.
     */
    private fun cd(arg: String) {
        if (arg.isEmpty() || arg == ".." || arg == "/") {
            leaveDeck()
            return say("(back in the library: search covers the whole card pool)", Tone.DIM)
        }
        val deck = findDeck(arg).getOrElse { return say(it.message!!) } ?: return say("(no deck named '$arg'; the decks are in the list on the left)")
        if (!enterDeck(deck.id)) return say("(no deck named '$arg')")
        val entered = scope!!
        val filters = entered.filters.map { it.second }
        say(if (filters.isEmpty()) "in ${entered.deckName}: no commander and no format, so search is not filtered"
            else "in ${entered.deckName}: search is limited to ${filters.joinToString("  ")}  (`cd ..` for the full pool)", Tone.DIM)
    }

    /** The deck `<deck>` or `<folder>/<deck>` names; null for none, a failure naming the folders when several match. */
    private fun findDeck(arg: String): Result<DeckSummary?> {
        val folder = if ('/' in arg) arg.substringBefore('/').trim() else null
        val name = arg.substringAfter('/').trim()
        val matches = decks().filter { d ->
            d.name.equals(name, ignoreCase = true) &&
                (folder == null || (d.folderName ?: UNSORTED).equals(folder, ignoreCase = true) || (d.folderName == null && folder.equals("(no folder)", ignoreCase = true)))
        }
        return when (matches.size) {
            0 -> Result.success(null)
            1 -> Result.success(matches.single())
            else -> Result.failure(IllegalArgumentException(
                "('$name' is in several folders: ${matches.joinToString(", ") { it.folderName ?: UNSORTED }} — use `<folder>/$name`)"))
        }
    }

    /** The decks in the folder named [name] (ignoring case), or null when no folder has that name. */
    private fun folderDecks(name: String): List<DeckSummary>? =
        decks().filter { it.folderName.equals(name, ignoreCase = true) }.takeIf { it.isNotEmpty() }

    /** The deck being worked on, else the one selected in the library. */
    private fun currentDeck(): DeckSummary? = (scope?.deckId ?: selectedDeck())?.let { id -> decks().firstOrNull { it.id == id } }

    private fun deckList(d: DeckSummary) = lookup.analysis.deckList(d.id, d.name)

    /** `sync [--force] [<source> ...]`: the whole pipeline, or the sources named, in their own order. */
    private fun sync(arg: String) {
        val run = sync ?: return say("(sync is not available here)", Tone.DIM)
        val words = arg.split(' ').filter { it.isNotEmpty() && it != "--only" }
        val force = "--force" in words
        val named = words.filter { it != "--force" }
        val unknown = named.filter { Source.of(it) == null }
        if (unknown.isNotEmpty()) return say("usage: sync [--force] [${Source.entries.joinToString("|") { it.key }} ...]  (no source '${unknown.first()}')")
        run(force, if (named.isEmpty()) Source.entries.toSet() else named.mapNotNull(Source::of).toSet())
    }

    /**
     * `results`: every deck's record against each opponent; `results <deck>`
     * (or the deck you are in or on, with `results .`) just that deck's.
     */
    private fun results(arg: String) {
        val games = lookup.games.played()
        if (arg.isEmpty()) {
            val keys = Records.decks(games).sortedBy { it.name.lowercase() }
            return say(renderResults(keys.map { it.name to Records.of(it, games) }))
        }
        val deck = (if (arg == ".") currentDeck() else findDeck(arg).getOrElse { return say(it.message!!) })
            ?: return say(if (arg == ".") "usage: results [<deck>]   (`results .` is the deck you are in or on)" else "results: no deck named '$arg'")
        val matchups = Records.of(DeckKey(deck.id, deck.name), games)
        if (matchups.isEmpty()) return say("(no games recorded for ${deck.name} yet: play one, or simulate from the setup screen)", Tone.DIM)
        say(renderResults(listOf(deck.name to matchups)))
    }

    /** `combos` in or on a deck: every combo it holds whole, numbered for `combo-info <N>`. */
    private fun deckCombos() {
        val deck = currentDeck() ?: return
        val combos = lookup.combos.inDeck(deck.id)
        lastCombos = combos
        val name = mtgoracle.core.analysis.Py.repr(deck.name)
        if (combos.isEmpty()) return say("(no combos fully contained in $name)")
        say(renderComboList(combos, "${combos.size} combo(s) fully contained in $name:"))
    }

    /**
     * `profile` (the deck you are in or on), `profile <deck>`, or `profile
     * <folder>`: every deck in it side by side, then the cards they play
     * most. A deck's name wins over a folder's.
     */
    private fun profile(arg: String) {
        if (arg.isEmpty()) {
            val deck = currentDeck() ?: return say("usage: profile [<deck>|<folder>]   (or select a deck first to profile it)")
            return profileDecks(listOf(deckList(deck)))
        }
        val deck = findDeck(arg).getOrElse { return say(it.message!!) }
        if (deck != null) return profileDecks(listOf(deckList(deck)))
        val folder = folderDecks(arg) ?: return say("profile: no deck or folder named '$arg'")
        profileDecks(folder.map(::deckList), ranking = true)
    }

    private fun profileDecks(lists: List<DeckList>, ranking: Boolean = false) {
        val pool = lookup.analysis.pool(lists.flatMap { it.cards.keys })
        val rank = Archetype.rank(lists, pool, Roles.REPORT_ROLES)
        say(renderProfile(lists.map { Archetype.profile(it, pool) }, rank.lowConfidence))
        if (ranking) say(renderRanking(rank, lists.size))
    }

    /**
     * `compare <deck>`: the deck you are in or on, head to head with that
     * one; `compare <folder>`: against every deck in it (itself left out),
     * with the ranges it steps outside. Import reference lists into a folder
     * and they are a reference set.
     */
    private fun compare(arg: String) {
        val subject = currentDeck() ?: return say("(select or open a deck before `compare`)")
        if (arg.isEmpty()) return say("usage: compare <deck>|<folder>   (measures '${subject.name}' against it)")
        val other = findDeck(arg).getOrElse { return say(it.message!!) }
        val reference = when {
            other != null && other.id == subject.id -> return say("(a deck compared to itself deviates nowhere — name a different one)")
            other != null -> listOf(other)
            else -> folderDecks(arg)?.filter { it.id != subject.id } ?: return say("compare: no deck or folder named '$arg'")
        }
        if (reference.isEmpty()) return say("compare: '$arg' holds nothing but '${subject.name}' itself")
        val lists = reference.map(::deckList)
        val mine = deckList(subject)
        val pool = lookup.analysis.pool((lists + mine).flatMap { it.cards.keys })
        say(renderComparison(Archetype.compare(mine, lists, pool)))
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
