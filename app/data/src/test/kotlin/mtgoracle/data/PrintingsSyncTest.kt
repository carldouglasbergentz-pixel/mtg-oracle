package mtgoracle.data

import mtgoracle.core.sync.Source
import mtgoracle.data.sync.Bulk
import mtgoracle.data.sync.CardsIngest
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
 * cut of default_cards), the incremental sync that fetches only the sets
 * whose card count moved, and `cards.games` widened to every printing's.
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

    private fun card(name: String, set: String, number: String, lang: String = "en", digital: Boolean = false, games: String = "paper") =
        """{"object":"card","id":"${"%08x".format((name + set + number + lang).hashCode())}-0000-0000-0000-000000000000","name":"$name","lang":"$lang","set":"$set","set_name":"Set $set","collector_number":"$number","released_at":"2026-01-01","layout":"normal","games":["$games"],"digital":$digital,"artist":"A","image_uris":{"normal":"x"},"finishes":["nonfoil"]}"""

    /** A database with these cards, each on paper only, as one oracle printing might say. */
    private fun cardsDb(vararg names: String): File = File(dir, "mtg.db").apply { createNewFile() }.also { f ->
        MtgDb(f).migrate(null)
        MtgDb(f).write { conn -> names.forEach { n -> conn.prepareStatement("INSERT INTO cards (name, games) VALUES (?, 'paper')").use { it.setString(1, n); it.executeUpdate() } } }
    }

    private inner class Fake : Upstream {
        val calls = mutableListOf<String>()
        var sets = """{"data":[{"code":"aaa","card_count":2,"digital":false},{"code":"bbb","card_count":1,"digital":false}]}"""
        val searches = mutableMapOf<String, String?>()
        val bulk = File(dir, "default.jsonl.gz").also { f ->
            GZIPOutputStream(f.outputStream()).bufferedWriter().use { w ->
                // Counterspell's MTGO printing is digital: no printing row, but its game counts.
                listOf(card("Sol Ring", "aaa", "1"), card("Lightning Bolt", "aaa", "2"), card("Counterspell", "bbb", "1"),
                    card("Counterspell", "mmm", "7", digital = true, games = "mtgo")).forEach { w.write(it); w.write("\n") }
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
        val db = cardsDb("Sol Ring", "Lightning Bolt", "Counterspell", "Brainstorm")
        val up = Fake()
        fun sync(force: Boolean = false) = Sync(MtgDb(db), up, File(dir, "raw"), File(dir, "formats")).run(force, only = setOf(Source.PRINTINGS))
        assertEquals(emptyList(), sync().failures)
        assertEquals(listOf(Upstream.SCRYFALL_SETS, "download"), up.calls, "the first sync reads the bulk export")
        assertEquals(3, rows(db, "SELECT COUNT(*) FROM printings").single()[0]!!.toInt())
        assertEquals("mtgo,paper", games(db, "Counterspell"), "a digital printing's game is the card's")
        assertEquals("paper", games(db, "Sol Ring"))

        up.calls.clear()
        sync()
        assertEquals(listOf(Upstream.SCRYFALL_SETS), up.calls, "nothing moved: only the sets list")

        // aaa gains a card (two pages, in three languages), ccc is new and only in Japanese, ddd is digital.
        up.sets = """{"data":[{"code":"aaa","card_count":3,"digital":false},{"code":"bbb","card_count":1,"digital":false},
            {"code":"ccc","card_count":1,"digital":false},{"code":"ddd","card_count":9,"digital":true}]}"""
        val page2 = "https://api.scryfall.com/cards/search?page=2&q=e%3Aaaa"
        up.searches[Upstream.scryfallSetSearch("aaa")] = """{"has_more":true,"next_page":"$page2","data":[${card("Sol Ring", "aaa", "1", lang = "de")},""" +
            """${card("Sol Ring", "aaa", "1")},${card("Lightning Bolt", "aaa", "2")},${card("Lightning Bolt", "aaa", "3", lang = "ja")}]}"""
        up.searches[page2] = """{"has_more":false,"data":[${card("Sol Ring", "aaa", "1s")}]}"""
        up.searches[Upstream.scryfallSetSearch("ccc")] = """{"has_more":false,"data":[${card("Brainstorm", "ccc", "1", lang = "ja")}]}"""
        up.searches[Upstream.scryfallSetSearch("ddd")] =
            """{"has_more":false,"data":[${card("Lightning Bolt", "ddd", "4", digital = true, games = "arena")}]}"""
        up.calls.clear()
        val report = sync()
        assertEquals(emptyList(), report.failures)
        assertEquals(listOf(Upstream.SCRYFALL_SETS, Upstream.scryfallSetSearch("aaa"), page2, Upstream.scryfallSetSearch("ccc"), Upstream.scryfallSetSearch("ddd")),
            up.calls, "only the moved sets, every page, a digital one for its games; bbb unchanged")
        assertEquals("arena,paper", games(db, "Lightning Bolt"), "the digital set's game is added")
        assertTrue(rows(db, "SELECT 1 FROM printings WHERE set_code = 'ddd'").isEmpty(), "but its printing is not one")
        assertEquals(listOf(listOf("1", "en"), listOf("1s", "en"), listOf("2", "en"), listOf("3", "ja")),
            rows(db, "SELECT collector_number, lang FROM printings WHERE set_code = 'aaa' ORDER BY collector_number"),
            "English where there is one, else the language it was printed in (a Japanese-only one kept, as default_cards keeps it)")
        assertEquals(listOf(listOf("Brainstorm", "ja")), rows(db, "SELECT card_name, lang FROM printings WHERE set_code = 'ccc'"))
        val note = report.notes.getValue(Source.PRINTINGS).single()
        assertTrue("AAA: 4" in note && "DDD: digital, 1 card(s) gained a game" in note, note)

        // bbb grows, and its second page doesn't come: the source fails, and bbb is as it was and still due.
        up.sets = up.sets.replace(""""code":"bbb","card_count":1""", """"code":"bbb","card_count":2""")
        up.searches[Upstream.scryfallSetSearch("bbb")] =
            """{"has_more":true,"next_page":"https://api.scryfall.com/cards/search?page=2&q=e%3Abbb","data":[${card("Opt", "bbb", "2")}]}"""
        val failed = sync()
        assertEquals(1, failed.failures.size, "${failed.failures}")
        assertEquals(listOf(listOf("Counterspell")), rows(db, "SELECT card_name FROM printings WHERE set_code = 'bbb'"), "not replaced by its first page")
        assertEquals("1", rows(db, "SELECT card_count FROM printing_sets WHERE set_code = 'bbb'").single()[0], "and fetched again next time")

        up.calls.clear()
        sync(force = true)
        assertTrue("download" in up.calls, "force reads the bulk export again")
        assertEquals(3, rows(db, "SELECT COUNT(*) FROM printings").single()[0]!!.toInt(), "and replaces everything with it")
        assertEquals("arena,paper", games(db, "Lightning Bolt"), "a card's games only grow: the export lacks ddd, the game stays")
    }

    private fun games(db: File, name: String): String? = rows(db, "SELECT games FROM cards WHERE name = '$name'").single()[0]

    @Test
    fun `games are widened once on a database that has printings but never widened them, and a cards sync keeps them`() {
        val db = cardsDb("Counterspell")
        val up = Fake()
        fun sync() = Sync(MtgDb(db), up, File(dir, "raw"), File(dir, "formats")).run(only = setOf(Source.PRINTINGS))
        sync()
        // As a database from before this change: printings, and no marker.
        MtgDb(db).write { conn -> conn.createStatement().use { it.executeUpdate("UPDATE cards SET games = 'paper'"); it.executeUpdate("DELETE FROM sync_state WHERE source = 'scryfall_printings_games'") } }
        up.calls.clear()
        sync()
        assertEquals(listOf(Upstream.SCRYFALL_SETS, "download"), up.calls, "the export is read once more")
        assertEquals("mtgo,paper", games(db, "Counterspell"))
        up.calls.clear()
        sync()
        assertEquals(listOf(Upstream.SCRYFALL_SETS), up.calls, "and then not again")

        // The oracle printing says paper only: merged into what is stored, not written over it.
        val oracle = File(dir, "oracle.jsonl.gz").also { f ->
            GZIPOutputStream(f.outputStream()).bufferedWriter().use { it.write(card("Counterspell", "bbb", "1")) }
        }
        MtgDb(db).write(foreignKeys = false) { conn -> CardsIngest.cards(conn, oracle) }
        assertEquals("mtgo,paper", games(db, "Counterspell"))
    }

    @Test
    fun `with no cards yet there is nothing to widen, and the export is read again until there are`() {
        val db = File(dir, "mtg.db").apply { createNewFile() }.also { MtgDb(it).migrate(null) }
        val up = Fake()
        repeat(2) { Sync(MtgDb(db), up, File(dir, "raw"), File(dir, "formats")).run(only = setOf(Source.PRINTINGS)) }
        assertEquals(2, up.calls.count { it == "download" })
    }
}
