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
 * A valid but empty cards or rulings export changes nothing. The cards source
 * clears every legality before it reads the export, and an empty one left
 * every card illegal everywhere until a later sync, while the other sources
 * already refused to parse to nothing.
 */
class EmptyExportTest {
    private val dir = createTempDirectory("mtg-oracle-empty-").toFile()

    @AfterTest fun clean() { dir.deleteRecursively() }

    @Test
    fun `an empty cards export fails the source and keeps the legalities`() {
        val db = File(dir, "mtg.db").apply { createNewFile() }.also { MtgDb(it).migrate(null) }
        DriverManager.getConnection("jdbc:sqlite:${db.path}").use { c ->
            c.createStatement().use { st ->
                st.executeUpdate("INSERT INTO cards (name, color_identity) VALUES ('Sol Ring', '')")
                st.executeUpdate("INSERT INTO card_legalities (card_name, format, status) VALUES ('Sol Ring', 'commander', 'legal')")
            }
        }
        val empty = File(dir, "empty.jsonl.gz").also { f -> GZIPOutputStream(f.outputStream()).use { } }
        val upstream = object : Upstream {
            override fun scryfallBulk() = mapOf("oracle_cards" to Bulk("t2", "oracle"), "rulings" to Bulk("t2", "rulings"))
            override fun rulesPage() = error("not here")
            override fun bytes(url: String) = error("not here")
            override fun spellbookMarker() = ""
            override fun download(url: String, target: File) { target.parentFile.mkdirs(); empty.copyTo(target, overwrite = true) }
        }
        val report = Sync(MtgDb(db), upstream, File(dir, "raw"), File(dir, "formats")).run(force = true, only = setOf(Source.CARDS))
        assertEquals(listOf(Source.CARDS), report.failures.map { it.first }, "${report.failures}")
        assertTrue("held no cards" in report.failures.single().second, report.failures.single().second)
        val legal = DriverManager.getConnection("jdbc:sqlite:${db.path}").use { c ->
            c.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM card_legalities").use { it.next(); it.getInt(1) } }
        }
        assertEquals(1, legal, "nothing was committed")
    }
}
