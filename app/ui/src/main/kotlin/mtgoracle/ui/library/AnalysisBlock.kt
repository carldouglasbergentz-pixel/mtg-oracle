package mtgoracle.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import mtgoracle.core.analysis.DeckInsight
import mtgoracle.core.analysis.Py
import mtgoracle.core.analysis.Roles
import mtgoracle.core.analysis.left
import mtgoracle.ui.kit.BoxPane
import mtgoracle.ui.kit.region
import mtgoracle.ui.lookup.LinkSpan
import mtgoracle.ui.lookup.OutLine
import mtgoracle.ui.lookup.OutputLineView
import mtgoracle.ui.lookup.OutputLink
import mtgoracle.ui.lookup.Tone

/** Role names short enough for one line of the block. */
private val SHORT = mapOf(
    "land" to "Lands", "mana" to "Rocks/dorks", "ritual" to "Rituals", "counter" to "Counters", "sweeper" to "Sweepers",
    "discard" to "Discard", "spot" to "Removal", "burn" to "Burn", "tutor" to "Tutors", "recursion" to "Recursion",
    "threat" to "Threats", "draw" to "Card adv.", "cantrip" to "Cantrips", "utility" to "Utility",
)

/** The turns the block quotes odds for: the early game, where a change of one card shows. */
private val QUOTED_TURNS = listOf(2, 4)

/** [parts] onto as few lines as fit [width], never splitting a part; continuation lines under [hang]. */
private fun pack(parts: List<String>, width: Int, sep: String, lead: String = "", hang: String = lead): List<String> {
    val lines = mutableListOf<String>()
    val line = StringBuilder(lead)
    var empty = true
    for (part in parts) {
        if (!empty && line.length + sep.length + part.length > width) {
            lines += line.toString()
            line.setLength(0)
            line.append(hang)
            empty = true
        }
        if (!empty) line.append(sep)
        line.append(part)
        empty = false
    }
    lines += line.toString()
    return lines
}

/** The block's labels, `by T2/T4` the widest: every row's content starts in the column after them. */
private val LABEL = "by T${QUOTED_TURNS.joinToString("/T")}".length + 2

private fun label(text: String) = text.left(LABEL)

/**
 * One count per colour, `U:66`, each colour in a slot of its own across [rows] so U stands over U;
 * a colour a row lacks leaves its slot blank. A slot is its widest count and two spaces.
 */
private fun colourSlots(rows: List<Map<Char, Int>>): List<List<String>> {
    val colours = "WUBRGC".filter { c -> rows.any { (it[c] ?: 0) > 0 } }
    val slot = colours.associateWith { c -> rows.maxOf { "$c:${it[c] ?: 0}".length } + 2 }
    return rows.map { counts -> colours.map { c -> (counts[c]?.takeIf { it > 0 }?.let { "$c:$it" } ?: "").left(slot.getValue(c)) } }
}

/** A pips or sources row: [lead] then its slots, or, where the pane is too narrow for the slots, the counts packed under the content column. */
private fun slotRow(lead: String, slots: List<String>, width: Int): List<String> {
    val line = (lead + slots.joinToString("")).trimEnd()
    return if (line.length <= width) listOf(line) else pack(slots.map { it.trim() }.filter { it.isNotEmpty() }, width, " ", lead = lead, hang = " ".repeat(LABEL))
}

/**
 * The block above a deck: what it is made of and when it works, so a change
 * shows at once. Its lines as text for a [width], links for the actions.
 * Plain data in, lines out: the composable only draws them. Every row's
 * label is in one column and its content in the next, headers and values
 * left-aligned alike, so the first characters line up.
 */
