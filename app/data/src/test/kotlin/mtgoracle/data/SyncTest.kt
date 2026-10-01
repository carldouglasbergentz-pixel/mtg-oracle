package mtgoracle.data

import mtgoracle.data.sync.Bulk
import mtgoracle.core.sync.Source
import mtgoracle.data.sync.Sync
import mtgoracle.data.sync.Upstream
import java.io.File
import java.sql.DriverManager
import java.util.zip.GZIPOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The sync's rules on a fresh database and tiny exports, offline: what is
 * skipped and when, the order, a failing source, name collisions, the rules
 * text's irregular numbers, and a format naming an unknown card.
 */
class SyncTest {
    private val dir = createTempDirectory("mtg-oracle-synctest-").toFile()
    private val db = MtgDb(File(dir, "mtg.db").apply { createNewFile() }).also { it.migrate(null) }
    private val formats = File(dir, "formats").apply { mkdirs() }

    @AfterTest fun clean() { dir.deleteRecursively() }

    private fun gz(name: String, lines: List<String>) = File(dir, name).also { f ->
        GZIPOutputStream(f.outputStream()).bufferedWriter(Charsets.UTF_8).use { w -> lines.forEach { w.write(it); w.write("\n") } }
    }

    private val cards = gz("cards.jsonl.gz", listOf(
        """{"name": "Lightning Bolt", "oracle_id": "b1", "oracle_text": "Lightning Bolt deals 3 damage to any target.", "mana_cost": "{R}", "cmc": 1.0, "colors": ["R"], "color_identity": ["R"], "type_line": "Instant", "layout": "normal", "rarity": "common", "games": ["paper", "arena"], "legalities": {"vintage": "legal", "modern": "legal", "standard": "not_legal"}, "set_type": "masters"}""",
        """{"name": "Fire // Ice", "oracle_id": "f1", "cmc": 4.0, "color_identity": ["U", "R"], "layout": "split", "games": ["paper"], "legalities": {"vintage": "legal"}, "card_faces": [{"name": "Fire", "mana_cost": "{1}{R}", "type_line": "Instant", "oracle_text": "Fire deals 2 damage divided as you choose among one or two targets.", "colors": ["R"]}, {"name": "Ice", "mana_cost": "{1}{U}", "type_line": "Instant", "oracle_text": "Tap target permanent.\nDraw a card.", "colors": ["U"]}]}""",
        // A memorabilia printing that shares a real card's name: legal nowhere, so it loses either way round.
        """{"name": "Lightning Bolt", "oracle_id": "bad", "oracle_text": "Not this one.", "layout": "normal", "legalities": {"vintage": "not_legal"}, "set_type": "memorabilia"}""",
        """{"name": "Llanowar Elves", "oracle_id": "e1", "oracle_text": "{T}: Add {G}.", "mana_cost": "{G}", "cmc": 1, "type_line": "Creature — Elf Druid", "layout": "normal", "power": "1", "toughness": "1", "legalities": {"vintage": "legal"}}""",
        """{"name": "Goblin Token", "layout": "token"}""",
    ))
    private val rulings = gz("rulings.jsonl.gz", listOf(
        """{"oracle_id": "b1", "published_at": "2024-01-01", "comment": "It can target a planeswalker."}""",
        """{"oracle_id": "unknown", "published_at": "2024-01-01", "comment": "A card this database lacks."}""",
    ))
    private val oracleTags = gz("tags.jsonl.gz", listOf(
        """{"label": "removal", "taggings": [{"oracle_id": "b1", "weight": "median"}, {"oracle_id": "nobody"}]}""",
        """{"label": "mana dork", "taggings": [{"oracle_id": "e1"}]}""",
    ))
    private val rulesText = listOf(
        "Magic: The Gathering Comprehensive Rules", "", "1. Game Concepts", "", "100. General", "",
        "100.1. These Magic rules apply to any Magic game.", "", "Example: A game with two players.",
        "704.5aa If a permanent has both a +1/+1 counter and a -1/-1 counter, they are removed.",
        "606.5 Loyalty abilities without a period.", "", "Glossary", "", "1. Text on an object",
    ).joinToString("\r\n")
    private val variants = File(dir, "variants.json").apply {
        writeText("""{"timestamp": "now", "version": "6", "variants": [
            {"id": "1-2", "uses": [{"card": {"name": "Lightning Bolt"}, "quantity": 1}, {"card": {"name": "Llanowar Elves"}, "quantity": 1}],
             "produces": [{"feature": {"name": "Win the game"}}], "identity": "RG", "description": "Step one.\n\nStep two.", "notes": "n"},
            {"id": "", "uses": []}
        ], "aliases": [{"id": "x", "variant": null}]}""")
    }

