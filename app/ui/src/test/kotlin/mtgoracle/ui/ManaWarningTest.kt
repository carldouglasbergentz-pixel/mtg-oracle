package mtgoracle.ui

import mtgoracle.core.model.BoardState
import mtgoracle.core.model.InputKind
import mtgoracle.core.model.InputPrompt
import mtgoracle.core.model.SeatAction
import mtgoracle.core.model.SeatCommand
import mtgoracle.ui.board.Interaction
import mtgoracle.ui.board.ManaWarningTargets
import mtgoracle.ui.board.PendingPass
import mtgoracle.ui.board.UiEvent
import mtgoracle.ui.board.UiKey
import mtgoracle.ui.board.manaAtRisk
import mtgoracle.ui.board.poolSymbols
import mtgoracle.ui.board.question
import mtgoracle.ui.board.reduce
import mtgoracle.ui.kit.ClickTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The floating-mana warning, without a window: when it asks, and what Pass and Stay send. */
class ManaWarningTest {
    private val priority = InputPrompt(1, "Priority", InputKind.PRIORITY, "InputPassPriority", 0, "OK", "End Turn", true, true, emptySet(), emptySet())
    private fun BoardState.withPool(pool: String, empties: Boolean = true) =
        copy(players = players.map { if (it.isSeat) it.copy(manaPool = pool, manaEmpties = empties) else it })

    @Test
    fun `the pool as symbols`() {
        assertEquals("{W}{W}", poolSymbols("W2"))
        assertEquals("{W}{U}{C}{C}", poolSymbols("W1 U1 C2"))
        assertEquals("{G}×12", poolSymbols("G12"))
    }

    @Test
    fun `it asks only when the pass can end the step - priority, an empty stack, mana that empties`() {
        val quiet = quietBoard()
        assertEquals("{W}{W}", manaAtRisk(priority, quiet.withPool("W2")))
        assertNull(manaAtRisk(priority, quiet), "no mana, no question")
        assertNull(manaAtRisk(priority, sampleBoard().withPool("W2")), "something on the stack: priority comes back in this step")
        assertNull(manaAtRisk(priority, quiet.withPool("W2", empties = false)), "mana that stays (Upwelling) is not lost")
        assertNull(manaAtRisk(priority.copy(kind = InputKind.TARGET), quiet.withPool("W2")), "only a priority prompt passes")
    }

    @Test
    fun `every way of passing is held back, and Pass sends it`() {
        val passes = listOf(
            UiEvent.Key(UiKey.ENTER) to (SeatAction.Ok to null),
            UiEvent.Click(ClickTarget.Ok) to (SeatAction.Ok to null),
            UiEvent.Key(UiKey.ESCAPE) to (SeatAction.Cancel to null), // End Turn
            UiEvent.Key(UiKey.F2) to (null to SeatCommand.PASS),
            UiEvent.Key(UiKey.F4) to (null to SeatCommand.END_TURN),
            UiEvent.Key(UiKey.F6) to (null to SeatCommand.SKIP_TURN),
        )
        for ((event, sent) in passes) {
            val held = reduce(Interaction.start(priority), priority, event, "{W}{W}")
            assertNull(held.action, "$event sends nothing yet"); assertNull(held.command, "$event sends nothing yet")
            val pending = assertNotNull(held.state.pendingPass, "$event is held")
            assertEquals("{W}{W} is floating and empties when this step ends — pass anyway?", pending.question())
            for (confirm in listOf(UiEvent.Key(UiKey.ENTER), UiEvent.Click(ManaWarningTargets.PASS))) {
                val out = reduce(held.state, priority, confirm, "{W}{W}")
                assertEquals(sent, out.action to out.command, "$confirm after $event")
                assertNull(out.state.pendingPass)
            }
        }
    }

    @Test
    fun `Undo is not a pass - it gives the mana back, so it goes through at once`() {
        // A Library of Alexandria tapped by mistake: Forge's cancel button is "Undo (1)".
        val undo = priority.copy(cancelLabel = "Undo (1)", cancelUndoes = true)
        for (event in listOf(UiEvent.Key(UiKey.ESCAPE), UiEvent.Click(ClickTarget.Cancel))) {
            val out = reduce(Interaction.start(undo), undo, event, "{C}")
            assertNull(out.state.pendingPass, "$event is not held behind the warning")
            assertEquals(SeatAction.Cancel, out.action, "$event undoes")
        }
        val ok = reduce(Interaction.start(undo), undo, UiEvent.Key(UiKey.ENTER), "{C}")
        assertNotNull(ok.state.pendingPass, "OK still passes, so it still asks")
    }

    @Test
    fun `Stay keeps priority, and nothing else acts while the warning is up`() {
        val held = reduce(Interaction.start(priority), priority, UiEvent.Key(UiKey.ENTER), "{W}").state
        for (stay in listOf(UiEvent.Key(UiKey.ESCAPE), UiEvent.Click(ManaWarningTargets.STAY))) {
            val out = reduce(held, priority, stay, "{W}")
            assertEquals(null to null, out.action to out.command)
            assertNull(out.state.pendingPass, "the warning is gone; the prompt is still ours")
        }
        for (other in listOf(UiEvent.Key(UiKey.F6), UiEvent.Click(ClickTarget.Card(20)), UiEvent.Key(UiKey.D1))) {
            val out = reduce(held, priority, other, "{W}")
            assertEquals(null to null, out.action to out.command, "$other")
            assertEquals(PendingPass("{W}", SeatAction.Ok), out.state.pendingPass)
        }
    }

    @Test
    fun `with nothing at risk a pass goes straight through`() {
        assertEquals(SeatAction.Ok, reduce(Interaction.start(priority), priority, UiEvent.Key(UiKey.ENTER), null).action)
        assertEquals(SeatCommand.SKIP_TURN, reduce(Interaction.start(priority), priority, UiEvent.Key(UiKey.F6), null).command)
    }

    @Test
    fun `a new prompt drops the warning`() {
        val held = reduce(Interaction.start(priority), priority, UiEvent.Key(UiKey.ENTER), "{W}").state
        val next = priority.copy(id = 2)
        assertEquals(SeatAction.Ok, reduce(held, next, UiEvent.Key(UiKey.ENTER), null).action)
    }
}
