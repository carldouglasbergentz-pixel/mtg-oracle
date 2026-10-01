package mtgoracle.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mtgoracle.core.analysis.Archetype
import mtgoracle.core.analysis.Comparison
import mtgoracle.core.analysis.Costs
import mtgoracle.core.analysis.DeckList
import mtgoracle.core.analysis.DeckProfile
import mtgoracle.core.analysis.Py
import mtgoracle.core.analysis.Roles
import mtgoracle.core.deck.DeckExport
import mtgoracle.core.deck.DeckSummary
import mtgoracle.core.lookup.ComboDetail
import mtgoracle.core.lookup.ComboSummary
import mtgoracle.core.lookup.SearchLanguage
import mtgoracle.core.play.DeckKey
import mtgoracle.core.play.Records
import mtgoracle.core.play.Tally
import mtgoracle.core.sync.Source
import mtgoracle.core.sync.SyncReport
import mtgoracle.data.Library
import mtgoracle.data.Lookup
import mtgoracle.data.MtgDb
import mtgoracle.data.sync.HttpUpstream
import mtgoracle.data.sync.Prune
import mtgoracle.data.sync.Sync
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream

/**
 * `mtg <command> [--json]`: the app's lookups, analysis and data pipeline in
 * a terminal, for scripts and the LLM layer. Text is what the app's output
 * pane shows (the same renderings, through the same commands); `--json` is
 * structured, and for `profile` / `compare` the shape analyse_archetype.py's
 * `--json` had.
 */
object Cli {
    val USAGE = """
        mtg <command> [--json]
          card <name>                      a card's profile
          search <query> [--page N]        the search language (`mtg search help`)
          rule <number>                    a rule and its sub-rules
          combo <card>                     combos with a card
          combos <card>; <card>[; ...]     combos with all of them
          combo-info <id>                  one combo in full
          deck list                        the decks, by folder
          deck show <deck>                 a deck's cards
          deck export <deck> [--front-face] [--grouped]
          profile <deck>|<folder>          what the cards do, and when each role is castable
          profile --dir <path>             ...over a folder of decklist .txt files
          compare <deck> --against <deck>|<folder>
                                           head to head, or against every deck in the folder
          compare <deck> --against --dir <path>   against a folder of decklist .txt files
          results [<deck>]                 wins-losses-draws per opponent
          sync [--force] [<source> ...]    fetch what moved upstream
          prune [--yes]                    card rows no export writes any more (a dry run without --yes)
        A deck is its name, or <folder>/<name>.
    """.trimIndent()

    private val json = Json { prettyPrint = true; prettyPrintIndent = " " }

    /** Where the answer goes: the console (as UTF-8, see [console]), or a test's buffer. */
    private var out: PrintStream = System.out

    /** The console as UTF-8: oracle text is full of em-dashes, and mtg.cmd sets the code page to match. */
    fun console(): PrintStream = PrintStream(FileOutputStream(FileDescriptor.out), true, Charsets.UTF_8)

    fun run(paths: AppPaths, args: List<String>, to: PrintStream = console()): Int {
        out = to
        val asJson = "--json" in args
        val words = args.filter { it != "--json" }
        val command = words.firstOrNull() ?: return usage()
        val rest = words.drop(1)
        val db = MtgDb(paths.db).also { it.migrate(paths.backups) }
        return when (command) {
            "help", "--help", "-h" -> usage(ok = true)
            "sync" -> sync(paths, db, rest, asJson)
            "prune" -> prune(db, rest, asJson)
            else -> lookup(db, command, rest, asJson)
        }
    }

    private fun usage(ok: Boolean = false): Int { out.println(USAGE); return if (ok) 0 else 64 }

    private fun emit(element: JsonElement) = out.println(json.encodeToString(JsonElement.serializer(), element))

    private fun fail(message: String): Int { System.err.println(message); return 1 }

    private val width: Int get() = System.getenv("COLUMNS")?.toIntOrNull() ?: 100

