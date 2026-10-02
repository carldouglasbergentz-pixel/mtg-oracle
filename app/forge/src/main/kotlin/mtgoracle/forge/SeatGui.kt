package mtgoracle.forge

import forge.LobbyPlayer
import forge.deck.CardPool
import forge.game.GameEntityView
import forge.game.GameState
import forge.game.card.CardView
import forge.game.phase.PhaseType
import forge.game.player.DelayedReveal
import forge.game.player.IHasIcon
import forge.game.player.PlayerView
import forge.game.spellability.SpellAbilityView
import forge.game.spellability.StackItemView
import forge.game.zone.ZoneType
import forge.gamemodes.match.AbstractGuiGame
import forge.gamemodes.match.input.Input
import forge.gamemodes.match.input.InputAttack
import forge.gamemodes.match.input.InputBlock
import forge.gamemodes.match.input.InputConfirm
import forge.gamemodes.match.input.InputConfirmMulligan
import forge.gamemodes.match.input.InputLockUI
import forge.gamemodes.match.input.InputLondonMulligan
import forge.gamemodes.match.input.InputPassPriority
import forge.gamemodes.match.input.InputPayMana
import forge.gamemodes.match.input.InputSelectManyBase
import forge.gamemodes.match.input.InputSelectTargets
import forge.gui.control.WatchLocalGame
import forge.gui.interfaces.IGuiGame
import forge.interfaces.IGameController
import forge.item.PaperCard
import forge.localinstance.properties.ForgePreferences.FPref
import forge.localinstance.skin.FSkinProp
import forge.player.PlayerControllerHuman
import forge.player.PlayerZoneUpdate
import forge.trackable.TrackableCollection
import forge.util.FSerializableFunction
import forge.util.ITriggerEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import mtgoracle.core.model.BoardRef
import mtgoracle.core.model.BoardState
import mtgoracle.core.model.CardState
import mtgoracle.core.model.ChoiceOption
import mtgoracle.core.model.ChoicePrompt
import mtgoracle.core.model.ConfirmPrompt
import mtgoracle.core.model.DistributePrompt
import mtgoracle.core.model.DistributeTarget
import mtgoracle.core.model.GameSeat
import mtgoracle.core.model.InputKind
import mtgoracle.core.model.InputPrompt
import mtgoracle.core.model.NumberPrompt
import mtgoracle.core.model.OrderPrompt
import mtgoracle.core.model.PhaseStops
import mtgoracle.core.model.Prompt
import mtgoracle.core.model.SeatAction
import mtgoracle.core.model.SeatCommand
import mtgoracle.core.model.Step
import mtgoracle.core.model.DeckEntry
import mtgoracle.core.model.SideboardPrompt
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Forge's per-game GUI (`IGuiGame`) for one seat — the human-player seam.
 *
 * `PlayerControllerHuman` talks to this in two ways, and both become a
 * [Prompt] on [prompt]:
 *
 * 1. **Inputs.** The engine pushes an `Input` onto the controller's
 *    `InputQueue` and parks the game thread on the Input's latch. Forge posts
 *    `showMessageInitial` to its EDT ([ForgeEdt]); the Input then calls
 *    [showPromptMessage], [updateButtons], [setSelectables] here. After each
 *    EDT task we read the queue: an Input on top that has shown its message
 *    is published as an [InputPrompt]. The answer is a gesture replayed on
 *    the EDT against `IGameController` — exactly what Forge's own board does
 *    on a click. The Input releases its latch; the engine resumes.
 * 2. **Direct dialogs** (`getChoices`, `confirm`, `order`, `getInteger`,
 *    `assignCombatDamage`...). Forge calls these synchronously — from the game
 *    thread, or from the EDT in the middle of a click — and wants a value
 *    back. We publish a prompt and block *that* thread on a future the UI
 *    completes. The Compose thread never blocks.
 *
 * Methods with no UI yet answer automatically and write `UNHANDLED` to the
 * game log, so a game never hangs on them.
 */
