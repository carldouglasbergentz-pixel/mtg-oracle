package mtgoracle.data

import org.sqlite.SQLiteConfig
import java.io.File
import java.sql.Connection

/**
 * data/mtg.db, the contract between the Python side and this app (ADR 0001 §3).
 *
 * Reads go through a read-only connection, so a bug here cannot touch the
 * user's decks. The only write this app makes — a `games` row — opens its own
 * short-lived read-write connection ([GameStore]).
 */
class MtgDb(val file: File) {

    init {
        if (!file.isFile) throw MissingDatabaseException(file)
    }

    fun <T> read(block: (Connection) -> T): T = connect(readOnly = true).use(block)

    internal fun <T> write(block: (Connection) -> T): T = connect(readOnly = false).use { conn ->
        conn.autoCommit = false
        try {
            block(conn).also { conn.commit() }
        } catch (e: Exception) {
            conn.rollback()
            throw e
        }
    }

    private fun connect(readOnly: Boolean): Connection {
        val config = SQLiteConfig().apply {
            setReadOnly(readOnly)
            enforceForeignKeys(true)
            busyTimeout = 5_000 // the TUI may be writing at the same moment
        }
        return config.createConnection("jdbc:sqlite:${file.path}")
    }

    /** Fails with [SchemaTooOldException] unless every table and column this app uses is there. */
    fun checkSchema() {
        val missing = read { SchemaCheck.missing(it) }
        if (missing.isNotEmpty()) throw SchemaTooOldException(file, missing)
    }
}

class MissingDatabaseException(val file: File) :
    IllegalStateException("No database at $file. Build it with `python scripts/sync.py` (see README.md).")

/**
 * The database is older than this app. Python's scripts/self_heal.py owns
 * every migration, so the fix is always to run it (sync runs it too) — never
 * to migrate from here.
 */
class SchemaTooOldException(val file: File, val missing: List<String>) : IllegalStateException(
    "$file is older than this app: it has no ${missing.joinToString(", ")}. " +
        "Run `python scripts/self_heal.py` (or `python scripts/sync.py`), then start the app again.",
)

object SchemaCheck {
    /** Everything the app reads or writes. `games` and the printing columns come from the step-2 migrations. */
    val REQUIRED: Map<String, List<String>> = mapOf(
        "deck_folders" to listOf("id", "name", "format"),
        "decks" to listOf("id", "folder_id", "name", "format", "updated_at"),
        "deck_cards" to listOf("id", "deck_id", "card_name", "quantity", "category", "is_commander", "is_sideboard", "added_at", "set_code", "collector_number"),
        // Deck editing (step 4): the history both apps write, and the considering list.
        "deck_revisions" to listOf("id", "deck_id", "at", "action", "note"),
        "deck_changes" to listOf(
            "revision_id", "card_name", "section", "qty_before", "qty_after",
            "set_code_before", "collector_number_before", "set_code_after", "collector_number_after",
        ),
        "deck_considering" to listOf("id", "deck_id", "card_name", "quantity", "added_at"),
        "custom_format_points" to listOf("format", "card_name", "points"),
        "cards" to listOf(
            "name", "mana_cost", "type_line", "oracle_text", "power", "toughness",
            "colors", "color_identity", "mana_value", "rarity", "layout", "games", "reserved", "edhrec_rank",
        ),
        // Lookup and search (step 3).
        "card_tags" to listOf("card_name", "tag", "category"),
        "card_abilities" to listOf("card_name", "ability_index", "ability_type", "cost", "effect", "has_target", "produces_mana", "is_mana_ability"),
        "card_legalities" to listOf("card_name", "format", "status"),
        "card_oracle_tags" to listOf("card_name", "tag"), // otag:
        "rulings" to listOf("card_name", "date", "text"),
        "rules" to listOf("rule_number", "parent_rule", "section_title", "text"),
        "combos" to listOf("id", "name", "color_identity", "description"),
        "combo_cards" to listOf("combo_id", "card_name", "quantity"),
        "combo_steps" to listOf("combo_id", "step_order", "text"),
        "combo_results" to listOf("id", "combo_id", "text"),
        "combo_prerequisites" to listOf("id", "combo_id", "text"),
        "user_combos" to listOf("id", "name", "color_identity", "description"),
        "user_combo_cards" to listOf("combo_id", "card_name", "quantity"),
        "corrections" to listOf("id", "topic", "category", "incorrect_claim", "correct_claim", "explanation", "relates_to", "source", "added_at"),
        "custom_formats" to listOf("format", "name", "aliases", "derives_from", "points_budget", "singleton"),
        "forge_substitutions" to listOf("deck_id", "card_name", "substitute"),
        "games" to listOf(
            "id", "played_at", "mode", "deck_id", "deck_name", "opponent_deck_id", "opponent_name",
            "opponent_ai_variant", "seed", "winner", "turns", "duration_ms", "forge_version", "log_path",
            "match_id", "game_no", "match_format", "conceded",
        ),
    )

    /** "table games" or "column deck_cards.set_code", one per gap. */
    fun missing(conn: Connection): List<String> = REQUIRED.flatMap { (table, columns) ->
        val present = conn.prepareStatement("SELECT name FROM pragma_table_info(?)").use { st ->
            st.setString(1, table)
            st.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
        }
        if (present.isEmpty()) listOf("table $table")
        else columns.filter { it !in present }.map { "column $table.$it" }
    }
}