    private fun lookup(db: MtgDb, command: String, rest: List<String>, asJson: Boolean): Int {
        val lookup = Lookup(db)
        val library = Library(db)
        fun deck(text: String): DeckSummary? {
            val folder = if ('/' in text) text.substringBefore('/').trim() else null
            val name = text.substringAfter('/').trim()
            val matches = library.decks().filter { it.name.equals(name, ignoreCase = true) && (folder == null || it.folderName.equals(folder, ignoreCase = true)) }
            return matches.singleOrNull()
        }
        val arg = rest.joinToString(" ")
        // Text: the app's own commands, the last one's output as the pane would show it.
        fun text(vararg lines: String, selected: DeckSummary? = null): Int {
            val commands = LookupCommands(lookup, decks = { library.decks() }, faceOf = { null }, selectedDeck = { selected?.id })
            lines.forEach(commands::submit)
            commands.output.entries.drop(commands.output.entries.indexOfLast { it.isEcho } + 1).flatMap { it.rendering.lines(width) }.forEach { out.println(it.text) }
            return 0
        }
        return when (command) {
            "card" -> if (!asJson) text("card $arg") else lookup.cards.profile(arg)?.let { emit(card(it)); 0 } ?: fail("card not found: $arg")
            "search" -> {
                val page = rest.indexOf("--page").takeIf { it >= 0 }?.let { rest.getOrNull(it + 1)?.toIntOrNull() } ?: 1
                val query = rest.filterIndexed { i, w -> w != "--page" && (i == 0 || rest[i - 1] != "--page") }.joinToString(" ")
                if (!asJson) text(*listOfNotNull("search $query", "page $page".takeIf { page > 1 }).toTypedArray())
                else {
                    val result = lookup.search.page(SearchLanguage.parse(query), page = page, pageSize = LookupCommands.PAGE_SIZE)
                    emit(buildJsonObject {
                        put("query", query); put("total", result.total); put("page", result.page); put("last_page", result.lastPage)
                        put("rows", buildJsonArray { result.rows.forEach { r -> add(buildJsonObject { put("name", r.name); put("mana_cost", r.manaCost); put("type_line", r.typeLine) }) } })
                    })
                    0
                }
            }
            "rule" -> if (!asJson) text("rule $arg") else lookup.rules.rule(arg)?.let { r ->
                emit(buildJsonObject {
                    put("number", r.number); put("section", r.sectionTitle); put("text", r.text)
                    put("children", buildJsonArray { r.children.forEach { c -> add(buildJsonObject { put("number", c.number); put("text", c.text) }) } })
                }); 0
            } ?: fail("rule not found: $arg")
            "combo" -> if (!asJson) text("combo $arg") else { emit(JsonArray(lookup.combos.withCard(arg).map(::comboSummary))); 0 }
            "combos" -> if (!asJson) text("combos $arg") else { emit(JsonArray(lookup.combos.withAll(arg.split(';').map { it.trim() }.filter { it.isNotEmpty() }).map(::comboSummary))); 0 }
            "combo-info" -> if (!asJson) text("combo-info $arg") else lookup.combos.detail(arg)?.let { emit(comboDetail(it)); 0 } ?: fail("combo not found: $arg")
            "results" -> if (!asJson) text("results $arg") else {
                val games = lookup.games.played()
                val keys = if (arg.isEmpty()) Records.decks(games) else listOf(deck(arg)?.let { DeckKey(it.id, it.name) } ?: return fail("no deck named '$arg'"))
                emit(JsonArray(keys.map { k ->
                    buildJsonObject {
                        put("deck", k.name)
                        put("opponents", JsonArray(Records.of(k, games).map { m ->
                            buildJsonObject { put("opponent", m.opponent.name); put("played", tally(m.played)); put("simulated", tally(m.simulated)); put("average_turns", m.total.averageTurns) }
                        }))
                    }
                }))
                0
            }
            "profile" -> {
                // --dir: a folder of decklist files (analyse_archetype --dir), identical files counted once.
                if (rest.firstOrNull() == "--dir") {
                    val dir = java.io.File(rest.drop(1).joinToString(" ")).takeIf { it.isDirectory } ?: return fail("not a directory: ${rest.drop(1).joinToString(" ")}")
                    val lists = mtgoracle.data.ReferenceLists.read(dir).map { it.deck }.ifEmpty { return fail("no decklists in $dir") }
                    if (asJson) emit(profiles(lists, lookup)) else {
                        val pool = lookup.analysis.pool(lists.flatMap { it.cards.keys })
                        val rank = Archetype.rank(lists, pool, Roles.REPORT_ROLES)
                        mtgoracle.ui.lookup.renderProfile(lists.map { Archetype.profile(it, pool) }, rank.lowConfidence).lines(width).forEach { out.println(it.text) }
                        mtgoracle.ui.lookup.renderRanking(rank, lists.size).lines(width).forEach { out.println(it.text) }
                    }
                    return 0
                }
                if (!asJson) return text("profile $arg")
                val decks = deck(arg)?.let { listOf(it) } ?: library.decks().filter { it.folderName.equals(arg, ignoreCase = true) }.ifEmpty { return fail("no deck or folder named '$arg'") }
                val lists = decks.map { lookup.analysis.deckList(it.id, it.name) }
                emit(profiles(lists, lookup))
                0
            }
            "compare" -> {
                // Two names of several words each need a divider: `compare Izzet Delver --against Blue Moon`.
                val at = rest.indexOf("--against")
                if (at < 1) return fail("usage: compare <deck> --against <deck>|<folder>")
                val subjectName = rest.take(at).joinToString(" ")
                val subject = deck(subjectName) ?: return fail("no deck named '$subjectName' (or several: name it <folder>/<deck>)")
                val against = rest.drop(at + 1).joinToString(" ").ifEmpty { return fail("usage: compare <deck> --against <deck>|<folder>") }
                val fromDir = against.startsWith("--dir ")
                if (!asJson && !fromDir) return text("compare $against", selected = subject)
                val mine = lookup.analysis.deckList(subject.id, subject.name)
                val lists = if (fromDir) {
                    val dir = java.io.File(against.removePrefix("--dir ").trim()).takeIf { it.isDirectory } ?: return fail("not a directory: ${against.removePrefix("--dir ")}")
                    mtgoracle.data.ReferenceLists.read(dir).map { it.deck }
                } else {
                    val reference = deck(against)?.let { listOf(it) } ?: library.decks().filter { it.folderName.equals(against, ignoreCase = true) && it.id != subject.id }
                    reference.map { lookup.analysis.deckList(it.id, it.name) }
                }
                if (lists.isEmpty()) return fail("compare: no deck, folder or decklists named '$against'")
                if (!asJson) {
                    val pool = lookup.analysis.pool((lists + mine).flatMap { it.cards.keys })
                    mtgoracle.ui.lookup.renderComparison(Archetype.compare(mine, lists, pool)).lines(width).forEach { out.println(it.text) }
                    return 0
                }
                val pool = lookup.analysis.pool((lists + mine).flatMap { it.cards.keys })
                emit(comparison(Archetype.compare(mine, lists, pool)))
                0
            }
            "deck" -> deckCommand(rest, asJson, library, lookup, ::deck)
            else -> { System.err.println("no command '$command'\n"); usage() }
        }
    }

