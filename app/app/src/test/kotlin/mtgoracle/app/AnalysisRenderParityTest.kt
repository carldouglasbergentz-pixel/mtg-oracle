package mtgoracle.app

import mtgoracle.core.analysis.Archetype
import mtgoracle.core.analysis.DeckList
import mtgoracle.core.analysis.Roles
import mtgoracle.data.DbFixture
import mtgoracle.data.Lookup
import mtgoracle.data.ReferenceLists
import mtgoracle.ui.lookup.Rendering
import mtgoracle.ui.lookup.renderComparison
import mtgoracle.ui.lookup.renderProfile
import mtgoracle.ui.lookup.renderRanking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The analysis tables as the TUI and analyse_archetype print them, byte for
 * byte: profile, most-played ranking, range comparison and head-to-head, over
 * every reference folder and the user's own decks.
 */
class AnalysisRenderParityTest {

    private val python = """
        import sys, sqlite3
        from pathlib import Path
        sys.path.insert(0, 'scripts')
        import analyse_archetype as aa
        from mtg_oracle import services as s, renderer as r, roles, decks as d
        kw = dict(role_labels=roles.LABELS, role_order=aa.REPORT_ROLES)
        T = aa.TURNS
        out = []
        def report(decks):
            profiles = s.profile_decks(decks)
            ranking, low = s.rank_cards(decks, aa.REPORT_ROLES)
            out.append(r.render_profile(profiles, turns=T, low_confidence=low, **kw))
            out.append(r.render_ranking(ranking, len(profiles), **kw))
            if len(decks) > 1:
                out.append(r.render_comparison(s.compare_decks(decks[0], decks[1:], turns=T), turns=T, **kw))
                out.append(r.render_comparison(s.compare_decks(decks[0], decks[1:2], turns=T), turns=T, **kw))
        for folder in sorted(Path('docs/reports/decklists').iterdir(), key=lambda p: p.name.lower()):
            if folder.is_dir():
                report(aa.read_dir(folder, False))
        conn = sqlite3.connect('data/mtg.db')
        own = [s.deck_cards_for_analysis(s.DeckRef(deck=n, folder=fo if fo is not None else d.UNSORTED))
               for n, fo in conn.execute('SELECT d.name, f.name FROM decks d LEFT JOIN deck_folders f ON f.id = d.folder_id ORDER BY d.id')]
        for deck in own:
            _, low = s.rank_cards([deck], aa.REPORT_ROLES)
            out.append(r.render_profile([s.profile_deck(deck['name'], deck['cards'])], turns=T, low_confidence=low, **kw))
        if len(own) > 1:
            out.append(r.render_comparison(s.compare_decks(own[0], own[1:2], turns=T), turns=T, **kw))
        sys.stdout.write(chr(30).join(out))
    """.trimIndent()

    private fun text(r: Rendering) = r.lines(200).joinToString("\n") { it.text }

    @Test
    fun `the analysis tables are Python's, byte for byte`() {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        val db = DbFixture.readOnly()
        val lookup = Lookup(db)
        val kotlin = mutableListOf<String>()
        fun report(decks: List<DeckList>) {
            val pool = lookup.analysis.pool(decks.flatMap { it.cards.keys })
            val ranking = Archetype.rank(decks, pool, Roles.REPORT_ROLES)
            kotlin += text(renderProfile(decks.map { Archetype.profile(it, pool) }, ranking.lowConfidence))
            kotlin += text(renderRanking(ranking, decks.size))
            if (decks.size > 1) {
                kotlin += text(renderComparison(Archetype.compare(decks[0], decks.drop(1), pool)))
                kotlin += text(renderComparison(Archetype.compare(decks[0], decks.subList(1, 2), pool)))
            }
        }
        File(DbFixture.repoRoot, "docs/reports/decklists").listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name.lowercase() }
            .forEach { report(ReferenceLists.read(it).map { e -> e.deck }) }
        val own = db.read { conn -> conn.prepareStatement("SELECT id, name FROM decks ORDER BY id").use { st -> st.executeQuery().use { rs ->
            buildList { while (rs.next()) add(lookup.analysis.deckList(rs.getInt(1), rs.getString(2))) }
        } } }
        for (deck in own) {
            val pool = lookup.analysis.pool(deck.cards.keys)
            kotlin += text(renderProfile(listOf(Archetype.profile(deck, pool)), Archetype.rank(listOf(deck), pool, Roles.REPORT_ROLES).lowConfidence))
        }
        if (own.size > 1) {
            val pool = lookup.analysis.pool(own[0].cards.keys + own[1].cards.keys)
            kotlin += text(renderComparison(Archetype.compare(own[0], own.subList(1, 2), pool)))
        }

        val expected = DbFixture.python(python).replace("\r\n", "\n").split('\u001E')
        assertEquals(expected.size, kotlin.size, "number of reports")
        expected.indices.forEach { i ->
            val want = expected[i].lines()
            val got = kotlin[i].lines()
            val first = want.indices.firstOrNull { it >= got.size || want[it] != got[it] }
            assertTrue(first == null && want.size == got.size,
                "report $i differs at line $first:\n  python: ${first?.let { want.getOrNull(it) }}\n  kotlin: ${first?.let { got.getOrNull(it) }}")
        }
    }
}
