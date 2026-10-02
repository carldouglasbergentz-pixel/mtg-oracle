package mtgoracle.core.seat

import mtgoracle.core.model.BoardState
import mtgoracle.core.model.CardState
import mtgoracle.core.model.ChoicePrompt
import mtgoracle.core.model.ConfirmPrompt
import mtgoracle.core.model.DistributePrompt
import mtgoracle.core.model.GameSeat
import mtgoracle.core.model.InputKind
import mtgoracle.core.model.InputPrompt
import mtgoracle.core.model.NumberPrompt
import mtgoracle.core.model.OrderPrompt
import mtgoracle.core.model.PlayerState
import mtgoracle.core.model.Prompt
import mtgoracle.core.model.SeatAction
import mtgoracle.core.model.SideboardPrompt

/** A decision for one prompt, or null to fall back to [ScriptedSeat]'s default policy. */
typealias Policy = (prompt: Prompt, board: BoardState, attempt: Int) -> SeatAction?

/**
 * A stand-in for a person at the window, for headless verification.
 *
 * It sees exactly what the window sees — [GameSeat.board] and
 * [GameSeat.prompt] — and hands each decision to [submit]: by default
 * [GameSeat.answer], and in the tests and the evidence run a click on the
 * offscreen window (ui.OffscreenDriver).
 *
 * Default policy: keep 7, play the first playable land, cast the first
 * playable spell in a main phase, target the opponent's stuff first, pay with
 * Forge's auto-payer, attack with everything, never block, accept Forge's
 * suggested order and damage split. [policy] overrides any of it.
 *
 * A gesture an Input ignores leaves the prompt unchanged, so a prompt still
 * current after [retryAfterMillis] counts as refused and the next candidate
 * is tried (the `attempt` a policy sees).
 */
