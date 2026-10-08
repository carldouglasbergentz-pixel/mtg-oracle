package mtgoracle.data

import mtgoracle.core.lookup.SearchError
import mtgoracle.core.lookup.SearchLanguage
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The port against its original: every query here ran through Python's
 * scryfall_search on the fixture (`expected/search.txt`), and ours must give
 * the same count and the same first page, order included. One query per
 * operator and sort field at least, plus the inputs name resolution has to
 * tolerate (`expected/names.txt`). A deliberate change to the language edits
 * the expected line with it.
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
        // Free text and its ranking, and the operators added after step 3.
        "bolt",
        "lightning bolt",
        "counterspell",
        "goblin mv<=1 c:r",
        "\"draw a card\" t:instant c:u mv=1",
        "-bolt t:instant c:r mv=1",
        "bolt or shock",
        "elf order:desc_pow",
        "m:{U}{U} t:instant",
        "m:2uu",
        "m={1}{U} t:instant",
        "m:{U}{1} t:instant",
        "mana:{G/W}{G/W}",
        "c:m t:creature mv<=2",
        "otag:removal c:w mv<=2",
        "otag:mana-rock mv=2",
        "function:ramp c:g mv=2",
        "is:commander ci:bg",
        "is:permanent mv=0 -t:land",
        "is:spell c:c mv=0",
        "is:historic t:instant",
        "is:dfc t:werewolf",
        "is:mdfc t:land",
        "is:split c:m",
        "f:dc t:instant c:u mv=1",
        "f:chl t:land order:asc_name",
        "m:2uq",
        "c=m",
        "pow!3",
        "f:brawll",
        "order:mv",
        "foo:bar",
        "game:xbox",
        "c:xyz",
        "t:goblin -",
        // Ours, not Python's: what is left of a limited deck's pool (its sideboard), by the deck's id.
        "pool:999999",
        "pool:abc",
        // Ours too: the cards most like a card, likest first unless the search orders them (LikeIndex).
        "like:\"lightning bolt\"",
        "like:\"lightning bolt\" mv<=1",
        "like:\"lightning bolt\" order:asc_name",
        "-like:\"lightning bolt\" t:instant c:r mv=1",
        "like:\"no such card\"",
    )

    private val names = listOf(
        "sol ring", "fire/ice", "Fire//Ice", "fire // ice", "delver of secrets", "eomer, marshal of rohan",
        "Kongming, Sleeping Dragon", "lim-duls vault", "%", "aether vial", "jotun grunt", "Nonexistent Card",
        "  Lightning Bolt  ", "emeritus of ideation", "thassa’s oracle", "LIGHTNING BOLT", "brazen borrower",
    )

    @Test
    fun `every query counts and orders as Python's does`() {
        val lookup = Lookup(MtgDb(FixtureDb.file))
        val expected = FixtureDb.expected("search.txt")
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
        val lookup = Lookup(MtgDb(FixtureDb.file))
        val expected = FixtureDb.expected("names.txt")
        names.indices.forEach { i -> assertEquals(expected[i], lookup.names.resolve(names[i]) ?: "-", "name '${names[i]}'") }
    }
}
