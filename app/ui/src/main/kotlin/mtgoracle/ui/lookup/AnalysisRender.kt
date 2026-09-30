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
 * The archetype analysis tables (renderer.render_profile / render_ranking /
 * render_comparison), byte for byte: the columns are fixed, so a table wider
 * than the pane runs past its edge rather than wrapping into nonsense.
 * Section heads are bold and card names are links.
 */

private const val ROLE_COL = 26
private const val TURN_COL = 7

private val REPORT = Roles.REPORT_ROLES
private val LABELS = Roles.LABELS

/** Lines in Python's layout: never wrapped, `===` heads in bold. */
private class Table {
    val out = mutableListOf<OutLine>()
    fun add(text: String = "") {
        out += OutLine(text, if (text.startsWith("===") || text.startsWith("HEAD TO HEAD") || text.startsWith("COMPARISON")) Tone.BOLD else Tone.PLAIN)
    }
    /** [prefix] then a card name that opens the card, then [suffix]. */
    fun card(prefix: String, name: String, shown: String = name, suffix: String = "") {
        out += OutLine(prefix + shown + suffix, spans = listOf(LinkSpan(prefix.length, prefix.length + shown.length, OutputLink.Card(name))))
    }
}

private fun mean(values: List<Double>) = if (values.isEmpty()) 0.0 else Py.sum(values) / values.size
private fun meanInt(values: List<Int>) = if (values.isEmpty()) 0.0 else values.sum().toDouble() / values.size

private fun median(values: List<Int>): Double {
    val s = values.sorted()
    if (s.isEmpty()) return 0.0
    val mid = s.size / 2
    return if (s.size % 2 == 1) s[mid].toDouble() else (s[mid - 1] + s[mid]) / 2.0
}

/** `+3`, `-2`, or `=` when there is no difference. */
private fun signed(delta: Double) = when {
    delta > 0 -> "+" + Py.general(delta)
    delta != 0.0 -> Py.general(delta)
    else -> "="
}

private fun f(x: Double, digits: Int, width: Int, plus: Boolean = false) = Py.fixed(x, digits, plus).right(width)
private fun turnHeads(turns: List<Int>) = turns.joinToString("") { "T$it".right(TURN_COL) }

/** Density, reach, on-curve and ceiling tables for one or more decks; [lowConfidence] are the cards nothing could place. */
fun renderProfile(profiles: List<DeckProfile>, lowConfidence: Collection<String> = emptyList(), turns: List<Int> = DeckProfile.TURNS, onPlay: Boolean = true): Rendering {
    val t = Table()
    val ids = profiles.map { it.name }
    t.add("${profiles.size} list(s): ${ids.joinToString(", ")}")
    val odd = profiles.filter { it.size != 100 }
    if (odd.isNotEmpty()) t.add("  note: not 100 cards -> {" + odd.joinToString(", ") { "${Py.repr(it.name)}: ${it.size}" } + "}")
    profiles.filter { it.unresolved.isNotEmpty() }.forEach { p ->
        t.add("  ${p.name}: ${p.unresolved.size} unresolved -> ${p.unresolved.take(6).joinToString(", ")}")
    }
    val unsure = lowConfidence.sorted()
    if (unsure.isNotEmpty()) {
        t.add()
        t.add("  ${unsure.size} card(s) fell through to 'utility' - classification worth checking:")
        unsure.forEach { t.card("    ", it) }
    }

    t.add()
    t.add("=== DENSITY (primary role; sums to deck size) ===")
    t.add("role".left(ROLE_COL) + "mean".right(6) + "med".right(5) + "min".right(5) + "max".right(5) + "   " +
        ids.joinToString(" ") { it.takeLast(6).right(6) })
    // A role every list has zero of is noise in a nine-column table.
    val rows = REPORT.filter { r -> profiles.any { (it.counts[r] ?: 0) != 0 } } + "land"
    fun statRow(label: String, values: List<Int>) = label.left(ROLE_COL) + f(meanInt(values), 1, 6) + f(median(values), 0, 5) +
        values.min().toString().right(5) + values.max().toString().right(5) + "   " + values.joinToString(" ") { it.toString().right(6) }
    for (role in rows) t.add(statRow(LABELS.getValue(role), profiles.map { it.counts[role] ?: 0 }))
    t.add(statRow("MANA SOURCES", profiles.map { it.manaSources }))
    val mv = profiles.map { it.avgMv }
    t.add("avg effective MV".left(ROLE_COL) + f(mean(mv), 2, 6) + "".right(5) + f(mv.min(), 2, 5) + f(mv.max(), 2, 5) + "   " +
        mv.joinToString(" ") { f(it, 2, 6) })

    t.add()
    t.add("=== REACH (every role a card can fill, not just its primary) ===")
    t.add("role".left(ROLE_COL) + "primary".right(8) + "reach".right(7) + "diff".right(7) + "engines".right(9))
    for (role in REPORT) {
        val prim = meanInt(profiles.map { it.counts[role] ?: 0 })
        val reach = meanInt(profiles.map { (it.roleMv[role] ?: emptyMap()).values.sum() })
        val eng = meanInt(profiles.map { it.engines[role] ?: 0 })
        t.add(LABELS.getValue(role).left(ROLE_COL) + f(prim, 1, 8) + f(reach, 1, 7) + f(reach - prim, 1, 7, plus = true) +
            (if (eng != 0.0) Py.fixed(eng, 1) else "-").right(9))
    }
    t.add("  (engines = permanents that keep producing the effect rather than resolving once;")
    t.add("   a planeswalker that draws every turn is not interchangeable with Memory Deluge)")

    val label = if (onPlay) "on the play" else "on the draw"
    t.add()
    t.add("=== ON CURVE - role is playable on turn T, $label ===")
    t.add("role".left(ROLE_COL) + turnHeads(turns))
    val live = REPORT.associateWith { role -> profiles.map { it.liveCurve(role, turns, onPlay) } }
    for (role in REPORT) {
        val curves = live.getValue(role)
        t.add(LABELS.getValue(role).left(ROLE_COL) + turns.joinToString("") { tt -> f(mean(curves.map { it.getValue(tt) }) * 100, 0, 6) + "%" })
    }

    t.add()
    t.add("=== CEILING - holding one at all, mana ignored ===")
    t.add("role".left(ROLE_COL) + turnHeads(turns) + "gap T4".right(8))
    for (role in REPORT) {
        val ceilings = profiles.map { it.ceiling(role, turns, onPlay) }
        val means = turns.map { tt -> mean(ceilings.map { it.getValue(tt) }) }
        // The gap is what mana costs you: held by turn 4 minus castable then.
        val gap = if (turns.size > 3) means[3] - mean(live.getValue(role).map { it.getValue(turns[3]) }) else 0.0
        t.add(LABELS.getValue(role).left(ROLE_COL) + means.joinToString("") { f(it * 100, 0, 6) + "%" } + f(gap * 100, 0, 7) + "p")
    }
    return Rendering { t.out }
}

