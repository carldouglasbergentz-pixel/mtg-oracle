package mtgoracle.data

import mtgoracle.core.analysis.Archetype
import mtgoracle.core.analysis.Comparison
import mtgoracle.core.analysis.DeckAnalysis
import mtgoracle.core.analysis.DeckList
import mtgoracle.core.analysis.DeckProfile
import mtgoracle.core.analysis.Py
import mtgoracle.core.analysis.Roles
import mtgoracle.core.analysis.hasLandBack
import mtgoracle.core.analysis.isLand
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The analysis engine against its original, on the same database: every
 * card's classification, every deck's analytics, and profiles, rankings and
 * comparisons over every reference folder and the user's own decks. Numbers
 * are compared at twelve decimals, so a rounding or summation difference
 * shows up here rather than as a report that reads one percent off.
 */
class AnalysisParityTest {

    private val db by lazy { DbFixture.readOnly() }
    private val analysis by lazy { Lookup(db).analysis }
    private val referenceDirs: List<File> by lazy {
        File(DbFixture.repoRoot, "docs/reports/decklists").listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name.lowercase() }
    }

    private fun f(x: Double) = Py.fixed(x, 12)
    private fun f(x: Int) = Py.fixed(x.toDouble(), 12)
    private fun bool(b: Boolean) = if (b) "True" else "False"

    /** Line-by-line, reporting the first differences with their context rather than one huge string diff. */
    private fun assertSameLines(expected: List<String>, actual: List<String>, what: String) {
        val diffs = expected.indices.filter { it >= actual.size || expected[it] != actual[it] }.take(8)
        assertTrue(diffs.isEmpty(), "$what differs:\n" + diffs.joinToString("\n") { "  python: ${expected[it]}\n  kotlin: ${actual.getOrNull(it)}" })
        assertEquals(expected.size, actual.size, "$what line count")
    }

    private fun pythonLines(code: String) = DbFixture.python(code).lines().map { it.trimEnd('\r') }.filter { it.isNotEmpty() }

    @Test
    fun `every card in the database classifies as Python classifies it`() {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        val python = pythonLines(
            """
            import sys, sqlite3
            from mtg_oracle import roles, queries as q
            conn = sqlite3.connect('data/mtg.db'); conn.row_factory = sqlite3.Row
            tags = {}
            for n, t in conn.execute('SELECT card_name, tag FROM card_oracle_tags'):
                tags.setdefault(n.lower(), set()).add(t)
            out = []
            for row in conn.execute('SELECT ' + ', '.join(q.CARD_FACT_COLUMNS) + ' FROM cards'):
                card = dict(row)
                cl = roles.classify(card, tags=tags.get(card['name'].lower(), ()))
                c = cl.cost
                out.append(chr(9).join([cl.name, cl.primary, ','.join(cl.roles), str(c.effective), str(c.printed), c.reason, cl.source,
                    str(int(cl.low_confidence)), str(int(cl.engine)), '-' if c.alternative is None else str(c.alternative), c.alternative_reason,
                    str(int(roles.is_land(card))), str(int(roles.has_land_back(card)))]))
            sys.stdout.write(chr(10).join(out))
            """.trimIndent(),
        )
        val kotlin = analysis.everyCard().map { (card, tags) ->
            val cl = Roles.classify(card, tags = tags)
            val c = cl.cost
            listOf(
                cl.name, cl.primary, cl.roles.joinToString(","), c.effective, c.printed, c.reason, cl.source,
                if (cl.lowConfidence) 1 else 0, if (cl.engine) 1 else 0, c.alternative ?: "-", c.alternativeReason,
                if (card.isLand()) 1 else 0, if (card.hasLandBack()) 1 else 0,
            ).joinToString("\t")
        }
        assertTrue(python.size > 30_000, "expected the whole card table, got ${python.size}")
        assertSameLines(python, kotlin, "classification")
    }

    @Test
    fun `every deck's curve, pips and sources are Python's`() {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        val python = pythonLines(
            """
            import sqlite3
            from mtg_oracle import decks as d, analytics as a
            conn = sqlite3.connect('data/mtg.db')
            for i, name, folder in conn.execute('SELECT d.id, d.name, f.name FROM decks d LEFT JOIN deck_folders f ON f.id = d.folder_id ORDER BY d.id'):
                r = a.compute_deck_analytics(d.get_deck(name, folder=folder if folder is not None else d.UNSORTED))
                print(chr(9).join([str(i), ','.join(str(r['mana_curve'][k]) for k in range(7)), '%.12f' % r['mv_avg'],
                    ','.join(str(r['color_pips'][c]) for c in 'WUBRGC'), str(r['pip_total']),
                    ','.join(str(r['mana_sources'][c]) for c in 'WUBRGC'), str(r['nonland_count']), str(r['land_count']), str(r['mdfc_land_count'])]))
            """.trimIndent(),
        )
        val ids = db.read { conn -> conn.query("SELECT id FROM decks ORDER BY id") { getInt(1) } }
        val kotlin = ids.map { id ->
            val r = DeckAnalysis.compute(analysis.rows(id))
            listOf(
                id, r.manaCurve.joinToString(","), f(r.mvAvg), "WUBRGC".map { r.colorPips.getValue(it) }.joinToString(","), r.pipTotal,
                "WUBRGC".map { r.manaSources.getValue(it) }.joinToString(","), r.nonlandCount, r.landCount, r.mdfcLandCount,
            ).joinToString("\t")
        }
        assertTrue(ids.isNotEmpty())
        assertSameLines(python, kotlin, "deck analytics")
    }

    // --- archetype analysis: one report format, printed by both sides ---

    private val pythonReport = """
        import sys
        from pathlib import Path
        sys.path.insert(0, 'scripts')
        import analyse_archetype as aa
        from mtg_oracle import services as s, roles, decks as d
        R, T = aa.REPORT_ROLES, aa.TURNS
        def f(x): return '%.12f' % x
        def mv(m): return ','.join(str(k) + ':' + str(v) for k, v in sorted(m.items()))
        def prof(p):
            print('P ' + p.name + ' size=' + str(p.size) + ' lands=' + str(p.lands) + ' rocks=' + str(p.rocks) + ' backs=' + str(p.land_backs) + ' avg=' + f(p.avg_mv) + ' unresolved=' + '|'.join(p.unresolved))
            print('  counts ' + ','.join(r + ':' + str(p.counts[r]) for r in roles.ROLES))
            for r in roles.ROLES:
                print('  mv ' + r + ' ' + mv(p.role_mv[r]) + ' / ' + mv(p.on_curve_mv[r]) + ' eng=' + str(p.engines.get(r, 0)))
            print('  curve ' + mv(p.curve))
            for play in (True, False):
                for r in R:
                    print('  live ' + str(int(play)) + ' ' + r + ' ' + ','.join(f(v) for v in p.live_curve(r, T, play).values()))
                    print('  ceil ' + str(int(play)) + ' ' + r + ' ' + ','.join(f(v) for v in p.ceiling(r, T, play).values()))
        def rank(decks):
            ranking, low = s.rank_cards(decks, R)
            for r in R:
                for row in ranking[r]:
                    print('R ' + r + ' ' + '|'.join(str(row[k]) for k in ('name', 'n', 'pct', 'mv', 'printed', 'cost', 'primary', 'reason', 'source')))
            print('LOW ' + '|'.join(sorted(low)))
        def compare(subject, refs):
            c = s.compare_decks(subject, refs, turns=T)
            for x in list(c.roles) + [c.mana_sources]:
                print('D ' + ' '.join([x.role, str(x.subject), f(x.ref_mean), str(x.ref_min), str(x.ref_max), f(x.ref_median), f(x.delta), x.verdict]))
            print('AVG ' + f(c.avg_mv[0]) + ' ' + f(c.avg_mv[1]))
            for r, dd in c.curve_delta.items():
                print('CD ' + r + ' ' + ','.join(f(v) for v in dd.values()))
            for x in c.missing:
                print('M ' + '|'.join([x.name, x.role, str(x.mv), str(x.n_lists), str(x.of_lists)]))
            for x in c.unique:
                print('U ' + '|'.join([x.name, x.role, str(x.mv)]))
            for n, dist in c.nearest:
                print('N ' + n + ' ' + f(dist))
        def report(decks):
            for p in s.profile_decks(decks):
                prof(p)
            rank(decks)
            if len(decks) > 1:
                compare(decks[0], decks[1:])
                compare(decks[0], decks[1:2])
        for folder in sorted(Path('docs/reports/decklists').iterdir(), key=lambda p: p.name.lower()):
            if folder.is_dir():
                print('== ' + folder.name)
                report(aa.read_dir(folder, False))
        import sqlite3
        conn = sqlite3.connect('data/mtg.db')
        own = [s.deck_cards_for_analysis(s.DeckRef(deck=n, folder=fo if fo is not None else d.UNSORTED))
               for n, fo in conn.execute('SELECT d.name, f.name FROM decks d LEFT JOIN deck_folders f ON f.id = d.folder_id ORDER BY d.id')]
        print('== own decks')
        report(own)
    """.trimIndent()

    private fun mv(m: Map<Int, Int>) = m.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" }

    private fun MutableList<String>.profile(p: DeckProfile) {
        add("P ${p.name} size=${p.size} lands=${p.lands} rocks=${p.rocks} backs=${p.landBacks} avg=${f(p.avgMv)} unresolved=${p.unresolved.joinToString("|")}")
        add("  counts " + Roles.ROLES.joinToString(",") { "$it:${p.counts.getValue(it)}" })
        Roles.ROLES.forEach { add("  mv $it ${mv(p.roleMv.getValue(it))} / ${mv(p.onCurveMv.getValue(it))} eng=${p.engines[it] ?: 0}") }
        add("  curve ${mv(p.curve)}")
        for (play in listOf(true, false)) for (r in Roles.REPORT_ROLES) {
            val flag = if (play) 1 else 0
            add("  live $flag $r " + p.liveCurve(r, DeckProfile.TURNS, play).values.joinToString(",") { f(it) })
            add("  ceil $flag $r " + p.ceiling(r, DeckProfile.TURNS, play).values.joinToString(",") { f(it) })
        }
    }

    private fun MutableList<String>.comparison(c: Comparison) {
        (c.roles + c.manaSources).forEach {
            add("D " + listOf(it.role, it.subject, f(it.refMean), it.refMin, it.refMax, f(it.refMedian), f(it.delta), it.verdict).joinToString(" "))
        }
        add("AVG ${f(c.avgMv.first)} ${f(c.avgMv.second)}")
        c.curveDelta.forEach { (r, d) -> add("CD $r " + d.values.joinToString(",") { f(it) }) }
        c.missing.forEach { add("M " + listOf(it.name, it.role, it.mv, it.nLists, it.ofLists).joinToString("|")) }
        c.unique.forEach { add("U " + listOf(it.name, it.role, it.mv).joinToString("|")) }
        c.nearest.forEach { (n, d) -> add("N $n ${f(d)}") }
    }

    private fun MutableList<String>.report(decks: List<DeckList>) {
        val pool = analysis.pool(decks.flatMap { it.cards.keys })
        decks.forEach { profile(Archetype.profile(it, pool)) }
        val ranking = Archetype.rank(decks, pool, Roles.REPORT_ROLES)
        for (r in Roles.REPORT_ROLES) for (row in ranking.byRole.getValue(r)) {
            add("R $r " + listOf(row.name, row.n, row.pct, row.mv, row.printed, row.cost, bool(row.primary), row.reason, row.source).joinToString("|"))
        }
        add("LOW " + ranking.lowConfidence.sorted().joinToString("|"))
        if (decks.size > 1) {
            comparison(Archetype.compare(decks[0], decks.drop(1), pool))
            comparison(Archetype.compare(decks[0], decks.subList(1, 2), pool))
        }
    }

    @Test
    fun `profiles, rankings and comparisons are Python's, over every reference folder and the user's decks`() {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        val python = pythonLines(pythonReport)
        val kotlin = buildList {
            for (dir in referenceDirs) {
                add("== ${dir.name}")
                report(ReferenceLists.read(dir).map { it.deck })
            }
            add("== own decks")
            val own = db.read { conn -> conn.query("SELECT id, name FROM decks ORDER BY id") { getInt(1) to getString(2) } }
            report(own.map { (id, name) -> analysis.deckList(id, name) })
        }
        assertTrue(referenceDirs.isNotEmpty())
        assertSameLines(python, kotlin, "archetype report")
    }
}
