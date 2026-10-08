package mtgoracle.data

import org.junit.jupiter.api.Assumptions.assumeTrue
import mtgoracle.core.lookup.DeckScope
import mtgoracle.core.lookup.SearchLanguage
import mtgoracle.core.lookup.SearchNode
import mtgoracle.core.lookup.SearchQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `like:` on the user's database, held to what a player would call alike:
 * for a mana dork, a counterspell, a removal spell, a tutor and a burn
 * spell, the likest ten are mostly cards Tagger tags so, and the cards
 * that do it in the same words come first.
 */
class LikeQualityTest {
    private fun top(likes: LikeIndex, card: String, n: Int = 10) = likes.similar(card).take(n)

    private fun tagged(db: MtgDb, card: String, predicate: (String) -> Boolean): Boolean =
        db.read { c -> c.query("SELECT tag FROM card_oracle_tags WHERE card_name = ?", card) { getString(1) } }.any(predicate)

    @Test
    fun `the likest cards are the ones that do the same`() {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        val db = MtgDb(DbFixture.copy())
        val likes = LikeIndex(db)
        val groups = listOf(
            Triple("Llanowar Elves", { t: String -> t == "mana dork" }, listOf("Elvish Mystic", "Fyndhorn Elves")),
            Triple("Counterspell", { t: String -> t.startsWith("counterspell") }, emptyList()),
            Triple("Swords to Plowshares", { t: String -> t.startsWith("removal-") || t == "spot removal" }, emptyList()),
            Triple("Demonic Tutor", { t: String -> t.startsWith("tutor-") }, emptyList()),
            Triple("Lightning Bolt", { t: String -> t.startsWith("burn ") }, emptyList()),
        )
        for ((card, tag, first) in groups) {
            val ten = top(likes, card)
            println("LIKE $card: ${top(likes, card, 15)}")
            val hits = ten.count { tagged(db, it, tag) }
            assertTrue(hits >= 8, "$card: only $hits of the likest ten do the same: $ten")
            assertTrue(first.all { it in ten.take(3) }, "$card: ${first} first: $ten")
        }
    }

    @Test
    fun `a deck's likest are ranked within what the deck can play`() {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        val db = DbFixture.readOnly()
        val lookup = Lookup(db)
        // As the workspace's "find similar" asks it in a mono-blue premodern deck: the deck's filters added to the tree.
        val deck = DeckScope(0, "test", commanderCi = listOf("U"), format = lookup.formats.resolve("premodern"))
        val (query, _) = deck.restrict(SearchQuery(SearchNode.Term("like", ":", "Counterspell")))
        val page = lookup.search.page(query, pageSize = 10)
        println("LIKE Counterspell in mono-blue premodern (${page.total}): ${page.rows.map { it.name }}")
        // Ranked within the deck's filters (SearchLanguage.likeAmong), not filtered out of the likest of every card.
        assertTrue(page.total >= 50, "only ${page.total} cards like Counterspell")
        val legal = lookup.search.count(SearchLanguage.parse("f:premodern ci<=u"))
        val all = lookup.search.page(query, pageSize = 1000).rows.map { it.name }
        assertEquals(all.size, lookup.search.count(query.and(SearchLanguage.parse("f:premodern ci<=u").where!!)), "all within the deck")
        assertTrue(all.size < legal)
        val hits = page.rows.count { tagged(db, it.name) { t -> t.startsWith("counterspell") } }
        assertTrue(hits >= 8, "only $hits of the likest ten counter: ${page.rows.map { it.name }}")
    }
}