/** How many of [lists] lists play each card, per role, grouped by effective cost. */
fun renderRanking(ranking: Ranking, lists: Int): Rendering {
    val t = Table()
    t.add("=== MOST PLAYED per role (sorted by effective mana value) ===")
    for (role in REPORT) {
        val rows = ranking.byRole[role].orEmpty()
        if (rows.isEmpty()) continue
        t.add()
        t.add("  -- ${LABELS.getValue(role)} --")
        var last: Int? = null
        for (row in rows) {
            if (row.mv != last) {
                t.add("     MV ${row.mv}")
                last = row.mv
            }
            val shown = row.name.take(44)
            val tag = if (row.primary) "" else "  (secondary)"
            val why = if (row.reason.isNotEmpty()) "   [${row.reason}]" else ""
            t.card("       ${row.n.toString().right(2)}/$lists  ", row.name, shown, " ".repeat(46 - shown.length) + row.cost.left(16) + tag + why)
        }
    }
    return Rendering { t.out }
}

private val VERDICT_MARK = mapOf("under" to "<-- BELOW every list", "over" to "--> ABOVE every list", "in" to "")

/**
 * One deck against a reference set: ranges, curve deltas, card diff. Against
 * ONE deck it is a head-to-head instead: with one list the range is a point,
 * and every difference would read as stepping outside it.
 */
