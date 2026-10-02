package mtgoracle.data

import mtgoracle.core.deck.DeckSection
import mtgoracle.core.deck.Printing
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A card's chosen printing goes where its copies go: moving them between
 * the deck and the sideboard, or making one the commander, dropped it, and
 * the history recorded the loss as if it were meant. Undo brings it back.
 */
class PrintingMoveTest {
    private fun printings(db: java.io.File, deckId: Int): List<String> = DriverManager.getConnection("jdbc:sqlite:${db.path}").use { c ->
        c.prepareStatement("SELECT card_name, quantity, is_commander, is_sideboard, set_code, collector_number FROM deck_cards WHERE deck_id = ? ORDER BY is_commander DESC, is_sideboard, card_name").use { st ->
            st.setInt(1, deckId)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add((1..6).joinToString("|") { rs.getString(it).orEmpty() }) } }
        }
    }

    @Test
    fun `the printing goes with its copies, to the sideboard, back, and into the command zone`() {
        val copy = FixtureDb.copy()
        try {
            val db = MtgDb(copy)
            val deckId = LibraryWriter(db).createDeck("printings", null, null)
            val lookup = Lookup(db)
            val writer = DeckWriter(db, lookup.names, lookup.formats)
            writer.add(deckId, "Sol Ring", 2, force = true)
            writer.setPrinting(deckId, "Sol Ring", DeckSection.MAIN, Printing("c18", "222"))

            writer.move(deckId, "Sol Ring", DeckSection.MAIN, DeckSection.SIDEBOARD, 1, force = true)
            assertEquals(listOf("Sol Ring|1|0|0|c18|222", "Sol Ring|1|0|1|c18|222"), printings(copy, deckId), "both rows in the chosen printing")
            writer.move(deckId, "Sol Ring", DeckSection.SIDEBOARD, DeckSection.MAIN, 1, force = true)
            assertEquals(listOf("Sol Ring|2|0|0|c18|222"), printings(copy, deckId), "back: one row, still in it")

            writer.promote(deckId, "Sol Ring", force = true)
            assertEquals(listOf("Sol Ring|1|1|0|c18|222", "Sol Ring|1|0|0|c18|222"), printings(copy, deckId), "the commander copy keeps it too")

            writer.undo(deckId)
            assertEquals(listOf("Sol Ring|2|0|0|c18|222"), printings(copy, deckId), "and undo restores the row as it was")
        } finally {
            copy.parentFile.deleteRecursively()
        }
    }
}
