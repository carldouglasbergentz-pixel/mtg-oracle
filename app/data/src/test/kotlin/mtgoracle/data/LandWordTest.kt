package mtgoracle.data

import mtgoracle.core.lookup.SearchLanguage
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `is:spell` and `is:permanent` ask about the Land type, the word: "Lander"
 * holds "land", and `is:spell` left out Lander Rizzi, a creature. On the
 * user's database, since the fixture has no Lander.
 */
class LandWordTest {
    @Test
    fun `a Lander is a spell, and a land is still a land`() {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        val lookup = Lookup(DbFixture.readOnly())
        fun finds(query: String) = lookup.search.page(SearchLanguage.parse(query), pageSize = 10).rows.map { it.name }
        assumeTrue(finds("n=\"Lander Rizzi\"").isNotEmpty(), "the database has Lander Rizzi")
        assertTrue("Lander Rizzi" in finds("n=\"Lander Rizzi\" is:spell"))
        assertFalse("Island" in finds("n=Island is:spell"), "a basic land is no spell")
        assertTrue("Island" in finds("n=Island is:permanent"))
        assertTrue("Dryad Arbor" in finds("n=\"Dryad Arbor\" is:permanent") && "Dryad Arbor" !in finds("n=\"Dryad Arbor\" is:spell"),
            "Land Creature: a land, by its first word")
    }
}
