package mtgoracle.data

import java.io.File
import java.security.MessageDigest
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The sync against its original: the fixture's exports, ingested by the
 * app's sync into a fresh database, give every table exactly the rows
 * Python's sync gave from the same files (`expected/sync-tables.txt`: each
 * table's row count and a digest of its rows as SQLite quotes them, so `'3'`
 * is not `3`). The full exports were compared the same way, table for
 * table, before the Python sync was retired (step 6b).
 */
class SyncParityTest {

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
        // The markers and the counts; the stamps of the two local sources are "now". Printings came after Python.
        "(SELECT source, CASE WHEN source IN ('local_tags', 'custom_formats') THEN '' ELSE updated_at END AS marker, row_count FROM sync_state WHERE source != 'scryfall_printings')" to "source, marker, row_count",
    )

    /** `table <tab> rows <tab> sha-256 of the rows, in one order`, per table. */
    private fun digests(db: File): List<String> = DriverManager.getConnection("jdbc:sqlite:${db.path}").use { c ->
        tables.map { (table, columns) ->
            val quoted = columns.split(", ").joinToString(" || char(31) || ") { "quote($it)" }
            val sha = MessageDigest.getInstance("SHA-256")
            var rows = 0
            c.createStatement().use { st ->
                st.executeQuery("SELECT $quoted FROM $table ORDER BY $columns").use { rs ->
                    while (rs.next()) { sha.update(rs.getString(1).toByteArray(Charsets.UTF_8)); sha.update(10); rows++ }
                }
            }
            val name = if (table.startsWith("(")) "sync_state" else table
            "$name\t$rows\t" + sha.digest().joinToString("") { "%02x".format(it) }
        }
    }

    @Test
    fun `every table the sync writes is Python's, row for row`() {
        val expected = FixtureDb.expected("sync-tables.txt")
        val actual = digests(FixtureDb.file)
        actual.forEach { line -> if (!line.startsWith("combo_prerequisites")) assertTrue(line.split('\t')[1].toInt() > 0, "written: $line") }
        assertEquals(expected, actual)
    }
}
