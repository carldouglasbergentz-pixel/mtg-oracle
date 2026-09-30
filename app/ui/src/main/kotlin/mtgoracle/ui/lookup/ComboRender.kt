package mtgoracle.ui.lookup

import mtgoracle.core.lookup.ComboDetail
import mtgoracle.core.lookup.ComboSummary

private const val INDENT = "  "
private const val CONTINUATION = "    + "

/**
 * A numbered combo list: `combo-info <N>` resolves against it, and a click
 * on the row's `[  N]` opens that combo by id, so a row in the scrollback
 * still opens the right one after the list has changed.
 * (renderer.render_combo_list)
 */
fun renderComboList(combos: List<ComboSummary>, header: String): Rendering = Rendering { width ->
    Lines(width).apply {
        if (combos.isEmpty()) { add("(no matching combos)", Tone.DIM); return@apply }
        add(header, Tone.BOLD)
        combos.forEachIndexed { i, c -> comboRow(c, "[${(i + 1).toString().padStart(3)}]") }
        add("$INDENT(click a row number, or type `combo-info <N>`)", Tone.DIM)
    }.out
}

/**
 * One combo: `  [label] CI    (N cards) A + B + C`, wrapping between cards
 * (never inside a name) onto `    + ` lines. The label opens the combo, each
 * card name its profile. A `+` on the count means the steps name a card slot
 * the list doesn't, so it needs more cards than it shows.
 */
internal fun Lines.comboRow(c: ComboSummary, label: String) {
    val head = "$INDENT$label ${(c.colorIdentity ?: "-").padEnd(5)} (${c.cardCount}${if (c.hasTemplateVars) "+" else ""} cards) "
    val cards = c.cards?.takeIf { it.isNotBlank() }?.split(" + ").orEmpty()
    if (cards.isEmpty()) {
        // No card rows: the combo's own name stands in, and it is not a card to open.
        add(head + (c.name?.takeIf { it.isNotBlank() } ?: "(unnamed)"), spans = listOf(LinkSpan(INDENT.length, INDENT.length + label.length, OutputLink.Combo(c.id))))
        return
    }
    var text = StringBuilder(head)
    var spans = mutableListOf(LinkSpan(INDENT.length, INDENT.length + label.length, OutputLink.Combo(c.id)))
    var first = true
    for (card in cards) {
        val sep = if (first) "" else " + "
        if (text.length + sep.length + card.length > width) {
            add(text.toString().trimEnd(), spans = spans)
            // The first card under the head is not a continuation, so no `+`.
            text = StringBuilder(if (first) " ".repeat(CONTINUATION.length) else CONTINUATION); spans = mutableListOf()
        } else text.append(sep)
        spans += LinkSpan(text.length, text.length + card.length, OutputLink.Card(card))
        text.append(card)
        first = false
    }
    add(text.toString(), spans = spans)
}

/** `combo-info`: the combo in full. (renderer.render_combo) */
fun renderCombo(combo: ComboDetail): Rendering = Rendering { width ->
    Lines(width).apply {
        rule()
        add("Combo ${combo.id}  ${combo.colorIdentity?.takeIf { it.isNotBlank() } ?: "-"}" + if (combo.source == "user") "  (your own)" else "", Tone.BOLD)
        combo.name?.takeIf { it.isNotBlank() }?.let { add(it) }
        rule()
        combo.description?.takeIf { it.isNotBlank() }?.let { wrap(it) }
        if (combo.cards.isNotEmpty()) {
            section("Cards:")
            for (card in combo.cards) {
                val start = INDENT.length + 2
                add("$INDENT- ${card.name}" + if (card.quantity > 1) " x${card.quantity}" else "",
                    spans = listOf(LinkSpan(start, start + card.name.length, OutputLink.Card(card.name))))
            }
        }
        if (combo.prerequisites.isNotEmpty()) {
            section("Prerequisites:")
            combo.prerequisites.forEach { wrap(it, "$INDENT- ", "$INDENT  ") }
        }
        if (combo.steps.isNotEmpty()) {
            section("Steps:")
            combo.steps.forEachIndexed { i, s -> val n = "${i + 1}. "; wrap(s, INDENT + n, INDENT + " ".repeat(n.length)) }
        }
        if (combo.results.isNotEmpty()) {
            section("Results:")
            combo.results.forEach { wrap(it, "$INDENT- ", "$INDENT  ") }
        }
    }.out
}