class ScriptedSeat(
    private val seat: GameSeat,
    private val policy: Policy = { _, _, _ -> null },
    private val onDecision: (String) -> Unit = {},
    private val retryAfterMillis: Long = 800,
    private val submit: (Prompt, SeatAction) -> Unit = { prompt, action -> seat.answer(prompt.id, action) },
) {
    private var lastAnsweredId = -1L
    private var lastAnsweredAt = 0L
    private var attempt = 0
    private val triedThisTurn = mutableSetOf<Int>()
    private var triedTurn = -1
    private val choicesSeen = mutableMapOf<List<String>, Int>()
    var decisions = 0
        private set

    /** Polls until the game is over, [until] holds, or [maxDecisions] are made. Returns whether it stopped for a reason other than the clock. */
    fun play(maxDecisions: Int = 5000, timeoutMillis: Long = 15 * 60_000, until: () -> Boolean = { false }): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline && decisions < maxDecisions) {
            if (seat.board.value?.gameOver == true || until()) return true
            // Re-check right before answering: a prompt that arrived after the check above
            // may be the very one the caller wants to handle itself.
            seat.prompt.value?.let { if (!until()) step(it) }
            Thread.sleep(10)
        }
        return seat.board.value?.gameOver == true || until()
    }

    private fun step(prompt: Prompt) {
        val now = System.currentTimeMillis()
        if (prompt.id == lastAnsweredId) {
            if (now - lastAnsweredAt < retryAfterMillis) return
            attempt++
        } else {
            attempt = 0
        }
        val board = seat.board.value ?: return
        val action = policy(prompt, board, attempt) ?: decide(prompt, board, attempt)
        lastAnsweredId = prompt.id
        lastAnsweredAt = now
        decisions++
        onDecision("#${prompt.id} ${label(prompt)} -> $action${describe(action, board, prompt)}")
        submit(prompt, action)
    }

    private fun decide(prompt: Prompt, board: BoardState, attempt: Int): SeatAction = when (prompt) {
        is ConfirmPrompt -> SeatAction.Confirm(true)
        is ChoicePrompt -> SeatAction.Choose(
            when {
                prompt.isReveal -> emptyList()
                prompt.min <= 1 && prompt.max >= 1 -> listOf(choiceIndex(prompt))
                else -> (0 until prompt.min).toList()
            },
        )
        is OrderPrompt -> SeatAction.Order(prompt.items.indices.toList())
        is DistributePrompt -> SeatAction.Distribute(prompt.suggested)
        is NumberPrompt -> SeatAction.Number(minOf(prompt.max, maxOf(prompt.min, 1)))
        // Keep the deck as it is.
        is SideboardPrompt -> SeatAction.Sideboard(prompt.main.associate { it.name to it.count })
        is InputPrompt -> decideInput(prompt, board, attempt)
    }

    private fun decideInput(prompt: InputPrompt, board: BoardState, attempt: Int): SeatAction {
        val me = board.seat ?: return SeatAction.Ok
        val opponent = board.opponentsOf(me).first()
        return when (prompt.kind) {
            InputKind.MULLIGAN -> SeatAction.Ok
            InputKind.PRIORITY -> priority(prompt, board, me)
            InputKind.PAY_MANA -> when {
                prompt.okEnabled && attempt == 0 -> SeatAction.Ok // "Auto"
                prompt.cancelEnabled -> SeatAction.Cancel
                else -> prompt.actionableCardIds.firstOrNull()?.let { SeatAction.ClickCard(it) } ?: SeatAction.Ok
            }
            InputKind.TARGET -> target(prompt, me, opponent, attempt)
            InputKind.ATTACK -> me.battlefield.filter { it.id in prompt.actionableCardIds && !it.attacking }
                .getOrNull(attempt)?.let { SeatAction.ClickCard(it.id) } ?: SeatAction.Ok
            InputKind.BLOCK -> SeatAction.Ok
            InputKind.SELECT_CARDS, InputKind.CONFIRM, InputKind.OTHER -> when {
                prompt.okEnabled -> SeatAction.Ok
                // One not picked yet: clicking a picked card again un-picks it.
                prompt.selectableCardIds.isNotEmpty() -> (prompt.selectableCardIds - prompt.highlightedCardIds).ifEmpty { prompt.selectableCardIds }
                    .let { SeatAction.ClickCard(it.elementAt(attempt % it.size)) }
                prompt.cancelEnabled -> SeatAction.Cancel
                else -> SeatAction.Ok
            }
        }
    }

    private fun priority(prompt: InputPrompt, board: BoardState, me: PlayerState): SeatAction {
        if (board.turn != triedTurn) { triedTurn = board.turn; triedThisTurn.clear() }
        val myMainPhase = board.activePlayerId == me.id && board.phaseKey in setOf("MAIN1", "MAIN2")
        if (!myMainPhase || board.stack.isNotEmpty()) return SeatAction.Ok
        val untried = { cards: List<CardState> -> cards.filter { it.id in prompt.actionableCardIds && it.id !in triedThisTurn } }
        val hand = untried(me.hand)
        // Land drop, then a spell, then crack a fetchland (an actionable land on the battlefield).
        val next = hand.firstOrNull { it.isLand } ?: hand.firstOrNull { !it.isLand }
            ?: untried(me.battlefield).firstOrNull { it.isLand }
            ?: return SeatAction.Ok
        triedThisTurn += next.id
        return SeatAction.ClickCard(next.id)
    }

    private fun target(prompt: InputPrompt, me: PlayerState, opponent: PlayerState, attempt: Int): SeatAction {
        val theirs = prompt.selectableCardIds.filter { id -> opponent.battlefield.any { it.id == id } }
        val mine = prompt.selectableCardIds.filter { it !in theirs }
        val candidates: List<SeatAction> = theirs.map { SeatAction.ClickCard(it) } +
            SeatAction.ClickPlayer(opponent.id) + mine.map { SeatAction.ClickCard(it) } + SeatAction.ClickPlayer(me.id)
        return candidates.getOrNull(attempt) ?: if (prompt.okEnabled) SeatAction.Ok else SeatAction.Cancel
    }

    /** Same options offered again (a spell we couldn't pay for last time): take the next one. */
    private fun choiceIndex(prompt: ChoicePrompt): Int {
        val seen = (choicesSeen[prompt.labels] ?: 0).also { choicesSeen[prompt.labels] = it + 1 }
        return if (prompt.min == 0) seen % prompt.options.size else 0
    }

    private fun label(prompt: Prompt): String = when (prompt) {
        is InputPrompt -> "${prompt.kind}"
        is ChoicePrompt -> if (prompt.isReveal) "REVEAL" else "CHOICE"
        is ConfirmPrompt -> "CONFIRM"
        is OrderPrompt -> "ORDER"
        is DistributePrompt -> "DISTRIBUTE"
        is NumberPrompt -> "NUMBER"
        is SideboardPrompt -> "SIDEBOARD"
    }

    private fun describe(action: SeatAction, board: BoardState, prompt: Prompt): String = when (action) {
        is SeatAction.ClickCard -> " (${board.card(action.cardId)?.name ?: (prompt as? InputPrompt)?.selectableElsewhere?.firstOrNull { it.id == action.cardId }?.name ?: "card ${action.cardId}"})"
        is SeatAction.ClickPlayer -> " (${board.players.firstOrNull { it.id == action.playerId }?.name})"
        is SeatAction.Choose -> " (${action.indices.joinToString { (prompt as ChoicePrompt).labels[it] }})"
        else -> ""
    }
}
