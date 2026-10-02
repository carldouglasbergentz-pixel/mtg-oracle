package mtgoracle.core.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * The human seat, as the UI sees it.
 *
 * Forge asks a human two ways, and the split survives into this model:
 *
 * - An [InputPrompt] is one of Forge's `Input`s (mulligan, priority, paying
 *   mana, targets, attackers, blockers...). The engine thread is parked on a
 *   latch; the answer is a *gesture* — click a card, click a player, OK,
 *   Cancel — and the Input decides what the gesture means.
 * - Every other prompt is a direct dialog call (`getChoices`, `confirm`,
 *   `order`, `assignCombatDamage`, `getInteger`...). The calling thread blocks
 *   until we return a value, so the answer is the value itself.
 */

enum class InputKind { MULLIGAN, PRIORITY, PAY_MANA, TARGET, ATTACK, BLOCK, SELECT_CARDS, CONFIRM, OTHER }

/** Something on the board a dialog option stands for, so the board can be clicked to choose it. */
sealed interface BoardRef {
    data class Card(val id: Int) : BoardRef
    data class Player(val id: Int) : BoardRef
    data class StackItem(val id: Int) : BoardRef
}

data class ChoiceOption(val label: String, val ref: BoardRef? = null)

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
    /** Cards the Input asks you to pick from (targets, discards, blockers' attackers). */
    val selectableCardIds: Set<Int>,
    /** Cards Forge says you could act with right now (playable, attack- or block-capable). */
    val actionableCardIds: Set<Int>,
    /** Selectable cards the board doesn't show — a library being searched, a graveyard. */
    val selectableElsewhere: List<CardState> = emptyList(),
    /** Cards Forge marks in this Input: those picked so far (Gush's two Islands), the attacker being blocked. */
    val highlightedCardIds: Set<Int> = emptySet(),
    /** Cancel is Forge's Undo here (a mana ability can be undone), not End Turn: it gives the mana back rather than passing. */
    val cancelUndoes: Boolean = false,
) : Prompt

/** Pick [min]..[max] options. min = max = -1 is Forge's reveal: look, then continue. */
data class ChoicePrompt(
    override val id: Long,
    override val message: String,
    val options: List<ChoiceOption>,
    val min: Int,
    val max: Int,
) : Prompt {
    val isReveal: Boolean get() = min < 0
    val labels: List<String> get() = options.map { it.label }
}

data class ConfirmPrompt(
    override val id: Long,
    override val message: String,
    val yesLabel: String,
    val noLabel: String,
) : Prompt

/** Put all [items] in an order (first = top of library, first to resolve...). */
data class OrderPrompt(
    override val id: Long,
    override val message: String,
    val items: List<ChoiceOption>,
    /** What "first" means here, e.g. "top of library". */
    val firstLabel: String,
) : Prompt

data class DistributeTarget(
    val label: String, val ref: BoardRef?,
    /** Lethal damage, when it applies. */
    val lethal: Int?,
    /** The most this target may take ("two mana of different colors": 1 each); null for no limit. */
    val max: Int? = null,
)

/** Divide [total] among [targets]: combat damage, divided damage, shields. */
data class DistributePrompt(
    override val id: Long,
    override val message: String,
    val targets: List<DistributeTarget>,
    val total: Int,
    /** Every target must get at least one. */
    val atLeastOne: Boolean,
    /** Forge's default split (lethal in order), shown pre-filled. */
    val suggested: List<Int>,
    /**
     * The target (by index) that may take damage only once every other target
     * has its lethal: the player or planeswalker a trampler is attacking (CR
     * 702.19b). Null when there is none: without trample a blocked creature's
     * damage goes to its blockers only (CR 510.1c).
     */
    val excess: Int? = null,
    /** Each target must have its lethal before the next gets any: the damage assignment order, where Forge still keeps one. */
    val inOrder: Boolean = false,
) : Prompt {
    /** Why [amounts] can't be the answer (the total, at least one each, lethal first), or null when they can. */
    fun problem(amounts: List<Int>): String? {
        val sum = amounts.sum()
        fun short(i: Int) = targets[i].lethal?.let { amounts.getOrElse(i) { 0 } < it } == true
        return when {
            amounts.size != targets.size || sum != total -> "assigned $sum of $total"
            atLeastOne && amounts.any { it < 1 } -> "every target takes at least 1"
            targets.indices.any { i -> targets[i].max?.let { amounts[i] > it } == true } ->
                targets.indices.first { i -> targets[i].max?.let { amounts[i] > it } == true }.let { "${targets[it].label} takes at most ${targets[it].max}" }
            excess != null && amounts[excess] > 0 && targets.indices.any { it != excess && short(it) } ->
                "${targets[excess].label} takes damage only once every blocker has lethal (trample)"
            inOrder -> targets.indices.firstOrNull { i -> amounts[i] > 0 && (0 until i).any { it != excess && short(it) } }
                ?.let { i -> "${targets[(0 until i).first { it != excess && short(it) }].label} needs lethal before ${targets[i].label}" }
            else -> null
        }
    }
}

