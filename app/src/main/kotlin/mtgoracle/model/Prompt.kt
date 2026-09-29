package mtgoracle.model

import kotlinx.coroutines.flow.StateFlow

/*
 * The human seat, as the UI sees it.
 *
 * Forge asks a human two ways, and the split survives into this model:
 *
 * - An [InputPrompt] is one of Forge's `Input`s (mulligan, priority, paying
 *   mana, targets, attackers...). The engine thread is parked on a latch; the
 *   answer is a *gesture* — click a card, click a player, OK, Cancel — and the
 *   Input decides what the gesture means. Many prompts, one tiny vocabulary.
 * - A [ChoicePrompt] / [ConfirmPrompt] is a direct dialog call (`one`,
 *   `getChoices`, `confirm`...). The calling thread blocks until we return a
 *   value, so the answer is the value itself.
 */

enum class InputKind { MULLIGAN, PRIORITY, PAY_MANA, TARGET, ATTACK, BLOCK, SELECT_CARDS, CONFIRM, OTHER }

sealed interface Prompt {
    /** Unique per published prompt; an answer names the prompt it answers. */
    val id: Long
    val message: String
}

data class InputPrompt(
    override val id: Long,
    override val message: String,
    val kind: InputKind,
    /** Forge's class name for the Input, for logs and for kinds we don't map. */
    val inputName: String,
    /** Identity of the Forge Input behind this prompt: a re-shown message keeps it. */
    val inputSerial: Int,
    val okLabel: String,
    val cancelLabel: String,
    val okEnabled: Boolean,
    val cancelEnabled: Boolean,
    /** Cards the Input asks you to pick from (targets, discards). */
    val selectableCardIds: Set<Int>,
    /** Cards Forge says you could act with right now (playable, attack-capable). */
    val actionableCardIds: Set<Int>,
    /**
     * Selectable cards the board doesn't show as clickable — a library being
     * searched, a graveyard being targeted. Forge's own GUI opens the zone.
     */
    val selectableElsewhere: List<CardState> = emptyList(),
) : Prompt

data class ChoicePrompt(
    override val id: Long,
    override val message: String,
    val options: List<String>,
    /** -1/-1 means "just look": Forge's reveal. */
    val min: Int,
    val max: Int,
) : Prompt {
    val isReveal: Boolean get() = min < 0
}

data class ConfirmPrompt(
    override val id: Long,
    override val message: String,
    val yesLabel: String,
    val noLabel: String,
) : Prompt

sealed interface SeatAction {
    data class ClickCard(val cardId: Int) : SeatAction
    data class ClickPlayer(val playerId: Int) : SeatAction
    data object Ok : SeatAction
    data object Cancel : SeatAction
    data class Choose(val indices: List<Int>) : SeatAction
    data class Confirm(val yes: Boolean) : SeatAction
}

/** One seat at a table: what it sees, what it is asked, how it answers. */
interface GameSeat {
    val board: StateFlow<BoardState?>
    /** Null while the engine is not waiting on this seat. */
    val prompt: StateFlow<Prompt?>
    /** Answers prompt [promptId]; ignored (and logged) if that prompt is no longer current. */
    fun answer(promptId: Long, action: SeatAction)
}