    private fun deckCommand(rest: List<String>, asJson: Boolean, library: Library, lookup: Lookup, find: (String) -> DeckSummary?): Int {
        val sub = rest.firstOrNull() ?: return usage()
        val flags = rest.filter { it.startsWith("--") }.toSet()
        val arg = rest.drop(1).filter { !it.startsWith("--") }.joinToString(" ")
        if (sub == "list") {
            val decks = library.decks()
            if (asJson) emit(JsonArray(decks.map { d -> buildJsonObject { put("id", d.id); put("name", d.name); put("folder", d.folderName); put("format", d.format); put("cards", d.cardCount) } }))
            else decks.groupBy { it.folderName ?: "(no folder)" }.forEach { (folder, inFolder) ->
                out.println(folder)
                inFolder.forEach { out.println("  " + it.name.padEnd(36) + "%4d".format(it.cardCount) + (it.format?.let { f -> "  $f" } ?: "")) }
            }
            return 0
        }
        val summary = find(arg) ?: return fail("no deck named '$arg' (or several: name it <folder>/<deck>)")
        val deck = library.deck(summary.id) ?: return fail("no deck named '$arg'")
        return when (sub) {
            "show", "export" -> {
                val pool = if ("--grouped" in flags) lookup.analysis.pool(deck.cards.map { it.name }) else null
                val text = DeckExport.text(deck, frontFace = "--front-face" in flags, layoutOf = lookup::layout, primaryOf = pool?.let { p -> { n -> p.classify(n)?.primary } })
                if (!asJson) out.print(text)
                else emit(buildJsonObject {
                    put("name", deck.name); put("folder", deck.folderName); put("format", deck.format); put("text", text)
                    put("cards", JsonArray(deck.cards.map { c ->
                        buildJsonObject { put("name", c.name); put("quantity", c.quantity); put("section", c.section.name.lowercase()); put("set", c.setCode); put("number", c.collectorNumber) }
                    }))
                    put("considering", JsonArray(deck.considering.map { c -> buildJsonObject { put("name", c.name); put("quantity", c.quantity) } }))
                })
                0
            }
            else -> usage()
        }
    }

