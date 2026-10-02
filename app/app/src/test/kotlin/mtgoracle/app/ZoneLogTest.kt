package mtgoracle.app

import mtgoracle.core.model.BoardState
import mtgoracle.core.model.ChoicePrompt
import mtgoracle.core.model.ConfirmPrompt
import mtgoracle.core.model.InputKind
import mtgoracle.core.model.InputPrompt
import mtgoracle.core.model.Prompt
import mtgoracle.core.model.SeatAction
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A card leaving a public zone for a hand is in the log pane by name: Forge
 * logs Overlord of the Balemurk's mill but not the card it returns, nor a
 * bounce, and the trail's line was gone from the header at the next decision.
 */
class ZoneLogTest {
    private fun priority(p: Prompt) = p is InputPrompt && p.kind == InputKind.PRIORITY
    private fun BoardState.me() = seat!!
    private fun BoardState.ai() = players.first { !it.isSeat }

    private fun setup(hand: String, battlefield: String, library: String, ai: String = "") = listOf(
        "turn=3", "activeplayer=human", "activephase=MAIN1", "removesummoningsickness=true", "humanlife=20", "ailife=20",
        "humanhand=$hand", "humanbattlefield=$battlefield", "humangraveyard=", "humanlibrary=$library",
        "aihand=Swamp;Swamp", "aibattlefield=$ai", "ailibrary=" + List(20) { "Swamp" }.joinToString(";"),
    )

    @Test
    fun `Overlord of the Balemurk mills four, returns one, and the log says which`() {
        val library = (listOf("Swamp", "Grizzly Bears", "Swamp", "Swamp") + List(16) { "Swamp" }).joinToString(";")
        Scenario("zonelog-overlord", setup("Overlord of the Balemurk", List(5) { "Swamp" }.joinToString(";"), library)) { p, b, _ ->
            val overlord = b.me().hand.firstOrNull { it.name == "Overlord of the Balemurk" }
            when {
                priority(p) && b.stack.isEmpty() && overlord != null -> SeatAction.ClickCard(overlord.id)
                // Cast it as itself, not impending: either way it enters and triggers.
                p is ChoicePrompt && p.options.any { "Impending" in it.label } -> SeatAction.Choose(listOf(p.options.indexOfFirst { "Impending" !in it.label }))
                p is ChoicePrompt && p.options.any { "Grizzly Bears" in it.label } -> SeatAction.Choose(listOf(p.options.indexOfFirst { "Grizzly Bears" in it.label }))
                p is ConfirmPrompt -> SeatAction.Confirm(true)
                p is InputPrompt && p.kind == InputKind.CONFIRM -> SeatAction.Ok
                p is InputPrompt && p.kind == InputKind.SELECT_CARDS -> b.me().graveyard.firstOrNull { it.name == "Grizzly Bears" && it.id in p.selectableCardIds }?.let { SeatAction.ClickCard(it.id) } ?: SeatAction.Ok
                priority(p) -> SeatAction.Ok
                else -> null
            }
        }.use { s ->
            s.playUntil { s.board.me().hand.any { it.name == "Grizzly Bears" } }
            s.board.recentLog.takeLast(20).let { log ->
                assertTrue(log.any { "milled" in it && "Grizzly Bears" in it }, "Forge's mill line: $log")
                assertTrue("Zone Change: Grizzly Bears: graveyard → your hand." in log, "and ours for the card returned: $log")
            }
        }
    }

    @Test
    fun `a bounce names the card and whose hand it went to`() {
        Scenario("zonelog-unsummon", setup("Unsummon", "Island", List(20) { "Island" }.joinToString(";"), ai = "Hill Giant")) { p, b, _ ->
            val unsummon = b.me().hand.firstOrNull { it.name == "Unsummon" }
            val giant = b.ai().battlefield.firstOrNull { it.name == "Hill Giant" }
            when {
                priority(p) && b.stack.isEmpty() && unsummon != null -> SeatAction.ClickCard(unsummon.id)
                p is InputPrompt && p.kind == InputKind.TARGET && giant != null && giant.id in p.selectableCardIds -> SeatAction.ClickCard(giant.id)
                priority(p) -> SeatAction.Ok
                else -> null
            }
        }.use { s ->
            s.playUntil { s.board.me().graveyard.any { it.name == "Unsummon" } }
            assertTrue(s.board.recentLog.any { it.startsWith("Zone Change: Hill Giant: battlefield → AI") && it.endsWith("'s hand.") }, "${s.board.recentLog.takeLast(10)}")
            // Known in their hand, seen going there (KnownInHand); the rest of that hand stays backs.
            val hand = s.board.ai().hand
            assertTrue(hand.single { !it.hidden }.name == "Hill Giant", "the Giant is known in the AI's hand: $hand")
        }
    }
}
