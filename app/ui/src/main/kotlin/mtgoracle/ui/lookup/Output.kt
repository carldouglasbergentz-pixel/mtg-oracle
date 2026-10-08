package mtgoracle.ui.lookup

/*
 * The output pane's text: lines with clickable spans, rendered for a width.
 * The renderers own the columns they write, so they report where each link
 * sits (renderer.LinkSpan's rule on the Python side); nobody re-derives a
 * position from the drawn text.
 */

/** What clicking a span opens. Values, never command text to re-parse (a name can hold `;`, `'` or `//`). */
sealed interface OutputLink {
    data class Card(val name: String) : OutputLink
    data class Combo(val id: String) : OutputLink
    data class Rule(val number: String) : OutputLink
    /** One of our own fixed commands (`next`, `prev`): never built from data. */
    data class Run(val command: String) : OutputLink
    /** A list of cards to show, named by [title] (a mana value's cards in a profile). */
    data class Cards(val title: String, val names: List<String>) : OutputLink
    /** A change to the open deck (a result's `+`, `sb`, `?`). */
    data class Edit(val action: EditAction) : OutputLink
    /** A limited deck's pool laid out anew (its page's `sort:` slots): the page changes in place, nothing is added. */
    data class Sort(val sort: mtgoracle.core.lookup.PoolSort) : OutputLink
}

data class LinkSpan(val start: Int, val end: Int, val link: OutputLink)

enum class Tone { PLAIN, DIM, BOLD, ERROR, ECHO }

data class OutLine(val text: String, val tone: Tone = Tone.PLAIN, val spans: List<LinkSpan> = emptyList())

/** A block of output, rendered for the width it is laid out in; re-rendered when that changes. */
fun interface Rendering {
    fun lines(width: Int): List<OutLine>
}

/** Accumulates lines, and the spans on them, while a renderer writes. */
internal class Lines(val width: Int) {
    val out = mutableListOf<OutLine>()

    /**
     * One line. A line without links that is wider than the pane wraps under
     * its own indent; a line with links is the caller's to lay out, since
     * only it knows where they may break.
     */
    fun add(text: String = "", tone: Tone = Tone.PLAIN, spans: List<LinkSpan> = emptyList()) {
        if (spans.isNotEmpty() || text.length <= width) { out += OutLine(text, tone, spans); return }
        val lead = " ".repeat(text.takeWhile { it == ' ' }.length)
        wrapWords(text.trimStart(), width, lead, "$lead  ").forEach { out += OutLine(it, tone) }
    }

    /** A blank line, then [title] in bold: a section of a profile. */
    fun section(title: String) {
        add()
        add(title, Tone.BOLD)
    }

    /** [text] word-wrapped: the first line after [indent], the rest after [hang] (default: [indent]). */
    fun wrap(text: String, indent: String = "", hang: String = indent, tone: Tone = Tone.PLAIN) {
        for (paragraph in text.split('\n')) {
            wrapWords(paragraph, width, indent, hang).forEach { add(it, tone) }
        }
    }

    /** A rule across the width. */
    fun rule() = add("─".repeat(maxOf(1, width)), Tone.DIM)
}

/**
 * Words onto lines of at most [width]; a word longer than a line is broken
 * rather than cut (a URL, a long card name in a narrow pane). Never empty:
 * an empty paragraph is one empty line, as `textwrap` gives.
 */
internal fun wrapWords(text: String, width: Int, indent: String = "", hang: String = indent): List<String> {
    // What fits stays as written: spacing inside a line is often meant (`ci<=BG  f:commander`).
    if (indent.length + text.length <= width) return listOf((indent + text).trimEnd())
    val words = text.split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return listOf(indent.trimEnd())
    val lines = mutableListOf<String>()
    var line = StringBuilder(indent)
    var lead = indent.length
    for (whole in words) {
        var word = whole
        while (word.isNotEmpty()) {
            val room = width - line.length - (if (line.length > lead) 1 else 0)
            when {
                word.length <= room -> {
                    if (line.length > lead) line.append(' ')
                    line.append(word); word = ""
                }
                line.length > lead -> { lines += line.toString(); line = StringBuilder(hang); lead = hang.length }
                else -> {
                    // Nothing on this line yet and still too long: break the word here.
                    val take = maxOf(1, width - line.length)
                    line.append(word.take(take)); word = word.drop(take)
                    lines += line.toString(); line = StringBuilder(hang); lead = hang.length
                }
            }
        }
    }
    if (line.length > lead) lines += line.toString()
    return lines
}

/** [text] in exactly [width] cells: padded, or cut with `…`. */
internal fun cell(text: String, width: Int): String = when {
    width <= 0 -> ""
    text.length <= width -> text.padEnd(width)
    else -> text.take(width - 1) + "…"
}

/**
 * Preformatted text (help): each line as written, wrapped only where it is
 * wider than the pane, the continuation under the line's own indent.
 */
fun preformatted(text: String, tone: Tone = Tone.PLAIN): Rendering = Rendering { width ->
    text.lines().flatMap { raw ->
        if (raw.length <= width) listOf(OutLine(raw, tone))
        else {
            val lead = " ".repeat(raw.takeWhile { it == ' ' }.length)
            wrapWords(raw.trimStart(), width, lead, "$lead  ").map { OutLine(it, tone) }
        }
    }
}

/** One line of text, as is. */
fun message(text: String, tone: Tone = Tone.PLAIN): Rendering = preformatted(text, tone)
