package mtgoracle.app

import mtgoracle.core.model.ChoicePrompt
import mtgoracle.core.model.InputKind
import mtgoracle.core.model.InputPrompt
import mtgoracle.core.model.SeatAction
import mtgoracle.forge.Log
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every draw has its line in the log pane, as MTGO has, so a stop in the
 * draw step shows the card is already drawn. Draws in a row are one line;
 * a card searched for is no draw; no line names the card.
 */
class DrawLogTest {
    @AfterTest fun cleanUp() { Log.toFile(null) }

    @Test
    fun `draws are logged by count - Brainstorm's three as one line, a tutor not at all, the opponent's draw step too`() {
        val state = listOf(
            "turn=3", "activeplayer=human", "activephase=MAIN1", "humanlife=20", "ailife=20",
            "humanhand=Brainstorm;Demonic Tutor", "humanbattlefield=Island;Swamp;Swamp",
            "humanlibrary=" + List(20) { "Island" }.joinToString(";"),
            "aihand=", "aibattlefield=", "ailibrary=" + List(20) { "Swamp" }.joinToString(";"),
        )
        var cast = 0
        Scenario("draw-log", state) { p, b, _ ->
            val me = b.seat!!
            val next = me.hand.firstOrNull { it.name == listOf("Demonic Tutor", "Brainstorm").getOrNull(cast) }
            when {
                p is InputPrompt && p.kind == InputKind.PRIORITY && b.stack.isEmpty() && next != null -> { cast++; SeatAction.ClickCard(next.id) }
                p is InputPrompt && p.kind == InputKind.PAY_MANA -> SeatAction.Ok
                p is ChoicePrompt && !p.isReveal && p.options.isNotEmpty() -> SeatAction.Choose(List(maxOf(1, p.min)) { it }) // the tutor finds an Island
                else -> null
            }
        }.use { s ->
            s.playUntil { s.board.recentLog.any { it.startsWith("Draw: AI (Swamp deck)") } }
            val pane = s.board.recentLog
            assertEquals(listOf("Draw: You draw 3 cards.", "Draw: AI (Swamp deck) draws a card."), pane.filter { it.startsWith("Draw:") }, "$pane")
            assertTrue(pane.indexOf("Phase: AI (Swamp deck)'s Draw step") < pane.indexOf("Draw: AI (Swamp deck) draws a card."), "the draw follows the step: $pane")
            assertTrue("Resolve Stack: Demonic Tutor" in s.logText(), "the tutor resolved, and drew nothing")
        }
    }
}
