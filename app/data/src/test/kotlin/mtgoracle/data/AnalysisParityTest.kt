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
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The analysis engine against its original, on the fixture: every card's
 * classification, every deck's analytics, and profiles, rankings and
 * comparisons over every reference folder, read as files and as the decks
 * they were imported into, against what Python's engine printed on the same
 * data (`expected/classification.txt`, `deck-analytics.txt`,
 * `archetype-report.txt`). Numbers are compared at twelve decimals, so a
 * rounding or summation difference shows up here rather than as a report
 * that reads one percent off.
 */
class AnalysisParityTest {

    private val db by lazy { MtgDb(FixtureDb.file) }
    private val analysis by lazy { Lookup(db).analysis }
    private val referenceDirs: List<File> by lazy {
        FixtureDb.decklists.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name.lowercase() }
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

    @Test
    fun `every card in the database classifies as Python classifies it`() {
        val python = FixtureDb.expected("classification.txt")
        val kotlin = analysis.everyCard().map { (card, tags) ->
            val cl = Roles.classify(card, tags = tags)
            val c = cl.cost
            listOf(
                cl.name, cl.primary, cl.roles.joinToString(","), c.effective, c.printed, c.reason, cl.source,
                if (cl.lowConfidence) 1 else 0, if (cl.engine) 1 else 0, c.alternative ?: "-", c.alternativeReason,
                if (card.isLand()) 1 else 0, if (card.hasLandBack()) 1 else 0,
            ).joinToString("\t")
        }
        assertTrue(python.size > 2_500, "expected the whole card table, got ${python.size}")
        assertSameLines(python, kotlin, "classification")
    }

    @Test
    fun `every deck's curve, pips and sources are Python's`() {
        val python = FixtureDb.expected("deck-analytics.txt")
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
    fun `profiles, rankings and comparisons are Python's, over every reference folder and the decks made of them`() {
        val python = FixtureDb.expected("archetype-report.txt")
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
