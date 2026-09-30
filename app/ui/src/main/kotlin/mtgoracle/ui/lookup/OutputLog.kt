package mtgoracle.ui.lookup

import androidx.compose.runtime.mutableStateListOf

/**
 * The output pane's scrollback: each command's echo (`> card sol ring`) and
 * what it printed, as renderings, so a resized pane re-renders them at its
 * new width instead of keeping lines cut for the old one.
 */
class OutputLog {
    data class Entry(val id: Long, val rendering: Rendering, val isEcho: Boolean = false)

    val entries = mutableStateListOf<Entry>()
    private var nextId = 0L
    /** The width the pane last laid the output out at, so `copy` gives what is on screen. */
    var width: Int = 100

    fun echo(command: String) = append(message("> $command", Tone.ECHO), isEcho = true)

    fun add(rendering: Rendering) = append(rendering, isEcho = false)

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
