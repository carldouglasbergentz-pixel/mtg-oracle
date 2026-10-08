package mtgoracle.ui.lookup

import mtgoracle.core.lookup.PoolSort
import mtgoracle.core.deck.DeckSection
import mtgoracle.core.lookup.SearchPage

private const val INDENT = "  "
private const val NAME_W = 42
private const val COST_W = 14
/** A result row's controls in the workspace, and where each puts the card. */
private val ACTIONS = listOf("+" to DeckSection.MAIN, "sb" to DeckSection.SIDEBOARD, "?" to DeckSection.CONSIDERING)

/**
 * One page of a search: rows numbered within the page (`card <N>` expands
 * one), the name a link, then the cost and as much of the type line as the
 * pane has room for. The last line pages, and its `next` / `prev` are links.
 * (renderer.render_search + render_search_nav_hint)
 *
 * With [actions] (the deck workspace) each row starts with `+ sb ?`: into
 * the deck, the sideboard, the considering list. [points] marks a pointed
 * card after its name, `Mana Drain (1)`, as the deck views do.
 */
fun renderSearch(page: SearchPage, actions: Boolean = false, points: (String) -> Int? = { null }): Rendering = Rendering { width ->
    Lines(width).apply {
        if (page.rows.isEmpty()) { add("(no cards matching filters)", Tone.DIM); return@apply }
        val offset = (page.page - 1) * page.pageSize
        val arranged = page.arrangement
        if (arranged != null) poolHeader(page.total, arranged.sort)
        else add("${page.total} card(s) — showing ${offset + 1}-${minOf(offset + page.rows.size, page.total)} (page ${page.page} of ${page.lastPage})", Tone.BOLD)
        // A pool's groups each begin under a heading of their own: where each starts, by row.
        val starts = arranged?.groups?.runningFold(0) { at, g -> at + g.second }?.zip(arranged.groups)?.associate { (at, g) -> at to g }.orEmpty()
        val controls = if (actions) ACTIONS.sumOf { it.first.length + 1 } else 0
        val prefixW = INDENT.length + 6 + controls // "  [  1] " and "+ sb ? "
        // The name keeps its 42 columns while the pane allows; the type line takes the rest.
        val nameW = (width - prefixW - COST_W - 2 - 12).coerceIn(12, NAME_W)
        val typeW = maxOf(8, width - prefixW - nameW - COST_W - 2)
        page.rows.forEachIndexed { i, row ->
            starts[i]?.let { (label, n) -> add(groupHeading(label, n), Tone.BOLD) }
            val text = StringBuilder("$INDENT[${(i + 1).toString().padStart(3)}] ")
            val spans = mutableListOf<LinkSpan>()
            if (actions) for ((label, section) in ACTIONS) {
                spans += LinkSpan(text.length, text.length + label.length, OutputLink.Edit(EditAction.Add(row.name, section)))
                text.append(label).append(' ')
            }
            val start = text.length
            val name = cell(row.name + (points(row.name)?.let { " ($it)" } ?: ""), nameW)
            text.append(name).append(' ').append(cell(row.manaCost.orEmpty(), COST_W)).append(' ').append(cell(row.typeLine.orEmpty(), typeW))
            // The link is the name; the points after it are not part of it.
            spans += LinkSpan(start, start + minOf(row.name.length, name.trimEnd().length), OutputLink.Card(row.name))
            add(text.toString().trimEnd(), spans = spans)
        }
        val parts = listOfNotNull("next".takeIf { page.hasNext }, "prev".takeIf { page.hasPrev })
        val text = StringBuilder("$INDENT| ")
        val spans = mutableListOf<LinkSpan>()
        for (p in parts) {
            spans += LinkSpan(text.length, text.length + p.length, OutputLink.Run(p))
            text.append(p).append("  |  ")
        }
        text.append("`card <N>` to expand row")
        add(text.toString(), Tone.DIM, spans)
    }.out
}

/** A pool group's heading, the same in lines and in the grid. */
fun groupHeading(label: String, count: Int): String = "$INDENT── $label ($count)"

/**
 * A limited deck's pool, and how it is laid out: each slot a link that turns
 * it to the next layer (`sort ...`, our own command), the first naming the groups.
 */
private fun Lines.poolHeader(total: Int, sort: PoolSort) {
    val text = StringBuilder("$total card(s) in the pool · sort: ")
    val spans = mutableListOf<LinkSpan>()
    sort.slots.forEachIndexed { i, layer ->
        if (i > 0) text.append(" › ")
        val label = "[${layer?.word ?: "-"}]"
        spans += LinkSpan(text.length, text.length + label.length, OutputLink.Sort(sort.cycled(i)))
        text.append(label)
    }
    text.append("  (click one to change it; the first groups)")
    add(text.toString(), Tone.BOLD, spans)
}

/** The filters a deck added to a search — announced, never applied silently. Empty when there are none. */
fun renderDeckFilterNotice(filters: List<String>): Rendering = Rendering { width ->
    if (filters.isEmpty()) emptyList()
    else Lines(width).apply { wrap("[deck filter: ${filters.joinToString("  ")}  (`cd ..` to search the full pool)]", tone = Tone.DIM) }.out
}
