package mtgoracle.data.sync

import mtgoracle.core.sync.Snapshot
import mtgoracle.core.sync.Source
import mtgoracle.core.sync.SyncReport
import mtgoracle.core.sync.TableChange
import mtgoracle.data.CardNames
import mtgoracle.data.MtgDb
import java.io.File
import java.sql.Connection

/**
 * The data pipeline (ported from scripts/sync.py, retired 2026-10): each source in [Source] order, each
 * skipped when its upstream marker hasn't moved (unless [run]'s `force`), a
 * failing source recorded without stopping the rest, and a before/after
 * changelog. Downloads land in [rawDir]; the format files are [formatsDir].
 * Never touches a deck.
 */
class Sync(
    private val db: MtgDb,
    private val upstream: Upstream,
    private val rawDir: File,
    private val formatsDir: File,
    /** One line per step, for a status line or a log. */
    private val log: (String) -> Unit = {},
) {
    fun run(force: Boolean = false, only: Set<Source> = Source.entries.toSet()): SyncReport {
        val selected = Source.entries.filter { it in only }
        val before = snapshot()
        val failures = mutableListOf<Pair<Source, String>>()
        val notes = linkedMapOf<Source, List<String>>()
        val bulk by lazy { upstream.scryfallBulk() }
        for (source in selected) {
            log("${source.key}: ${source.label}")
            try {
                val said = when (source) {
                    Source.CARDS -> cards(bulk, force)
                    Source.RULES -> rules(force)
                    Source.COMBOS -> combos(force)
                    Source.TAGS -> tags()
                    Source.ORACLETAGS -> oracleTags(bulk, force)
                    Source.FORMATS -> formats()
                }
                if (said.isNotEmpty()) notes[source] = said
            } catch (e: Exception) {
                log("${source.key} failed: ${e.message}")
                failures += source to (e.message ?: e::class.simpleName.orEmpty())
            }
        }
        return SyncReport(selected, diff(before, snapshot()), failures, notes, state())
    }

    private fun upToDate(conn: Connection, key: String, marker: String, force: Boolean) = !force && marker.isNotEmpty() && SyncState.get(conn, key) == marker

    /**
     * An ingest despite an unchanged marker, when a table the build depends
     * on was never filled (a new column or table after a migration).
     */
    private fun backfill(conn: Connection, type: String): String? {
        val checks = when (type) {
            "oracle_cards" -> listOf("SELECT EXISTS (SELECT 1 FROM card_legalities)" to "card_legalities is empty",
                "SELECT EXISTS (SELECT 1 FROM cards WHERE games IS NOT NULL)" to "cards.games has never been populated")
            "rulings" -> listOf("SELECT EXISTS (SELECT 1 FROM rulings)" to "rulings is empty")
            else -> emptyList()
        }
        return checks.firstOrNull { (sql, _) -> conn.createStatement().use { st -> st.executeQuery(sql).use { it.next(); it.getInt(1) == 0 } } }?.second
    }

    private fun cards(bulk: Map<String, Bulk>, force: Boolean): List<String> {
        val notes = mutableListOf<String>()
        for (type in listOf("oracle_cards", "rulings")) {
            val entry = bulk[type] ?: run { log("Scryfall has no bulk entry for $type: skipped"); null } ?: continue
            val marker = entry.updatedAt.orEmpty()
            val key = "scryfall_$type"
            val skip = db.read { conn -> upToDate(conn, key, marker, force) && backfill(conn, type).also { it?.let { r -> log("$type unchanged upstream, but $r: ingesting") } } == null }
            if (skip) { log("$type: up to date ($marker)"); continue }
            val uri = entry.jsonlUri ?: error("Scryfall's $type entry has no jsonl_download_uri: the bulk-data API changed")
            val file = File(rawDir, "scryfall_$type.jsonl.gz")
            log("downloading $type")
            upstream.download(uri, file)
            db.write(foreignKeys = false) { conn ->
                if (type == "oracle_cards") {
                    val r = CardsIngest.cards(conn, file)
                    log("$type: ${r.cards} cards (${r.skipped} non-card entries skipped)")
                    if (r.collisions > 0) notes += "${r.collisions} duplicate-name export entries resolved to the printing legal somewhere"
                    SyncState.set(conn, key, marker, r.cards)
                } else {
                    val n = CardsIngest.rulings(conn, file)
                    log("$type: $n rulings")
                    SyncState.set(conn, key, marker, n)
                }
            }
        }
        return notes
    }

    private fun rules(force: Boolean): List<String> {
        val (url, release) = RulesIngest.discover(upstream.rulesPage()) ?: error("no MagicCompRules .txt link on the rules page: its layout may have changed")
        if (db.read { upToDate(it, "wizards_cr", release, force) }) { log("rules: up to date (release $release)"); return emptyList() }
        log("downloading the rules ($release)")
        val bytes = upstream.bytes(url)
        rawDir.mkdirs()
        File(rawDir, "MagicCompRules.txt").writeBytes(bytes)
        val rules = RulesIngest.parse(String(bytes, Charsets.UTF_8).removePrefix("﻿"))
        check(rules.isNotEmpty()) { "the rules text parsed to no rules: nothing was changed" }
        db.write(foreignKeys = false) { conn ->
            val stored = RulesIngest.write(conn, rules)
            SyncState.set(conn, "wizards_cr", release, stored)
            log("rules: $stored")
        }
        return emptyList()
    }

    private fun combos(force: Boolean): List<String> {
        // Unknown when the HEAD fails: then nothing says upstream is unchanged, so it is fetched.
        val marker = runCatching { upstream.spellbookMarker() }.getOrDefault("")
        if (db.read { upToDate(it, "spellbook_variants", marker, force) }) { log("combos: up to date ($marker)"); return emptyList() }
        val file = File(rawDir, "spellbook_variants.json")
        log("downloading the combos")
        upstream.download(Upstream.SPELLBOOK, file)
        db.write(foreignKeys = false) { conn ->
            val local = SyncState.get(conn, "spellbook_variants")
            val n = CombosIngest.write(conn, Variants.read(file))
            // No marker: keep the previous one, not a placeholder the next marker-less run would match.
            SyncState.set(conn, "spellbook_variants", marker.ifEmpty { local.orEmpty() }, n)
            log("combos: $n")
        }
        return emptyList()
    }

    private fun tags(): List<String> {
        db.write(foreignKeys = false) { conn ->
            val (tags, abilities) = CardTagger.retag(conn)
            val now = nowStamp()
            SyncState.set(conn, "local_tags", now, tags + abilities)
            log("tags: $tags tag rows, $abilities ability rows")
        }
        return emptyList()
    }

    private fun oracleTags(bulk: Map<String, Bulk>, force: Boolean): List<String> {
        val entry = bulk["oracle_tags"] ?: error("no oracle_tags entry in Scryfall's bulk-data index")
        val marker = entry.updatedAt.orEmpty()
        val uri = entry.jsonlUri ?: error("the oracle_tags entry has no jsonl_download_uri")
        if (db.read { upToDate(it, "scryfall_oracle_tags", marker, force) }) { log("oracle tags: up to date ($marker)"); return emptyList() }
        val file = File(rawDir, "oracle_tags.jsonl.gz")
        log("downloading the oracle tags")
        upstream.download(uri, file)
        // Parsed whole before anything is deleted: a truncated download fails with the old rows still there.
        val byCard = OracleTagsIngest.invert(file)
        check(byCard.isNotEmpty()) { "the oracle tags parsed to no taggings: the old rows are kept" }
        db.write(foreignKeys = false) { conn ->
            val local = SyncState.get(conn, "scryfall_oracle_tags")
            val rows = OracleTagsIngest.write(conn, byCard)
            SyncState.set(conn, "scryfall_oracle_tags", marker.ifEmpty { local.orEmpty() }, rows)
            log("oracle tags: $rows taggings")
        }
        return emptyList()
    }

    private fun formats(): List<String> {
        val names = CardNames(db.read { conn -> conn.createStatement().use { st -> st.executeQuery("SELECT name FROM cards").use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } } })
        val definitions = FormatsIngest.loadAll(formatsDir, names) ?: run { log("formats: no ${formatsDir.name}/ directory, nothing to load"); return emptyList() }
        val notes = mutableListOf<String>()
        db.write(foreignKeys = false) { conn ->
            val (total, removed) = FormatsIngest.write(conn, definitions)
            SyncState.set(conn, "custom_formats", nowStamp(), total)
            removed.forEach { (format, decks) -> notes += "removed format '$format': its file is gone" + if (decks > 0) " ($decks deck(s) still name it and now get no format rules)" else "" }
            log("formats: ${definitions.size}, $total pointed cards")
        }
        return notes
    }

    private fun snapshot(): Snapshot = db.read { conn ->
        fun pairs(sql: String) = conn.createStatement().use { st -> st.executeQuery(sql).use { rs -> buildMap { while (rs.next()) put(rs.getString(1), rs.getString(2).orEmpty().hashCode()) } } }
        fun count(table: String) = runCatching { conn.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM $table").use { it.next(); it.getInt(1) } } }.getOrDefault(0)
        Snapshot(
            cards = pairs("SELECT oracle_id, oracle_text FROM cards WHERE oracle_id IS NOT NULL"),
            rules = pairs("SELECT rule_number, text FROM rules"),
            combos = conn.createStatement().use { st -> st.executeQuery("SELECT id FROM combos").use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } } },
            counts = listOf("rulings", "card_tags", "card_abilities", "custom_format_points", "card_oracle_tags").associateWith(::count),
        )
    }

    private fun diff(a: Snapshot, b: Snapshot): List<TableChange> {
        fun keyed(table: String, x: Map<String, Int>, y: Map<String, Int>) = TableChange(
            table, (y.keys - x.keys).size, (x.keys - y.keys).size, (x.keys intersect y.keys).count { x[it] != y[it] }, null, y.size,
        )
        fun net(table: String, column: String) = TableChange(table, null, null, null, b.counts.getValue(column) - a.counts.getValue(column), b.counts.getValue(column))
        return listOf(
            keyed("cards", a.cards, b.cards), keyed("rules", a.rules, b.rules),
            TableChange("combos", (b.combos - a.combos).size, (a.combos - b.combos).size, null, null, b.combos.size),
            net("rulings", "rulings"), net("tags", "card_tags"), net("abilities", "card_abilities"),
            net("points", "custom_format_points"), net("oracletags", "card_oracle_tags"),
        )
    }

    private fun state(): List<Triple<String, String?, Int?>> = db.read { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT source, updated_at, row_count FROM sync_state ORDER BY source").use { rs ->
                buildList { while (rs.next()) add(Triple(rs.getString(1), rs.getString(2), rs.getObject(3)?.let { rs.getInt(3) })) }
            }
        }
    }
}
