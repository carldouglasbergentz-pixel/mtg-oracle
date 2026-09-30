package mtgoracle.ui.lookup

import mtgoracle.core.lookup.SearchPage

private const val INDENT = "  "
private const val NAME_W = 42
private const val COST_W = 14

/**
 * One page of a search: rows numbered within the page (`card <N>` expands
 * one), the name a link, then the cost and as much of the type line as the
 * pane has room for. The last line pages, and its `next` / `prev` are links.
 * (renderer.render_search + render_search_nav_hint)
 */
fun renderSearch(page: SearchPage): Rendering = Rendering { width ->
    Lines(width).apply {
        if (page.rows.isEmpty()) { add("(no cards matching filters)", Tone.DIM); return@apply }
        val offset = (page.page - 1) * page.pageSize
        add("${page.total} card(s) — showing ${offset + 1}-${minOf(offset + page.rows.size, page.total)} (page ${page.page} of ${page.lastPage})", Tone.BOLD)
        val prefixW = INDENT.length + 6 // "  [  1] "
        // The name keeps its 42 columns while the pane allows; the type line takes the rest.
        val nameW = (width - prefixW - COST_W - 2 - 12).coerceIn(12, NAME_W)
        val typeW = maxOf(8, width - prefixW - nameW - COST_W - 2)
        page.rows.forEachIndexed { i, row ->
            val prefix = "$INDENT[${(i + 1).toString().padStart(3)}] "
            val name = cell(row.name, nameW)
            add(prefix + name + " " + cell(row.manaCost.orEmpty(), COST_W) + " " + cell(row.typeLine.orEmpty(), typeW).trimEnd(),
                spans = listOf(LinkSpan(prefix.length, prefix.length + name.trimEnd().length, OutputLink.Card(row.name))))
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

/** The filters a deck added to a search — announced, never applied silently. Empty when there are none. */
fun renderDeckFilterNotice(filters: List<String>): Rendering = Rendering { width ->
    if (filters.isEmpty()) emptyList()
    else Lines(width).apply { wrap("[deck filter: ${filters.joinToString("  ")}  (`cd ..` to search the full pool)]", tone = Tone.DIM) }.out
}
