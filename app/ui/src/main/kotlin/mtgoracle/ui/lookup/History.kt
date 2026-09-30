package mtgoracle.ui.lookup

/**
 * The command line's history, shell-style: Up walks back through what was
 * run, Down forward, and past the newest it gives back what was being typed
 * before Up was first pressed. An immediate repeat is stored once. In memory,
 * as the TUI's was.
 */
class History {
    private val entries = mutableListOf<String>()
    /** How far back Up has gone: -1 while not browsing, 0 at the newest entry. */
    private var back = -1
    private var draft = ""

    fun submit(line: String) {
        if (entries.lastOrNull() != line) entries += line
        back = -1
        draft = ""
    }

    /** Up: the next older entry (the oldest stays put); null with no history. [current] is kept as the draft. */
    fun older(current: String): String? {
        if (entries.isEmpty()) return null
        if (back == -1) { draft = current; back = 0 } else if (back + 1 < entries.size) back++
        return entries[entries.lastIndex - back]
    }

    /** Down: the next newer entry, then the draft; null when not browsing. */
    fun newer(): String? {
        if (back == -1) return null
        if (back == 0) { back = -1; return draft }
        back--
        return entries[entries.lastIndex - back]
    }
}
