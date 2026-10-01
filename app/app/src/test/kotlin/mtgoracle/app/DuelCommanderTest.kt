package mtgoracle.app

import mtgoracle.core.deck.AiCopy
import mtgoracle.core.deck.Deck
import mtgoracle.core.deck.DeckCard
import mtgoracle.core.deck.GameType
import mtgoracle.core.model.InputKind
import mtgoracle.core.model.InputPrompt
import mtgoracle.core.model.SeatAction
import mtgoracle.forge.Log
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A deck whose format is `duel` plays Forge's Duel Commander: 20 life, and
 * once one partner is cast from the command zone the other is locked there
 * for the game (DC 404). The board says so on the chip and in the card's text.
 */
class DuelCommanderTest {
    @AfterTest fun cleanUp() { Log.toFile(null) }

    private fun duelDeck(id: Int, commanders: List<String>, land: String) = AiCopy.asBuilt(Deck(id, "$land duel", "duel", null,
        commanders.map { DeckCard(it, 1, true, false) } + DeckCard(land, 99 - commanders.size, false, false)))

    private val mine = duelDeck(1, listOf("Krark, the Thumbless", "Kediss, Emberclaw Familiar"), "Mountain")
    private val theirs = duelDeck(2, listOf("Isamaru, Hound of Konda"), "Plains")

    @Test
    fun `a duel deck plays Forge's Duel Commander, at 20 life`() {
        assertEquals(GameType.DUEL_COMMANDER, mine.gameType)
        // Not staged: a staged board sets every life it isn't given to -1 (Forge's puzzles need that).
        Scenario("duel-commander-start", null, seatDeck = mine, opponentDeck = theirs) { _, _, _ -> null }.use { s ->
            s.playUntil { "GameEventGameStarted" in s.logText() && s.match.seat.board.value?.players?.size == 2 }
            assertEquals(listOf(20, 20), s.board.players.map { it.life }, "Duel Commander starts at 20, not Commander's 40")
            assertEquals(2, s.board.seat!!.command.count { it.name in setOf("Krark, the Thumbless", "Kediss, Emberclaw Familiar") }, "both partners start in the command zone")
            assertTrue("Duel Commander game between" in s.logText(), "Forge runs a Duel Commander game")
        }
    }

    @Test
    fun `once one partner is cast from the command zone, the other is locked there`() {
        val state = listOf(
            "turn=3", "activeplayer=human", "activephase=MAIN1", "removesummoningsickness=true", "humanlife=20", "ailife=20",
            "humanhand=", "humanbattlefield=Mountain;Mountain;Mountain;Mountain",
            "humancommand=Krark, the Thumbless|IsCommander;Kediss, Emberclaw Familiar|IsCommander",
            "humanlibrary=" + List(20) { "Mountain" }.joinToString(";"),
            "aihand=", "aibattlefield=", "aicommand=Isamaru, Hound of Konda|IsCommander",
            "ailibrary=" + List(20) { "Plains" }.joinToString(";"),
        )
        var before: Boolean? = null
        Scenario("duel-commander-lock", state, seatDeck = mine, opponentDeck = theirs) { p, b, _ ->
            val me = b.seat!!
            val krark = me.command.firstOrNull { it.name == "Krark, the Thumbless" }
            when {
                p !is InputPrompt -> null
                p.kind == InputKind.PRIORITY && krark != null -> {
                    before = me.command.first { it.name.startsWith("Kediss") }.castLocked
                    SeatAction.ClickCard(krark.id)
                }
                p.kind == InputKind.PAY_MANA -> SeatAction.Ok
                p.kind == InputKind.PRIORITY && b.stack.isNotEmpty() -> SeatAction.Ok
                else -> null
            }
        }.use { s ->
            s.playUntil { s.board.seat!!.battlefield.any { it.name == "Krark, the Thumbless" } && s.board.stack.isEmpty() }
            val me = s.board.seat!!
            assertEquals(false, before, "nothing is locked before a commander is cast")
            val kediss = me.command.first { it.name.startsWith("Kediss") }
            assertTrue(kediss.castLocked, "Kediss is locked in the command zone once Krark was cast from it")
            assertTrue("another commander was cast from there first" in kediss.text, kediss.text)
            assertFalse(s.board.players.first { !it.isSeat }.command.any { it.castLocked }, "the opponent cast nothing")
            assertTrue("[Kediss, Emberclaw Familiar] locked" in s.screenText(), "the chip says locked")
        }
    }
}