fun insightLines(insight: DeckInsight, width: Int): List<OutLine> {
    val a = insight.analytics
    val p = insight.profile
    val out = mutableListOf<OutLine>()
    fun add(text: String, tone: Tone = Tone.PLAIN) { out += OutLine(text, tone) }
    val empty = insight.size.main + insight.size.commanders == 0

    val lands = if (a.mdfcLandCount > 0) "${a.landCount} lands (${a.landTotal} with MDFC)" else "${a.landCount} lands"
    val mv = "avg MV ${Py.fixed(a.mvAvg, 2)}" + if (Py.round(p.avgMv, 2) != Py.round(a.mvAvg, 2)) " (effective ${Py.fixed(p.avgMv, 2)})" else ""
    val facts = if (empty) listOf(insight.size.label)
    else listOfNotNull(insight.size.label, lands, "${a.nonlandCount} spells", mv.takeIf { a.nonlandCount > 0 },
        p.rocks.takeIf { it > 0 }?.let { "$it rock${if (it == 1) "" else "s"}" })
    pack(facts, width, " · ").forEach { add(it) }

    // The curve as a small table; pips and sources beside it while they fit, under it when not.
    if (a.manaCurve.sum() > 0 || a.pipTotal > 0 || a.landTotal > 0) {
        val heads = label("curve") + listOf("0", "1", "2", "3", "4", "5", "6+").joinToString("") { it.left(3) }
        val counts = label("cards") + a.manaCurve.joinToString("") { it.toString().left(3) }
        val totals = listOf("(${a.pipTotal})", "(${a.landTotal})")
        val totalW = totals.maxOf { it.length } + 2
        val (pipSlots, sourceSlots) = colourSlots(listOf(a.colorPips, a.manaSources))
        val pipLead = label("pips") + totals[0].left(totalW)
        val sourceLead = label("sources") + totals[1].left(totalW)
        val gap = "    "
        val beside = pipLead.length + maxOf(pipSlots.joinToString("").trimEnd().length, sourceSlots.joinToString("").trimEnd().length)
        if (heads.length + gap.length + beside <= width) {
            add((heads + gap + if (a.pipTotal > 0) pipLead + pipSlots.joinToString("") else "").trimEnd())
            add((counts.padEnd(heads.length) + gap + if (a.landTotal > 0) sourceLead + sourceSlots.joinToString("") else "").trimEnd())
        } else {
            add(heads.trimEnd()); add(counts.trimEnd())
            if (a.pipTotal > 0) slotRow(pipLead, pipSlots, width).forEach { add(it) }
            if (a.landTotal > 0) slotRow(sourceLead, sourceSlots, width).forEach { add(it) }
        }
    }

    // What the cards do, the most common first; and how often each is castable early, on the play.
    val roles = Roles.REPORT_ROLES.filter { (p.counts[it] ?: 0) > 0 }.sortedByDescending { p.counts.getValue(it) }
    val hang = " ".repeat(LABEL)
    if (roles.isNotEmpty()) pack(roles.map { "${SHORT.getValue(it)} ${p.counts.getValue(it)}" }, width, " · ", lead = label("roles"), hang = hang).forEach { add(it) }
    val odds = roles.filter { it != "utility" }.map { role ->
        val live = p.liveCurve(role, QUOTED_TURNS)
        "${SHORT.getValue(role)} " + QUOTED_TURNS.joinToString("/") { Py.fixed(live.getValue(it) * 100, 0) } + "%"
    }
    if (odds.isNotEmpty()) pack(odds, width, " · ", lead = label("by T${QUOTED_TURNS.joinToString("/T")}"), hang = hang).forEach { add(it, Tone.DIM) }

    // The actions, as links: the full report, the combos, the cards nothing placed. No combos is a fact, not a link.
    val unsure = insight.unsure.takeIf { it.isNotEmpty() }?.let { names ->
        "[ ${names.size} card${if (names.size == 1) "" else "s"} unplaced ]" to
            OutputLink.Cards("${p.name}: unplaced, so counted as utility", names)
    }
    val actions = listOfNotNull(
        "[ full profile ]" to OutputLink.Run("profile"),
        insight.combos.takeIf { it > 0 }?.let { n -> "[ $n combo${if (n == 1) "" else "s"} in the deck ]" to OutputLink.Run("combos") },
        unsure,
        ("no combos in the deck" to null).takeIf { insight.combos == 0 },
    )
    val line = StringBuilder()
    val spans = mutableListOf<LinkSpan>()
    var dimFrom: Int? = null
    fun flush() { out += OutLine(line.toString(), spans = spans.toList(), dimFrom = dimFrom); line.setLength(0); spans.clear(); dimFrom = null }
    for ((text, link) in actions) {
        if (line.isNotEmpty() && line.length + 2 + text.length > width) flush()
        if (line.isNotEmpty()) line.append("  ")
        if (link != null) spans += LinkSpan(line.length, line.length + text.length, link) else dimFrom = line.length
        line.append(text)
    }
    flush()
    // A name the database lacks is left out of every number above, so it is named.
    if (p.unresolved.isNotEmpty()) {
        pack(p.unresolved, width, ", ", lead = "unknown (${p.unresolved.size}): ", hang = "  ").forEach { add(it, Tone.ERROR) }
    }
    return out
}

/**
 * The analysis block, fixed at the top of the middle column: above the deck
 * in the library, above the search in the workspace. It does not scroll with
 * what is under it, so an edit's effect is always in view.
 */
@Composable
internal fun AnalysisPane(insight: DeckInsight?, cols: Int, onOpen: (OutputLink) -> Unit, modifier: Modifier = Modifier) {
    if (insight == null) return
    BoxPane("analysis", modifier.region("analysis")) {
        Column {
            insightLines(insight, cols - 2).forEachIndexed { i, line ->
                OutputLineView(line, ANALYSIS_TARGETS + i * 10L, onOpen, onHover = {})
            }
        }
    }
}

/** Click-target ids of the block's links, apart from the output pane's (entry id × 100 000). */
private const val ANALYSIS_TARGETS = -1_000_000L
