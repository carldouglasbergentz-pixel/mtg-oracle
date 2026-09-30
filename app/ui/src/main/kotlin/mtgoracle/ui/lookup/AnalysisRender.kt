package mtgoracle.ui.lookup

import mtgoracle.core.analysis.Comparison
import mtgoracle.core.analysis.DeckProfile
import mtgoracle.core.analysis.Py
import mtgoracle.core.analysis.Ranking
import mtgoracle.core.analysis.RoleDelta
import mtgoracle.core.analysis.Roles
import mtgoracle.core.analysis.left
import mtgoracle.core.analysis.right

/*
 * The archetype analysis as tables to read (profile, most played, compare).
 * The numbers are the engine's, held to Python by AnalysisParityTest; the
 * layout is the app's own:
 *  - only roles that exist in the decks shown get a row;
 *  - every table says under it what its columns mean;
 *  - the tables fit the pane, dropping late turns or per-deck columns before a row would run past the edge.
 */

private val REPORT = Roles.REPORT_ROLES

/** What a role is called in a report. `mana` is the non-land mana: "Mana sources" read as if it counted the lands. */
private fun label(role: String): String = if (role == "mana") "Mana rocks / dorks" else Roles.LABELS.getValue(role)

private class Report(val width: Int) {
    val out = mutableListOf<OutLine>()
    fun add(text: String = "", tone: Tone = Tone.PLAIN) { out += OutLine(text.trimEnd(), tone) }
    fun head(text: String) { add(); wrapWords(text, width, "", "    ").forEach { add(it, Tone.BOLD) } }
    /** An explanation under a table: dim, wrapped to the pane under a two-space hang. */
    fun note(text: String) = wrapWords(text, width, "  ", "    ").forEach { add(it, Tone.DIM) }
    /** [prefix] then a card name that opens the card, then [suffix]. */
    fun card(prefix: String, name: String, shown: String = name, suffix: String = "") {
        out += OutLine((prefix + shown + suffix).trimEnd(), spans = listOf(LinkSpan(prefix.length, prefix.length + shown.length, OutputLink.Card(name))))
    }
    /** [text] wrapped to the pane. */
    fun wrap(text: String, tone: Tone = Tone.PLAIN) = wrapWords(text, width, "", "  ").forEach { add(it, tone) }
}

private fun mean(values: List<Double>) = if (values.isEmpty()) 0.0 else Py.sum(values) / values.size
private fun meanInt(values: List<Int>) = if (values.isEmpty()) 0.0 else values.sum().toDouble() / values.size
private fun pct(p: Double) = Py.fixed(p * 100, 0) + "%"
/** A difference in percentage points; one that rounds to nothing is `0p`, never `-0p`. */
private fun points(delta: Double) = if (Math.abs(delta * 100) < 0.5) "0p" else Py.fixed(delta * 100, 0, plus = true) + "p"
private fun reach(p: DeckProfile, role: String) = (p.roleMv[role] ?: emptyMap()).values.sum()

/** `+3`, `-2`, or `=` when there is no difference. */
private fun signed(delta: Double) = when {
    delta > 0 -> "+" + Py.general(delta)
    delta != 0.0 -> Py.general(delta)
    else -> "="
}

/** The roles any of [profiles] can fill at all, in the taxonomy's order. */
private fun present(profiles: List<DeckProfile>) = REPORT.filter { r -> profiles.any { (it.counts[r] ?: 0) > 0 || reach(it, r) > 0 } }

private fun absentNote(r: Report, profiles: List<DeckProfile>, what: String) {
    val absent = REPORT.filter { it !in present(profiles) }
    if (absent.isNotEmpty()) r.note("No $what: ${absent.joinToString(", ") { label(it).lowercase() }}.")
}

private fun unsureAndUnknown(r: Report, profiles: List<DeckProfile>, lowConfidence: Collection<String>) {
    profiles.filter { it.unresolved.isNotEmpty() }.forEach { p ->
        r.wrap("${p.unresolved.size} name(s) in ${p.name} not found, and left out of every number: ${p.unresolved.joinToString(", ")}", Tone.ERROR)
    }
    val unsure = lowConfidence.sorted()
    if (unsure.isEmpty()) return
    r.add()
    r.wrap("Worth checking: nothing could place ${if (unsure.size == 1) "this card" else "these ${unsure.size} cards"}, so ${if (unsure.size == 1) "it counts" else "they count"} as utility.")
    unsure.forEach { r.card("    ", it) }
}

/** The turn columns that fit next to a [lead]-wide label and [tail] more columns: late turns go first. */
private fun fitTurns(turns: List<Int>, width: Int, lead: Int, tail: Int, col: Int): List<Int> {
    var shown = turns
    while (shown.size > 4 && lead + shown.size * col + tail > width) shown = shown.dropLast(1)
    return shown
}

