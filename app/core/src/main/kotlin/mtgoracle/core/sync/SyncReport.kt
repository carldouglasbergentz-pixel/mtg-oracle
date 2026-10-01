package mtgoracle.core.sync

/*
 * What a sync did, as plain data: the data layer runs it, the screens and
 * the command line show it.
 */

/** The sources, in the order they run: every later one resolves names against the cards. */
enum class Source(val key: String, val label: String) {
    CARDS("cards", "Scryfall cards + rulings"),
    RULES("rules", "Wizards Comprehensive Rules"),
    COMBOS("combos", "Commander Spellbook"),
    TAGS("tags", "Local tagging (keywords, types, abilities)"),
    ORACLETAGS("oracletags", "Scryfall Tagger oracle tags"),
    FORMATS("formats", "Community formats (points lists)"),
    PRINTINGS("printings", "Scryfall printings (every card's art)");

    companion object {
        fun of(key: String): Source? = entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
    }
}

/** Keyed state before and after a run, for the changelog: ids and a hash of their text, and row counts. */
data class Snapshot(
    val cards: Map<String, Int>,
    val rules: Map<String, Int>,
    val combos: Set<String>,
    val counts: Map<String, Int>,
)

/** One changelog row: added, removed and modified by key, or only the net change for a wipe-and-rebuild table. */
data class TableChange(val table: String, val added: Int?, val removed: Int?, val modified: Int?, val net: Int?, val total: Int)

data class SyncReport(
    val ran: List<Source>,
    val changes: List<TableChange>,
    val failures: List<Pair<Source, String>>,
    /** What only an ingest can see (name collisions), by source. */
    val notes: Map<Source, List<String>>,
    /** `sync_state` after the run: (source, updated_at, rows). */
    val state: List<Triple<String, String?, Int?>>,
) {
    val touched: Boolean get() = changes.any { (it.added ?: 0) + (it.removed ?: 0) + (it.modified ?: 0) + kotlin.math.abs(it.net ?: 0) > 0 }
    val changedCards: Boolean get() = changes.any { it.table in setOf("cards", "points", "oracletags", "tags") && ((it.added ?: 0) + (it.removed ?: 0) + (it.modified ?: 0) + kotlin.math.abs(it.net ?: 0) > 0) }
}
