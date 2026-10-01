package mtgoracle.data

import mtgoracle.data.sync.Bulk
import mtgoracle.data.sync.Sync
import mtgoracle.data.sync.Upstream
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The sync against its original, offline: Python's own sync functions and
 * ours ingest the same cached exports in data/raw into two copies of the
 * database, and every table they write must come out the same, row for row
 * and type for type. Opt-in (`gradlew :data:test -PsyncParity`): Spellbook's
 * export alone is 600 MB, and the whole run takes minutes.
 */
class SyncParityTest {
    private val raw = File(DbFixture.repoRoot, "data/raw")
    private val exports = listOf("scryfall_oracle_cards.jsonl.gz", "scryfall_rulings.jsonl.gz", "MagicCompRules.txt", "spellbook_variants.json", "oracle_tags.jsonl.gz")

    /** The network, served from data/raw. */
    private inner class Cached : Upstream {
        override fun scryfallBulk() = listOf("oracle_cards", "rulings", "oracle_tags").associateWith { Bulk("cached", "cached:$it") }
        override fun rulesPage() = """<a href="https://media.wizards.com/2026/downloads/MagicCompRules%2020260101.txt">rules</a>"""
        override fun bytes(url: String) = File(raw, "MagicCompRules.txt").readBytes()
        override fun spellbookMarker() = "cached"
        override fun download(url: String, target: File) {
            val source = when (url) {
                "cached:oracle_cards" -> "scryfall_oracle_cards.jsonl.gz"
                "cached:rulings" -> "scryfall_rulings.jsonl.gz"
                "cached:oracle_tags" -> "oracle_tags.jsonl.gz"
                Upstream.SPELLBOOK -> "spellbook_variants.json"
                else -> error("no cached export for $url")
            }
            target.parentFile.mkdirs()
            Files.copy(File(raw, source).toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Python's sync functions, their network calls replaced by the cached files; data/raw is only read. */
    private val python = """
        import sys, json
        from pathlib import Path
        sys.path.insert(0, 'scripts')
        import sync_cards, sync_rules, sync_combos, sync_oracle_tags, tag_cards, load_custom_formats
        from mtg_oracle import queries as q
        db, scratch, raw = Path(sys.argv[1]), Path(sys.argv[2]), Path('data/raw')
        for m in (sync_cards, sync_rules, sync_combos, sync_oracle_tags, tag_cards, load_custom_formats, q):
            m.DB_PATH = db
        entry = lambda: {'updated_at': 'cached', 'jsonl_download_uri': 'cached'}
        sync_cards.fetch_bulk_index = lambda: {'oracle_cards': entry(), 'rulings': entry()}
        sync_cards.download_bulk = lambda e, label: raw / ('scryfall_' + label + '.jsonl.gz')
        sync_cards.sync(force=True)
        sync_rules.RAW_DIR = scratch
        sync_rules.discover_cr_url = lambda: ('cached', '20260101')
        sync_rules._http_get = lambda url, accept='text/html': (raw / 'MagicCompRules.txt').read_bytes()
        sync_rules.sync(force=True)
        sync_combos._head_etag = lambda: ('cached', '')
        sync_combos.fetch = lambda: json.load(open(raw / 'spellbook_variants.json', encoding='utf-8'))
        sync_combos.sync(force=True)
        tag_cards.sync()
        sync_oracle_tags.bulk_meta = entry
        sync_oracle_tags.fetch = lambda uri: raw / 'oracle_tags.jsonl.gz'
        sync_oracle_tags.sync(force=True)
        load_custom_formats.sync()
    """.trimIndent()

    /** Each table's columns, timestamps and row ids left out (they differ by run, not by rule). */
    private val tables = mapOf(
        "cards" to "name, oracle_id, oracle_text, mana_cost, mana_value, colors, color_identity, power, toughness, rarity, type_line, layout, card_faces, games, reserved, edhrec_rank",
        "card_legalities" to "card_name, format, status",
        "rulings" to "card_name, oracle_id, date, text",
        "rules" to "rule_number, parent_rule, section_title, text",
        "combos" to "id, name, color_identity, description",
        "combo_cards" to "combo_id, card_name, quantity",
        "combo_results" to "combo_id, text",
        "combo_prerequisites" to "combo_id, text",
        "combo_steps" to "combo_id, step_order, text",
        "card_tags" to "card_name, tag, category, source",
        "card_abilities" to "card_name, ability_index, ability_type, cost, effect, has_target, produces_mana, is_mana_ability, raw_text",
        "card_oracle_tags" to "card_name, tag, weight",
        "custom_formats" to "format, name, aliases, derives_from, points_budget, singleton, source_url, list_current_as_of",
        "custom_format_points" to "format, card_name, points",
    )

    /** [table]'s rows in both databases, in one order, compared as SQLite quotes them (`'3'` is not `3`); the first differences. */
    private fun differences(a: File, b: File, table: String, columns: String): Pair<Int, List<String>> {
        val quoted = columns.split(", ").joinToString(" || char(31) || ") { "quote($it)" }
        val sql = "SELECT $quoted FROM $table ORDER BY $columns"
        fun rows(f: File) = DriverManager.getConnection("jdbc:sqlite:${f.path}").use { c ->
            c.createStatement().use { st -> st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }
        }
        val x = rows(a)
        val y = rows(b)
        val only = (x - y.toSet()).take(3).map { "  python only: ${it.take(300)}" } + (y - x.toSet()).take(3).map { "  kotlin only: ${it.take(300)}" }
        return x.size to if (x == y) emptyList() else listOf("$table: python ${x.size} rows, kotlin ${y.size}") + only
    }

    /**
     * Empties what the sync writes, so every row compared below was written
     * by the sync under test: the copies start from the user's database,
     * which an earlier Python sync filled from these very exports. Cards keep
     * their rows (decks name them) with every Scryfall column cleared.
     */
    private fun blank(db: File) = DriverManager.getConnection("jdbc:sqlite:${db.path}").use { c ->
        c.createStatement().use { st ->
            (tables.keys - "cards").forEach { st.executeUpdate("DELETE FROM $it") }
            st.executeUpdate("DELETE FROM sync_state")
            st.executeUpdate(
                "UPDATE cards SET oracle_id = NULL, oracle_text = NULL, mana_cost = NULL, mana_value = NULL, colors = NULL, " +
                    "color_identity = NULL, power = NULL, toughness = NULL, rarity = NULL, type_line = NULL, layout = NULL, " +
                    "card_faces = NULL, games = NULL, reserved = 0, edhrec_rank = NULL",
            )
        }
    }

    @Test
    fun `every source ingests the cached exports as Python does`() {
        assumeTrue(System.getProperty("mtgoracle.syncParity") == "true", "opt-in: gradlew :data:test -PsyncParity")
        assumeTrue(DbFixture.available && exports.all { File(raw, it).isFile }, "needs data/mtg.db and every export in data/raw")
        val py = DbFixture.copy()
        val kt = DbFixture.copy()
        val scratch = Files.createTempDirectory("mtg-oracle-sync-").toFile()
        blank(py)
        blank(kt)
        try {
            var started = System.nanoTime()
            DbFixture.python(python, py.absolutePath, scratch.absolutePath)
            println("python sync: ${(System.nanoTime() - started) / 1_000_000_000} s")
            started = System.nanoTime()
            val report = Sync(MtgDb(kt), Cached(), File(scratch, "raw"), File(DbFixture.repoRoot, "data/formats"), log = ::println).run(force = true)
            println("kotlin sync: ${(System.nanoTime() - started) / 1_000_000_000} s")
            assertEquals(emptyList(), report.failures)

            val problems = mutableListOf<String>()
            for ((table, columns) in tables) {
                val (rows, diff) = differences(py, kt, table, columns)
                println("$table: $rows rows" + if (diff.isEmpty()) "" else "  DIFFERS")
                if (table != "combo_prerequisites") assertTrue(rows > 0, "$table was written by both")
                problems += diff
            }
            // sync_state: the markers and the counts; the stamps of the two local sources are "now".
            val (_, state) = differences(py, kt, "(SELECT source, CASE WHEN source IN ('local_tags', 'custom_formats') THEN '' ELSE updated_at END AS marker, row_count FROM sync_state)", "source, marker, row_count")
            problems += state
            assertTrue(problems.isEmpty(), problems.joinToString("\n"))
        } finally {
            scratch.deleteRecursively()
            py.parentFile.deleteRecursively()
            kt.parentFile.deleteRecursively()
        }
    }
}
