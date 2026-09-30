package mtgoracle.ui

import mtgoracle.core.model.BoardRef
import mtgoracle.core.model.ChoiceOption
import mtgoracle.core.model.ChoicePrompt
import mtgoracle.core.model.DistributePrompt
import mtgoracle.core.model.DistributeTarget
import mtgoracle.core.model.InputKind
import mtgoracle.core.model.InputPrompt
import mtgoracle.core.model.NumberPrompt
import mtgoracle.core.model.OrderPrompt
import mtgoracle.core.model.Prompt
import mtgoracle.core.model.SeatAction
import mtgoracle.core.model.SeatCommand
import mtgoracle.ui.board.Interaction
import mtgoracle.ui.board.UiEvent
import mtgoracle.ui.board.UiKey
import mtgoracle.ui.board.reduce
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.theme.GlyphGuard
import mtgoracle.ui.kit.fit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Every prompt's controls, without a window: click or key in, answer out. */
class InteractionTest {
    private fun run(prompt: Prompt, vararg events: UiEvent): List<SeatAction> {
        var state = Interaction.start(prompt)
        val out = mutableListOf<SeatAction>()
        for (e in events) { val o = reduce(state, prompt, e); state = o.state; o.action?.let(out::add) }
        return out
    }
    private fun click(t: ClickTarget) = UiEvent.Click(t)
    private fun key(k: UiKey) = UiEvent.Key(k)

    private val priority = InputPrompt(1, "Priority", InputKind.PRIORITY, "InputPassPriority", 0, "OK", "End Turn", true, true, emptySet(), setOf(20))

    @Test
    fun `an input prompt turns clicks into gestures and Enter or Esc into its buttons`() {
        assertEquals(listOf(SeatAction.ClickCard(20), SeatAction.ClickPlayer(2), SeatAction.Ok, SeatAction.Cancel),
            run(priority, click(ClickTarget.Card(20)), click(ClickTarget.Player(2)), key(UiKey.ENTER), key(UiKey.ESCAPE)))
        assertEquals(emptyList(), run(priority.copy(okEnabled = false), key(UiKey.ENTER)), "a disabled button does nothing")
    }

    @Test
    fun `the F-keys are commands, with or without a prompt`() {
        val map = mapOf(UiKey.F2 to SeatCommand.PASS, UiKey.F3 to SeatCommand.CANCEL_YIELDS, UiKey.F4 to SeatCommand.END_TURN, UiKey.F6 to SeatCommand.SKIP_TURN)
        for ((k, c) in map) {
            assertEquals(c, reduce(Interaction(), null, key(k)).command)
            assertEquals(c, reduce(Interaction(), priority, key(k)).command)
        }
    }

    @Test
    fun `a single choice answers on click, digit, or a click on the board object it stands for`() {
        val p = ChoicePrompt(2, "Target a spell", listOf(ChoiceOption("Lightning Bolt", BoardRef.StackItem(90)), ChoiceOption("Opt", BoardRef.StackItem(91))), 1, 1)
        assertEquals(listOf(SeatAction.Choose(listOf(1))), run(p, click(ClickTarget.Option(1))))
        assertEquals(listOf(SeatAction.Choose(listOf(0))), run(p, key(UiKey.D1)))
        assertEquals(listOf(SeatAction.Choose(listOf(0))), run(p, click(ClickTarget.StackItem(90))), "clicking the stack entry picks it")
        assertEquals(emptyList(), run(p, key(UiKey.ENTER)), "min 1: Enter alone is not an answer")
    }

    @Test
    fun `a multi choice ticks, then Done sends when the count fits`() {
        val p = ChoicePrompt(3, "Pick 2", List(4) { ChoiceOption("c$it") }, 2, 2)
        assertEquals(listOf(SeatAction.Choose(listOf(0, 2))), run(p, click(ClickTarget.Option(0)), key(UiKey.ENTER), key(UiKey.D3), click(ClickTarget.Done)))
        assertEquals(listOf(SeatAction.Choose(emptyList())), run(ChoicePrompt(4, "look", listOf(ChoiceOption("x")), -1, -1), key(UiKey.ENTER)), "a reveal continues")
    }

    @Test
    fun `an order is picked by clicking in sequence, unpicked items follow`() {
        val p = OrderPrompt(5, "Order", List(3) { ChoiceOption("t$it") }, "top")
        assertEquals(listOf(SeatAction.Order(listOf(2, 0))), run(p, click(ClickTarget.Option(2)), click(ClickTarget.Option(0)), click(ClickTarget.Done)))
        assertEquals(listOf(SeatAction.Order(listOf(1))), run(p, key(UiKey.D3), key(UiKey.ESCAPE), key(UiKey.D2), key(UiKey.ENTER)), "Esc clears")
    }

    @Test
    fun `a damage split starts from Forge's suggestion and only sends a full split`() {
        val p = DistributePrompt(6, "Assign 4", listOf(DistributeTarget("Bear A", null, 2), DistributeTarget("Bear B", null, 2)), 4, false, listOf(2, 2))
        assertEquals(listOf(SeatAction.Distribute(listOf(3, 1))), run(p, click(ClickTarget.Less(1)), click(ClickTarget.More(0)), click(ClickTarget.Done)))
        assertEquals(listOf(SeatAction.Distribute(listOf(2, 2))), run(p, click(ClickTarget.More(0)), key(UiKey.ENTER)), "+ past the total is refused")
        assertEquals(emptyList(), run(p, click(ClickTarget.Less(0)), key(UiKey.ENTER)), "3 of 4 assigned: not yet")
        assertEquals(listOf(SeatAction.Distribute(listOf(1, 3))), run(p, key(UiKey.D1), key(UiKey.MINUS), key(UiKey.D2), key(UiKey.PLUS), key(UiKey.ENTER)))
    }

    @Test
    fun `a number is typed or stepped, bounded, and Esc cancels when allowed`() {
        val p = NumberPrompt(7, "X", 0, 10, cancellable = true)
        assertEquals(listOf(SeatAction.Number(7)), run(p, key(UiKey.D7), key(UiKey.ENTER)))
        assertEquals(listOf(SeatAction.Number(1)), run(p, key(UiKey.ENTER)), "Enter alone: X = 1")
        assertEquals(listOf(SeatAction.Number(3)), run(p, click(ClickTarget.More(0)), click(ClickTarget.More(0)), click(ClickTarget.Done)))
        assertEquals(listOf(SeatAction.Number(null)), run(p, key(UiKey.ESCAPE)))
        assertEquals(emptyList(), run(p, key(UiKey.D4), key(UiKey.D2), key(UiKey.ENTER)), "42 is out of range")
        assertNull(reduce(Interaction.start(p.copy(cancellable = false)), p.copy(cancellable = false), key(UiKey.ESCAPE)).action)
    }
}

class GlyphGuardTest {
    @Test
    fun `characters the font lacks become one-character stand-ins, the rest pass`() {
        assertEquals("┌─┐│└┘╔═╗", GlyphGuard.safe("┌─┐│└┘╔═╗"))
        assertEquals("Troll of Khazad-dûm — Lórien", GlyphGuard.safe("Troll of Khazad-dûm — Lórien"))
        val guarded = GlyphGuard.safe("▶ You")
        assertEquals(5, guarded.length)
        assertEquals(guarded, GlyphGuard.safe(guarded), "a guarded string is stable")
        assertEquals(12, fit("▶★ over and beyond", 12).length, "fit holds its width whatever it guarded")
    }
}
