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
 * A spell that leaves the stack without resolving gets a line of its own
 * (Countered): in the trail, in the log pane, and for the seat's own spell
 * in the warning line. Spell Snare on Consult the Star Charts was easy to
 * miss among the triggers of the moment.
 */
class CounteredTest {
    @AfterTest fun cleanUp() { Log.toFile(null) }

    private fun state(hand: String, battlefield: String) = listOf(
        "turn=3", "activeplayer=human", "activephase=MAIN1", "removesummoningsickness=true", "humanlife=20", "ailife=20",
        "humanhand=$hand", "humanbattlefield=$battlefield", "humanlibrary=" + List(20) { "Island" }.joinToString(";"),
        "aihand=", "aibattlefield=", "ailibrary=" + List(20) { "Swamp" }.joinToString(";"),
    )

    @Test
    fun `a countered spell is said - who countered what`() {
        var stage = 0
        Scenario("countered", state("Opt;Counterspell", "Island;Island;Island")) { p, b, _ ->
            val me = b.seat!!
            when {
                p is InputPrompt && p.kind == InputKind.PRIORITY && stage == 0 && b.stack.isEmpty() -> { stage = 1; SeatAction.ClickCard(me.hand.first { it.name == "Opt" }.id) }
                p is InputPrompt && p.kind == InputKind.PRIORITY && stage == 1 && b.stack.isNotEmpty() -> { stage = 2; SeatAction.ClickCard(me.hand.first { it.name == "Counterspell" }.id) }
                p is ChoicePrompt && p.labels.any { "Opt" in it } -> SeatAction.Choose(listOf(p.labels.indexOfFirst { "Opt" in it }))
                p is InputPrompt && p.kind == InputKind.PAY_MANA -> SeatAction.Ok
                else -> null
            }
        }.use { s ->
            s.playUntil { s.board.trail.any { "countered" in it.text } && s.board.stack.isEmpty() }
            assertTrue(s.board.trail.any { it.text == "Counterspell countered Opt" }, "${s.board.trail}")
            assertTrue("Countered: Counterspell countered Opt." in s.board.recentLog, "${s.board.recentLog}")
            assertEquals("Counterspell countered Opt.", s.match.seat.warning.value)
        }
    }

    @Test
    fun `a spell returned to its owner's hand is not said to be countered`() {
        var stage = 0
        Scenario("bounced", state("Opt;Unsubstantiate", "Island;Island;Island")) { p, b, _ ->
            val me = b.seat!!
            when {
                p is InputPrompt && p.kind == InputKind.PRIORITY && stage == 0 && b.stack.isEmpty() -> { stage = 1; SeatAction.ClickCard(me.hand.first { it.name == "Opt" }.id) }
                p is InputPrompt && p.kind == InputKind.PRIORITY && stage == 1 && b.stack.isNotEmpty() -> { stage = 2; SeatAction.ClickCard(me.hand.first { it.name == "Unsubstantiate" }.id) }
                p is ChoicePrompt && p.labels.any { "Opt" in it } -> SeatAction.Choose(listOf(p.labels.indexOfFirst { "Opt" in it }))
                p is InputPrompt && p.kind == InputKind.PAY_MANA -> SeatAction.Ok
                else -> null
            }
        }.use { s ->
            s.playUntil { s.board.stack.isEmpty() && s.board.seat!!.graveyard.any { it.name == "Unsubstantiate" } }
            assertTrue(s.board.trail.any { it.text == "Unsubstantiate returned Opt to its owner's hand" }, "${s.board.trail}")
            assertTrue(s.board.trail.none { "countered" in it.text }, "${s.board.trail}")
            assertTrue("Removed: Unsubstantiate returned Opt to its owner's hand." in s.board.recentLog, "${s.board.recentLog}")
        }
    }

    @Test
    fun `a spell whose target is gone fizzles, and says so`() {
        var stage = 0
        Scenario("fizzled", state("Lightning Bolt;Unsummon", "Grizzly Bears;Mountain;Island")) { p, b, _ ->
            val me = b.seat!!
            val bears = me.battlefield.firstOrNull { it.name == "Grizzly Bears" }
            when {
                p is InputPrompt && p.kind == InputKind.PRIORITY && stage == 0 && b.stack.isEmpty() -> { stage = 1; SeatAction.ClickCard(me.hand.first { it.name == "Lightning Bolt" }.id) }
                p is InputPrompt && p.kind == InputKind.PRIORITY && stage == 1 && b.stack.isNotEmpty() -> { stage = 2; SeatAction.ClickCard(me.hand.first { it.name == "Unsummon" }.id) }
                p is InputPrompt && p.kind == InputKind.TARGET && bears != null -> SeatAction.ClickCard(bears.id)
                p is InputPrompt && p.kind == InputKind.PAY_MANA -> SeatAction.Ok
                else -> null
            }
        }.use { s ->
            s.playUntil { s.board.trail.any { "fizzled" in it.text } && s.board.stack.isEmpty() }
            assertTrue(s.board.trail.any { it.text == "Lightning Bolt fizzled: its targets were gone" }, "${s.board.trail}")
            assertTrue("Fizzled: Lightning Bolt fizzled: its targets were gone." in s.board.recentLog, "${s.board.recentLog}")
            assertTrue(s.board.trail.none { "Unsummon" in it.text && "countered" in it.text }, "Unsummon resolved: ${s.board.trail}")
        }
    }
}
