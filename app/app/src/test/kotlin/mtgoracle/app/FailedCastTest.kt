package mtgoracle.app

import mtgoracle.core.model.ChoicePrompt
import mtgoracle.core.model.ConfirmPrompt
import mtgoracle.core.model.InputKind
import mtgoracle.core.model.InputPrompt
import mtgoracle.core.model.SeatAction
import mtgoracle.forge.Log
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A cast Forge stops without asking anything is said (FailedCasts): the
 * user's case, Bilbo's attack trigger offering a graveyard card under the
 * opponent's Drannith Magistrate. A cast the seat cancels itself is not.
 */
class FailedCastTest {
    @AfterTest fun cleanUp() { Log.toFile(null) }

    @Test
    fun `a graveyard cast Drannith Magistrate forbids is warned about, naming it`() {
        val state = listOf(
            "turn=3", "activeplayer=human", "activephase=MAIN1", "removesummoningsickness=true", "humanlife=20", "ailife=20",
            "humanhand=", "humanbattlefield=Bilbo, Thief in the Night;Mountain;Mountain;Island",
            "humangraveyard=Galvanic Discharge", "humanlibrary=" + List(20) { "Island" }.joinToString(";"),
            "aihand=", "aibattlefield=Drannith Magistrate", "ailibrary=" + List(20) { "Plains" }.joinToString(";"),
        )
        var chosen = false
        Scenario("failed-cast-drannith", state) { p, b, _ ->
            val bilbo = b.seat!!.battlefield.firstOrNull { it.name.startsWith("Bilbo") }
            when {
                p is InputPrompt && p.kind == InputKind.ATTACK && bilbo != null -> if (!bilbo.attacking) SeatAction.ClickCard(bilbo.id) else SeatAction.Ok
                p is ConfirmPrompt -> SeatAction.Confirm(true)
                // One card to offer: Forge asks "Do you want to play Galvanic Discharge?" rather than a list.
                p is InputPrompt && p.kind == InputKind.CONFIRM -> { if ("Galvanic Discharge" in p.message) chosen = true; SeatAction.Ok }
                p is ChoicePrompt && p.labels.any { "Galvanic Discharge" in it } -> { chosen = true; SeatAction.Choose(listOf(p.labels.indexOfFirst { "Galvanic Discharge" in it })) }
                else -> null
            }
        }.use { s ->
            s.playUntil { s.match.seat.warning.value != null }
            assertTrue(chosen, "Bilbo's trigger offered Galvanic Discharge")
            assertEquals("Galvanic Discharge couldn't be cast from your graveyard: Drannith Magistrate forbids it. Nothing happened.", s.match.seat.warning.value)
            assertTrue(s.board.trail.any { it.text == "couldn't cast Galvanic Discharge (Drannith Magistrate)" }, "${s.board.trail}")
            assertTrue("WARNING Galvanic Discharge couldn't be cast from the graveyard: Drannith Magistrate forbids it" in s.logText())
            assertTrue(s.board.seat!!.graveyard.any { it.name == "Galvanic Discharge" }, "the card went back where it was")
        }
    }

    @Test
    fun `a cast the seat cancels at the payment is its own doing, and not warned about`() {
        val state = listOf(
            "turn=3", "activeplayer=human", "activephase=MAIN1", "humanlife=20", "ailife=20",
            "humanhand=Opt", "humanbattlefield=Island", "humanlibrary=" + List(20) { "Island" }.joinToString(";"),
            "aihand=", "aibattlefield=", "ailibrary=" + List(20) { "Plains" }.joinToString(";"),
        )
        var cancelled = false
        Scenario("failed-cast-cancelled", state) { p, b, _ ->
            val opt = b.seat!!.hand.firstOrNull { it.name == "Opt" }
            when {
                p is InputPrompt && p.kind == InputKind.PRIORITY && opt != null && !cancelled -> SeatAction.ClickCard(opt.id)
                p is InputPrompt && p.kind == InputKind.PAY_MANA -> { cancelled = true; SeatAction.Cancel }
                else -> null
            }
        }.use { s ->
            s.playUntil { cancelled && s.board.seat!!.hand.any { it.name == "Opt" } && s.board.stack.isEmpty() }
            assertNull(s.match.seat.warning.value, "cancelling is not a failure")
            assertTrue(s.board.trail.none { it.text.startsWith("couldn't cast") })
        }
    }
}
