package mtgoracle.data

import mtgoracle.core.deck.DeckParser
import mtgoracle.data.sync.Bulk
import mtgoracle.data.sync.Sync
import mtgoracle.data.sync.Upstream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream

/**
 * The frozen test database: a 3,000-card cut of the upstream exports
 * (`fixture/raw`, made once on 2026-10-01), ingested by the app's own sync
 * into a fresh database, with the reference lists in `fixture/decklists`
 * imported as decks. The parity tests compare against answers the Python
 * original gave on this same data (`fixture/expected`), so unlike
 * data/mtg.db it never moves under them, and it needs no user database.
 */
object FixtureDb {
    val dir: File = File(DbFixture.repoRoot, "app/data/src/testFixtures/fixture")
    val raw = File(dir, "raw")
    val decklists = File(dir, "decklists")

    /** The format each reference folder's decks are imported with. */
    val FOLDER_FORMATS = mapOf("duel-commander-2026" to "duel", "elminster-duel-commander" to "duel", "uw-canlander" to "canlander")

    /** One answer Python gave, as lines. */
    fun expected(name: String): List<String> = File(dir, "expected/$name").readText(Charsets.UTF_8).replace("\r\n", "\n").trimEnd('\n').split('\n')

    /** The fixture's exports, served as the network would serve them. */
    class Exports(private val raw: File) : Upstream {
        override fun scryfallBulk() = listOf("oracle_cards", "rulings", "oracle_tags").associateWith { Bulk("fixture", "fixture:$it") }
        override fun rulesPage() = """<a href="https://media.wizards.com/2026/downloads/MagicCompRules%2020260101.txt">rules</a>"""
        override fun bytes(url: String) = gunzip(File(raw, "MagicCompRules.txt.gz"))
        override fun spellbookMarker() = "fixture"
        override fun download(url: String, target: File) {
            target.parentFile.mkdirs()
            when (url) {
                "fixture:oracle_cards" -> copy(File(raw, "scryfall_oracle_cards.jsonl.gz"), target)
                "fixture:rulings" -> copy(File(raw, "scryfall_rulings.jsonl.gz"), target)
                "fixture:oracle_tags" -> copy(File(raw, "oracle_tags.jsonl.gz"), target)
                Upstream.SPELLBOOK -> target.writeBytes(gunzip(File(raw, "spellbook_variants.json.gz")))
                else -> error("no fixture export for $url")
            }
        }

        private fun copy(from: File, to: File) { Files.copy(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        private fun gunzip(file: File) = GZIPInputStream(file.inputStream()).use { it.readBytes() }
    }

    /** Built once per test JVM, a few seconds. Read it; a test that writes takes [copy]. */
    val file: File by lazy { build() }

    /** A throwaway copy of the fixture in its own temp dir. */
    fun copy(): File {
        val target = File(Files.createTempDirectory("mtg-oracle-fixture-").toFile(), "mtg.db")
        Files.copy(file.toPath(), target.toPath())
        return target
    }

    private fun build(): File {
        val home = Files.createTempDirectory("mtg-oracle-fixture-db-").toFile()
        Runtime.getRuntime().addShutdownHook(Thread { home.deleteRecursively() })
        val db = File(home, "mtg.db").apply { createNewFile() }
        MtgDb(db).migrate(backups = null)
        val report = Sync(MtgDb(db), Exports(raw), File(home, "raw"), File(dir, "formats")).run(force = true)
        check(report.failures.isEmpty()) { "the fixture did not sync: ${report.failures}" }
        val mtg = MtgDb(db)
        val lookup = Lookup(mtg)
        val writer = DeckWriter(mtg, lookup.names, lookup.formats)
        val library = LibraryWriter(mtg)
        for (folder in decklists.listFiles { f -> f.isDirectory }!!.sortedBy { it.name.lowercase() }) {
            val folderId = library.createFolder(folder.name)
            for (list in folder.listFiles { f -> f.extension == "txt" }!!.sortedBy { it.name.lowercase() }) {
                writer.importDeck(list.nameWithoutExtension, folderId, FOLDER_FORMATS.getValue(folder.name), DeckParser.parse(list.readText(Charsets.UTF_8)))
            }
        }
        return db
    }
}
