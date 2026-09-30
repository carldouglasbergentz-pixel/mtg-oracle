package mtgoracle.data

import mtgoracle.core.lookup.SearchError
import mtgoracle.core.lookup.SearchLanguage
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * tests/test_search_language.py's database cases, ported, plus the lookups
 * Python never tested. Reads the real database read-only; skipped without it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LookupTest {
    private lateinit var lookup: Lookup
    private var realStamp = 0L

    @BeforeAll
    fun setUp() {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        realStamp = DbFixture.realDb.lastModified()
        lookup = Lookup(DbFixture.readOnly())
    }

    @AfterAll
    fun tearDown() {
        if (::lookup.isInitialized) assertEquals(realStamp, DbFixture.realDb.lastModified(), "the real database must not be touched")
    }

    private fun count(q: String) = lookup.search.page(SearchLanguage.parse(q), pageSize = 1).total
    private fun names(q: String, size: Int = 50) = lookup.search.page(SearchLanguage.parse(q), pageSize = size).rows.map { it.name }

    // --- search counts (TestSearchCounts) ---

    @Test
    fun `negation keeps rows where the column is null`() {
        assertEquals(count(""), count("pow>=4") + count("-pow>=4"))
    }

    @Test
    fun `explicit and is juxtaposition`() {
        assertEquals(count("t:creature c:u"), count("t:creature and c:u"))
    }

    @Test
    fun `like wildcards in input are literal`() {
        assertTrue(count("n:_____") < 100)
    }

    @Test
    fun `negative power is a number`() {
        assertTrue("Spinal Parasite" in names("pow<0"))
        assertFalse("Tarmogoyf" in names("pow<=1", 1000)) // '*' is not 1
    }

    @Test
    fun `an oversized page clamps rather than resetting`() {
        assertTrue(names("t:goblin", 2000).size > 50)
    }

    @Test
    fun `paging walks the same order`() {
        val q = SearchLanguage.parse("t:goblin order:asc_mv")
        val all = lookup.search.page(q, pageSize = 60).rows.map { it.name }
        val second = lookup.search.page(q, page = 2, pageSize = 30)
        assertEquals(all.drop(30), second.rows.map { it.name })
        assertTrue(second.hasPrev)
    }

    @Test
    fun `compile errors are search errors`() {
        for (bad in listOf("pow!3", "f:brawll", "foo:bar", "game:xbox", "is:foil", "c:xyz", "mv>three", "order:asc_colour")) {
            assertFailsWith<SearchError>(bad) { lookup.search.page(SearchLanguage.parse(bad)) }
        }
    }

    // --- names (TestNameTolerance) ---

    @Test
    fun `names resolve tolerantly`() {
        val n = lookup.names
        assertEquals("Éomer, Marshal of Rohan", n.resolve("eomer, marshal of rohan"))
        assertEquals("Kongming, \"Sleeping Dragon\"", n.resolve("Kongming, Sleeping Dragon"))
        assertEquals("Fire // Ice", n.resolve("fire/ice"))
        assertEquals("Fire // Ice", n.resolve("Fire//Ice"))
        assertEquals("Sol Ring", n.resolve("  sol RING "))
        assertTrue(n.resolve("Delver of Secrets")!!.startsWith("Delver of Secrets // "))
        assertNull(n.resolve("%"))
        assertNull(n.resolve(""))
    }

    @Test
    fun `rulings and combos resolve the front face`() {
        assertTrue(lookup.cards.rulings("Delver of Secrets").isNotEmpty())
        assertTrue(lookup.combos.withCard("Birgi, God of Storytelling").isNotEmpty())
    }

    @Test
    fun `combos with all ignore a duplicate name`() {
        val one = lookup.combos.withAll(listOf("Thassa's Oracle")).map { it.id }
        assertTrue(one.isNotEmpty())
        assertEquals(one, lookup.combos.withAll(listOf("Thassa's Oracle", "thassa's oracle")).map { it.id })
    }

    // --- the card profile (TestCardProfile) ---

    @Test
    fun `corrections match whole names, and a face`() {
        val strangle = lookup.cards.profile("Strangle")!!.corrections.map { it.id }.toSet()
        val geist = lookup.cards.profile("Strangleroot Geist")!!.corrections.map { it.id }.toSet()
        assertTrue(geist.isNotEmpty())
        assertTrue((strangle intersect geist).isEmpty())
        assertTrue(lookup.cards.profile("Emeritus of Ideation")!!.corrections.isNotEmpty())
    }

    @Test
    fun `the profile carries tags, abilities, legality and flagged combos`() {
        val bolt = lookup.cards.profile("lightning bolt")!!
        assertEquals("Lightning Bolt", bolt.name)
        assertEquals("{R}", bolt.manaCost)
        assertTrue("legacy" in bolt.legalities.getValue("legal"))
        assertTrue(bolt.tags.getValue("type").contains("instant"), bolt.tags.toString())
        val altar = lookup.cards.profile("Ashnod's Altar")!!
        assertTrue(altar.combos.size in 1..10)
        assertTrue(altar.combos.zipWithNext().all { (a, b) -> a.cardCount <= b.cardCount })
        assertTrue(altar.abilities.any { it.isManaAbility })
        assertNull(altar.combosFilteredByCi)
    }

    @Test
    fun `restricted in duel is no_commander`() {
        val sql = "SELECT card_name FROM card_legalities WHERE format = 'duel' AND status = 'restricted' LIMIT 1"
        val card = DbFixture.readOnly().read { c -> c.prepareStatement(sql).use { s -> s.executeQuery().use { r -> if (r.next()) r.getString(1) else null } } }
        assumeTrue(card != null, "no duel restricted row")
        val legal = lookup.cards.profile(card!!)!!.legalities
        assertTrue("duel" in legal.getValue("no_commander"))
        assertFalse(legal["restricted"].orEmpty().contains("duel"))
    }

    @Test
    fun `combos filtered to a deck identity fit it`() {
        val filtered = lookup.cards.profile("Ashnod's Altar", restrictToCi = listOf("B"))!!
        assertEquals("B", filtered.combosFilteredByCi)
        assertTrue(filtered.combos.all { c -> c.colorIdentity.orEmpty().all { it == 'B' || it == 'C' } }) // Spellbook spells colourless `C`
        assertEquals("C", lookup.cards.profile("Ashnod's Altar", restrictToCi = emptyList())!!.combosFilteredByCi)
    }

    // --- rules (TestRuleOrder) and the rest ---

    @Test
    fun `children sort naturally`() {
        val numbers = lookup.rules.rule("702")!!.children.map { it.number }
        assertTrue(numbers.indexOf("702.2") < numbers.indexOf("702.10"))
        assertNotNull(lookup.rules.rule("605.1A"), "the letter suffix is case-insensitive")
        assertNull(lookup.rules.rule("999.999"))
    }

    @Test
    fun `rules search is literal and naturally ordered`() {
        val hits = lookup.rules.search("mana ability", limit = 200)
        assertTrue(hits.isNotEmpty())
        assertEquals(hits.map { it.number }, hits.map { it.number }.sortedWith(mtgoracle.core.lookup.RuleOrder))
        assertTrue(lookup.rules.search("%").isEmpty() || lookup.rules.search("%").all { "%" in it.text })
    }

    @Test
    fun `a combo's detail has cards and steps, and a user combo is found too`() {
        val id = lookup.combos.withCard("Thassa's Oracle").first { it.source == "spellbook" }.id
        val combo = lookup.combos.detail(id)!!
        assertTrue(combo.cards.any { it.name == "Thassa's Oracle" })
        assertTrue(combo.steps.isNotEmpty())
        val user = DbFixture.readOnly().read { c -> c.prepareStatement("SELECT id FROM user_combos LIMIT 1").use { s -> s.executeQuery().use { r -> if (r.next()) r.getString(1) else null } } }
        if (user != null) assertEquals("user", lookup.combos.detail(user)!!.source)
        assertNull(lookup.combos.detail("no-such-combo"))
    }

    @Test
    fun `corrections filter on one box, literally`() {
        val all = lookup.corrections.list()
        assertTrue(all.isNotEmpty())
        assertTrue(all.all { it.relatesTo.isNotEmpty() })
        assertTrue(lookup.corrections.list("tutor").isNotEmpty())
        assertTrue(lookup.corrections.list("t_tor").isEmpty())
        assertTrue(lookup.corrections.list("%").isEmpty())
    }

    @Test
    fun `a commander deck's scope is its identity and format`() {
        val deckId = DbFixture.readOnly().read { c ->
            c.prepareStatement("SELECT deck_id FROM deck_cards WHERE is_commander = 1 LIMIT 1").use { s -> s.executeQuery().use { r -> if (r.next()) r.getInt(1) else null } }
        }
        assumeTrue(deckId != null, "no commander deck")
        val scope = lookup.deckScope(deckId!!)!!
        assertNotNull(scope.commanderCi)
        val (query, labels) = scope.restrict(SearchLanguage.parse("t:creature order:asc_mv"))
        assertTrue(labels.first().startsWith("ci<="))
        val page = lookup.search.page(query, filters = labels)
        assertTrue(page.total in 1 until count("t:creature"))
        assertNull(lookup.deckScope(-1))
    }
}
