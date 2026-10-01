package mtgoracle.data

import mtgoracle.core.sync.Source
import mtgoracle.data.sync.Bulk
import mtgoracle.data.sync.Sync
import mtgoracle.data.sync.Upstream
import java.io.File
import java.sql.DriverManager
import java.util.zip.GZIPOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The printings source: what counts as a paper printing (on the fixture's
 * cut of default_cards), and the incremental sync that fetches only the sets
 * whose card count moved.
 */
class PrintingsSyncTest {
    private val dir = createTempDirectory("mtg-oracle-printings-").toFile()

    @AfterTest fun clean() { dir.deleteRecursively() }

    private fun rows(db: File, sql: String): List<List<String?>> = DriverManager.getConnection("jdbc:sqlite:${db.path}").use { c ->
        c.createStatement().use { st -> st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add((1..rs.metaData.columnCount).map { rs.getString(it) }) } } }
    }

    @Test
    fun `the fixture's printings - paper only, tokens out, a printing in French only kept, faces and labels`() {
        val db = FixtureDb.file
        val names = rows(db, "SELECT card_name, set_code, lang FROM printings").map { it[0] to it[1] }
        assertTrue(names.count { it.first == "Sol Ring" } > 50, "every paper Sol Ring")
        assertTrue(names.none { it.first == "Bronze Horse" }, "an MTGO-only printing is dropped")
        assertTrue(names.none { it.first == "Spirit" }, "a token is dropped")
        val foreign = rows(db, "SELECT card_name, lang FROM printings WHERE set_code = 'fbb'")
        assertTrue(listOf("Island", "fr") in foreign && foreign.all { it[1] == "fr" }, "Foreign Black Border, printed in French only: kept, as French: $foreign")
        assertTrue(rows(db, "SELECT image_faces FROM printings WHERE card_name LIKE 'Delver of Secrets%'").all { it[0] == "2" }, "a transform card has two images")
        assertTrue(rows(db, "SELECT image_faces FROM printings WHERE card_name = 'Fire // Ice'").all { it[0] == "1" }, "a split card has one")
        assertTrue(rows(db, "SELECT labels FROM printings WHERE card_name = 'Sol Ring'").any { "borderless" in it[0].orEmpty() }, "labels say borderless")
        assertEquals(168, rows(db, "SELECT COUNT(*) FROM printing_sets").single()[0]!!.toInt())
    }

    private fun card(name: String, set: String, number: String, lang: String = "en", digital: Boolean = false) =
        """{"object":"card","id":"${"%08x".format((name + set + number + lang).hashCode())}-0000-0000-0000-000000000000","name":"$name","lang":"$lang","set":"$set","set_name":"Set $set","collector_number":"$number","released_at":"2026-01-01","layout":"normal","games":["paper"],"digital":$digital,"artist":"A","image_uris":{"normal":"x"},"finishes":["nonfoil"]}"""

    private inner class Fake : Upstream {
        val calls = mutableListOf<String>()
        var sets = """{"data":[{"code":"aaa","card_count":2,"digital":false},{"code":"bbb","card_count":1,"digital":false}]}"""
        val searches = mutableMapOf<String, String?>()
        val bulk = File(dir, "default.jsonl.gz").also { f ->
            GZIPOutputStream(f.outputStream()).bufferedWriter().use { w ->
                listOf(card("Sol Ring", "aaa", "1"), card("Lightning Bolt", "aaa", "2"), card("Counterspell", "bbb", "1")).forEach { w.write(it); w.write("\n") }
            }
        }
        override fun scryfallBulk() = mapOf("default_cards" to Bulk("t1", "default"))
        override fun rulesPage() = error("not here")
        override fun bytes(url: String) = error("not here")
        override fun spellbookMarker() = ""
        override fun download(url: String, target: File) { calls += "download"; target.parentFile.mkdirs(); bulk.copyTo(target, overwrite = true) }
        override fun scryfallApi(url: String): String? { calls += url; return if (url == Upstream.SCRYFALL_SETS) sets else searches[url] }
    }

    @Test
    fun `later syncs fetch only the sets whose card count moved`() {
        val db = File(dir, "mtg.db").apply { createNewFile() }.also { MtgDb(it).migrate(null) }
        val up = Fake()
        fun sync(force: Boolean = false) = Sync(MtgDb(db), up, File(dir, "raw"), File(dir, "formats")).run(force, only = setOf(Source.PRINTINGS))
        assertEquals(emptyList(), sync().failures)
        assertEquals(listOf(Upstream.SCRYFALL_SETS, "download"), up.calls, "the first sync reads the bulk export")
        assertEquals(3, rows(db, "SELECT COUNT(*) FROM printings").single()[0]!!.toInt())

        up.calls.clear()
        sync()
        assertEquals(listOf(Upstream.SCRYFALL_SETS), up.calls, "nothing moved: only the sets list")

        // aaa gains a card (two pages), ccc is new and only in Japanese, ddd is digital.
        up.sets = """{"data":[{"code":"aaa","card_count":3,"digital":false},{"code":"bbb","card_count":1,"digital":false},
            {"code":"ccc","card_count":1,"digital":false},{"code":"ddd","card_count":9,"digital":true}]}"""
        val page2 = "https://api.scryfall.com/cards/search?page=2&q=e%3Aaaa"
        up.searches[Upstream.scryfallSetSearch("aaa")] =
            """{"has_more":true,"next_page":"$page2","data":[${card("Sol Ring", "aaa", "1")},${card("Lightning Bolt", "aaa", "2")}]}"""
        up.searches[page2] = """{"has_more":false,"data":[${card("Sol Ring", "aaa", "1s")}]}"""
        up.searches[Upstream.scryfallSetSearch("ccc")] = null
        up.searches[Upstream.scryfallSetSearch("ccc", anyLanguage = true)] = """{"has_more":false,"data":[${card("Brainstorm", "ccc", "1", lang = "ja")}]}"""
        up.calls.clear()
        val report = sync()
        assertEquals(emptyList(), report.failures)
        assertEquals(listOf(Upstream.SCRYFALL_SETS, Upstream.scryfallSetSearch("aaa"), page2, Upstream.scryfallSetSearch("ccc"), Upstream.scryfallSetSearch("ccc", anyLanguage = true)), up.calls,
            "only the moved sets, every page; a digital set never; bbb unchanged")
        assertEquals(listOf("1", "1s", "2"), rows(db, "SELECT collector_number FROM printings WHERE set_code = 'aaa' ORDER BY collector_number").map { it[0] })
        assertEquals(listOf(listOf("Brainstorm", "ja")), rows(db, "SELECT card_name, lang FROM printings WHERE set_code = 'ccc'"))
        assertTrue(report.notes.getValue(Source.PRINTINGS).single().contains("AAA: 3"), report.notes.toString())

        up.calls.clear()
        sync(force = true)
        assertTrue("download" in up.calls, "force reads the bulk export again")
        assertEquals(3, rows(db, "SELECT COUNT(*) FROM printings").single()[0]!!.toInt(), "and replaces everything with it")
    }
}