    private fun sync(paths: AppPaths, db: MtgDb, rest: List<String>, asJson: Boolean): Int {
        val sources = rest.filter { it != "--force" && it != "--only" }.map { Source.of(it) ?: return fail("no source '$it' (have: ${Source.entries.joinToString { s -> s.key }})") }
        val report = Sync(db, HttpUpstream(), paths.data.resolve("raw"), paths.data.resolve("formats"), log = { if (!asJson) out.println("-- $it") })
            .run("--force" in rest, sources.ifEmpty { Source.entries }.toSet())
        if (asJson) emit(syncReport(report)) else mtgoracle.ui.lookup.renderSyncReport(report).lines(width).forEach { out.println(it.text) }
        return if (report.failures.isEmpty()) 0 else 1
    }

    private fun prune(db: MtgDb, rest: List<String>, asJson: Boolean): Int {
        // In a terminal --yes is the confirmation: there is nobody to ask.
        val report = Prune.run(db, delete = "--yes" in rest)
        if (asJson) emit(buildJsonObject {
            put("total", report.total); put("refused", report.refused)
            put("prunable", JsonArray(report.prunable.map(::JsonPrimitive))); put("kept", JsonArray(report.kept.map(::JsonPrimitive)))
            put("ignored", JsonArray(report.ignored.map(::JsonPrimitive)))
            put("deleted", JsonObject(report.deleted.mapValues { JsonPrimitive(it.value) }))
        })
        else {
            out.println(LookupCommands.pruneText(report))
            report.deleted.forEach { (table, n) -> out.println("  deleted from $table: $n") }
        }
        return 0
    }

    // --- JSON shapes ---------------------------------------------------

    private fun tally(t: Tally) = buildJsonObject { put("wins", t.wins); put("losses", t.losses); put("draws", t.draws) }

    private fun comboSummary(c: ComboSummary) = buildJsonObject {
        put("id", c.id); put("name", c.name); put("color_identity", c.colorIdentity); put("card_count", c.cardCount)
        put("cards", c.cards); put("source", c.source); put("more_cards_than_listed", c.hasTemplateVars)
    }

    private fun comboDetail(c: ComboDetail) = buildJsonObject {
        put("id", c.id); put("name", c.name); put("color_identity", c.colorIdentity); put("description", c.description); put("source", c.source)
        put("cards", JsonArray(c.cards.map { buildJsonObject { put("name", it.name); put("quantity", it.quantity) } }))
        put("prerequisites", JsonArray(c.prerequisites.map(::JsonPrimitive)))
        put("steps", JsonArray(c.steps.map(::JsonPrimitive)))
        put("results", JsonArray(c.results.map(::JsonPrimitive)))
    }

    private fun card(p: mtgoracle.core.lookup.CardProfile) = buildJsonObject {
        put("name", p.name); put("mana_cost", p.manaCost); put("type_line", p.typeLine); put("oracle_text", p.oracleText)
        put("games", p.games); put("reserved", p.reserved); put("edhrec_rank", p.edhrecRank)
        put("tags", JsonObject(p.tags.mapValues { (_, v) -> JsonArray(v.map(::JsonPrimitive)) }))
        put("abilities", JsonArray(p.abilities.map { a ->
            buildJsonObject { put("type", a.type); put("cost", a.cost); put("effect", a.effect); put("has_target", a.hasTarget); put("produces_mana", a.producesMana); put("is_mana_ability", a.isManaAbility) }
        }))
        put("rulings", JsonArray(p.rulings.map { r -> buildJsonObject { put("date", r.date); put("text", r.text) } }))
        put("legalities", JsonObject(p.legalities.mapValues { (_, v) -> JsonArray(v.map(::JsonPrimitive)) }))
        put("combos", JsonArray(p.combos.map(::comboSummary)))
        put("corrections", JsonArray(p.corrections.map { c -> buildJsonObject { put("id", c.id); put("topic", c.topic); put("correct_claim", c.correctClaim); put("explanation", c.explanation) } }))
    }

    private fun mvMap(m: Map<Int, Int>) = JsonObject(m.entries.sortedBy { it.key }.associate { (k, v) -> k.toString() to JsonPrimitive(v) })
    private fun turnMap(m: Map<Int, Double>) = JsonObject(m.entries.associate { (t, v) -> t.toString() to JsonPrimitive(Py.round(v, 4)) })

