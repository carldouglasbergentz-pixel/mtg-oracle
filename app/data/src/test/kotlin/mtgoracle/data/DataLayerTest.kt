package mtgoracle.data

import mtgoracle.core.deck.AiCopy
import mtgoracle.core.play.GameMode
import mtgoracle.core.play.GameRecord
import mtgoracle.core.play.Winner
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.sql.SQLException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Against a temp copy of data/mtg.db (DbFixture); skipped when there is no database. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DataLayerTest {
    private lateinit var copy: File
    private lateinit var db: MtgDb

    @BeforeAll
    fun setUp() {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        copy = DbFixture.copy()
        db = MtgDb(copy)
    }

    @AfterAll
    fun tearDown() {
        if (!::copy.isInitialized) return
        DbFixture.assertUntouched()
        copy.parentFile.deleteRecursively()
    }

    @Test
    fun `the contract schema passes the check`() {
        db.checkSchema()
    }

    @Test
    fun `folders and decks read, unsorted decks last`() {
        val folders = Library(db).folders()
        assertTrue(folders.any { it.name == "Canadian Highlander" && it.format == "canlander" })
        val decks = Library(db).decks()
        assertTrue(decks.isNotEmpty())
        val firstUnsorted = decks.indexOfFirst { it.folderId == null }
        if (firstUnsorted >= 0) assertTrue(decks.drop(firstUnsorted).all { it.folderId == null })
    }

    @Test
    fun `a deck reads with card info and substitutions, and builds its AI copy`() {
        val summary = Library(db).decks().firstOrNull { it.name == "Rakdos Midrange" }
        assumeTrue(summary != null, "the user's Rakdos Midrange deck")
        val deck = assertNotNull(Library(db).deck(summary!!.id))
        assertEquals(summary.cardCount, deck.mainCount)
        assertTrue(deck.cards.all { it.info != null }, "every deck row joins to cards")
        assertTrue(deck.substitutions.any { it.cardName == "City of Traitors" && it.substitute == "Barbarian Ring" })
        val ai = assertNotNull(AiCopy.aiCopy(deck))
        assertTrue(ai.cards.none { it.forgeName == "City of Traitors" })
        assertTrue(ai.cards.any { it.forgeName == "Barbarian Ring" })
    }

    @Test
    fun `a printing set on the copy reads back`() {
        val deck = Library(db).deck(Library(db).decks().first { it.cardCount > 0 }.id)!!
        val card = deck.cards.first()
        DbFixture.setPrinting(copy, deck.id, card.name, "c18", "263")
        val again = Library(db).deck(deck.id)!!.cards.first { it.name == card.name }
        assertEquals("c18" to "263", again.setCode to again.collectorNumber)
    }

    @Test
    fun `a game row inserts in a transaction`() {
        val deck = Library(db).decks().first()
        val record = GameRecord(
            playedAt = Instant.parse("2026-09-29T08:00:00Z"), mode = GameMode.HUMAN_VS_AI,
            deckId = deck.id, deckName = deck.name, opponentDeckId = deck.id, opponentName = "${deck.name} (AI)",
            opponentAiVariant = true, seed = 42, winner = Winner.OPPONENT, turns = 12, durationMs = 90_000,
            forgeVersion = "2.0.14", logPath = "data/game_logs/20260929-100000.log",
        )
        val id = GameStore(db).insert(record)
        val row = db.read { conn ->
            conn.prepareStatement("SELECT played_at, mode, winner, opponent_ai_variant, seed FROM games WHERE id = ?").use { st ->
                st.setLong(1, id)
                st.executeQuery().use { rs -> rs.next(); listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getLong(5)) }
            }
        }
        assertEquals(listOf<Any>("2026-09-29T08:00:00Z", "human_vs_ai", "opponent", 1, 42L), row)
    }

    @Test
    fun `the read connection cannot write`() {
        assertFailsWith<SQLException> {
            db.read { it.createStatement().execute("DELETE FROM games") }
        }
    }
}
