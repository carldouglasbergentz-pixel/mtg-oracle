package mtgoracle.app

import mtgoracle.core.analysis.Archetype
import mtgoracle.core.analysis.Roles
import mtgoracle.data.DbFixture
import mtgoracle.data.Lookup
import mtgoracle.ui.lookup.renderComparison
import mtgoracle.ui.lookup.renderProfile
import mtgoracle.ui.lookup.renderRanking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The analysis tables as the app lays them out: only roles the decks have,
 * a note under every table, and nothing wider than the pane but the
 * most-played rows' reasons. The numbers are AnalysisParityTest's.
 */
class AnalysisRenderTest {
    @Test
    fun `reports name only what the decks hold, explain themselves, and fit the pane`() {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        val db = DbFixture.readOnly()
        val lookup = Lookup(db)
        val decks = db.read { c -> c.prepareStatement("SELECT id, name FROM decks ORDER BY id").use { st -> st.executeQuery().use { rs ->
            buildList { while (rs.next()) add(lookup.analysis.deckList(rs.getInt(1), rs.getString(2))) }
        } } }
        assumeTrue(decks.size >= 3, "three decks")
        val pool = lookup.analysis.pool(decks.flatMap { it.cards.keys })
        val profiles = decks.map { Archetype.profile(it, pool) }
        val out = File(System.getProperty("mtgoracle.pngDir"), "analysis-reports.txt")
        val dump = StringBuilder()
        for (width in listOf(72, 88, 140)) {
            for ((i, p) in profiles.withIndex()) {
                val lines = renderProfile(listOf(p)).lines(width)
                val text = lines.joinToString("\n") { it.text }
                if (width == 88 && i < 2) dump.append(text).append("\n\n")
                assertTrue(lines.all { it.text.length <= width }, "profile of ${p.name} at $width:\n$text")
                for (role in Roles.REPORT_ROLES) {
                    val has = (p.counts[role] ?: 0) > 0 || (p.roleMv[role] ?: emptyMap()).values.sum() > 0
                    val row = lines.any { it.text.startsWith(if (role == "mana") "Mana rocks / dorks" else Roles.LABELS.getValue(role)) }
                    assertTrue(has == row, "${p.name}: $role has cards = $has, has a row = $row")
                }
                assertTrue("Cards: each card" in text && "Gap: what the" in text, text)
                assertTrue("=== ON CURVE, by effective mana value ===" in text && lines.any { it.text.startsWith("on the draw") }, text)
                val mvLinks = lines.flatMap { it.spans }.map { it.link }.filterIsInstance<mtgoracle.ui.lookup.OutputLink.Cards>()
                assertEquals(p.curveCards.values.sumOf { it.size }, mvLinks.sumOf { it.names.size }, "the links hold every spell of the curve")
            }
            val set = renderProfile(profiles.take(4)).lines(width)
            assertTrue(set.all { it.text.length <= width }, set.joinToString("\n") { it.text })
            val cmp = renderComparison(Archetype.compare(decks[0], decks.drop(1).take(3), pool)).lines(width)
            assertTrue(cmp.all { it.text.length <= width || it.spans.isNotEmpty() }, cmp.joinToString("\n") { it.text })
            if (width == 88) {
                dump.append(set.joinToString("\n") { it.text }).append("\n\n").append(cmp.joinToString("\n") { it.text }).append("\n\n")
                dump.append(renderRanking(Archetype.rank(decks.take(4), pool, Roles.REPORT_ROLES), 4).lines(width).joinToString("\n") { it.text })
            }
        }
        out.writeText(dump.toString())
    }
}
