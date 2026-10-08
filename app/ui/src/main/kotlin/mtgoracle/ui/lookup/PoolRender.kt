package mtgoracle.ui.lookup

import mtgoracle.core.limited.OpenedPool

private const val INDENT = "    "

/**
 * A sealed pool as it was opened: pack by pack, each card a link to it, in
 * as many columns as the width holds. A foil card is marked `*`.
 */
fun renderPool(title: String, pool: OpenedPool): Rendering = Rendering { width ->
    Lines(width).apply {
        add(title, Tone.BOLD)
        wrap("${pool.packs.size} packs of ${pool.set.name} (${pool.set.code}), ${pool.cards.size} cards. It is all in the sideboard: move cards to the main deck to build.", tone = Tone.DIM)
        val names = pool.cards.map { it.name + if (it.foil) "*" else "" }
        val column = (names.maxOfOrNull { it.length } ?: 0) + 2
        val perLine = ((width - INDENT.length) / column).coerceAtLeast(1)
        pool.packs.forEachIndexed { index, pack ->
            section("pack ${index + 1}")
            pack.chunked(perLine).forEach { row ->
                val text = StringBuilder(INDENT)
                val spans = row.map { card ->
                    val start = text.length
                    val label = card.name + if (card.foil) "*" else ""
                    text.append(label.padEnd(column))
                    LinkSpan(start, start + label.length, OutputLink.Card(card.name))
                }
                add(text.toString(), spans = spans)
            }
        }
    }.out
}
