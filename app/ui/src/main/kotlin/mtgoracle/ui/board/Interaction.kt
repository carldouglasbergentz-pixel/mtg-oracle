package mtgoracle.ui.board

import mtgoracle.core.model.BoardRef
import mtgoracle.core.model.ChoicePrompt
import mtgoracle.core.model.ConfirmPrompt
import mtgoracle.core.model.DistributePrompt
import mtgoracle.core.model.InputKind
import mtgoracle.core.model.InputPrompt
import mtgoracle.core.model.NumberPrompt
import mtgoracle.core.model.OrderPrompt
import mtgoracle.core.model.Prompt
import mtgoracle.core.model.SeatAction
import mtgoracle.core.model.SeatCommand
import mtgoracle.core.model.SideboardPrompt
import mtgoracle.ui.kit.ClickTarget

/** The keys the board understands (docs/app-design.md "The game board"). */
enum class UiKey { ENTER, ESCAPE, F2, F3, F4, F6, PLUS, MINUS, BACKSPACE, D1, D2, D3, D4, D5, D6, D7, D8, D9, D0 }

val UiKey.digit: Int? get() = when (this) {
    UiKey.D0 -> 0; UiKey.D1 -> 1; UiKey.D2 -> 2; UiKey.D3 -> 3; UiKey.D4 -> 4
    UiKey.D5 -> 5; UiKey.D6 -> 6; UiKey.D7 -> 7; UiKey.D8 -> 8; UiKey.D9 -> 9
    else -> null
}

/**
 * What the player has done inside the current prompt but not yet sent:
 * ticked options, an order being picked, a damage split, digits typed.
 * Pure state; [reduce] turns a click or key into the next state and maybe an
 * answer, so every prompt's controls are testable without a window.
 */
data class Interaction(
    val promptId: Long = -1,
    /** Choice ticks, or the order picked so far (first first). */
    val picks: List<Int> = emptyList(),
    val amounts: List<Int> = emptyList(),
    /** The distribute row that +/- act on. */
    val row: Int = 0,
    val digits: String = "",
    /** Sideboarding: the main deck being built, by card name. */
    val deck: Map<String, Int> = emptyMap(),
    /** A pass waiting on the floating-mana warning ([manaAtRisk]); while set, only Pass and Stay act. */
    val pendingPass: PendingPass? = null,
) {
    companion object {
        fun start(prompt: Prompt?): Interaction = when (prompt) {
            null -> Interaction()
            is DistributePrompt -> Interaction(prompt.id, amounts = prompt.suggested)
            is SideboardPrompt -> Interaction(prompt.id, deck = prompt.main.associate { it.name to it.count })
            else -> Interaction(prompt.id)
        }
    }
}

/** Sideboard rows are named controls: `sb-main:<card>` moves one copy out of the deck, `sb-side:<card>` one in. */
fun sideboardTarget(inMain: Boolean, name: String) = ClickTarget.Control((if (inMain) "sb-main:" else "sb-side:") + name)

/** Copies of each card between deck and sideboard. */
fun SideboardPrompt.total(name: String): Int = (main + side).filter { it.name == name }.sumOf { it.count }

sealed interface UiEvent {
    data class Click(val target: ClickTarget) : UiEvent
    data class Key(val key: UiKey) : UiEvent
}

data class Outcome(val state: Interaction, val action: SeatAction? = null, val command: SeatCommand? = null)

/**
 * [manaAtRisk]: the floating mana a pass would lose (the function of that
 * name, over the board), or null. While it is set, a pass (OK, End Turn,
 * F2, F4, F6) is held back behind the warning instead of sent.
 */
