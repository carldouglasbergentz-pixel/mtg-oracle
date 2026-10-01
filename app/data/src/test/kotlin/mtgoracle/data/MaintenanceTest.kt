package mtgoracle.data

import mtgoracle.core.deck.DeckRefusal
import mtgoracle.data.sync.Prune
import java.io.File
import java.sql.DriverManager
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Pruning stale card rows and the user's own combos, on a fresh database. */
class MaintenanceTest {
    private val dir = createTempDirectory("mtg-oracle-maint-").toFile()
    private val db = MtgDb(File(dir, "mtg.db").apply { createNewFile() }).also { it.migrate(null) }

    @AfterTest fun clean() { dir.deleteRecursively() }

    private fun sql(vararg statements: String) = DriverManager.getConnection("jdbc:sqlite:${db.file.path}").use { c -> c.createStatement().use { st -> statements.forEach { st.executeUpdate(it) } } }
    private fun count(sql: String) = DriverManager.getConnection("jdbc:sqlite:${db.file.path}").use { c -> c.createStatement().use { st -> st.executeQuery(sql).use { it.next(); it.getInt(1) } } }

    private fun card(name: String, oracleId: String? = "o-$name", ci: String? = "", layout: String = "normal") =
        "INSERT INTO cards (name, oracle_id, color_identity, games, layout) VALUES ('$name', ${oracleId?.let { "'$it'" } ?: "NULL"}, ${ci?.let { "'$it'" } ?: "NULL"}, 'paper', '$layout')"

    @Test
    fun `prune lists stale rows, keeps a deck's, deletes on request with what hangs off them`() {
        sql(
            card("Sol Ring"), card("Blood Crypt // Blood Crypt", oracleId = null, ci = null), card("Old Rebalance", ci = null),
            card("Counters", layout = "front_card"), card("Kept By A Deck", oracleId = null),
            "INSERT INTO rulings (card_name, oracle_id, date, text) VALUES ('Old Rebalance', 'o', 'd', 't')",
            "INSERT INTO decks (name, format, created_at, updated_at) VALUES ('Mine', NULL, 'now', 'now')",
            "INSERT INTO deck_cards (deck_id, card_name, quantity, is_commander, is_sideboard, added_at) VALUES (1, 'Kept By A Deck', 1, 0, 0, 'now')",
        )
        val dry = Prune.run(db)
        assertEquals(listOf("Blood Crypt // Blood Crypt", "Counters", "Old Rebalance"), dry.prunable)
        assertEquals(listOf("Kept By A Deck"), dry.kept)
        assertTrue(dry.deleted.isEmpty() && count("SELECT COUNT(*) FROM cards") == 5, "a dry run deletes nothing")
        val done = Prune.run(db, delete = true)
        assertEquals(3, done.deleted["cards"])
        assertEquals(1, done.deleted["rulings"])
        assertEquals(2, count("SELECT COUNT(*) FROM cards"), "Sol Ring and the deck's card stay")
    }

    @Test
    fun `too many stale rows to believe deletes nothing, and an unfilled column is not trusted`() {
        sql(card("Real"))
        (1..300).forEach { sql(card("Stale $it", ci = null)) }
        val r = Prune.run(db, delete = true)
        assertTrue(r.refused && r.deleted.isEmpty())
        assertEquals(301, count("SELECT COUNT(*) FROM cards"))

        sql("DELETE FROM cards", "INSERT INTO cards (name, oracle_id, color_identity, layout) VALUES ('Fresh Column', 'o', '', 'normal')")
        assertEquals(listOf("games"), Prune.run(db).ignored, "no card has games yet: that check would flag every row")
    }

    @Test
    fun `a user combo resolves its cards, takes their colours, and can be removed`() {
        sql(
            "INSERT INTO cards (name, color_identity) VALUES ('Thassa''s Oracle', 'U')",
            "INSERT INTO cards (name, color_identity) VALUES ('Demonic Consultation', 'B')",
            "INSERT INTO cards (name, color_identity) VALUES ('Sol Ring', '')",
        )
        val combos = UserCombos(db, CardNames(listOf("Thassa's Oracle", "Demonic Consultation", "Sol Ring")))
        val id = combos.add(listOf("thassas oracle", "Demonic Consultation"), "Cast Consultation naming a card not in the deck, then Oracle.", "Thoracle")
        assertEquals("user-001", id)
        assertEquals("UB", DriverManager.getConnection("jdbc:sqlite:${db.file.path}").use { c -> c.createStatement().use { st -> st.executeQuery("SELECT color_identity FROM user_combos").use { it.next(); it.getString(1) } } }, "WUBRG order")
        assertEquals(2, count("SELECT COUNT(*) FROM user_combo_cards WHERE combo_id = 'user-001'"))
        assertEquals("user-002", combos.add(listOf("Sol Ring", "Thassa's Oracle"), "x"))
        assertFailsWith<DeckRefusal> { combos.add(listOf("Sol Ring", "Not A Card"), "x") }
        assertFailsWith<DeckRefusal> { combos.add(listOf("Sol Ring"), "x") }
        assertFailsWith<DeckRefusal> { combos.remove("1234-5678") }
        assertTrue(combos.remove("user-001"))
        assertEquals(0, count("SELECT COUNT(*) FROM user_combo_cards WHERE combo_id = 'user-001'"))
        assertEquals(false, combos.remove("user-001"))
    }
}
