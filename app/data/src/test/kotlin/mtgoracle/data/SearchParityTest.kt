package mtgoracle.data

import mtgoracle.core.lookup.SearchError
import mtgoracle.core.lookup.SearchLanguage
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The port against its original: every query here runs through Python's
 * scryfall_search and through ours, on the same database, and the count and
 * the first page (order included) must agree. One query per operator and
 * sort field at least, plus the inputs name resolution has to tolerate.
 */
class SearchParityTest {

    private val queries = listOf(
        "t:creature c:u mv<=3 o:flash",
        "kw:flying (c:w or c:u) -t:artifact",
        "c=wu t:instant",
        "c:c t:artifact",
        "c:\"blue white\" t:creature",
        "c:{U}{W} t:sorcery",
        "ci<=UR t:instant",
        "ci:bg t:land",
        "ci=wubrg",
        "ci>=wu t:enchantment",
        "ci:wubrg mv=0 t:artifact",
        "pow>=4 t:creature r:mythic",
        "pow=* t:creature",
        "tou<0",
        "-pow>=4 t:goblin",
        "mv!=3 t:instant c:r",
        "cmc>7",
        "f:commander game:paper t:artifact mv<=2 order:asc_edhrec",
        "banned:commander",
        "restricted:vintage",
        "restricted:duel",
        "f:canlander t:land order:desc_mv",
        "f:\"competitive brawl\" t:instant order:asc_name",
        "legal:edh t:saga",
        "layout:modal_dfc order:asc_mv",
        "is:reserved order:desc_rarity",
        "n:bolt order:asc_ci",
        "n=\"Lightning Bolt\"",
        "n:_____",
        "name:\"sol \"",
        "\"enters the battlefield\" o:\"draw a card\" c:u",
        "not flash t:instant c:u mv=1",
        "t:goblin or t:kobold order:desc_pow",
        "order:asc_tou t:wall",
        "order:asc_color n:dragon",
        "order:asc_power order:desc_name t:elf",
        "sort:desc_cmc t:sphinx",
        "game:arena -game:paper",
        "oracle:\"100%\"",
        "(kw:flying or kw:trample) c:g mv<=3",
        "pow!3",
        "f:brawll",
        "order:mv",
        "foo:bar",
        "game:xbox",
        "c:xyz",
        "t:goblin -",
    )

    private val names = listOf(
        "sol ring", "fire/ice", "Fire//Ice", "fire // ice", "delver of secrets", "eomer, marshal of rohan",
        "Kongming, Sleeping Dragon", "lim-duls vault", "%", "aether vial", "jotun grunt", "Nonexistent Card",
        "  Lightning Bolt  ", "emeritus of ideation", "thassa’s oracle", "LIGHTNING BOLT", "brazen borrower",
    )

    private fun python(mode: String, lines: List<String>): List<String> {
        val input = File.createTempFile("parity-", ".txt").apply { writeText(lines.joinToString("\n"), Charsets.UTF_8); deleteOnExit() }
        val code = """
            import sys
            from mtg_oracle import scryfall_search as ss, queries as q
            mode, path = sys.argv[1], sys.argv[2]
            for line in open(path, encoding='utf-8').read().split('\n'):
                if mode == 'search':
                    try:
                        print(str(ss.count_query(line)) + '\t' + '\x1f'.join(r['name'] for r in ss.run_query(line, limit=25)))
                    except ss.SearchError:
                        print('ERR')
                else:
                    print(q.resolve_card_name(line) or '-')
        """.trimIndent()
        return DbFixture.python(code, mode, input.absolutePath).trimEnd('\n', '\r').lines().map { it.trimEnd('\r') }
    }

    @Test
    fun `every query counts and orders as Python's does`() {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        val lookup = Lookup(DbFixture.readOnly())
        val expected = python("search", queries)
        val actual = queries.map { q ->
            try {
                val page = lookup.search.page(SearchLanguage.parse(q), pageSize = 25)
                "${page.total}\t" + page.rows.joinToString("\u001F") { it.name }
            } catch (e: SearchError) {
                "ERR"
            }
        }
        queries.indices.forEach { i -> assertEquals(expected[i], actual[i], "query ${queries[i]}") }
        assertEquals(queries.size, expected.size)
    }

    @Test
    fun `every name resolves as Python resolves it`() {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        val lookup = Lookup(DbFixture.readOnly())
        val expected = python("names", names)
        names.indices.forEach { i -> assertEquals(expected[i], lookup.names.resolve(names[i]) ?: "-", "name '${names[i]}'") }
    }
}
