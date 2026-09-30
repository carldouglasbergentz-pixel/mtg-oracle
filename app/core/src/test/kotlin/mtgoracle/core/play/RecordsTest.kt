package mtgoracle.core.play

import kotlin.test.Test
import kotlin.test.assertEquals

/** Win-loss records from game rows: both sides of a simulation, your games apart, mirrors once, broken-off games not at all. */
class RecordsTest {
    private val rakdos = DeckKey(1, "Rakdos Midrange")
    private val boros = DeckKey(2, "Boros Death and Taxes")
    private val gone = DeckKey(null, "Old Deck")

    private fun sim(winner: Winner?, turns: Int? = 8, a: DeckKey = rakdos, b: DeckKey = boros) = PlayedGame(GameMode.AI_VS_AI, a, b, winner, turns)
    private fun played(winner: Winner, turns: Int = 10) = PlayedGame(GameMode.HUMAN_VS_AI, rakdos, boros, winner, turns)

    private val games = listOf(
        sim(Winner.ME, 6), sim(Winner.ME, 8), sim(Winner.OPPONENT, 10), sim(Winner.DRAW, 30),
        sim(null), // the app broke off: no result
        played(Winner.ME, 12),
        sim(Winner.OPPONENT, 7, a = boros, b = rakdos), // Boros as seat A: a win for Boros
        sim(Winner.ME, 9, a = rakdos, b = rakdos), // a mirror
        PlayedGame(GameMode.HUMAN_VS_AI, rakdos, gone, Winner.OPPONENT, 5),
    )

    @Test
    fun `a deck's record counts both seats, keeps your games apart, and skips what has no result`() {
        val records = Records.of(rakdos, games)
        val vsBoros = records.first { it.opponent.sameAs(boros) }
        assertEquals(Tally(wins = 3, losses = 1, draws = 1, turnsTotal = 6 + 8 + 10 + 7, decided = 4), vsBoros.simulated, "seat A and seat B alike")
        assertEquals("1–0", vsBoros.played.score())
        assertEquals("3–1–1", vsBoros.simulated.score())
        assertEquals((6 + 8 + 10 + 7 + 12) / 5.0, vsBoros.total.averageTurns!!, 1e-9, "the draw is left out of the length")
        assertEquals("1–0", records.first { it.opponent.sameAs(rakdos) }.simulated.score(), "a mirror is counted once")
        assertEquals("0–1", records.first { it.opponent.sameAs(gone) }.played.score(), "a deleted deck keeps its name")
        assertEquals(boros, records.first().opponent, "the most games first")
    }

    @Test
    fun `the other side sees the same games from its seat`() {
        val vsRakdos = Records.of(boros, games).single()
        assertEquals("1–3–1", vsRakdos.simulated.score())
        assertEquals("0–1", vsRakdos.played.score())
        assertEquals(listOf(rakdos, boros, gone), Records.decks(games))
    }
}