fun reduce(state0: Interaction, prompt: Prompt?, event: UiEvent, manaAtRisk: String? = null): Outcome {
    val state = if (prompt != null && state0.promptId != prompt.id) Interaction.start(prompt) else state0
    state.pendingPass?.let { return confirmPass(state, it, event) }
    if (manaAtRisk != null) passOf(prompt, event)?.let { (action, command) ->
        return Outcome(state.copy(pendingPass = PendingPass(manaAtRisk, action, command)))
    }
    // Turn-flow keys work with or without a prompt.
    if (event is UiEvent.Key) when (event.key) {
        UiKey.F2 -> return Outcome(state, command = SeatCommand.PASS)
        UiKey.F3 -> return Outcome(state, command = SeatCommand.CANCEL_YIELDS)
        UiKey.F4 -> return Outcome(state, command = SeatCommand.END_TURN)
        UiKey.F6 -> return Outcome(state, command = SeatCommand.SKIP_TURN)
        else -> {}
    }
    return when (prompt) {
        null -> Outcome(state)
        is InputPrompt -> input(state, prompt, event)
        is ConfirmPrompt -> confirm(state, event)
        is ChoicePrompt -> choice(state, prompt, event)
        is OrderPrompt -> order(state, prompt, event)
        is DistributePrompt -> distribute(state, prompt, event)
        is NumberPrompt -> number(state, prompt, event)
        is SideboardPrompt -> sideboard(state, prompt, event)
    }
}

/**
 * What [event] passes priority with, if it does: OK or End Turn at a priority prompt, or F2, F4, F6.
 * Cancel passes only as End Turn: as Undo it gives the floating mana back.
 */
private fun passOf(prompt: Prompt?, event: UiEvent): Pair<SeatAction?, SeatCommand?>? {
    val priority = (prompt as? InputPrompt)?.takeIf { it.kind == InputKind.PRIORITY }
    val cancelPasses = priority?.cancelEnabled == true && !priority.cancelUndoes
    return when (event) {
        is UiEvent.Key -> when (event.key) {
            UiKey.F2 -> null to SeatCommand.PASS
            UiKey.F4 -> null to SeatCommand.END_TURN
            UiKey.F6 -> null to SeatCommand.SKIP_TURN
            UiKey.ENTER -> (SeatAction.Ok to null).takeIf { priority?.okEnabled == true }
            UiKey.ESCAPE -> (SeatAction.Cancel to null).takeIf { cancelPasses }
            else -> null
        }
        is UiEvent.Click -> when (event.target) {
            ClickTarget.Ok -> (SeatAction.Ok to null).takeIf { priority?.okEnabled == true }
            ClickTarget.Cancel -> (SeatAction.Cancel to null).takeIf { cancelPasses }
            else -> null
        }
    }
}

/** The warning has the keyboard: Enter or [ Pass ] sends the held pass, Esc or [ Stay ] keeps priority. */
private fun confirmPass(state: Interaction, pending: PendingPass, event: UiEvent): Outcome {
    val pass = when (event) {
        is UiEvent.Key -> when (event.key) { UiKey.ENTER -> true; UiKey.ESCAPE -> false; else -> null }
        is UiEvent.Click -> when (event.target) { ManaWarningTargets.PASS -> true; ManaWarningTargets.STAY -> false; else -> null }
    } ?: return Outcome(state)
    val cleared = state.copy(pendingPass = null)
    return if (pass) Outcome(cleared, pending.action, pending.command) else Outcome(cleared)
}

private fun sideboard(state: Interaction, prompt: SideboardPrompt, event: UiEvent): Outcome {
    val size = state.deck.values.sum()
    val done = SeatAction.Sideboard(state.deck.filterValues { it > 0 }).takeIf { size >= prompt.minMain }
    return when (event) {
        is UiEvent.Key -> Outcome(state, if (event.key == UiKey.ENTER) done else null)
        is UiEvent.Click -> {
            val t = event.target
            val name = (t as? ClickTarget.Control)?.name
            when {
                t == ClickTarget.Done -> Outcome(state, done)
                name != null && name.startsWith("sb-main:") -> {
                    val card = name.removePrefix("sb-main:")
                    val n = state.deck[card] ?: 0
                    Outcome(if (n > 0) state.copy(deck = state.deck + (card to n - 1)) else state)
                }
                name != null && name.startsWith("sb-side:") -> {
                    val card = name.removePrefix("sb-side:")
                    val n = state.deck[card] ?: 0
                    Outcome(if (n < prompt.total(card)) state.copy(deck = state.deck + (card to n + 1)) else state)
                }
                else -> Outcome(state)
            }
        }
    }
}