    /** An upstream whose markers the test moves, and which counts downloads. */
    private inner class Fake : Upstream {
        var marker = "m1"
        var spellbook = "e1"
        var rulesFail = false
        val downloads = mutableListOf<String>()
        override fun scryfallBulk() = mapOf(
            "oracle_cards" to Bulk(marker, "cards"), "rulings" to Bulk(marker, "rulings"), "oracle_tags" to Bulk(marker, "tags"),
        )
        override fun rulesPage(): String {
            check(!rulesFail) { "the rules page is down" }
            return """<a href="https://media.wizards.com/2026/downloads/MagicCompRules 20260227.txt">"""
        }
        override fun bytes(url: String) = ("﻿" + rulesText).toByteArray(Charsets.UTF_8)
        override fun spellbookMarker() = spellbook
        override fun download(url: String, target: File) {
            downloads += url
            val from = when (url) { "cards" -> cards; "rulings" -> rulings; "tags" -> oracleTags; else -> variants }
            from.copyTo(target, overwrite = true)
        }
    }

    private fun sync(up: Fake) = Sync(db, up, File(dir, "raw"), formats)

    private fun rows(sql: String): List<List<String?>> = DriverManager.getConnection("jdbc:sqlite:${db.file.path}").use { c ->
        c.createStatement().use { st -> st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add((1..rs.metaData.columnCount).map { rs.getString(it) }) } } }
    }

    @Test
    fun `a first sync fills every table, and the next skips what upstream didn't move`() {
        val up = Fake()
        val first = sync(up).run()
        assertEquals(emptyList(), first.failures)
        assertEquals(listOf("cards", "rulings", Upstream.SPELLBOOK, "tags"), up.downloads, "each export once, in source order")
        assertEquals(listOf(listOf("Fire // Ice", "Fire\nFire deals 2 damage divided as you choose among one or two targets.\n\n// \n\nIce\nTap target permanent.\nDraw a card.",
            "{1}{R} // {1}{U}", "R,U", "Instant // Instant")),
            rows("SELECT name, oracle_text, mana_cost, colors, type_line FROM cards WHERE name = 'Fire // Ice'"))
        assertEquals(listOf(listOf("b1", "Lightning Bolt deals 3 damage to any target.")), rows("SELECT oracle_id, oracle_text FROM cards WHERE name = 'Lightning Bolt'"),
            "the memorabilia printing lost the name")
        assertEquals(listOf(listOf("Lightning Bolt", "modern"), listOf("Lightning Bolt", "vintage")),
            rows("SELECT card_name, format FROM card_legalities WHERE card_name = 'Lightning Bolt' ORDER BY format"), "not_legal is no row")
        assertEquals(1, rows("SELECT * FROM rulings").size, "a ruling for an unknown card is skipped")
        assertEquals(0, rows("SELECT * FROM cards WHERE name = 'Goblin Token'").size)
        assertEquals(listOf(listOf("100.1", "100", "Game Concepts", "These Magic rules apply to any Magic game.\nExample: A game with two players.")),
            rows("SELECT rule_number, parent_rule, section_title, text FROM rules WHERE rule_number = '100.1'"))
        assertEquals(listOf(listOf("704.5aa", "704.5"), listOf("606.5", "606")), rows("SELECT rule_number, parent_rule FROM rules WHERE rule_number IN ('704.5aa', '606.5') ORDER BY rule_number DESC"))
        assertTrue(rows("SELECT rule_number FROM rules").none { it[0]!!.length < 3 }, "the glossary's numbered senses are no rules")
        assertEquals(listOf(listOf("1-2", "RG", "n")), rows("SELECT id, color_identity, description FROM combos"))
        assertEquals(listOf(listOf("0", "Step one."), listOf("1", "Step two.")), rows("SELECT step_order, text FROM combo_steps ORDER BY step_order"))
        assertEquals(listOf(listOf("Lightning Bolt", "removal", "median"), listOf("Llanowar Elves", "mana dork", "")),
            rows("SELECT card_name, tag, weight FROM card_oracle_tags ORDER BY card_name"))
        assertEquals(listOf(listOf("activated", "{T}", "Add {G}.", "0", "1", "1")),
            rows("SELECT ability_type, cost, effect, has_target, produces_mana, is_mana_ability FROM card_abilities WHERE card_name = 'Llanowar Elves'"))
        assertTrue(listOf("elf", "subtype") in rows("SELECT tag, category FROM card_tags WHERE card_name = 'Llanowar Elves'"))
        assertEquals(3, first.changes.first { it.table == "cards" }.added, "three cards: the memorabilia Bolt shares a name")
        assertTrue(first.touched)

        up.downloads.clear()
        val second = sync(up).run()
        assertEquals(emptyList(), up.downloads, "nothing moved upstream: nothing fetched")
        assertTrue(!second.touched, second.changes.toString())

        up.marker = "m2"
        sync(up).run(only = setOf(Source.ORACLETAGS, Source.CARDS))
        assertEquals(listOf("cards", "rulings", "tags"), up.downloads, "cards before oracle tags, whatever order they are asked in")
        up.downloads.clear()
        sync(up).run(force = true, only = setOf(Source.COMBOS))
        assertEquals(listOf(Upstream.SPELLBOOK), up.downloads, "force fetches an unchanged source")
    }

    @Test
    fun `a failing source is reported and the rest still run, and an unknown marker never skips`() {
        val up = Fake().apply { rulesFail = true; spellbook = "" }
        val report = sync(up).run()
        assertEquals(listOf(Source.RULES), report.failures.map { it.first })
        assertContains(report.failures.single().second, "the rules page is down")
        assertTrue(rows("SELECT * FROM combos").isNotEmpty(), "combos ran after the failure")
        up.downloads.clear()
        sync(up).run(only = setOf(Source.COMBOS))
        assertEquals(listOf(Upstream.SPELLBOOK), up.downloads, "no marker upstream: fetched again")
    }

    @Test
    fun `a format naming a card the database lacks loads nothing`() {
        val up = Fake()
        sync(up).run()
        File(formats, "test.json").writeText("""{"format": "testfmt", "name": "Test", "derives_from": "vintage", "points_budget": 10, "singleton": true, "aliases": ["tf"], "points": {"lightning bolt": 2, "Fire/Ice": 1}}""")
        assertEquals(emptyList(), sync(up).run(only = setOf(Source.FORMATS)).failures)
        assertEquals(listOf(listOf("testfmt", "Fire // Ice", "1"), listOf("testfmt", "Lightning Bolt", "2")), rows("SELECT format, card_name, points FROM custom_format_points ORDER BY card_name"))
        assertEquals(listOf(listOf("[\"tf\"]", "1")), rows("SELECT aliases, singleton FROM custom_formats"))
        File(formats, "test.json").writeText("""{"format": "testfmt", "name": "Test", "points": {"Lightning Bolt": 2, "Not A Card": 1}}""")
        val report = sync(up).run(only = setOf(Source.FORMATS))
        assertContains(report.failures.single().second, "'Not A Card'")
        assertEquals(2, rows("SELECT * FROM custom_format_points").size, "the old points stay")
        File(formats, "test.json").delete()
        val removed = sync(up).run(only = setOf(Source.FORMATS))
        assertContains(removed.notes.getValue(Source.FORMATS).single(), "removed format 'testfmt'")
        assertEquals(0, rows("SELECT * FROM custom_formats").size)
    }
}
