package mtgoracle.app

import mtgoracle.core.model.ChoicePrompt
import mtgoracle.core.model.ConfirmPrompt
import mtgoracle.core.model.InputKind
import mtgoracle.core.model.InputPrompt
import mtgoracle.core.model.SeatAction
import mtgoracle.forge.Log
import mtgoracle.ui.kit.CardMode
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the table was told stays on the table: the type Cavern of Souls
 * chose, and the cards shown to you in the other hand (Cloud's Lion Sash
 * was gone with the dialog that showed it).
 */
class KnownChoicesTest {
    @AfterTest fun cleanUp() { Log.toFile(null) }

    @Test
    fun `Cavern of Souls shows its type, and a hand looked at stays known`() {
        val state = listOf(
            "turn=3", "activeplayer=human", "activephase=MAIN1", "humanlife=20", "ailife=20",
            "humanhand=Gitaxian Probe", "humanbattlefield=Cavern of Souls|ChosenType:Human;Island",
            "humanlibrary=" + List(10) { "Island" }.joinToString(";"),
            "aihand=Lion Sash;Swords to Plowshares", "aibattlefield=Plains", "ailibrary=" + List(10) { "Plains" }.joinToString(";"),
        )
        var looked = false
        var drawn: List<String?> = emptyList()
        Scenario("known-choices", state, mode = CardMode.ART) { p, b, _ ->
            val ai = b.players.first { !it.isSeat }
            when {
                p is InputPrompt && p.kind == InputKind.PRIORITY && b.stack.isEmpty() ->
                    b.seat!!.hand.firstOrNull { it.name == "Gitaxian Probe" }?.let { SeatAction.ClickCard(it.id) }
                p is InputPrompt && p.kind == InputKind.TARGET -> SeatAction.ClickPlayer(ai.id)
                p is InputPrompt && p.kind == InputKind.PAY_MANA -> SeatAction.Ok
                p is ConfirmPrompt -> SeatAction.Confirm(false) // pay {U}, not 2 life
                p is ChoicePrompt && p.isReveal -> { looked = true; drawn = p.options.map { it.card?.name }; SeatAction.Choose(emptyList()) }
                else -> null
            }
        }.use { s ->
            s.playUntil { looked && s.board.seat!!.graveyard.any { it.name == "Gitaxian Probe" } }
            val cavern = s.board.seat!!.battlefield.single { it.name == "Cavern of Souls" }
            assertEquals("Human", cavern.chosen)
            assertContains(s.screenText(), "[Human]", message = "the frame says the type")
            val hand = s.board.players.first { !it.isSeat }.hand
            assertEquals(setOf("Lion Sash", "Swords to Plowshares"), hand.map { it.name }.toSet(), "the hand looked at is known: $hand")
            assertTrue(s.board.trail.any { "shown in hand" in it.text }, "and the trail says so: ${s.board.trail.map { it.text }}")
            // The reveal carried the cards themselves, for the panel to draw; and the log keeps it, for this seat alone.
            assertEquals(setOf("Lion Sash", "Swords to Plowshares"), drawn.toSet(), "the reveal's cards, drawable")
            val shown = s.board.log.last { it.kind == mtgoracle.core.model.LogKind.REVEAL && it.text.startsWith("Shown to you") }
            assertTrue("Lion Sash" in shown.text && "in AI (Swamp deck)'s hand" in shown.text, shown.text)
            assertEquals(setOf(s.board.seat!!.id), shown.seenBy, "for this seat only: Forge can't tell a reveal from a look")
            assertEquals(setOf("Lion Sash", "Swords to Plowshares"), shown.cards.map { it.name }.toSet(), "its cards marked")
            s.png("known-choices")
        }
    }
}