/**
 * The castable-by-turn table: the mean over [profiles] of the chance a role
 * is castable on each turn, then drawn by turn 4 and the gap between the two.
 */
private fun whenTable(r: Report, profiles: List<DeckProfile>, roles: List<String>, turns: List<Int>, onPlay: Boolean) {
    val labelWidth = (roles.map { label(it).length } + "castable by turn".length).max() + 2
    val shown = fitTurns(turns, r.width, labelWidth, 9 + 6, 5)
    r.head("=== WHEN CAN IT HAPPEN? ${if (onPlay) "on the play" else "on the draw"} ===")
    r.add("castable by turn".left(labelWidth) + shown.joinToString("") { "T$it".right(5) } + "drawn T4".right(10) + "gap".right(5))
    for (role in roles) {
        val live = profiles.map { it.liveCurve(role, turns, onPlay) }
        val drawn = mean(profiles.map { it.ceiling(role, turns, onPlay).getValue(4) })
        val castable4 = mean(live.map { it.getValue(4) })
        r.add(label(role).left(labelWidth) + shown.joinToString("") { t -> pct(mean(live.map { it.getValue(t) })).right(5) } +
            pct(drawn).right(10) + (Py.fixed((drawn - castable4) * 100, 0) + "p").right(5))
    }
    r.note("Castable: a card of the role is in hand AND the lands and rocks drawn so far pay for it that turn. " +
        "Drawn T4: one is in hand by turn 4 at all, mana ignored: the most castable can ever be. " +
        "Gap: what the mana costs you on turn 4. A wide gap says the cards are too expensive for the mana; " +
        "a narrow gap with a low number says there are too few of them.")
}

/** Density, reach and the odds for one deck, or side by side for several (a folder). */
fun renderProfile(profiles: List<DeckProfile>, lowConfidence: Collection<String> = emptyList(), turns: List<Int> = DeckProfile.TURNS, onPlay: Boolean = true): Rendering =
    Rendering { width ->
        val r = Report(width)
        if (profiles.size == 1) deckProfile(r, profiles.single(), turns, onPlay) else setProfile(r, profiles, turns, onPlay)
        unsureAndUnknown(r, profiles, lowConfidence)
        r.out
    }

private fun deckProfile(r: Report, p: DeckProfile, turns: List<Int>, onPlay: Boolean) {
    r.wrap("${p.name} · ${p.size} cards · average effective mana value ${Py.fixed(p.avgMv, 2)}", Tone.BOLD)
    r.note("Effective: what a spell really costs to cast for its effect. A pitch spell is 0, delve counts six cards in the yard, {X} is 2.")
    val backs = if (p.landBacks == 0) "" else " (${p.landBackNames.joinToString(", ")}: a spell with a land back, counted as a land)"
    val rocks = if (p.rocks == 0) "" else " + ${p.rocks} rock${if (p.rocks == 1) "" else "s"} / dork${if (p.rocks == 1) "" else "s"} (${p.rockNames.joinToString(", ")})"
    r.wrap("Mana: ${p.lands} lands$backs$rocks = ${p.manaSources} mana sources")

    val roles = present(listOf(p)).sortedWith(compareByDescending<String> { p.counts[it] ?: 0 }.thenByDescending { reach(p, it) })
    val labelWidth = (roles.map { label(it).length } + "Lands".length + "role".length).max() + 2
    r.head("=== WHAT THE CARDS DO ===")
    r.add("role".left(labelWidth) + "cards".right(6) + "also".right(6) + "engines".right(9))
    for (role in roles) {
        val cards = p.counts[role] ?: 0
        val also = reach(p, role) - cards
        val engines = p.engines[role] ?: 0
        r.add(label(role).left(labelWidth) + cards.toString().right(6) + (if (also > 0) "+$also" else "-").right(6) + (if (engines > 0) "$engines" else "-").right(9))
    }
    r.add("Lands".left(labelWidth) + p.lands.toString().right(6))
    r.note("Cards: each card once, under its main job; the column adds up to the deck. " +
        "Also: cards whose main job is another role but that can do this one too (Cryptic Command also draws). " +
        "Engines: permanents that do it again every turn rather than once.")
    absentNote(r, listOf(p), "cards in the deck for")
    whenTable(r, listOf(p), roles.filter { it != "utility" }, turns, onPlay)
}

