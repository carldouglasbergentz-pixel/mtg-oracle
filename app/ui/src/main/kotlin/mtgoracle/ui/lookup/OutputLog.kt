package mtgoracle.ui.lookup

import androidx.compose.runtime.mutableStateListOf
import mtgoracle.core.lookup.SearchPage

/**
 * The output pane's scrollback: each command's echo (`> card sol ring`) and
 * what it printed, as renderings, so a resized pane re-renders them at its
 * new width instead of keeping lines cut for the old one.
 */
class OutputLog {
    /**
     * [page]: a search's page, which the pane may draw as a grid of cards
     * instead of [rendering]'s lines. [ofSearch]: the page, or the deck's
     * filters said above it, which go when the next search comes.
     */
    data class Entry(val id: Long, val rendering: Rendering, val isEcho: Boolean = false, val page: SearchPage? = null, val ofSearch: Boolean = page != null)

    val entries = mutableStateListOf<Entry>()
    private var nextId = 0L
    /** The width the pane last laid the output out at, so `copy` gives what is on screen. */
    var width: Int = 100

    fun echo(command: String) = append(message("> $command", Tone.ECHO), isEcho = true)

    fun add(rendering: Rendering) = append(rendering, isEcho = false)

    /**
     * A page of search results, lines in text mode, a grid of cards
     * otherwise, under [notice] (the deck's filters) when there is one. It
     * takes the place of every search before it: the pane holds one search
     * at a time, and what else was asked stays.
     */
    fun addSearch(page: SearchPage, rendering: Rendering, notice: Rendering? = null) {
        dropSearches()
        if (notice != null) entries += Entry(nextId++, notice, ofSearch = true)
        entries += Entry(nextId++, rendering, isEcho = false, page = page)
    }

    /** Every search's entries out, and an earlier echo left with nothing under it; the newest echo is the command now running. */
    private fun dropSearches() {
        val running = entries.lastOrNull { it.isEcho }?.id
        val drop = mutableSetOf<Long>()
        var echo: Entry? = null
        val printed = mutableListOf<Entry>()
        // A command whose output was all search goes with it; one that printed something else keeps its echo.
        fun close() {
            val e = echo ?: return
            if (e.id != running && printed.isNotEmpty() && printed.all { it.ofSearch }) drop += e.id
        }
        for (entry in entries) {
            if (entry.isEcho) { close(); echo = entry; printed.clear() } else printed += entry
            if (entry.ofSearch) drop += entry.id
        }
        close()
        if (drop.isNotEmpty()) entries.removeAll { it.id in drop }
    }

    /** [page] in place of the newest search page, where it stands and under its id, so nothing scrolls; added when there is none. */
    fun replaceSearch(page: SearchPage, rendering: Rendering) {
        val at = entries.indexOfLast { it.page != null }
        if (at < 0) addSearch(page, rendering) else entries[at] = Entry(entries[at].id, rendering, isEcho = false, page = page)
    }

    /** The newest search page in the scrollback: what the arrow keys select in. */
    val latestSearch: Entry? get() = entries.lastOrNull { it.page != null }

    fun clear() = entries.clear()

    private fun append(rendering: Rendering, isEcho: Boolean) {
        entries += Entry(nextId++, rendering, isEcho)
    }

    /** Everything, as text. */
    fun allText(): String = text(entries, width)

    /**
     * What the command before the last one printed: `copy last` is itself the
     * last command, so this is the output between the two latest echoes.
     */
    fun lastOutputText(): String {
        val echoes = entries.withIndex().filter { it.value.isEcho }.map { it.index }
        if (echoes.size < 2) return ""
        return text(entries.subList(echoes[echoes.size - 2] + 1, echoes.last()), width)
    }

    private fun text(of: List<Entry>, width: Int) = of.flatMap { it.rendering.lines(width) }.joinToString("\n") { it.text }
}
