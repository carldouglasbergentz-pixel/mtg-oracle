package mtgoracle.ui.lookup

import mtgoracle.core.lookup.Correction

private const val INDENT = "  "
private const val FIELD = "$INDENT$INDENT         " // under the value of `wrong:   `

/** `correction [text]`: what was wrong, what is right, why, and the cards it is about (links). (renderer.render_corrections) */
fun renderCorrections(rows: List<Correction>): Rendering = Rendering { width ->
    Lines(width).apply {
        if (rows.isEmpty()) { add("(no corrections)", Tone.DIM); return@apply }
        add("${rows.size} correction(s):", Tone.BOLD)
        for (c in rows) {
            add("$INDENT#${c.id} [${c.category.orEmpty()}/${c.source.orEmpty()}] ${c.topic}", Tone.BOLD)
            c.incorrectClaim?.let { wrap(it, "$INDENT${INDENT}wrong:   ", FIELD) }
            wrap(c.correctClaim, "$INDENT${INDENT}correct: ", FIELD)
            c.explanation?.takeIf { it.isNotBlank() }?.let { wrap(it, "$INDENT${INDENT}why:     ", FIELD, Tone.DIM) }
            if (c.relatesTo.isNotEmpty()) relatesTo(c.relatesTo)
        }
    }.out
}

/** `re:      A, B, C`, each name a link, wrapping between names. */
private fun Lines.relatesTo(names: List<String>) {
    var text = StringBuilder("$INDENT${INDENT}re:      ")
    var spans = mutableListOf<LinkSpan>()
    names.forEachIndexed { i, name ->
        val sep = if (i == 0) "" else ", "
        if (i > 0 && text.length + sep.length + name.length > width) {
            add(text.toString() + ",", spans = spans)
            text = StringBuilder(FIELD); spans = mutableListOf()
        } else text.append(sep)
        spans += LinkSpan(text.length, text.length + name.length, OutputLink.Card(name))
        text.append(name)
    }
    add(text.toString(), spans = spans)
}