fun renderComparison(cmp: Comparison, turns: List<Int> = DeckProfile.TURNS, onPlay: Boolean = true): Rendering {
    val t = Table()
    val subj = cmp.subject
    val refs = cmp.reference
    val n = refs.size
    val solo = n == 1
    t.add()
    t.add("=".repeat(76))
    t.add(if (solo) "HEAD TO HEAD - ${Py.repr(subj.name)} against ${Py.repr(refs[0].name)}" else "COMPARISON - ${Py.repr(subj.name)} against $n reference list(s)")
    t.add("=".repeat(76))
    if (subj.size != 100) {
        t.add("  note: subject is ${subj.size} cards; the draw maths still treats the deck as 100, so the unfilled slots count as blanks")
    }

    t.add()
    if (solo) {
        t.add("=== ROLE COUNTS ===")
        t.add("role".left(ROLE_COL) + "yours".right(7) + "theirs".right(10) + "delta".right(8))
    } else {
        t.add("=== IN RANGE? (range, not mean - a range nobody left is the rule) ===")
        t.add("role".left(ROLE_COL) + "yours".right(7) + "ref mean".right(10) + "range".right(10) + "delta".right(8) + "   verdict")
    }
    val order = (REPORT + "land").withIndex().associate { (i, r) -> r to i }
    fun roleRow(label: String, d: RoleDelta) = if (solo) {
        label.left(ROLE_COL) + d.subject.toString().right(7) + f(d.refMean, 0, 10) + signed(d.delta).right(8)
    } else {
        label.left(ROLE_COL) + d.subject.toString().right(7) + f(d.refMean, 1, 10) + "${d.refMin}-${d.refMax}".right(10) +
            signed(d.delta).right(8) + "   " + VERDICT_MARK.getValue(d.verdict)
    }
    cmp.roles.sortedBy { order[it.role] ?: 99 }.forEach { t.add(roleRow(LABELS.getValue(it.role), it)) }
    t.add(roleRow("MANA SOURCES", cmp.manaSources))
    val (mine, theirs) = cmp.avgMv
    t.add("avg effective MV".left(ROLE_COL) + f(mine, 2, 7) + f(theirs, 2, 10) + signed(Py.round(mine - theirs, 2)).right(if (solo) 8 else 18))

    if (solo) {
        t.add()
        t.add("  role-density distance: ${Py.fixed(cmp.nearest[0].second, 2)}   (0 would be the same 100 cards by role)")
    } else {
        val out = cmp.outOfRange
        t.add()
        t.add("  ${out.size} role(s) outside the reference range" +
            if (out.isNotEmpty()) ": ${out.joinToString(", ") { LABELS.getValue(it.role) }}" else " - this deck sits inside the archetype on every axis")
        // A reference set spanning two archetypes has a mean that describes neither.
        val widest = cmp.roles.maxBy { it.refMax - it.refMin }
        if (widest.refMax - widest.refMin >= 6) {
            t.add()
            t.add("  CAUTION: the reference set spans ${widest.refMin}-${widest.refMax} on ${LABELS.getValue(widest.role).lowercase()}, so it holds more than one")
            t.add("  build and the mean above describes neither. Use the nearest-list line, or re-run")
            t.add("  with only the lists you actually want to resemble.")
        }
        t.add()
        t.add("=== NEAREST REFERENCE LIST (role-density distance) ===")
        cmp.nearest.take(5).forEach { (name, dist) -> t.add("   ${f(dist, 2, 6)}  $name") }
        t.add("   (lower is more alike; the axis with the widest spread in the reference set dominates, which is the axis that defines the build)")
    }

    val label = if (onPlay) "on the play" else "on the draw"
    t.add()
    t.add("=== ON CURVE, yours minus ${if (solo) "theirs" else "the reference mean"} ($label) ===")
    t.add("role".left(ROLE_COL) + turnHeads(turns))
    for (role in REPORT) {
        val d = cmp.curveDelta[role].orEmpty()
        t.add(LABELS.getValue(role).left(ROLE_COL) + turns.joinToString("") { f((d[it] ?: 0.0) * 100, 0, 6, plus = true) + "p" })
    }
    t.add("  (percentage points; + means this deck does it more often)")

    if (cmp.missing.isNotEmpty()) {
        // "Played by more than one list" would hide everything when there is only one list to be played by.
        val shown = if (solo) cmp.missing else cmp.missing.filter { it.nLists > 1 }.ifEmpty { cmp.missing }
        t.add()
        t.add("=== ${if (solo) "CARDS THEY PLAY" else "CARDS THE REFERENCE PLAYS"} THAT THIS DECK DOESN'T (${cmp.missing.size}) ===")
        for (role in REPORT + "land") {
            val group = shown.filter { it.role == role }
            if (group.isEmpty()) continue
            t.add()
            t.add("  -- ${LABELS.getValue(role)}")
            group.forEach { c ->
                val count = if (solo) "" else "${c.nLists.toString().right(2)}/${c.ofLists}  "
                t.card("       ${count}MV${c.mv.toString().left(3)} ", c.name)
            }
        }
        if (shown.size < cmp.missing.size) {
            t.add()
            t.add("  (${cmp.missing.size - shown.size} more played by exactly one list - pass --min-share 0 and read the JSON for those)")
        }
    }

    if (cmp.unique.isNotEmpty()) {
        t.add()
        t.add("=== CARDS ONLY THIS DECK PLAYS (${cmp.unique.size}) ===")
        t.add("  Not a criticism - this is where your build is its own thing.")
        cmp.unique.forEach { c -> t.card("       ${(LABELS[c.role] ?: c.role).left(ROLE_COL)} MV${c.mv.toString().left(3)} ", c.name) }
    }
    return Rendering { t.out }
}
