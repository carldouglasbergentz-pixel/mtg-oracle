package mtgoracle.ui.board

import mtgoracle.core.model.BoardState
import mtgoracle.core.model.InputKind
import mtgoracle.core.model.InputPrompt
import mtgoracle.core.model.Prompt
import mtgoracle.core.model.SeatAction
import mtgoracle.core.model.SeatCommand
import mtgoracle.ui.kit.ClickTarget

/**
 * A pass held back by the floating-mana warning: [action] or [command] is
 * sent on `[ Pass ]` (Enter) and dropped on `[ Stay ]` (Esc). It lives in
 * [Interaction], so it belongs to one prompt and goes when the prompt does;
 * nothing is sent to the seat until the player decides.
 */
data class PendingPass(val pool: String, val action: SeatAction? = null, val command: SeatCommand? = null)

object ManaWarningTargets {
    val PASS = ClickTarget.Control("mana:pass")
    val STAY = ClickTarget.Control("mana:stay")
}

/** "W2 R1", as the board carries the pool, as symbols: `{W}{W}{R}`, written as the pool line writes them. */
fun poolSymbols(pool: String): String = poolParts(pool).joinToString("") { (colour, amount) -> poolSymbols(colour, amount) }

/**
 * The seat's floating mana as symbols when passing priority now could lose
 * it, else null.
 *
 * The rule: the seat holds priority, the stack is empty, and the pool holds
 * mana that empties at the end of the step. With an empty stack, passing hands
 * priority to the other side, and if they pass too the step ends and the mana
 * is gone. With something on the stack a pass only lets the top resolve (or
 * the other side respond), and priority comes back within the same step with
 * the mana still usable. The engine is held to that (the forge module's
 * FloatingMana), so asking there would only get in the way of the response
 * the mana was floated for. Forge's own prompt (UI_MANA_LOST_PROMPT, off here)
 * uses the same test.
 */
fun manaAtRisk(prompt: Prompt?, board: BoardState?): String? {
    val seat = board?.seat ?: return null
    if ((prompt as? InputPrompt)?.kind != InputKind.PRIORITY) return null
    if (board.stack.isNotEmpty() || seat.manaPool.isBlank() || !seat.manaEmpties) return null
    return poolSymbols(seat.manaPool)
}

/** The question, in the house style. */
fun PendingPass.question(): String = "$pool is floating and empties when this step ends — pass anyway?"