    /** analyse_archetype's `--json`: each list, the most played per role, and the conventions the numbers rest on. */
    private fun profiles(lists: List<DeckList>, lookup: Lookup): JsonObject {
        val pool = lookup.analysis.pool(lists.flatMap { it.cards.keys })
        val profiles = lists.map { Archetype.profile(it, pool) }
        val ranking = Archetype.rank(lists, pool, Roles.REPORT_ROLES)
        return buildJsonObject {
            put("lists", JsonArray(profiles.map(::profile)))
            put("ranking", JsonObject(ranking.byRole.mapValues { (_, rows) ->
                JsonArray(rows.map { r ->
                    buildJsonObject {
                        put("name", r.name); put("n", r.n); put("pct", r.pct); put("mv", r.mv); put("printed", r.printed); put("cost", r.cost)
                        put("primary", r.primary); put("reason", r.reason); put("source", r.source)
                    }
                })
            }))
            put("low_confidence", JsonArray(ranking.lowConfidence.sorted().map(::JsonPrimitive)))
            put("conventions", buildJsonObject {
                put("x_value", Costs.X_VALUE); put("delve_yard", Costs.DELVE_YARD); put("cantrip_max_mana", Roles.CANTRIP_MAX_MANA)
                put("miracle_costed", false); put("castable_second_faces", JsonArray(Costs.CASTABLE_SECOND_FACE.sorted().map(::JsonPrimitive))); put("on_play", true)
            })
        }
    }

    private fun profile(p: DeckProfile) = buildJsonObject {
        put("name", p.name); put("size", p.size)
        put("counts", JsonObject(Roles.ROLES.associateWith { JsonPrimitive(p.counts[it] ?: 0) }))
        put("role_mv", JsonObject(p.roleMv.filterValues { it.isNotEmpty() }.mapValues { (_, m) -> mvMap(m) }))
        put("curve", mvMap(p.curve)); put("lands", p.lands); put("rocks", p.rocks); put("land_backs", p.landBacks)
        put("mana_sources", p.manaSources); put("avg_mv", Py.round(p.avgMv, 3))
        put("unresolved", JsonArray(p.unresolved.map(::JsonPrimitive)))
        put("on_curve", JsonObject(Roles.REPORT_ROLES.associateWith { turnMap(p.liveCurve(it)) }))
        put("ceiling", JsonObject(Roles.REPORT_ROLES.associateWith { turnMap(p.ceiling(it)) }))
    }

    /** analyse_archetype's comparison_json. */
    private fun comparison(c: Comparison) = buildJsonObject {
        put("subject", c.subject.name)
        put("reference", JsonArray(c.reference.map { JsonPrimitive(it.name) }))
        put("roles", JsonArray(c.roles.map { r ->
            buildJsonObject {
                put("role", r.role); put("label", Roles.LABELS[r.role]); put("subject", r.subject); put("ref_mean", r.refMean); put("ref_min", r.refMin)
                put("ref_max", r.refMax); put("ref_median", r.refMedian); put("delta", r.delta); put("verdict", r.verdict)
            }
        }))
        put("mana_sources", buildJsonObject {
            val m = c.manaSources
            put("subject", m.subject); put("ref_mean", m.refMean); put("ref_min", m.refMin); put("ref_max", m.refMax); put("verdict", m.verdict)
        })
        put("avg_mv", buildJsonObject { put("subject", c.avgMv.first); put("reference", c.avgMv.second) })
        put("out_of_range", JsonArray(c.outOfRange.map { JsonPrimitive(it.role) }))
        put("nearest", JsonArray(c.nearest.map { (n, d) -> buildJsonObject { put("name", n); put("distance", d) } }))
        put("curve_delta", JsonObject(c.curveDelta.mapValues { (_, d) -> turnMap(d) }))
        put("missing", JsonArray(c.missing.map { m ->
            buildJsonObject { put("name", m.name); put("role", m.role); put("mv", m.mv); put("n_lists", m.nLists); put("of_lists", m.ofLists); put("share", Py.round(m.share, 3)) }
        }))
        put("unique", JsonArray(c.unique.map { u -> buildJsonObject { put("name", u.name); put("role", u.role); put("mv", u.mv) } }))
    }

    private fun syncReport(r: SyncReport) = buildJsonObject {
        put("ran", JsonArray(r.ran.map { JsonPrimitive(it.key) }))
        put("changes", JsonArray(r.changes.map { c ->
            buildJsonObject { put("table", c.table); put("added", c.added); put("removed", c.removed); put("modified", c.modified); put("net", c.net); put("total", c.total) }
        }))
        put("failures", JsonArray(r.failures.map { (s, why) -> buildJsonObject { put("source", s.key); put("error", why) } }))
        put("notes", JsonObject(r.notes.entries.associate { (s, lines) -> s.key to JsonArray(lines.map(::JsonPrimitive)) }))
        put("state", JsonArray(r.state.map { (source, marker, rows) -> buildJsonObject { put("source", source); put("updated_at", marker); put("rows", rows) } }))
    }
}