private fun setProfile(r: Report, profiles: List<DeckProfile>, turns: List<Int>, onPlay: Boolean) {
    r.wrap("${profiles.size} decks, side by side", Tone.BOLD)
    profiles.forEachIndexed { i, p -> r.add("  #${i + 1}  ${p.name}" + if (p.size != 100) "  (${p.size} cards)" else "") }

    val roles = present(profiles)
    val labelWidth = (roles.map { label(it).length } + "Lands + rocks".length + "avg effective MV".length).max() + 2
    val fixed = labelWidth + 6 + 11 + 6 + 9
    val perDeck = fixed + profiles.size * 5 <= r.width
    r.head("=== WHAT THE CARDS DO (cards per deck, by main job) ===")
    r.add("role".left(labelWidth) + "mean".right(6) + "range".right(11) + "also".right(6) + "engines".right(9) +
        if (perDeck) profiles.indices.joinToString("") { "#${it + 1}".right(5) } else "")
    fun row(label: String, values: List<Int>, also: Double? = null, engines: Double? = null) = r.add(
        label.left(labelWidth) + Py.fixed(meanInt(values), 1).right(6) + "${values.min()}-${values.max()}".right(11) +
            (also?.let { if (it > 0) "+" + Py.fixed(it, 1) else "-" } ?: "").right(6) +
            (engines?.let { if (it > 0) Py.fixed(it, 1) else "-" } ?: "").right(9) +
            if (perDeck) values.joinToString("") { it.toString().right(5) } else "",
    )
    for (role in roles) {
        row(label(role), profiles.map { it.counts[role] ?: 0 },
            also = meanInt(profiles.map { reach(it, role) - (it.counts[role] ?: 0) }), engines = meanInt(profiles.map { it.engines[role] ?: 0 }))
    }
    row("Lands", profiles.map { it.lands })
    row("Lands + rocks", profiles.map { it.manaSources })
    val mv = profiles.map { it.avgMv }
    r.add("avg effective MV".left(labelWidth) + Py.fixed(mean(mv), 2).right(6) + "${Py.fixed(mv.min(), 2)}-${Py.fixed(mv.max(), 2)}".right(11) +
        "".right(15) + if (perDeck) mv.joinToString("") { Py.fixed(it, 2).right(5) } else "")
    r.note("Mean and range: over the decks. Also and engines are means too; see a single deck's profile for what they count." +
        if (perDeck) "" else " One column per deck needs ${fixed + profiles.size * 5} columns: widen the window to see them.")
    absentNote(r, profiles, "deck has cards for")
    whenTable(r, profiles, roles.filter { it != "utility" }, turns, onPlay)
}

/** How many of [lists] decks play each card, per role, grouped by effective cost. */
fun renderRanking(ranking: Ranking, lists: Int): Rendering = Rendering { width ->
    val r = Report(width)
    r.head("=== MOST PLAYED, per role (by effective mana value) ===")
    for (role in REPORT) {
        val rows = ranking.byRole[role].orEmpty()
        if (rows.isEmpty()) continue
        r.add()
        r.add("  -- ${label(role)} --")
        var last: Int? = null
        for (row in rows) {
            if (row.mv != last) {
                r.add("     MV ${row.mv}")
                last = row.mv
            }
            val prefix = "       ${row.n.toString().right(2)}/$lists  "
            val shown = row.name.take(maxOf(12, minOf(44, width - prefix.length - 1)))
            val tag = if (row.primary) "" else "  (also)"
            val why = if (row.reason.isNotEmpty()) "   [${row.reason}]" else ""
            r.card(prefix, row.name, shown, " ".repeat(maxOf(1, 46 - shown.length)) + row.cost.left(16) + tag + why)
        }
    }
    r.note("n/$lists: how many of the decks play the card. (also): the card is here for a side job, its main one is another role. " +
        "[...]: why its effective cost differs from the printed one.")
    r.out
}

private val VERDICT_MARK = mapOf("under" to "<- below all", "over" to "-> above all", "in" to "")

/**
 * One deck against a reference set: ranges, the odds, the card diff. Against
 * ONE deck it is a head-to-head instead: with one list the range is a point,
 * and every difference would read as stepping outside it.
 */