class SeatGui(
    private val recorder: GameRecorder,
    private val edt: ForgeEdt,
    initialStops: PhaseStops = PhaseStops.DEFAULT,
) : AbstractGuiGame(), GameSeat {

    private val boardFlow = MutableStateFlow<BoardState?>(null)
    private val promptFlow = MutableStateFlow<Prompt?>(null)
    private val stopsFlow = MutableStateFlow(initialStops)
    private val yieldFlow = MutableStateFlow<String?>(null)
    private val showHandsFlow = MutableStateFlow(false)
    private val warningFlow = MutableStateFlow<String?>(null)
    override val warning: StateFlow<String?> get() = warningFlow
    override val board: StateFlow<BoardState?> get() = boardFlow
    override val prompt: StateFlow<Prompt?> get() = promptFlow
    override val stops: StateFlow<PhaseStops> get() = stopsFlow
    override val yieldStatus: StateFlow<String?> get() = yieldFlow
    override val showAllHands: StateFlow<Boolean> get() = showHandsFlow
    override val canShowAllHands: Boolean get() = seatController == null

    /** Watching AI vs AI only: show both hands (off by default). A seat never sees the opponent's hand this way. */
    override fun setShowAllHands(show: Boolean) {
        if (!canShowAllHands) return
        showHandsFlow.value = show
        recorder.seat("SHOW ALL HANDS $show")
        dirty = true
        edt.later { }
    }

    /**
     * What this seat may see of [card]. A seat: Forge's own rule for its
     * player (AbstractGuiGame.mayView -> CardView.canBeShownToAny), which
     * follows reveals, "look at" effects and face-down ownership. A spectator:
     * public zones only, unless both hands are switched on.
     */
    /** A face-down card's real face: Forge's rule, as its own board's mayFlip uses it. */
    private fun peek(card: CardView): Boolean =
        if (seatController != null) card.canFaceDownBeShownToAny(localPlayers) else showHandsFlow.value

    private fun visible(card: CardView): Boolean =
        if (seatController != null) mayView(card)
        else showHandsFlow.value || (card.zone !in HIDDEN_ZONES && !card.isFaceDown)

    private val cardViews = ConcurrentHashMap<Int, CardView>()
    private val playerViews = ConcurrentHashMap<Int, PlayerView>()
    private val snapshots = Snapshots(cardViews, playerViews)

    /** Per player name, the cards whose chosen printing Forge lacks and the art key to draw them with instead. */
    internal fun setArtOverrides(overrides: Map<String, Map<String, String>>) { snapshots.artOverrides = overrides }
    /** Named under the board's own rule: seen, and if face-down, peekable. */
    private val trail = Trail(named = { cv -> visible(cv) && (!cv.isFaceDown || peek(cv)) }, onChange = { dirty = true })
    private val floatingMana = FloatingMana(recorder)
    private val drawLog = DrawLog(recorder)
    /** The trail's seq when the seat last decided something: what came after is "just happened". */
    @Volatile private var decisionSeq = 0L
    private val promptIds = AtomicLong()
    private val failedCasts = FailedCasts(promptCount = { promptIds.get() }, isSeats = { cv -> cv.controller?.id in seatPlayerIds }, onFailed = ::castFailed)
    private val refreshHook: AutoCloseable = edt.afterEachTask { refresh() }

    @Volatile private var seatController: PlayerControllerHuman? = null
    @Volatile private var seatPlayerIds: Set<Int> = emptySet()
    @Volatile private var dirty = true
    @Volatile private var finished = false
    /** F6: the turn being skipped; opponent actions don't interrupt it. */
    @Volatile private var skippingTurn: Int? = null

    /** Runs once, on the EDT, when the game has finished (the app records it). */
    @Volatile var onFinished: (() -> Unit)? = null

    // What the current Input has shown. Written by Forge's Inputs, on the EDT.
    private var shownInput: Input? = null
    private var shownMessage = ""
    private var okLabel = ""
    private var cancelLabel = ""
    private var okEnabled = false
    private var cancelEnabled = false
    @Volatile private var selectableIds: Set<Int> = emptySet()
    @Volatile private var actionableIds: Set<Int> = emptySet()

    // Direct dialogs in flight, innermost last. A click can open a dialog on
    // the EDT while the game thread is parked on an Input, so this can nest.
    private val dialogs = ArrayDeque<PendingDialog>()

    private class PendingDialog(val prompt: Prompt, val reply: CompletableFuture<SeatAction> = CompletableFuture())

    // --- GameSeat -----------------------------------------------------------

    override fun answer(promptId: Long, action: SeatAction) {
        val current = promptFlow.value
        if (current == null || current.id != promptId) {
            Log.warn("stale answer $action for prompt #$promptId (current ${current?.id})")
            return
        }
        recorder.seat("ANSWER #$promptId $action${describe(action, current)}")
        decisionSeq = trail.lastSeq()
        when (current) {
            is InputPrompt -> edt.later { applyGesture(action) }
            else -> synchronized(dialogs) { dialogs.firstOrNull { it.prompt.id == promptId }?.reply?.complete(action) }
        }
    }

    override fun command(command: SeatCommand) {
        recorder.seat("COMMAND $command")
        decisionSeq = trail.lastSeq()
        edt.later {
            val controller = seatController ?: return@later
            val yields = controller.yieldController
            val atPriority = controller.inputQueue.input is InputPassPriority
            when (command) {
                SeatCommand.PASS -> if (controller.inputQueue.input != null) controller.selectButtonOk()
                SeatCommand.CANCEL_YIELDS -> {
                    floatingMana.forget()
                    yields.clearActiveYieldAndDispatch()
                    yields.clearAutoYields()
                    endSkip(controller)
                }
                // With mana floating, the yield is held (not dropped) before this pass: the engine
                // must not auto-pass the next priority, and F6 goes on once the pool is empty.
                SeatCommand.END_TURN -> {
                    floatingMana.forget()
                    endSkip(controller)
                    controller.autoPassUntilEndOfTurn()
                    floatingMana.update()
                    if (atPriority) controller.selectButtonOk()
                }
                SeatCommand.SKIP_TURN -> {
                    floatingMana.forget()
                    // Forge's end-of-turn yield, with the interrupts that F4 keeps switched off
                    // for this controller until the turn ends (updateTurn restores them).
                    INTERRUPTS.forEach { yields.setPref(it, "false") }
                    skippingTurn = gameView?.turn
                    controller.autoPassUntilEndOfTurn()
                    floatingMana.update()
                    if (atPriority) controller.selectButtonOk()
                }
            }
            refreshYieldStatus()
        }
    }

    /** Whether this seat conceded the game being played (or just finished). */
    @Volatile var conceded = false
        private set

    /** Concede the game for this seat (or, watching AI vs AI, end it as a draw). */
    fun concedeNow() {
        conceded = true
        val controller = seatController
        if (controller != null) edt.later { controller.concede() }
        else gameView?.game?.let { game -> game.action.invoke { game.setGameOver(forge.game.GameEndReason.Draw) } }
    }

    override fun setStops(stops: PhaseStops) {
        stopsFlow.value = stops
        recorder.seat("STOPS ${stops.serialise()}")
    }

    private fun endSkip(controller: PlayerControllerHuman) {
        if (skippingTurn == null) return
        skippingTurn = null
        INTERRUPTS.forEach { controller.yieldController.setPref(it, "true") }
    }

    private fun describe(action: SeatAction, prompt: Prompt): String = when (action) {
        is SeatAction.ClickCard -> cardViews[action.cardId]?.let { " = ${it.currentState.name}" } ?: " = unknown card"
        is SeatAction.ClickPlayer -> playerViews[action.playerId]?.let { " = ${it.name}" } ?: " = unknown player"
        is SeatAction.Choose -> (prompt as? ChoicePrompt)?.let { p -> " = " + action.indices.joinToString(", ") { p.labels.getOrElse(it) { "?" } } } ?: ""
        is SeatAction.Order -> (prompt as? OrderPrompt)?.let { p -> " = " + action.indices.joinToString(" > ") { p.items.getOrNull(it)?.label ?: "?" } } ?: ""
        is SeatAction.Distribute -> (prompt as? DistributePrompt)?.let { p -> " = " + p.targets.zip(action.amounts).joinToString { "${it.first.label}=${it.second}" } } ?: ""
        else -> ""
    }

    /** On the EDT, as Forge's own board would on a click. */
    private fun applyGesture(action: SeatAction) {
        val controller = seatController ?: return
        when (action) {
            is SeatAction.ClickCard -> {
                val view = cardViews[action.cardId]
                if (view == null) Log.warn("click on unknown card ${action.cardId}")
                else if (!controller.selectCard(view, null, null)) {
                    recorder.seat("  (the ${(promptFlow.value as? InputPrompt)?.inputName} ignored the click)")
                }
            }
            is SeatAction.ClickPlayer -> playerViews[action.playerId]?.let { controller.selectPlayer(it, null) }
            is SeatAction.UseMana -> MANA_BYTES[action.colour]?.let { controller.useMana(it) } ?: Log.warn("no mana colour ${action.colour}")
            SeatAction.Ok -> controller.selectButtonOk()
            SeatAction.Cancel -> controller.selectButtonCancel()
            else -> Log.warn("$action is not a gesture")
        }
    }

    // --- prompt and board publication (EDT) ----------------------------------

    private fun refresh() {
        if (dirty) snapshot()
        publishInputPrompt()
        refreshYieldStatus()
    }

    private fun refreshYieldStatus() {
        val yields = seatController?.yieldController
        yieldFlow.value = when {
            yields == null || finished -> null
            yields.autoPassUntilEndOfTurn() && skippingTurn != null -> "skipping the rest of the turn (F3 cancels)"
            yields.autoPassUntilEndOfTurn() -> "done for the turn; stops if the opponent acts (F3 cancels)"
            yields.autoPassUntilStackEmpty() -> "yielding until the stack resolves (F3 cancels)"
            else -> null
        }
    }

    private fun snapshot() {
        val view = gameView ?: return
        dirty = false
        try {
            val board = snapshots.build(view, seatPlayerIds, ::visible, ::peek, recorder.recentLog(60), finished, trail.snapshot(), decisionSeq)
            val empties = floatingMana.floating()
            boardFlow.value = board.copy(players = board.players.map { if (it.isSeat && it.manaPool.isNotEmpty()) it.copy(manaEmpties = empties) else it })
        } catch (e: RuntimeException) {
            // The engine thread changed a collection mid-read; the next tick retries.
            dirty = true
            Log.debug("snapshot retried: $e")
        }
    }

    private fun publishInputPrompt() {
        if (synchronized(dialogs) { dialogs.isNotEmpty() }) return
        val controller = seatController ?: return
        val top = controller.inputQueue.input
        val current = promptFlow.value
        if (top == null || top !== shownInput || top is InputLockUI || finished) {
            if (current is InputPrompt) promptFlow.value = null
            return
        }
        val candidate = InputPrompt(
            id = 0, message = shownMessage, kind = kindOf(top), inputName = top.javaClass.simpleName,
            inputSerial = System.identityHashCode(top),
            okLabel = okLabel, cancelLabel = cancelLabel, okEnabled = okEnabled, cancelEnabled = cancelEnabled,
            selectableCardIds = selectableIds, actionableCardIds = actionableIds,
            // InputPassPriority names the cancel button "Undo (n)" exactly when the last action can be undone.
            cancelUndoes = cancelLabel.startsWith(forge.util.Localizer.getInstance().getMessage("lblUndo")),
        )
        if (current is InputPrompt && current.copy(id = 0, selectableElsewhere = emptyList()) == candidate) return
        snapshot() // the engine is parked on this Input: the board is consistent now
        publish(candidate.copy(id = promptIds.incrementAndGet(), selectableElsewhere = selectableElsewhere()))
    }

    /** Selectable cards the board doesn't draw (a library being searched): the zones it does draw are clickable in place. */
    private fun selectableElsewhere(): List<CardState> {
        val shown = boardFlow.value?.players.orEmpty().flatMap { it.hand + it.battlefield + it.graveyard + it.exile + it.command }.map { it.id }.toSet()
        return selectableIds.filter { it !in shown }.mapNotNull { id -> cardViews[id]?.let { snapshots.card(it) } }
    }

    private fun publish(prompt: Prompt) {
        promptFlow.value = prompt
        val detail = when (prompt) {
            is InputPrompt -> "${prompt.kind}/${prompt.inputName} [ok=${prompt.okLabel.ifBlank { "-" }}${if (prompt.okEnabled) "" else "(off)"}" +
                " cancel=${prompt.cancelLabel.ifBlank { "-" }}${if (prompt.cancelEnabled) "" else "(off)"}]" +
                " selectable=${prompt.selectableCardIds.size} actionable=${prompt.actionableCardIds.size}" +
                (if (prompt.selectableElsewhere.isEmpty()) "" else " elsewhere=${prompt.selectableElsewhere.map { it.name }}")
            is ChoicePrompt -> "CHOICE min=${prompt.min} max=${prompt.max} options=${prompt.labels}"
            is ConfirmPrompt -> "CONFIRM [${prompt.yesLabel}/${prompt.noLabel}]"
            is OrderPrompt -> "ORDER (${prompt.firstLabel} first) ${prompt.items.map { it.label }}"
            is DistributePrompt -> "DISTRIBUTE ${prompt.total} over ${prompt.targets.map { it.label }} suggested=${prompt.suggested}"
            is NumberPrompt -> "NUMBER ${prompt.min}..${prompt.max}"
            is SideboardPrompt -> "SIDEBOARD main ${prompt.main.sumOf { it.count }} side ${prompt.side.sumOf { it.count }}"
        }
        recorder.seat("PROMPT #${prompt.id} $detail '${prompt.message.trim()}'")
    }

    private fun kindOf(input: Input): InputKind = when (input) {
        is InputConfirmMulligan -> InputKind.MULLIGAN
        is InputPassPriority -> InputKind.PRIORITY
        is InputPayMana -> InputKind.PAY_MANA
        is InputSelectTargets -> InputKind.TARGET
        is InputAttack -> InputKind.ATTACK
        is InputBlock -> InputKind.BLOCK
        is InputConfirm -> InputKind.CONFIRM
        is InputSelectManyBase<*>, is InputLondonMulligan -> InputKind.SELECT_CARDS
        else -> InputKind.OTHER
    }

    /** Blocks the calling Forge thread until the seat answers [make]'s prompt. */
    private fun awaitDialog(make: (Long) -> Prompt, fallback: SeatAction, betweenGames: Boolean = false): SeatAction {
        if (finished && !betweenGames) return fallback
        val pending = PendingDialog(make(promptIds.incrementAndGet()))
        synchronized(dialogs) { dialogs.addLast(pending) }
        if (!edt.isCurrent()) snapshot() // the calling engine thread is the one waiting
        publish(pending.prompt)
        try {
            return pending.reply.get()
        } finally {
            val outer = synchronized(dialogs) { dialogs.remove(pending); dialogs.lastOrNull() }
            promptFlow.value = outer?.prompt
            if (outer == null) edt.later { } // let the Input underneath re-publish
        }
    }

    private fun optionFor(item: Any?, label: String): ChoiceOption = ChoiceOption(
        label,
        when (item) {
            is CardView -> BoardRef.Card(item.id).also { cardViews[item.id] = item }
            is PlayerView -> BoardRef.Player(item.id).also { playerViews[item.id] = item }
            is StackItemView -> BoardRef.StackItem(item.id)
            else -> null
        },
    )

    private fun <T> choose(message: String, min: Int, max: Int, items: List<T>, label: (T) -> String): List<T> {
        if (items.isEmpty()) return emptyList()
        val options = items.map { optionFor(it, label(it)) }
        if (min < 0) { // a reveal: show it, nothing to pick
            awaitDialog({ ChoicePrompt(it, message, options, -1, -1) }, SeatAction.Choose(emptyList()))
            return emptyList()
        }
        if (min >= items.size && max >= items.size) return items // all of them, no decision
        val action = awaitDialog({ ChoicePrompt(it, message, options, min, max) }, SeatAction.Choose((0 until min).toList()))
        val picked = (action as? SeatAction.Choose)?.indices?.distinct()?.filter { it in items.indices }
        if (picked == null || picked.size < min || picked.size > max) {
            autoAnswered("choice", "invalid answer $action for '$message' (min=$min max=$max); took the first $min")
            return items.take(min)
        }
        return picked.map { items[it] }
    }

    /** All of [items] in an order; the answer lists indices first-first, the rest keep their place after them. */
    private fun <T> ordered(message: String, firstLabel: String, items: List<T>, label: (T) -> String): List<T> {
        if (items.size < 2) return items
        val action = awaitDialog({ OrderPrompt(it, message, items.map { item -> optionFor(item, label(item)) }, firstLabel) }, SeatAction.Order(items.indices.toList()))
        val chosen = (action as? SeatAction.Order)?.indices?.distinct()?.filter { it in items.indices }.orEmpty()
        return (chosen + items.indices.filter { it !in chosen }).map { items[it] }
    }

    private fun unhandled(method: String, detail: String) = autoAnswered("IGuiGame.$method", detail)

    /**
     * A decision made for the seat without asking it. Never silent: the game
     * log gets `UNHANDLED`, the app log a warning, and the board a warning
     * line ([warning]) until the next one.
     */
    fun autoAnswered(where: String, detail: String) {
        recorder.seat("UNHANDLED $where: $detail")
        Log.warn("UNHANDLED $where: $detail")
        warningFlow.value = "auto-answered $where: $detail (see the game log)"
    }

    /** A cast Forge stopped without a word ([FailedCasts]): said in the game log, the trail and the warning line. */
    private fun castFailed(card: CardView, from: ZoneType, forbiddenBy: String?) {
        val name = card.currentState?.name ?: "a card"
        val where = from.name.lowercase()
        val why = forbiddenBy?.let { ": $it forbids it" }.orEmpty()
        recorder.note("WARNING $name couldn't be cast from the $where$why; it went back and nothing happened")
        trail.note(card.controller?.id ?: -1, "couldn't cast $name" + (forbiddenBy?.let { " ($it)" }.orEmpty()), card)
        warningFlow.value = "$name couldn't be cast from your $where$why. Nothing happened."
    }

    /** Whether a person answers this seat's prompts (false when watching AI vs AI). */
    val isHumanSeat: Boolean get() = seatController != null

    // --- lifecycle -----------------------------------------------------------

    /** Once per game of a match: the seat starts every game fresh. */
    override fun openView(myPlayers: TrackableCollection<PlayerView>?) {
        seatPlayerIds = myPlayers?.map { it.id }?.toSet().orEmpty()
        finished = false
        conceded = false
        gameView?.game?.let { recorder.attach(it); trail.attach(it); floatingMana.attach(it); failedCasts.attach(it); drawLog.attach(it) }
        dirty = true
    }

    override fun setOriginalGameController(player: PlayerView, gameController: IGameController) {
        super.setOriginalGameController(player, gameController)
        if (gameController is PlayerControllerHuman && gameController !is WatchLocalGame) {
            seatController = gameController
            floatingMana.controller = gameController
        }
    }

    override fun updateCurrentPlayer(player: PlayerView?) {}

    override fun finishGame() {
        if (finished) return
        finished = true
        dirty = true
        recorder.note("finishGame: ${gameView?.let { if (it.outcome?.isDraw == true) "draw" else "${it.winningPlayerName} won" }}")
        synchronized(dialogs) { dialogs.forEach { it.reply.complete(SeatAction.Cancel) } }
        edt.later {
            snapshot()
            promptFlow.value = null
            yieldFlow.value = null
            onFinished?.invoke()
        }
    }

    override fun afterGameEnd() {
        super.afterGameEnd()
        finished = true
        dirty = true
    }

    /** When the match is over or left: this seat takes no more games. */
    fun dispose() = refreshHook.close()

    // --- Input feedback (EDT) ------------------------------------------------

    /**
     * Forge announces every new yield here (AbstractGuiGame.updateAutoPassPrompt, which is final),
     * including its own End Turn button's, before the pass that goes with it releases the engine:
     * the one place a yield Forge sets itself can be held for floating mana in time.
     */
    override fun showPromptMessage(playerView: PlayerView?, message: String?) {
        floatingMana.update()
        super.showPromptMessage(playerView, message)
    }

    override fun showPromptMessage(playerView: PlayerView?, message: String?, card: CardView?) {
        shownMessage = message.orEmpty()
        shownInput = seatController?.inputProxy?.input
    }

    override fun updateButtons(owner: PlayerView?, label1: String?, label2: String?, enable1: Boolean, enable2: Boolean, focus1: Boolean) {
        okLabel = label1.orEmpty()
        cancelLabel = label2.orEmpty()
        okEnabled = enable1
        cancelEnabled = enable2
    }

    override fun setSelectables(cards: Iterable<CardView>, min: Int, max: Int) {
        super.setSelectables(cards, min, max)
        cards.forEach { cardViews[it.id] = it }
        selectableIds = cards.map { it.id }.toSet()
    }

    override fun clearSelectables() {
        super.clearSelectables()
        selectableIds = emptySet()
    }

    override fun setWeaklySelectable(cards: Iterable<CardView>) {
        super.setWeaklySelectable(cards)
        cards.forEach { cardViews[it.id] = it }
        actionableIds = cards.map { it.id }.toSet()
    }

    override fun clearWeaklySelectable() {
        super.clearWeaklySelectable()
        actionableIds = emptySet()
    }

    /**
     * The phase stops, as MTGO has them. Forge asks this only when the stack
     * is empty. Floating mana is a stop wherever the stops are ([FloatingMana]).
     */
    override fun isUiSetToSkipPhase(playerTurn: PlayerView?, phase: PhaseType?): Boolean {
        val step = Step.entries.firstOrNull { it.name == phase?.name } ?: return false
        if (floatingMana.floating()) {
            recorder.seat("FLOATING MANA: priority in $step, whatever the stops say")
            return false
        }
        val seatsTurn = playerTurn != null && playerTurn.id in seatPlayerIds
        return !stopsFlow.value.stopsAt(seatsTurn, step)
    }

    override fun updateTurn(player: PlayerView?) {
        dirty = true
        val skipping = skippingTurn ?: return
        if ((gameView?.turn ?: skipping) != skipping) seatController?.let { endSkip(it) }
    }

    override fun flashIncorrectAction() = recorder.seat("  (Forge flagged the last action as incorrect)")
    override fun alertUser() {}
    override fun showCombat() { dirty = true }
    override fun updatePhase(saveState: Boolean) { dirty = true }
    override fun updatePlayerControl() { dirty = true }
    override fun updateStack() { dirty = true }
    override fun updateZones(zonesToUpdate: Iterable<PlayerZoneUpdate>?) { dirty = true }
    override fun updateCards(cards: Iterable<CardView>?) { dirty = true }
    override fun updateManaPool(manaPoolUpdate: Iterable<PlayerView>?) { dirty = true; floatingMana.update() }
    override fun updateLives(livesUpdate: Iterable<PlayerView>?) { dirty = true }
    override fun updateShards(shardsUpdate: Iterable<PlayerView>?) { dirty = true }
    override fun refreshField() { dirty = true }
    override fun enableOverlay() {}
    override fun disableOverlay() {}
    override fun showManaPool(player: PlayerView?) {}
    override fun hideManaPool(player: PlayerView?) {}
    override fun setCard(card: CardView?) {}
    override fun setPanelSelection(hostCard: CardView?) {}
    override fun setPlayerAvatar(player: LobbyPlayer?, ihi: IHasIcon?) {}
    override fun getGamestate(): GameState? = null

    // --- direct dialogs ------------------------------------------------------

    override fun message(message: String?, title: String?) = recorder.note("MESSAGE ${title.orEmpty()}: ${message.orEmpty()}")

    override fun showErrorDialog(message: String?, title: String?) {
        recorder.note("ERROR DIALOG ${title.orEmpty()}: ${message.orEmpty()}")
        Log.warn("Forge error dialog: $title: $message")
    }

    override fun showConfirmDialog(message: String, title: String?, yesButtonText: String, noButtonText: String, defaultYes: Boolean): Boolean {
        val action = awaitDialog({ ConfirmPrompt(it, listOfNotNull(title, message).joinToString(": "), yesButtonText, noButtonText) },
            SeatAction.Confirm(defaultYes))
        return (action as? SeatAction.Confirm)?.yes ?: defaultYes
    }

    override fun confirm(c: CardView?, question: String, defaultIsYes: Boolean, options: MutableList<String>?): Boolean {
        val yes = options?.getOrNull(0) ?: "Yes"
        val no = options?.getOrNull(1) ?: "No"
        val subject = c?.let { "${it.currentState.name}: " }.orEmpty()
        val action = awaitDialog({ ConfirmPrompt(it, subject + question, yes, no) }, SeatAction.Confirm(defaultIsYes))
        return (action as? SeatAction.Confirm)?.yes ?: defaultIsYes
    }

    override fun showOptionDialog(message: String, title: String?, icon: FSkinProp?, options: MutableList<String>, defaultOption: Int): Int =
        choose(listOfNotNull(title, message).joinToString(": "), 1, 1, options.indices.toList()) { options[it] }
            .firstOrNull() ?: defaultOption

    override fun showInputDialog(message: String?, title: String?, icon: FSkinProp?, initialInput: String?, inputOptions: MutableList<String>?, isNumeric: Boolean): String? {
        if (!inputOptions.isNullOrEmpty()) return choose("${title.orEmpty()}: ${message.orEmpty()}", 1, 1, inputOptions) { it }.firstOrNull()
        if (isNumeric) return number("${title.orEmpty()}: ${message.orEmpty()}", 0, Int.MAX_VALUE, cancellable = true)?.toString()
        unhandled("showInputDialog", "free text '$message' -> '$initialInput'")
        return initialInput
    }

    override fun <T : Any?> getChoices(message: String, min: Int, max: Int, choices: MutableList<T>, selected: MutableList<T>?, display: FSerializableFunction<T, String>?): MutableList<T> =
        choose(message, min, max, choices) { display?.apply(it) ?: it.toString() }.toMutableList()

    /** Here -1 means "no limit" (scry's "put any number on the bottom"), not a reveal. */
    override fun <T : Any?> many(title: String, topCaption: String?, min: Int, max: Int, sourceChoices: MutableList<T>, destChoices: MutableList<T>?, c: CardView?): MutableList<T> {
        val lo = if (min < 0) 0 else min
        val hi = if (max < 0) sourceChoices.size else max
        return choose(listOfNotNull(title, topCaption).joinToString(" — "), lo, hi, sourceChoices) { it.toString() }.toMutableList()
    }

    /**
     * Forge's `order` is two things. With nothing to leave behind (remaining
     * 0..0) it orders the whole list: triggers, cards going to the top of a
     * library. Otherwise it picks a subset (remaining = what stays behind; -1
     * is no limit), in the order picked.
     */
    override fun <T : Any?> order(title: String?, top: String?, remainingObjectsMin: Int, remainingObjectsMax: Int, sourceChoices: MutableList<T>, destChoices: MutableList<T>?, referenceCard: CardView?, sideboardingMode: Boolean, showRememberCheckbox: Boolean): IGuiGame.OrderResult<T> {
        val message = listOfNotNull(title, top).joinToString(" — ")
        if (remainingObjectsMin == 0 && remainingObjectsMax == 0) {
            return IGuiGame.OrderResult(ordered(message, top ?: "first", sourceChoices) { it.toString() }.toMutableList(), false)
        }
        val size = sourceChoices.size
        val minPick = if (remainingObjectsMax < 0) 0 else (size - remainingObjectsMax).coerceIn(0, size)
        val maxPick = if (remainingObjectsMin < 0) size else (size - remainingObjectsMin).coerceIn(minPick, size)
        return IGuiGame.OrderResult(choose(message, minPick, maxPick, sourceChoices) { it.toString() }.toMutableList(), false)
    }

    override fun manipulateCardList(title: String?, cards: Iterable<CardView>, manipulable: Iterable<CardView>?, toTop: Boolean, toBottom: Boolean, toAnywhere: Boolean): MutableList<CardView> =
        ordered(title ?: "Arrange the cards", "top", cards.toList()) { it.currentState.name }.toMutableList()

    /**
     * Which ability to play. Forge asks this for a card clicked at priority,
     * and also for every one of our **triggered abilities** as it goes on the
     * stack (PlaySpellAbility.playSpellAbility → chooseOptionalAdditionalCosts
     * → PlayerControllerHuman.getAbilityToPlay) — and null there drops the
     * trigger without a word. A trigger's view is never `canPlay()`, so we
     * filter on it only to trim a menu, never to empty one: one ability is
     * simply that ability, as in Forge's own CMatchUI.
     */
    override fun getAbilityToPlay(hostCard: CardView?, abilities: MutableList<SpellAbilityView>, triggerEvent: ITriggerEvent?): SpellAbilityView? {
        if (abilities.isEmpty()) return null
        val playable = abilities.filter { it.canPlay() }
        if (abilities.size == 1 && !(abilities[0].promptIfOnlyPossibleAbility() && playable.isNotEmpty())) return abilities[0]
        val menu = playable.ifEmpty { abilities }
        if (menu.size == 1 && !menu[0].promptIfOnlyPossibleAbility()) return menu[0]
        val name = hostCard?.currentState?.name ?: "card"
        return choose("$name: choose an ability", 0, 1, menu) { it.description }.firstOrNull()
    }

    override fun chooseSingleEntityForEffect(title: String, optionList: List<GameEntityView>, delayedReveal: DelayedReveal?, isOptional: Boolean): GameEntityView? {
        delayedReveal?.let { recorder.seat("  (${it.cards.size} cards revealed for this choice)") }
        return choose(title, if (isOptional) 0 else 1, 1, optionList) { it.toString() }.firstOrNull()
    }

    override fun chooseEntitiesForEffect(title: String, optionList: List<GameEntityView>, min: Int, max: Int, delayedReveal: DelayedReveal?): MutableList<GameEntityView> {
        delayedReveal?.let { recorder.seat("  (${it.cards.size} cards revealed for this choice)") }
        return choose(title, min, max, optionList) { it.toString() }.toMutableList()
    }

    override fun getInteger(message: String, min: Int, max: Int, sortDesc: Boolean): Int? =
        if (max <= min) min else number(message, min, max, cancellable = true)

    override fun getInteger(message: String, min: Int, max: Int, cutoff: Int): Int? =
        if (max <= min || cutoff < min) min else number(message, min, max, cancellable = true)

    private fun number(message: String, min: Int, max: Int, cancellable: Boolean): Int? {
        val affordable = runCatching { affordableX(message) }.getOrNull()?.coerceIn(min, max)
        val action = awaitDialog({ NumberPrompt(it, message, min, max, cancellable, suggested = affordable, note = affordable?.let { a -> "max affordable $a" }) },
            SeatAction.Number(if (cancellable) null else min))
        val value = (action as? SeatAction.Number)?.value ?: return if (cancellable) null else min
        return value.coerceIn(min, max)
    }

    /**
     * "Choose X for Wrath of the Skies": the largest X this seat can pay right
     * now. Only a hint — a larger X can be typed, and Forge then refuses the
     * payment.
     *
     * A spell is on the stack by the time X is asked, carrying the ability
     * actually cast, so its cost is the one being paid: a miracle's {X}{W}{W},
     * not Entreat the Angels' printed {X}{X}{W}{W}{W}. Forge's own payment
     * check (the one its AI plays by) then tries each X: it knows which
     * sources really make mana and in which colours, where counting lands
     * took a fetchland for a mana.
     */
    private fun affordableX(message: String): Int? {
        val name = Regex("Choose X for (.+)", RegexOption.IGNORE_CASE).find(message)?.groupValues?.get(1)?.trim()?.removeSuffix(".") ?: return null
        val player = gameView?.game?.players?.firstOrNull { it.id in seatPlayerIds } ?: return null
        val cast = player.getCardsIn(ZoneType.Stack).firstOrNull { it.name == name }?.castSA
        if (cast != null && cast.payCosts.totalMana.countX() > 0) {
            fun payable(x: Int) = forge.ai.ComputerUtilMana.canPayManaCost(cast, player, x, false)
            if (!payable(0)) return 0
            var x = 0
            while (x < MAX_SUGGESTED_X && payable(x + 1)) x++
            return x
        }
        // An activated ability (Walking Ballista): nothing on the stack yet, so the printed cost and Forge's estimate of its sources.
        val card = player.getCardsIn(ZoneType.Battlefield).firstOrNull { it.name == name } ?: player.getCardsIn(ZoneType.Hand).firstOrNull { it.name == name } ?: return null
        val xs = card.manaCost.countX().takeIf { it > 0 } ?: return null
        val pool = MANA_BYTES.values.sumOf { player.manaPool.getAmountOfColor(it) }
        val available = forge.ai.ComputerUtilMana.getAvailableManaEstimate(player, false) + pool
        return maxOf(0, (available - card.manaCost.cmc) / xs)
    }

    /**
     * Combat damage among blockers (and the defender, with trample), or among
     * the attackers one blocker blocks. The prompt comes pre-filled with lethal
     * damage in order, the rest to the defender or the last one.
     */
    override fun assignCombatDamage(attacker: CardView?, blockers: MutableList<CardView>, damage: Int, defender: GameEntityView?, overrideOrder: Boolean, maySkip: Boolean): MutableMap<CardView?, Int>? {
        val lethal = blockers.map { maxOf(0, it.currentState.toughness - it.damage) }
        val suggested = MutableList(blockers.size + if (defender != null) 1 else 0) { 0 }
        var left = damage
        lethal.forEachIndexed { i, need -> val dealt = minOf(left, need); suggested[i] = dealt; left -= dealt }
        if (left > 0) suggested[if (defender != null) blockers.size else blockers.lastIndex] += left
        val targets = blockers.mapIndexed { i, b -> DistributeTarget(b.currentState.name, BoardRef.Card(b.id).also { cardViews[b.id] = b }, lethal[i]) } +
            listOfNotNull(defender?.let { DistributeTarget(it.toString(), (it as? PlayerView)?.let { p -> BoardRef.Player(p.id) }, null) })
        val source = attacker?.currentState?.name ?: "combat"
        val amounts = distribute("$source: assign $damage damage", targets, damage, atLeastOne = false, suggested, allowSkip = maySkip) ?: return null
        val result = LinkedHashMap<CardView?, Int>()
        blockers.forEachIndexed { i, b -> if (amounts[i] > 0) result[b] = amounts[i] }
        if (defender != null && amounts.last() > 0) result[null] = amounts.last()
        return result
    }

    override fun assignGenericAmount(effectSource: CardView?, target: MutableMap<Any, Int>, amount: Int, atLeastOne: Boolean, amountLabel: String?): MutableMap<Any, Int> {
        val keys = target.keys.toList()
        val suggested = MutableList(keys.size) { if (atLeastOne) 1 else 0 }
        if (keys.isNotEmpty()) suggested[0] += amount - suggested.sum()
        val targets = keys.map { key -> optionFor(key, key.toString()).let { DistributeTarget(it.label, it.ref, null) } }
        val source = effectSource?.currentState?.name ?: "effect"
        val amounts = distribute("$source: divide $amount ${amountLabel.orEmpty()}".trim(), targets, amount, atLeastOne, suggested, allowSkip = false)
            ?: suggested
        return LinkedHashMap<Any, Int>().apply { keys.forEachIndexed { i, key -> if (amounts[i] > 0) put(key, amounts[i]) } }
    }

    /** Amounts that sum to [total] (each at least one when [atLeastOne]), or null when skipped. */
    private fun distribute(message: String, targets: List<DistributeTarget>, total: Int, atLeastOne: Boolean, suggested: List<Int>, allowSkip: Boolean): List<Int>? {
        val action = awaitDialog({ DistributePrompt(it, message, targets, total, atLeastOne, suggested) }, SeatAction.Distribute(suggested))
        if (action == SeatAction.Cancel && allowSkip) return null
        val amounts = (action as? SeatAction.Distribute)?.amounts
        val valid = amounts != null && amounts.size == targets.size && amounts.sum() == total &&
            amounts.all { it >= (if (atLeastOne) 1 else 0) }
        if (!valid) {
            autoAnswered("split", "invalid answer $action for '$message'; used Forge's $suggested")
            return suggested
        }
        return amounts
    }

    /**
     * Between games of a match. Forge validates the answer itself and asks
     * again if the deck is short (PlayerControllerHuman.sideboard).
     */
    override fun sideboard(sideboard: CardPool?, main: CardPool?, message: String?): MutableList<PaperCard>? {
        val mainCards = main?.toFlatList().orEmpty()
        val sideCards = sideboard?.toFlatList().orEmpty()
        if (sideCards.isEmpty()) return null // nothing to swap: Forge keeps the deck
        fun entries(cards: List<PaperCard>) = cards.groupingBy { it.name }.eachCount().map { (name, n) -> DeckEntry(name, n) }.sortedBy { it.name }
        val action = awaitDialog({ SideboardPrompt(it, message ?: "Sideboard for the next game", entries(mainCards), entries(sideCards), minOf(60, mainCards.size)) },
            SeatAction.Sideboard(mainCards.groupingBy { it.name }.eachCount()), betweenGames = true)
        val wanted = (action as? SeatAction.Sideboard)?.main ?: return null
        // The same card objects Forge gave us, re-dealt: every copy of a name is interchangeable.
        val pool = (mainCards + sideCards).groupBy { it.name }.mapValues { it.value.toMutableList() }
        val chosen = wanted.flatMap { (name, n) -> List(n) { pool[name]?.removeFirstOrNull() }.filterNotNull() }
        recorder.seat("SIDEBOARD main ${chosen.size}, sideboard ${mainCards.size + sideCards.size - chosen.size}")
        return chosen.toMutableList()
    }

    private companion object {
        /** What F4 stops for and F6 ignores. */
        val HIDDEN_ZONES = setOf(ZoneType.Hand, ZoneType.Library)
        /** Where the affordable-X search stops: each step is one of Forge's payment checks. */
        const val MAX_SUGGESTED_X = 99
        /** The pool's letters (as Snapshots writes them) to Forge's mana atoms, which the pool click takes (colourless has its own bit). */
        val MANA_BYTES = mapOf(
            'W' to forge.card.mana.ManaAtom.WHITE, 'U' to forge.card.mana.ManaAtom.BLUE, 'B' to forge.card.mana.ManaAtom.BLACK,
            'R' to forge.card.mana.ManaAtom.RED, 'G' to forge.card.mana.ManaAtom.GREEN, 'C' to forge.card.mana.ManaAtom.COLORLESS,
        ).mapValues { it.value.toByte() }
        val INTERRUPTS = listOf(FPref.YIELD_INTERRUPT_ON_OPPONENT_SPELL, FPref.YIELD_INTERRUPT_ON_ATTACKERS, FPref.YIELD_INTERRUPT_ON_TARGETING)
    }
}