/** A number in [min]..[max]: X costs, "choose a number". */
data class NumberPrompt(
    override val id: Long,
    override val message: String,
    val min: Int,
    val max: Int,
    val cancellable: Boolean,
    /** Where to start (an X cost: the largest the mana you have now pays for); any value in range may still be given. */
    val suggested: Int? = null,
    /** A word on [suggested], e.g. "max affordable 5". */
    val note: String? = null,
) : Prompt

/** A card and how many copies, in a deck section. */
data class DeckEntry(val name: String, val count: Int)

/**
 * Between games of a match: your main deck and sideboard, to swap cards
 * between. The answer is the new main deck; what is left is the sideboard.
 */
data class SideboardPrompt(
    override val id: Long,
    override val message: String,
    val main: List<DeckEntry>,
    val side: List<DeckEntry>,
    /** The fewest cards the main deck may hold (60 in constructed). */
    val minMain: Int,
) : Prompt

sealed interface SeatAction {
    /** The main deck to play the next game with, by card name. */
    data class Sideboard(val main: Map<String, Int>) : SeatAction
    /** Paying a cost: spend one mana of [colour] (W U B R G C) from your pool, as a click on Forge's pool does. */
    data class UseMana(val colour: Char) : SeatAction
    data class ClickCard(val cardId: Int) : SeatAction
    data class ClickPlayer(val playerId: Int) : SeatAction
    data object Ok : SeatAction
    data object Cancel : SeatAction
    data class Choose(val indices: List<Int>) : SeatAction
    data class Confirm(val yes: Boolean) : SeatAction
    /** Indices of the prompt's items, first first; items left out keep their order after them. */
    data class Order(val indices: List<Int>) : SeatAction
    data class Distribute(val amounts: List<Int>) : SeatAction
    data class Number(val value: Int?) : SeatAction
}

/** Turn-flow commands (MTGO's F-keys), valid whether or not a prompt is showing. */
enum class SeatCommand {
    /** F2: OK, or pass priority once. */
    PASS,
    /** F3: cancel every active yield and auto-yield. */
    CANCEL_YIELDS,
    /** F4: done for this turn, but stop if the opponent acts. */
    END_TURN,
    /** F6: skip the rest of the turn, whatever happens (decisions still stop). */
    SKIP_TURN,
}

/** One seat at a table: what it sees, what it is asked, how it answers. */
interface GameSeat {
    val board: StateFlow<BoardState?>
    /** Null while the engine is not waiting on this seat. */
    val prompt: StateFlow<Prompt?>
    val stops: StateFlow<PhaseStops>
    /** A short line on the active yield ("yielding until end of turn"), or null. */
    val yieldStatus: StateFlow<String?>
    /** Answers prompt [promptId]; ignored (and logged) if that prompt is no longer current. */
    fun answer(promptId: Long, action: SeatAction)
    fun command(command: SeatCommand)
    fun setStops(stops: PhaseStops)

    /**
     * The last decision something other than you made for you — a Forge
     * dialog we have no prompt for, answered with its default — or null. The
     * board shows it; a game should never have one.
     */
    val warning: StateFlow<String?> get() = NO_WARNING

    /** True only when watching AI vs AI: then both hands may be shown, behind [setShowAllHands]. */
    val canShowAllHands: Boolean get() = false
    val showAllHands: StateFlow<Boolean>
    fun setShowAllHands(show: Boolean) {}
}

private val NO_WARNING: StateFlow<String?> = MutableStateFlow(null)
