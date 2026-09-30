package mtgoracle.core.lookup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Name folding, rule order and format names: the pure pieces lookup stands on. */
class LookupPiecesTest {

    @Test
    fun `folding drops diacritics, ligatures and quotes, keeps hyphens and commas`() {
        assertEquals("lorien revealed", NameFold.fold("Lórien Revealed"))
        assertEquals("aether vial", NameFold.fold("Æther Vial"))
        assertEquals("lim-duls vault", NameFold.fold("Lim-Dûl's Vault"))
        assertEquals("kongming, sleeping dragon", NameFold.fold("Kongming, \"Sleeping Dragon\""))
        assertEquals("eomer, marshal of rohan", NameFold.fold("Éomer, Marshal of Rohan"))
        assertEquals("jotun grunt", NameFold.fold("Jötun Grunt"))
        assertEquals("thassas oracle", NameFold.fold("Thassa’s Oracle"))
    }

    @Test
    fun `rules sort naturally`() {
        val numbers = listOf("702.10a", "702.9", "702.10", "702.2", "702", "100.1b", "100.1a")
        assertEquals(listOf("100.1a", "100.1b", "702", "702.2", "702.9", "702.10", "702.10a"), numbers.sortedWith(RuleOrder))
        assertTrue(RuleOrder.compare("702.9", "702.10") < 0)
        assertTrue(RuleOrder.compare("702.10", "702.10a") < 0)
        assertEquals(0, RuleOrder.compare("702.10A", "702.10a"))
    }

    @Test
    fun `format names fold spaces, hyphens and aliases`() {
        assertEquals("competitivebrawl", Formats.fold("Competitive Brawl"))
        assertEquals("competitivebrawl", Formats.fold("competitive-brawl"))
        assertEquals("commander", Formats.fold("EDH"))
        assertEquals("duel", Formats.fold("1v1 commander"))
        assertEquals("tlr", Formats.fold("Tiny Leaders"))
    }

    private val catalog = FormatCatalog(listOf(
        CustomFormat("canadianhighlander", "Canadian Highlander", listOf("canlander", "Canadian Highlander"), "vintage", 10, singleton = true),
    ))

    @Test
    fun `a custom format resolves through its aliases to the pool it inherits`() {
        val info = catalog.resolve("Canlander")!!
        assertEquals(FormatInfo("canadianhighlander", "Canadian Highlander", "vintage", 10, singleton = true, custom = true), info)
        assertEquals("vintage", catalog.legalityKey("canadian highlander"))
        assertEquals(FormatInfo("commander", "commander", "commander", null, singleton = true, custom = false), catalog.resolve("edh"))
        assertEquals(false, catalog.resolve("modern")!!.singleton)
    }

    @Test
    fun `an unknown format is null to resolve and an error to the search language`() {
        assertNull(catalog.resolve("kitchen table"))
        assertNull(catalog.resolve(null))
        val e = assertFailsWith<SearchError> { catalog.legalityKey("brawll") }
        assertTrue("brawl" in e.message!! && "canadianhighlander" in e.message!!, e.message)
    }
}
