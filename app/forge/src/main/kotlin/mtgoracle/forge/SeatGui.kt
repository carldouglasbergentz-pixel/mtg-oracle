package mtgoracle.forge

import forge.LobbyPlayer
import forge.deck.CardPool
import forge.game.GameEntityView
import forge.game.GameState
import forge.game.card.CardView
import forge.game.keyword.Keyword
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
import mtgoracle.core.model.LogKind
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

    /** A face-down card's real face: Forge's rule, as its own board's mayFlip uses it. */
    private fun peek(card: CardView): Boolean =
        if (seatController != null) card.canFaceDownBeShownToAny(localPlayers) else showHandsFlow.value

    /**
     * What this seat may see of [card]. A seat: Forge's own rule for its
     * player (AbstractGuiGame.mayView -> CardView.canBeShownToAny), which
     * follows reveals, "look at" effects and face-down ownership. A spectator:
     * public zones only, unless both hands are switched on. Either way, a
     * card known to be in a hand ([KnownInHand]): it went there in plain sight.
     */
    private fun visible(card: CardView): Boolean = knownInHand.knows(card) ||
        if (seatController != null) mayView(card)
        else showHandsFlow.value || (card.zone !in HIDDEN_ZONES && !card.isFaceDown)

    private val cardViews = ConcurrentHashMap<Int, CardView>()
    private val playerViews = ConcurrentHashMap<Int, PlayerView>()
    private val snapshots = Snapshots(cardViews, playerViews)

    /** Per player name, the cards whose chosen printing Forge lacks and the art key to draw them with instead. */
    internal fun setArtOverrides(overrides: Map<String, Map<String, String>>) { snapshots.artOverrides = overrides }
    /** Named under the board's own rule: seen, and if face-down, peekable. */
    private val seesCard: (CardView) -> Boolean = { cv -> visible(cv) && (!cv.isFaceDown || peek(cv)) }
    private val trail = Trail(named = seesCard, onChange = { dirty = true })
    private val floatingMana = FloatingMana(recorder)
    private val drawLog = DrawLog(recorder)
    private val zoneLog = ZoneLog(recorder)
    private val knownInHand = KnownInHand(isViewer = { it in seatPlayerIds })
    private val countered = Countered(named = seesCard, onCountered = ::reportCountered)
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
    @Volatile private var highlightedIds: Set<Int> = emptySet()
    /** X is being priced ([affordableX]): a dialog now would be Forge asking the human about a payment no one is making. */
    @Volatile private var pricing = false

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
            // Only to the Input the prompt was about: a second answer queued behind the first would land on whatever Forge holds by then.
            is InputPrompt -> edt.later {
                val top = seatController?.inputQueue?.input
                if (top != null && System.identityHashCode(top) != current.inputSerial) recorder.seat("  (the input moved on before #$promptId's $action: ignored)")
                else applyGesture(action)
            }
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
                // Forge's Inputs trust the button state: OK on a discard to hand size with nothing picked discards nothing.
                SeatCommand.PASS -> if (controller.inputQueue.input != null && okEnabled) controller.selectButtonOk()
                SeatCommand.CANCEL_YIELDS -> {
                    floatingMana.forget()
                    yields.clearActiveYieldAndDispatch()
                    yields.clearAutoYields()
                    // clearAutoYields empties only this game's tier; Forge's default keeps yields for the match, and F3 means all of them.
                    yields.autoYields.toList().forEach { yields.setShouldAutoYield(it, false, yields.isAbilityScope) }
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
            // Only a button that is on: Forge's Inputs don't check (an OK with too few picks, an Auto with no plan to pay).
            SeatAction.Ok -> if (okEnabled) controller.selectButtonOk() else recorder.seat("  (OK is off: ignored)")
            SeatAction.Cancel -> if (cancelEnabled) controller.selectButtonCancel() else recorder.seat("  (Cancel is off: ignored)")
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
            val board = snapshots.build(view, seatPlayerIds, ::visible, ::peek, recorder.log(), finished, trail.snapshot(), decisionSeq)
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
            selectableCardIds = selectableIds, actionableCardIds = actionableIds, highlightedCardIds = highlightedIds,
            // InputPassPriority names the cancel button "Undo (n)" exactly when the last action can be undone.
            cancelUndoes = cancelLabel.startsWith(forge.util.Localizer.getInstance().getMessage("lblUndo")),
        )
        if (current is InputPrompt && current.copy(id = 0, selectableElsewhere = emptyList()) == candidate) return
        snapshot() // the engine is parked on this Input: the board is consistent now
        val elsewhere = selectableElsewhere(playable = candidate.kind == InputKind.PRIORITY)
        // Under the dialogs' lock, checked again: a dialog opened while the board was read has the prompt, and an
        // Input prompt over it left the dialog unseen and its thread waiting for good.
        synchronized(dialogs) {
            if (dialogs.isNotEmpty()) return
            publish(candidate.copy(id = promptIds.incrementAndGet(), selectableElsewhere = elsewhere))
        }
    }

    /**
     * Cards the board doesn't draw that a click can take: selectable ones (a library being searched), and with
     * [playable] the ones Forge says can be played from there (Future Sight's top card). The zones it does draw are clickable in place.
     */
    private fun selectableElsewhere(playable: Boolean): List<CardState> {
        val shown = boardFlow.value?.players.orEmpty().flatMap { it.cards }.map { it.id }.toSet()
        // The board's rule here too: a card the seat may not see is a back (its own id, to be clicked; the id names nothing),
        // and a face-down card is named only when the seat may look (Snapshots.sees).
        return (selectableIds + if (playable) actionableIds else emptySet()).filter { it !in shown }.mapNotNull { id ->
            cardViews[id]?.let { cv ->
                if (!visible(cv) || (cv.isFaceDown && !peek(cv))) CardState.back(cv.id, cv.isFaceDown) else snapshots.card(cv)
            }
        }
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
        if (pricing) throw PricingAsked(make(0).message)
        if (finished && !betweenGames) return fallback
        val pending = PendingDialog(make(promptIds.incrementAndGet()))
        if (!edt.isCurrent()) snapshot() // the calling engine thread is the one waiting
        synchronized(dialogs) { dialogs.addLast(pending); publish(pending.prompt) }
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
            rememberRevealed(items.filterIsInstance<CardView>())
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

    /**
     * Cards shown to this seat in another player's hand stay known there
     * ([KnownInHand]) and get a trail line, so a tutor's find outlives the
     * dialog that showed it: Cloud's Lion Sash was gone with one click.
     */
    private fun rememberRevealed(cards: Collection<CardView>) {
        cards.filter { it.zone == ZoneType.Hand && it.controller?.id?.let { owner -> owner !in seatPlayerIds } == true }
            .groupBy { it.controller!!.id }
            .forEach { (owner, inHand) ->
                inHand.forEach { knownInHand.revealed(it.id, owner) }
                val names = inHand.mapNotNull { it.currentState?.name }.joinToString(", ")
                trail.note(owner, "shown in hand: $names", inHand.singleOrNull())
            }
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

    /**
     * A spell or ability that left the stack without resolving ([Countered]):
     * a line in the trail and the log pane, and for one of the seat's own the
     * warning line too, so it is not lost among triggers.
     */
    private fun reportCountered(report: Countered.Report) {
        trail.note(report.actorId, report.text, report.card)
        recorder.play(report.kind, LogKind.COUNTERED, "${report.text}.", names = listOfNotNull(report.by, report.what))
        if (report.controllerId in seatPlayerIds) warningFlow.value = "${report.text}."
    }

    /** Whether a person answers this seat's prompts (false when watching AI vs AI). */
    val isHumanSeat: Boolean get() = seatController != null

    // --- lifecycle -----------------------------------------------------------

    /** Once per game of a match: the seat starts every game fresh. */
    override fun openView(myPlayers: TrackableCollection<PlayerView>?) {
        seatPlayerIds = myPlayers?.map { it.id }?.toSet().orEmpty()
        finished = false
        conceded = false
        // Card ids start again in every game: game 1's picks and views must not mark game 2's cards (Forge's own openView clears its selection).
        selectableIds = emptySet(); actionableIds = emptySet(); highlightedIds = emptySet()
        cardViews.clear(); playerViews.clear()
        skippingTurn = null
        gameView?.game?.let { recorder.attach(it); trail.attach(it); floatingMana.attach(it); failedCasts.attach(it); drawLog.attach(it); zoneLog.attach(it); knownInHand.attach(it); countered.attach(it) }
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

    /** Forge's mark on what is picked so far (InputSelectManyBase) — called from the game thread too. */
    override fun setHighlighted(entities: Iterable<GameEntityView>, b: Boolean) {
        super.setHighlighted(entities, b)
        val ids = entities.filterIsInstance<CardView>().map { it.id }
        synchronized(this) { highlightedIds = if (b) highlightedIds + ids else highlightedIds - ids.toSet() }
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

    /**
     * Forge's word to this player: what the AI chose for a card of its own
     * (Pithing Needle's name, a colour, a vote), "attack declaration
     * invalid", "no cards in hand". It went only to the log file, and the
     * board had no other way to learn it; now the warning line and the log pane say it.
     */
    override fun message(message: String?, title: String?) {
        recorder.note("MESSAGE ${title.orEmpty()}: ${message.orEmpty()}")
        tell(title, message)
    }

    /** Forge refusing something (a sideboarded deck, with why): said as a message is. */
    override fun showErrorDialog(message: String?, title: String?) {
        recorder.note("ERROR DIALOG ${title.orEmpty()}: ${message.orEmpty()}")
        Log.warn("Forge error dialog: $title: $message")
        tell(title, message)
    }

    private fun tell(title: String?, message: String?) {
        val text = listOfNotNull(title?.takeIf { it.isNotBlank() }, message?.takeIf { it.isNotBlank() }).joinToString(": ").replace(Regex("""\s*\n\s*"""), " ").trim()
        if (text.isEmpty() || !isHumanSeat) return
        warningFlow.value = text
        recorder.play(null, LogKind.OTHER, text)
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
        if (isNumeric) return number("${title.orEmpty()}: ${message.orEmpty()}", 0, Int.MAX_VALUE)?.toString()
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
        // The answer is what ends in the destination, and Forge may have put some there already: the second time the
        // same simultaneous triggers come, every one sits in [destChoices], in the order saved last time, and the
        // source is empty. Ordering only the source answered nothing, and Forge played none of them: prowess, Jori En,
        // Dreadhorde Arcanist and Bilbo each missed every other time (PlayerControllerHuman.orderSimultaneousSa).
        val placed = destChoices.orEmpty()
        if (remainingObjectsMin == 0 && remainingObjectsMax == 0) {
            return IGuiGame.OrderResult(ordered(message, top ?: "first", placed + sourceChoices) { it.toString() }.toMutableList(), false)
        }
        val size = sourceChoices.size
        val minPick = if (remainingObjectsMax < 0) 0 else (size - remainingObjectsMax).coerceIn(0, size)
        val maxPick = if (remainingObjectsMin < 0) size else (size - remainingObjectsMin).coerceIn(minPick, size)
        return IGuiGame.OrderResult((placed + choose(message, minPick, maxPick, sourceChoices) { it.toString() }).toMutableList(), false)
    }

    /**
     * Unreached while Forge's UI_SELECT_FROM_CARD_DISPLAYS is off (ForgeRuntime): only [manipulable] is ordered,
     * the rest keep their places after it, as PlayerControllerHuman.arrangeForMove reads the result.
     */
    override fun manipulateCardList(title: String?, cards: Iterable<CardView>, manipulable: Iterable<CardView>?, toTop: Boolean, toBottom: Boolean, toAnywhere: Boolean): MutableList<CardView> {
        val movable = manipulable?.toList() ?: cards.toList()
        val ordered = ordered(title ?: "Arrange the cards", "top", movable) { it.currentState.name }
        return (ordered + cards.filter { it !in movable }).toMutableList()
    }

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

    override fun chooseSingleEntityForEffect(title: String, optionList: List<GameEntityView>, delayedReveal: DelayedReveal?, isOptional: Boolean): GameEntityView? =
        choose(withReveal(title, delayedReveal, optionList), if (isOptional) 0 else 1, 1, optionList) { it.toString() }.firstOrNull()

    override fun chooseEntitiesForEffect(title: String, optionList: List<GameEntityView>, min: Int, max: Int, delayedReveal: DelayedReveal?): MutableList<GameEntityView> =
        choose(withReveal(title, delayedReveal, optionList), min, max, optionList) { it.toString() }.toMutableList()

    /**
     * "Look at the top five, you may take a creature": the cards looked at that
     * can't be taken, said in the choice's own question (named up to ten, else
     * counted). A prompt of their own before the choice made a library search
     * (Ash Barrens) a list of the whole library and a click before the lands
     * on offer. Forge passes a reveal only to the player who looks.
     */
    private fun withReveal(title: String, reveal: DelayedReveal?, options: List<GameEntityView>): String {
        rememberRevealed(reveal?.cards.orEmpty())
        val offered = options.mapNotNull { (it as? CardView)?.id }.toSet()
        val others = reveal?.cards?.filter { it.id !in offered }.orEmpty()
        if (others.isEmpty()) return title
        recorder.seat("  (${others.size} more card(s) looked at for this choice)")
        val seen = if (others.size <= 10) others.joinToString(", ") { it.name } else "${others.size} other cards"
        return "$title · also looked at: $seen"
    }

    override fun getInteger(message: String, min: Int, max: Int, sortDesc: Boolean): Int? =
        if (max <= min) min else number(message, min, max)

    override fun getInteger(message: String, min: Int, max: Int, cutoff: Int): Int? =
        if (max <= min || cutoff < min) min else number(message, min, max)

    /** A number in [min]..[max], or null when cancelled (every number Forge asks of a person can be). */
    private fun number(message: String, min: Int, max: Int): Int? {
        // Only a hint: a failure leaves the prompt without one, but said in the log.
        val affordable = runCatching { affordableX(message) }.onFailure { Log.warn("affordable X for '$message' not worked out: $it") }.getOrNull()?.coerceIn(min, max)
        val action = awaitDialog({ NumberPrompt(it, message, min, max, cancellable = true, suggested = affordable, note = affordable?.let { a -> "max affordable $a" }) },
            SeatAction.Number(null))
        val value = (action as? SeatAction.Number)?.value ?: return null
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
            // Forge's check asks the controller to delve or convoke, test or not: for the human that was a real prompt per X tried (Logic Knot).
            val helpers = ASKS_WHILE_PRICED.filter { cast.hostCard.hasKeyword(it) }
            if (helpers.isNotEmpty()) return estimateX(player, cast.payCosts.totalMana, helpers)
            fun payable(x: Int) = forge.ai.ComputerUtilMana.canPayManaCost(cast, player, x, false)
            pricing = true
            try {
                if (!payable(0)) return 0
                var x = 0
                while (x < MAX_SUGGESTED_X && payable(x + 1)) x++
                return x
            } catch (e: PricingAsked) {
                Log.debug("affordable X for $name not suggested: pricing it asked '${e.message}'")
                return null
            } finally {
                pricing = false
            }
        }
        // An activated ability (Walking Ballista): nothing on the stack yet, so the printed cost and Forge's estimate of its sources.
        val card = player.getCardsIn(ZoneType.Battlefield).firstOrNull { it.name == name } ?: player.getCardsIn(ZoneType.Hand).firstOrNull { it.name == name } ?: return null
        val xs = card.manaCost.countX().takeIf { it > 0 } ?: return null
        val pool = MANA_BYTES.values.sumOf { player.manaPool.getAmountOfColor(it) }
        val available = forge.ai.ComputerUtilMana.getAvailableManaEstimate(player, true) + pool
        return maxOf(0, (available - card.manaCost.cmc) / xs)
    }

    /**
     * The largest X [cost] could take with the mana Forge estimates and what
     * [helpers] pay for generic mana: a graveyard card each for delve, an
     * untapped creature for convoke, an untapped artifact for improvise. A
     * hint, colour-blind; assist (another player's mana) adds nothing.
     */
    private fun estimateX(player: forge.game.player.Player, cost: forge.card.mana.ManaCost, helpers: List<Keyword>): Int {
        val untapped = player.getCardsIn(ZoneType.Battlefield).filter { !it.isTapped }
        val help = helpers.sumOf { k ->
            when (k) {
                Keyword.DELVE -> player.getCardsIn(ZoneType.Graveyard).size
                Keyword.CONVOKE -> untapped.count { it.isCreature }
                Keyword.IMPROVISE -> untapped.count { it.isArtifact }
                else -> 0
            }
        }
        val pool = MANA_BYTES.values.sumOf { player.manaPool.getAmountOfColor(it) }
        val available = forge.ai.ComputerUtilMana.getAvailableManaEstimate(player, true) + pool + help
        return maxOf(0, (available - cost.cmc) / cost.countX())
    }

    /** A dialog raised while X is only being priced: never shown, the pricing stops instead. */
    private class PricingAsked(question: String) : RuntimeException(question)

    /**
     * Combat damage among blockers (and, with trample, the one attacked), or
     * among the attackers one blocker blocks: the prompt comes pre-filled
     * with lethal damage in order and the rest to the last target.
     *
     * Forge passes the defender whenever there is one; whether it may take
     * damage is the dialog's to decide (VAssignCombatDamage), and offering it
     * always let a blocked creature without trample hit the player (CR
     * 510.1c). As there: only with trample, after every blocker has lethal
     * (CR 702.19b), or for a creature that may divide its damage as it
     * chooses. Lethal is Forge's: deathtouch makes 1 lethal (CR 702.2c).
     */
    override fun assignCombatDamage(attacker: CardView?, blockers: MutableList<CardView>, damage: Int, defender: GameEntityView?, overrideOrder: Boolean, maySkip: Boolean): MutableMap<CardView?, Int>? {
        // Nothing to decide, as Forge's own window skips it (CMatchUI): no damage, or a first blocker that takes it all in the old order.
        if (damage <= 0) return mutableMapOf()
        val first = blockers.firstOrNull()
        if (first != null && !overrideOrder && attacker?.currentState?.hasDeathtouch() != true && first.lethalDamage >= damage) return mutableMapOf(first to damage)
        val source = attacker?.currentState
        val trample = defender != null && source?.hasTrample() == true
        val divides = source?.hasDivideDamage() == true && overrideOrder
        val toDefender = defender.takeIf { trample || divides }
        val lethal = blockers.map { b ->
            val need = maxOf(0, b.lethalDamage)
            when {
                b.currentState.isPlaneswalker -> b.currentState.loyalty.toIntOrNull() ?: need
                source?.hasDeathtouch() == true -> minOf(need, 1)
                else -> need
            }
        }
        val suggested = MutableList(blockers.size + if (toDefender != null) 1 else 0) { 0 }
        var left = damage
        lethal.forEachIndexed { i, need -> val dealt = minOf(left, need); suggested[i] = dealt; left -= dealt }
        if (left > 0) suggested[if (toDefender != null) blockers.size else blockers.lastIndex] += left
        val targets = blockers.mapIndexed { i, b -> DistributeTarget(b.currentState.name, BoardRef.Card(b.id).also { cardViews[b.id] = b }, lethal[i]) } +
            listOfNotNull(toDefender?.let { DistributeTarget(it.toString(), (it as? PlayerView)?.let { p -> BoardRef.Player(p.id) }, null) })
        val name = source?.name ?: "combat"
        val amounts = distribute("$name: assign $damage damage", targets, damage, atLeastOne = false, suggested, allowSkip = maySkip,
            excess = if (trample && !divides) blockers.size else null, inOrder = !overrideOrder) ?: return null
        val result = LinkedHashMap<CardView?, Int>()
        blockers.forEachIndexed { i, b -> if (amounts[i] > 0) result[b] = amounts[i] }
        if (toDefender != null && amounts.last() > 0) result[null] = amounts.last()
        return result
    }

    override fun assignGenericAmount(effectSource: CardView?, target: MutableMap<Any, Int>, amount: Int, atLeastOne: Boolean, amountLabel: String?): MutableMap<Any, Int> {
        val keys = target.keys.toList()
        // Each value is that target's most, as Forge's dialog has it (VAssignGenericAmount): "two mana of different colors" is 1 a colour.
        val caps = keys.map { target[it] }
        val suggested = MutableList(keys.size) { if (atLeastOne) 1 else 0 }
        var left = amount - suggested.sum()
        for (i in keys.indices) {
            val room = minOf(left, (caps[i] ?: amount) - suggested[i]).coerceAtLeast(0)
            suggested[i] += room; left -= room
        }
        val targets = keys.mapIndexed { i, key -> optionFor(key, key.toString()).let { DistributeTarget(it.label, it.ref, null, max = caps[i]) } }
        val source = effectSource?.currentState?.name ?: "effect"
        val amounts = distribute("$source: divide $amount ${amountLabel.orEmpty()}".trim(), targets, amount, atLeastOne, suggested, allowSkip = false)
            ?: suggested
        return LinkedHashMap<Any, Int>().apply { keys.forEachIndexed { i, key -> if (amounts[i] > 0) put(key, amounts[i]) } }
    }

    /** Amounts that sum to [total] (each at least one when [atLeastOne]), or null when skipped. */
    private fun distribute(
        message: String, targets: List<DistributeTarget>, total: Int, atLeastOne: Boolean, suggested: List<Int>, allowSkip: Boolean,
        excess: Int? = null, inOrder: Boolean = false,
    ): List<Int>? {
        fun prompt(id: Long) = DistributePrompt(id, message, targets, total, atLeastOne, suggested, excess, inOrder)
        val action = awaitDialog(::prompt, SeatAction.Distribute(suggested))
        if (action == SeatAction.Cancel && allowSkip) return null
        val amounts = (action as? SeatAction.Distribute)?.amounts
        // The board holds back Done on the same rule; this is the last line before Forge, which trusts the dialog.
        val valid = amounts != null && prompt(0).problem(amounts) == null
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
        // Forge's own least (PlayerControllerHuman.sideboard): the format's main minimum, not 60 — a Duel Commander deck cut to 60 was refused.
        val least = gameView?.game?.rules?.gameType?.deckFormat?.mainRange?.minimum ?: 60
        val action = awaitDialog({ SideboardPrompt(it, message ?: "Sideboard for the next game", entries(mainCards), entries(sideCards), minOf(least, mainCards.size)) },
            SeatAction.Sideboard(mainCards.groupingBy { it.name }.eachCount()), betweenGames = true)
        val wanted = (action as? SeatAction.Sideboard)?.main ?: return null
        // The same card objects Forge gave us, re-dealt: every copy of a name is interchangeable.
        val pool = (mainCards + sideCards).groupBy { it.name }.mapValues { it.value.toMutableList() }
        val chosen = wanted.flatMap { (name, n) -> List(n) { pool[name]?.removeFirstOrNull() }.filterNotNull() }
        recorder.seat("SIDEBOARD main ${chosen.size}, sideboard ${mainCards.size + sideCards.size - chosen.size}")
        return chosen.toMutableList()
    }

    private companion object {
        /** Zones a spectator sees nothing of unless both hands are switched on ([visible]). */
        val HIDDEN_ZONES = setOf(ZoneType.Hand, ZoneType.Library)
        /** Cost reductions whose check asks the controller (CostAdjustment.adjust): such a spell's X is estimated, not priced. */
        val ASKS_WHILE_PRICED = listOf(Keyword.DELVE, Keyword.CONVOKE, Keyword.IMPROVISE, Keyword.ASSIST)
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
