package mtgoracle.ui.lookup

import mtgoracle.core.analysis.left
import mtgoracle.core.analysis.right
import mtgoracle.core.sync.SyncReport

/**
 * A sync's changelog (sync.py's `=== changelog ===`): per keyed table what
 * was added, removed and modified; per wipe-and-rebuild table the net change;
 * then what failed and what only an ingest could see, and each source's
 * upstream marker.
 */
fun renderSyncReport(report: SyncReport): Rendering = Rendering { width ->
    val out = mutableListOf<OutLine>()
    fun add(text: String = "", tone: Tone = Tone.PLAIN) { out += OutLine(text.trimEnd(), tone) }
    fun signed(n: Int) = if (n == 0) "0" else "%+,d".format(n)
    fun note(text: String) = wrapWords(text, width, "  ", "    ").forEach { add(it, Tone.DIM) }

    add("=== sync: ${report.ran.joinToString(", ") { it.key }} ===", Tone.BOLD)
    add("table".left(12) + "added".right(9) + "removed".right(9) + "modified".right(10) + "total".right(11))
    for (c in report.changes) {
        if (c.net == null) {
            add(c.table.left(12) + signed(c.added ?: 0).right(9) + signed(-(c.removed ?: 0)).right(9) + (c.modified?.let(::signed) ?: "-").right(10) + "%,d".format(c.total).right(11))
        } else {
            add(c.table.left(12) + "(net ${signed(c.net!!)})".right(28) + "%,d".format(c.total).right(11))
        }
    }
    add()
    when {
        !report.touched && report.failures.isNotEmpty() -> add("No changes recorded: ${report.failures.size} source(s) failed.", Tone.ERROR)
        !report.touched -> add("No changes: every source was up to date.", Tone.DIM)
    }
    report.failures.forEach { (source, why) -> wrapWords("${source.key} failed: $why", width, "", "  ").forEach { add(it, Tone.ERROR) } }
    report.notes.forEach { (source, lines) -> lines.forEach { note("${source.key}: $it") } }
    note("Cards, rules and combos by key; rulings, tags, abilities, points and oracle tags are rebuilt whole, so only the net change shows. Decks are never touched.")
    add()
    add("upstream markers", Tone.BOLD)
    report.state.forEach { (source, marker, rows) -> add("  " + source.left(24) + (marker ?: "-").left(28) + (rows?.let { "%,d rows".format(it) } ?: "")) }
    out
}
