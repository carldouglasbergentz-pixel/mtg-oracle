package mtgoracle.seat

import mtgoracle.model.BoardState
import mtgoracle.model.CardState
import mtgoracle.model.ChoicePrompt
import mtgoracle.model.ConfirmPrompt
import mtgoracle.model.GameSeat
import mtgoracle.model.InputKind
import mtgoracle.model.InputPrompt
import mtgoracle.model.PlayerState
import mtgoracle.model.Prompt
import mtgoracle.model.SeatAction

/**
 * A stand-in for a person at the window, for headless verification.
 *
 * It sees exactly what the window sees — [GameSeat.board] and
 * [GameSeat.prompt] — and hands each decision to [submit]: by default
 * [GameSeat.answer] directly, and in the spike's evidence run a click on the
 * offscreen window (ui.OffscreenWindow). Nothing here imports Forge. Policy: keep 7, play the
 * first playable land, cast the first playable spell in a main phase, target
 * the opponent's stuff first, pay mana with Forge's auto-payer, attack with
 * everything, never block.
 *
 * A gesture an Input ignores leaves the prompt unchanged, so a prompt still
 * current after [retryAfterMillis] counts as refused and the next candidate
 * is tried.
 */
class ScriptedSeat(
    private val seat: GameSeat,
    private val onDecision: (String) -> Unit = {},
    private val retryAfterMillis: Long = 800,
    private val submit: (Prompt, SeatAction) -> Unit = { prompt, action -> seat.answer(prompt.id, action) },
) {
    private var lastAnsweredId = -1L
    private var lastAnsweredAt = 0L
    private var attempt = 0
    private val triedThisTurn = mutableSetOf<Int>()
    private var triedTurn = -1
    var decisions = 0
        private set

    /** Polls until the game is over or [maxDecisions] have been made. Returns whether the game finished. */
    fun playUntilGameOver(maxDecisions: Int = 5000, timeoutMillis: Long = 15 * 60_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline && decisions < maxDecisions) {
            if (seat.board.value?.gameOver == true) return true
            val prompt = seat.prompt.value
            if (prompt != null) step(prompt)
            Thread.sleep(15)
        }
        return seat.board.value?.gameOver == true
    }

    private fun step(prompt: Prompt) {
        val now = System.currentTimeMillis()
        if (prompt.id == lastAnsweredId) {
            if (now - lastAnsweredAt < retryAfterMillis) return
            attempt++ // refused: try the next candidate
        } else {
            attempt = 0
        }
        val board = seat.board.value ?: return
        val action = decide(prompt, board, attempt)
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
        is InputPrompt -> decideInput(prompt, board, attempt)
    }

    private fun decideInput(prompt: InputPrompt, board: BoardState, attempt: Int): SeatAction {
        val me = board.seat ?: return SeatAction.Ok
        val opponent = board.opponentsOf(me).first()
        return when (prompt.kind) {
            InputKind.MULLIGAN -> SeatAction.Ok // keep 7
            InputKind.PRIORITY -> priority(prompt, board, me)
            InputKind.PAY_MANA -> when {
                prompt.okEnabled && attempt == 0 -> SeatAction.Ok // "Auto"
                prompt.cancelEnabled -> SeatAction.Cancel
                else -> prompt.actionableCardIds.firstOrNull()?.let { SeatAction.ClickCard(it) } ?: SeatAction.Ok
            }
            InputKind.TARGET -> target(prompt, board, me, opponent, attempt)
            InputKind.ATTACK -> {
                val ready = me.battlefield.filter { it.id in prompt.actionableCardIds && !it.attacking }
                ready.getOrNull(attempt)?.let { SeatAction.ClickCard(it.id) } ?: SeatAction.Ok
            }
            InputKind.BLOCK -> SeatAction.Ok
            InputKind.SELECT_CARDS, InputKind.CONFIRM, InputKind.OTHER -> when {
                prompt.okEnabled -> SeatAction.Ok
                prompt.selectableCardIds.isNotEmpty() -> SeatAction.ClickCard(prompt.selectableCardIds.elementAt(attempt % prompt.selectableCardIds.size))
                prompt.cancelEnabled -> SeatAction.Cancel
                else -> SeatAction.Ok
            }
        }
    }

    private fun priority(prompt: InputPrompt, board: BoardState, me: PlayerState): SeatAction {
        if (board.turn != triedTurn) { triedTurn = board.turn; triedThisTurn.clear() }
        val myMainPhase = board.activePlayerId == me.id && board.phaseKey in setOf("MAIN1", "MAIN2")
        if (!myMainPhase || board.stack.isNotEmpty()) return SeatAction.Ok
        val untried = { cards: List<CardState> ->
            cards.filter { it.id in prompt.actionableCardIds && it.id !in triedThisTurn }
        }
        val hand = untried(me.hand.orEmpty())
        // Land drop, then a spell, then crack a fetchland (an actionable land on the battlefield).
        val next = hand.firstOrNull { it.isLand } ?: hand.firstOrNull { !it.isLand }
            ?: untried(me.battlefield).firstOrNull { it.isLand }
            ?: return SeatAction.Ok // nothing (left) to do: pass
        triedThisTurn += next.id
        return SeatAction.ClickCard(next.id)
    }

    /** Same options offered again (a spell we couldn't pay for last time): take the next one. */
    private fun choiceIndex(prompt: ChoicePrompt): Int {
        val seen = choicesSeen.merge(prompt.options, 1, Int::plus)!! - 1
        return if (prompt.min == 0 && prompt.max >= 1) seen % prompt.options.size else 0
    }
    private val choicesSeen = mutableMapOf<List<String>, Int>()

    private fun target(prompt: InputPrompt, board: BoardState, me: PlayerState, opponent: PlayerState, attempt: Int): SeatAction {
        val theirs = prompt.selectableCardIds.filter { id -> opponent.battlefield.any { it.id == id } }
        val mine = prompt.selectableCardIds.filter { it !in theirs }
        val candidates: List<SeatAction> =
            theirs.map { SeatAction.ClickCard(it) } +
                SeatAction.ClickPlayer(opponent.id) +
                mine.map { SeatAction.ClickCard(it) } +
                SeatAction.ClickPlayer(me.id)
        return candidates.getOrNull(attempt) ?: if (prompt.okEnabled) SeatAction.Ok else SeatAction.Cancel
    }

    private fun label(prompt: Prompt): String = when (prompt) {
        is InputPrompt -> "${prompt.kind}"
        is ChoicePrompt -> if (prompt.isReveal) "REVEAL" else "CHOICE"
        is ConfirmPrompt -> "CONFIRM"
    }

    private fun describe(action: SeatAction, board: BoardState, prompt: Prompt): String = when (action) {
        is SeatAction.ClickCard -> " (${board.card(action.cardId)?.name ?: "card ${action.cardId}"})"
        is SeatAction.ClickPlayer -> " (${board.players.firstOrNull { it.id == action.playerId }?.name})"
        is SeatAction.Choose -> " (${action.indices.joinToString { (prompt as ChoicePrompt).options[it] }})"
        else -> ""
    }
}
