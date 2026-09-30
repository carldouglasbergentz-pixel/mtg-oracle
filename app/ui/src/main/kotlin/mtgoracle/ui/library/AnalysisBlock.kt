package mtgoracle.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import mtgoracle.core.analysis.DeckInsight
import mtgoracle.core.analysis.Py
import mtgoracle.core.analysis.Roles
import mtgoracle.core.analysis.right
import mtgoracle.ui.kit.BoxPane
import mtgoracle.ui.kit.region
import mtgoracle.ui.lookup.LinkSpan
import mtgoracle.ui.lookup.OutLine
import mtgoracle.ui.lookup.OutputLineView
import mtgoracle.ui.lookup.OutputLink
import mtgoracle.ui.lookup.Tone

/** Role names short enough for one line of the block. */
private val SHORT = mapOf(
    "land" to "Lands", "mana" to "Mana", "ritual" to "Rituals", "counter" to "Counters", "sweeper" to "Sweepers",
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

/**
 * The block above a deck: what it is made of and when it works, so a change
 * shows at once. Its lines as text for a [width], links for the actions.
 * Plain data in, lines out: the composable only draws them.
 */
fun insightLines(insight: DeckInsight, width: Int): List<OutLine> {
    val a = insight.analytics
    val p = insight.profile
    val out = mutableListOf<OutLine>()
    fun add(text: String, tone: Tone = Tone.PLAIN) { out += OutLine(text, tone) }

    val lands = if (a.mdfcLandCount > 0) "${a.landCount} lands (${a.landTotal} with MDFC)" else "${a.landCount} lands"
    val mv = "avg MV ${Py.fixed(a.mvAvg, 2)}" + if (Py.round(p.avgMv, 2) != Py.round(a.mvAvg, 2)) " (effective ${Py.fixed(p.avgMv, 2)})" else ""
    pack(listOf("${p.size} cards", lands, "${a.nonlandCount} spells", mv) + (if (p.rocks > 0) listOf("${p.rocks} rocks") else emptyList()), width, " · ")
        .forEach { add(it) }

    // The curve as a small table; pips and sources beside it while they fit, under it when not.
    val heads = "curve " + listOf("0", "1", "2", "3", "4", "5", "6+").joinToString("") { it.right(3) }
    val counts = "      " + a.manaCurve.joinToString("") { it.toString().right(3) }
    val pips = if (a.pipTotal > 0) "pips (${a.pipTotal})  " + "WUBRGC".filter { (a.colorPips[it] ?: 0) > 0 }.map { "$it:${a.colorPips[it]}" }.joinToString(" ") else ""
    val sources = if (a.landTotal > 0) "sources (${a.landTotal})  " + "WUBRGC".filter { (a.manaSources[it] ?: 0) > 0 }.map { "$it:${a.manaSources[it]}" }.joinToString(" ") else ""
    val gap = "    "
    if (heads.length + gap.length + maxOf(pips.length, sources.length) <= width) {
        add(heads + gap + pips)
        add(counts.padEnd(heads.length) + gap + sources)
    } else {
        add(heads, Tone.PLAIN); add(counts)
        listOf(pips, sources).filter { it.isNotEmpty() }.forEach { add(it) }
    }

    // What the cards do, the most common first; and how often each is castable early, on the play.
    val roles = Roles.REPORT_ROLES.filter { (p.counts[it] ?: 0) > 0 }.sortedByDescending { p.counts.getValue(it) }
    pack(roles.map { "${SHORT.getValue(it)} ${p.counts.getValue(it)}" }, width, " · ", lead = "roles   ", hang = "        ").forEach { add(it) }
    val odds = roles.filter { it != "utility" }.map { role ->
        val live = p.liveCurve(role, QUOTED_TURNS)
        "${SHORT.getValue(role)} " + QUOTED_TURNS.joinToString("/") { Py.fixed(live.getValue(it) * 100, 0) } + "%"
    }
    if (odds.isNotEmpty()) {
        val lead = "by T${QUOTED_TURNS.joinToString("/T")}  "
        pack(odds, width, " · ", lead = lead, hang = " ".repeat(lead.length)).forEach { add(it, Tone.DIM) }
    }

    // The actions, as links: the full report, the combos, and the cards worth a look.
    val actions = listOfNotNull(
        "[ full profile ]" to OutputLink.Run("profile"),
        (if (insight.combos == 0) "[ no combos in the deck ]" else "[ ${insight.combos} combo${if (insight.combos == 1) "" else "s"} in the deck ]") to
            OutputLink.Run("combos"),
        insight.unsure.takeIf { it.isNotEmpty() }?.let { "[ ${it.size} card${if (it.size == 1) "" else "s"} unplaced ]" to OutputLink.Run("profile") },
    )
    val line = StringBuilder()
    val spans = mutableListOf<LinkSpan>()
    for ((text, link) in actions) {
        if (line.isNotEmpty() && line.length + 2 + text.length > width) {
            out += OutLine(line.toString(), spans = spans.toList())
            line.setLength(0)
            spans.clear()
        }
        if (line.isNotEmpty()) line.append("  ")
        spans += LinkSpan(line.length, line.length + text.length, link)
        line.append(text)
    }
    out += OutLine(line.toString(), spans = spans.toList())
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