fun renderComparison(cmp: Comparison, turns: List<Int> = DeckProfile.TURNS, onPlay: Boolean = true): Rendering = Rendering { width ->
    val r = Report(width)
    val subj = cmp.subject
    val refs = cmp.reference
    val solo = refs.size == 1
    val all = listOf(subj) + refs
    val roles = present(all)
    r.add()
    r.wrap(if (solo) "${subj.name} against ${refs[0].name}, head to head" else "${subj.name} against ${refs.size} reference decks", Tone.BOLD)

    val labelWidth = (roles.map { label(it).length } + "avg effective MV".length).max() + 2
    fun roleRow(label: String, d: RoleDelta) = if (solo) {
        label.left(labelWidth) + d.subject.toString().right(7) + Py.fixed(d.refMean, 0).right(8) + signed(d.delta).right(7)
    } else {
        label.left(labelWidth) + d.subject.toString().right(7) + Py.fixed(d.refMean, 1).right(8) + "${d.refMin}-${d.refMax}".right(8) +
            signed(d.delta).right(7) + "  " + VERDICT_MARK.getValue(d.verdict)
    }
    r.head("=== WHAT THE CARDS DO (cards by main job) ===")
    r.add("role".left(labelWidth) + "yours".right(7) + (if (solo) "theirs".right(8) else "mean".right(8) + "range".right(8)) + "delta".right(7))
    for (role in roles) cmp.role(role)?.let { r.add(roleRow(label(role), it)) }
    cmp.role("land")?.let { r.add(roleRow("Lands", it)) }
    r.add(roleRow("Lands + rocks", cmp.manaSources))
    val (mine, theirs) = cmp.avgMv
    r.add("avg effective MV".left(labelWidth) + Py.fixed(mine, 2).right(7) + Py.fixed(theirs, 2).right(8) +
        (if (solo) "" else "".right(8)) + signed(Py.round(mine - theirs, 2)).right(7))
    if (solo) {
        r.note("Delta: yours minus theirs.")
    } else {
        r.note("Mean and range: over the reference decks. \"below all\" / \"above all\": no reference deck went that far, which is a choice worth a second look.")
        val out = cmp.outOfRange
        r.wrap(if (out.isEmpty()) "Inside the reference range on every role." else "Outside the reference range: ${out.joinToString(", ") { label(it.role).takeIf { _ -> it.role != "land" } ?: "Lands" }}.")
        // A reference set spanning two builds has a mean that describes neither.
        val widest = cmp.roles.maxBy { it.refMax - it.refMin }
        if (widest.refMax - widest.refMin >= 6) {
            r.wrap("Careful: the reference decks run ${widest.refMin} to ${widest.refMax} ${label(widest.role).lowercase()}, so they hold more than one build " +
                "and their mean describes neither. The nearest deck below is the better guide.")
        }
        r.head("=== NEAREST REFERENCE DECK ===")
        cmp.nearest.take(5).forEach { (name, dist) -> r.add("   ${Py.fixed(dist, 2).right(6)}  $name") }
        r.note("How far apart the role counts are: 0 is the same deck by role.")
    }

    val turnRoles = roles.filter { it != "utility" }
    val labelTurns = (turnRoles.map { label(it).length } + "castable, yours minus".length).max() + 2
    val shown = fitTurns(turns, width, labelTurns, 0, 6)
    r.head("=== WHEN CAN IT HAPPEN? ${if (onPlay) "on the play" else "on the draw"} ===")
    r.add("castable, yours minus".left(labelTurns) + shown.joinToString("") { "T$it".right(6) })
    for (role in turnRoles) {
        val d = cmp.curveDelta[role].orEmpty()
        r.add(label(role).left(labelTurns) + shown.joinToString("") { points(d[it] ?: 0.0).right(6) })
    }
    r.note("Percentage points: how much more often (+) or less often (-) your deck can cast the role on that turn than ${if (solo) "theirs" else "the reference mean"}.")

    if (cmp.missing.isNotEmpty()) {
        // "Played by more than one deck" would hide everything when there is only one deck to be played by.
        val shownCards = if (solo) cmp.missing else cmp.missing.filter { it.nLists > 1 }.ifEmpty { cmp.missing }
        r.head("=== ${if (solo) "CARDS THEY PLAY" else "CARDS THE REFERENCE PLAYS"} THAT YOU DON'T (${cmp.missing.size}) ===")
        for (role in REPORT + "land") {
            val group = shownCards.filter { it.role == role }
            if (group.isEmpty()) continue
            r.add()
            r.add("  -- ${if (role == "land") "Lands" else label(role)}")
            group.forEach { c ->
                val count = if (solo) "" else "${c.nLists.toString().right(2)}/${c.ofLists}  "
                r.card("       ${count}MV${c.mv.toString().left(3)} ", c.name)
            }
        }
        if (shownCards.size < cmp.missing.size) r.note("${cmp.missing.size - shownCards.size} more are played by only one reference deck.")
    }

    if (cmp.unique.isNotEmpty()) {
        r.head("=== CARDS ONLY YOU PLAY (${cmp.unique.size}) ===")
        r.note("Not a criticism: this is where your build is its own thing.")
        val w = (cmp.unique.map { (if (it.role == "land") "Lands" else Roles.LABELS[it.role]?.let { _ -> label(it.role) } ?: it.role).length }.maxOrNull() ?: 0) + 1
        cmp.unique.forEach { c ->
            val role = if (c.role == "land") "Lands" else Roles.LABELS[c.role]?.let { label(c.role) } ?: c.role
            r.card("       ${role.left(w)} MV${c.mv.toString().left(3)} ", c.name)
        }
    }
    r.out
}
