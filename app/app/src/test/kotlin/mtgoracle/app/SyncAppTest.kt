package mtgoracle.app

import mtgoracle.data.DbFixture
import mtgoracle.data.FixtureDb
import mtgoracle.data.sync.Bulk
import mtgoracle.data.sync.Upstream
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.sql.DriverManager
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `sync` from the command line, on a copy, with a stand-in for the network:
 * the report lands in the output, a card new upstream can be looked up at
 * once (the lookup was rebuilt), and the scrollback, the command history and
 * every deck are as they were.
 */
class SyncAppTest {
    private val assets = File(System.getProperty("mtgoracle.forgeAssets"))
    private lateinit var data: File

    @AfterTest fun close() { if (this::data.isInitialized) data.deleteRecursively() }

    private fun gz(file: File, vararg lines: String) = file.also { f ->
        GZIPOutputStream(f.outputStream()).bufferedWriter(Charsets.UTF_8).use { w -> lines.forEach { w.write(it); w.write("\n") } }
    }

    private fun count(db: File, sql: String) = DriverManager.getConnection("jdbc:sqlite:${db.path}").use { c ->
        c.createStatement().use { st -> st.executeQuery(sql).use { it.next(); it.getInt(1) } }
    }

    @Test
    fun `sync reports, and the new card can be looked up at once`() {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        val copy = DbFixture.copy()
        data = copy.parentFile
        val exports = File(data, "exports").apply { mkdirs() }
        val cards = gz(File(exports, "cards.gz"),
            """{"name": "Zyzzyx, Sync Tester", "oracle_id": "zz1", "oracle_text": "Flying", "mana_cost": "{U}", "cmc": 1, "type_line": "Creature — Bird", "layout": "normal", "games": ["paper"], "legalities": {"vintage": "legal"}}""")
        val empty = gz(File(exports, "empty.gz"), """{"oracle_id": "none", "comment": "x"}""")
        val tags = gz(File(exports, "tags.gz"), """{"label": "evasion", "taggings": [{"oracle_id": "zz1"}]}""")
        val variants = File(exports, "variants.json").apply { writeText("""{"variants": [{"id": "z-1", "uses": [{"card": {"name": "Zyzzyx, Sync Tester"}}], "description": "Fly."}]}""") }
        val decksBefore = count(copy, "SELECT COUNT(*) FROM deck_cards")

        val app = AppController(AppPaths(data, assets, forgeHome = Scenario.home))
        app.upstream = object : Upstream {
            override fun scryfallBulk() = mapOf("oracle_cards" to Bulk("t1", "cards"), "rulings" to Bulk("t1", "rulings"), "oracle_tags" to Bulk("t1", "tags"))
            override fun rulesPage() = """MagicCompRules%2020990101.txt https://media.wizards.com/2099/downloads/MagicCompRules%2020990101.txt"""
            override fun bytes(url: String) = "1. Game Concepts\n100. General\n100.1. A rule.".toByteArray()
            override fun spellbookMarker() = "e1"
            override fun download(url: String, target: File) {
                target.parentFile.mkdirs()
                (when (url) { "cards" -> cards; "rulings" -> empty; "tags" -> tags; else -> variants }).copyTo(target, overwrite = true)
            }
        }
        app.boot()
        val ui = app.lookupUi!!
        ui.submit("card Sol Ring")
        ui.command.history.submit("card Sol Ring")
        val history = ui.command.history
        val outputBefore = ui.output.entries.size
        ui.submit("sync")
        val deadline = System.currentTimeMillis() + 120_000
        while (app.notice?.startsWith("sync done") != true) {
            if (System.currentTimeMillis() > deadline) fail("the sync did not finish: ${app.notice}")
            Thread.sleep(100)
        }
        val ui2 = app.lookupUi!!
        val text = { ui2.output.entries.flatMap { it.rendering.lines(120) }.joinToString("\n") { it.text } }
        assertTrue("=== sync: cards, rules, combos, tags, oracletags, formats ===" in text(), text())
        assertTrue(ui2.output.entries.size > outputBefore, "the scrollback is kept and the report added")
        assertTrue(ui2.command.history === history && history.older("") == "card Sol Ring", "the command history is kept")
        ui2.submit("card zyzzyx, sync tester")
        assertTrue("Zyzzyx, Sync Tester" in text() && "(card not found" !in text().substringAfterLast("> card zyzzyx"), "the new card resolves at once")
        assertEquals(decksBefore, count(copy, "SELECT COUNT(*) FROM deck_cards"), "no deck was touched")
        assertEquals(1, count(copy, "SELECT COUNT(*) FROM combos"))
    }

    @Test
    fun `a first start creates an empty database, and the first sync fills it`() {
        data = kotlin.io.path.createTempDirectory("mtg-oracle-first-start-").toFile()
        val app = AppController(AppPaths(data, assets, forgeHome = Scenario.home))
        app.upstream = FixtureDb.Exports(FixtureDb.raw)
        app.boot()
        assertEquals(Screen.Library, app.screen)
        assertTrue(app.notice!!.contains("[ Sync ]"), app.notice)
        assertEquals(0, count(File(data, "mtg.db"), "SELECT COUNT(*) FROM cards"))
        app.lookupUi!!.submit("sync")
        val deadline = System.currentTimeMillis() + 120_000
        while (app.notice?.startsWith("sync done") != true) {
            if (System.currentTimeMillis() > deadline) fail("the sync did not finish: ${app.notice}")
            Thread.sleep(100)
        }
        assertTrue(count(File(data, "mtg.db"), "SELECT COUNT(*) FROM cards") > 2_000)
        val ui = app.lookupUi!!
        ui.submit("card sol ring")
        assertTrue(ui.output.entries.flatMap { it.rendering.lines(120) }.any { "IS MANA ABILITY" in it.text }, "a card can be looked up")
    }
}