private fun input(state: Interaction, prompt: InputPrompt, event: UiEvent): Outcome {
    val action = when (event) {
        is UiEvent.Click -> when (val t = event.target) {
            is ClickTarget.Card -> SeatAction.ClickCard(t.id)
            is ClickTarget.Player -> SeatAction.ClickPlayer(t.id)
            is ClickTarget.Mana -> SeatAction.UseMana(t.colour).takeIf { prompt.kind == InputKind.PAY_MANA }
            ClickTarget.Ok -> SeatAction.Ok.takeIf { prompt.okEnabled }
            ClickTarget.Cancel -> SeatAction.Cancel.takeIf { prompt.cancelEnabled }
            else -> null
        }
        is UiEvent.Key -> when (event.key) {
            UiKey.ENTER -> SeatAction.Ok.takeIf { prompt.okEnabled }
            UiKey.ESCAPE -> SeatAction.Cancel.takeIf { prompt.cancelEnabled }
            else -> null
        }
    }
    return Outcome(state, action)
}

private fun confirm(state: Interaction, event: UiEvent): Outcome = Outcome(state, when (event) {
    is UiEvent.Click -> when (event.target) {
        ClickTarget.Ok -> SeatAction.Confirm(true)
        ClickTarget.Cancel -> SeatAction.Confirm(false)
        else -> null
    }
    is UiEvent.Key -> when (event.key) {
        UiKey.ENTER -> SeatAction.Confirm(true)
        UiKey.ESCAPE -> SeatAction.Confirm(false)
        else -> null
    }
})

/** The option a click on the board stands for, when a dialog's options are board objects. */
private fun optionFor(options: List<mtgoracle.core.model.ChoiceOption>, target: ClickTarget): Int? {
    val ref: BoardRef = when (target) {
        is ClickTarget.Option -> return target.index.takeIf { it in options.indices }
        is ClickTarget.Card -> BoardRef.Card(target.id)
        is ClickTarget.Player -> BoardRef.Player(target.id)
        is ClickTarget.StackItem -> BoardRef.StackItem(target.id)
        else -> return null
    }
    return options.indexOfFirst { it.ref == ref }.takeIf { it >= 0 }
}

private fun choice(state: Interaction, prompt: ChoicePrompt, event: UiEvent): Outcome {
    val picked: Int? = when (event) {
        is UiEvent.Click -> optionFor(prompt.options, event.target)
        is UiEvent.Key -> event.key.digit?.let { it - 1 }?.takeIf { it in prompt.options.indices }
    }
    val done = (event as? UiEvent.Click)?.target == ClickTarget.Done || (event as? UiEvent.Key)?.key == UiKey.ENTER
    if (prompt.isReveal) return Outcome(state, SeatAction.Choose(emptyList()).takeIf { done })
    if (prompt.max <= 1) {
        return when {
            picked != null -> Outcome(state, SeatAction.Choose(listOf(picked)))
            done && prompt.min == 0 -> Outcome(state, SeatAction.Choose(emptyList()))
            else -> Outcome(state)
        }
    }
    if (picked != null) {
        val picks = if (picked in state.picks) state.picks - picked else if (state.picks.size < prompt.max) state.picks + picked else state.picks
        return Outcome(state.copy(picks = picks))
    }
    if (done && state.picks.size in prompt.min..prompt.max) return Outcome(state, SeatAction.Choose(state.picks))
    if ((event as? UiEvent.Key)?.key == UiKey.ESCAPE) return Outcome(state.copy(picks = emptyList()))
    return Outcome(state)
}

