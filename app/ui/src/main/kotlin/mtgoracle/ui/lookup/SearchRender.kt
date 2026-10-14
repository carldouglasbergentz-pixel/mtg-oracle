package mtgoracle.ui.lookup

import mtgoracle.core.lookup.CardSort
import mtgoracle.core.deck.DeckSection
import mtgoracle.core.lookup.SearchPage
import mtgoracle.ui.kit.ruleText

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
        val showing = "${page.total} card(s) — showing ${offset + 1}-${minOf(offset + page.rows.size, page.total)} (page ${page.page} of ${page.lastPage})"
        when {
            arranged == null -> add(showing, Tone.BOLD)
            arranged.pool -> sortHeader("${page.total} card(s) in the pool", arranged.sort, pool = true)
            else -> sortHeader(showing, arranged.sort, pool = false)
        }
        // Groups each begin under a heading of their own, counted over the whole search: where each starts, by row.
        val starts = arranged?.groups?.runningFold(0) { at, g -> at + g.second }?.zip(arranged.groups)?.associate { (at, g) -> at to g }.orEmpty()
        val controls = if (actions) ACTIONS.sumOf { it.first.length + 1 } else 0
        // The row numbers right-aligned, padded outside their brackets: ` [9]`, `[10]`.
        val numberW = "[${page.rows.size}]".length
        val prefixW = INDENT.length + numberW + 1 + controls // "  [1] " and "+ sb ? "
        // The name keeps its 42 columns while the pane allows; the type line takes the rest.
        val nameW = (width - prefixW - COST_W - 2 - 12).coerceIn(12, NAME_W)
        val typeW = maxOf(8, width - prefixW - nameW - COST_W - 2)
        page.rows.forEachIndexed { i, row ->
            starts[i]?.let { (label, n) -> if (label.isNotEmpty()) add(groupHeading(label, arranged!!.total(label, n), width), Tone.RULE) }
            val text = StringBuilder("$INDENT${"[${i + 1}]".padStart(numberW)} ")
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
        // The paging links first, then the hint for the numbers: the grid draws only the links (it numbers nothing).
        val parts = listOfNotNull("next".takeIf { page.hasNext }, "prev".takeIf { page.hasPrev })
        val text = StringBuilder(INDENT)
        val spans = mutableListOf<LinkSpan>()
        for (p in parts) {
            spans += LinkSpan(text.length, text.length + p.length, OutputLink.Run(p))
            text.append(p).append(" · ")
        }
        text.append("`card <N>` expands row N")
        add(text.toString(), Tone.DIM, spans)
    }.out
}

/** A group's heading, the same in lines and in the grid, and drawn as the deck pane's are: `─ Black (725) ───`. */
fun groupHeading(label: String, count: Int, width: Int): String = ruleText(width, "$label ($count)")

/**
 * What a page holds, [what], and how it is laid out: each slot a link that
 * turns it to the next layer (`sort ...`, our own command), the first naming
 * the groups. [pool]: a limited deck's pool's sort, else the searches'.
 */
private fun Lines.sortHeader(what: String, sort: CardSort, pool: Boolean) {
    val text = StringBuilder("$what · sort: ")
    val spans = mutableListOf<LinkSpan>()
    sort.slots.forEachIndexed { i, layer ->
        if (i > 0) text.append(" › ")
        val label = "[${layer?.word ?: "-"}]"
        spans += LinkSpan(text.length, text.length + label.length, OutputLink.Sort(sort.cycled(i), pool))
        text.append(label)
    }
    text.append("  (click one to change it; the first groups)")
    add(text.toString(), Tone.BOLD, spans)
}

/**
 * Above a search the deck filtered: the way out of its filters. The filters
 * themselves are named in the search pane's title (and by `cd`), never
 * applied silently. Nothing without filters.
 */
fun renderDeckFilterNotice(filters: List<String>): Rendering = Rendering { width ->
    if (filters.isEmpty()) emptyList()
    else Lines(width).apply { wrap("`cd ..` searches every card, not only what the deck can play", tone = Tone.DIM) }.out
}