private fun order(state: Interaction, prompt: OrderPrompt, event: UiEvent): Outcome {
    val picked: Int? = when (event) {
        is UiEvent.Click -> optionFor(prompt.items, event.target)
        is UiEvent.Key -> event.key.digit?.let { it - 1 }?.takeIf { it in prompt.items.indices }
    }
    if (picked != null) return Outcome(state.copy(picks = if (picked in state.picks) state.picks - picked else state.picks + picked))
    val done = (event as? UiEvent.Click)?.target == ClickTarget.Done || (event as? UiEvent.Key)?.key == UiKey.ENTER
    if (done) return Outcome(state, SeatAction.Order(state.picks))
    if ((event as? UiEvent.Key)?.key == UiKey.ESCAPE) return Outcome(state.copy(picks = emptyList()))
    return Outcome(state)
}

private fun distribute(state: Interaction, prompt: DistributePrompt, event: UiEvent): Outcome {
    val floor = if (prompt.atLeastOne) 1 else 0
    fun adjust(row: Int, by: Int): Interaction {
        if (row !in prompt.targets.indices) return state
        val amounts = state.amounts.toMutableList()
        val next = amounts[row] + by
        if (next < floor || amounts.sum() + by > prompt.total) return state.copy(row = row)
        amounts[row] = next
        return state.copy(amounts = amounts, row = row)
    }
    return when (event) {
        is UiEvent.Click -> when (val t = event.target) {
            is ClickTarget.More -> Outcome(adjust(t.index, +1))
            is ClickTarget.Less -> Outcome(adjust(t.index, -1))
            is ClickTarget.Option -> Outcome(state.copy(row = t.index))
            ClickTarget.Done -> Outcome(state, SeatAction.Distribute(state.amounts).takeIf { state.amounts.sum() == prompt.total })
            ClickTarget.Cancel -> Outcome(state, SeatAction.Cancel)
            else -> Outcome(state)
        }
        is UiEvent.Key -> when (event.key) {
            UiKey.PLUS -> Outcome(adjust(state.row, +1))
            UiKey.MINUS -> Outcome(adjust(state.row, -1))
            UiKey.ENTER -> Outcome(state, SeatAction.Distribute(state.amounts).takeIf { state.amounts.sum() == prompt.total })
            UiKey.ESCAPE -> Outcome(state, SeatAction.Cancel)
            else -> event.key.digit?.let { d -> Outcome(state.copy(row = (d - 1).coerceIn(0, prompt.targets.lastIndex))) } ?: Outcome(state)
        }
    }
}

/** What Enter sends before anything is typed: 1, within bounds (X = 1 is the common case). */
/** Where the number starts: the prompt's suggestion (an affordable X), else 1 within range. Never enumerates the range. */
fun NumberPrompt.default(): Int = (suggested ?: maxOf(min, 1)).coerceIn(min, max)

private fun number(state: Interaction, prompt: NumberPrompt, event: UiEvent): Outcome {
    val value = state.digits.toIntOrNull() ?: prompt.default()
    fun submit() = Outcome(state, value.takeIf { it in prompt.min..prompt.max }?.let { SeatAction.Number(it) })
    fun step(by: Int) = Outcome(state.copy(digits = (value + by).coerceIn(prompt.min, prompt.max).toString()))
    return when (event) {
        is UiEvent.Click -> when (event.target) {
            is ClickTarget.More -> step(+1)
            is ClickTarget.Less -> step(-1)
            ClickTarget.Done, ClickTarget.Ok -> submit()
            ClickTarget.Cancel -> Outcome(state, SeatAction.Number(null).takeIf { prompt.cancellable })
            else -> Outcome(state)
        }
        is UiEvent.Key -> when (event.key) {
            UiKey.ENTER -> submit()
            UiKey.ESCAPE -> Outcome(state, SeatAction.Number(null).takeIf { prompt.cancellable })
            UiKey.PLUS -> step(+1)
            UiKey.MINUS -> step(-1)
            UiKey.BACKSPACE -> Outcome(state.copy(digits = state.digits.dropLast(1)))
            else -> event.key.digit?.let { d -> Outcome(state.copy(digits = (state.digits + d).trimStart('0').ifEmpty { "0" }.take(6))) } ?: Outcome(state)
        }
    }
}
