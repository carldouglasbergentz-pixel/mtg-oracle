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
import forge.localinstance.skin.FSkinProp
import forge.player.PlayerControllerHuman
import forge.player.PlayerZoneUpdate
import forge.player.PlayerZoneUpdates
import forge.trackable.TrackableCollection
import forge.util.FSerializableFunction
import forge.util.ITriggerEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import mtgoracle.model.BoardState
import mtgoracle.model.CardState
import mtgoracle.model.ChoicePrompt
import mtgoracle.model.ConfirmPrompt
import mtgoracle.model.GameSeat
import mtgoracle.model.InputKind
import mtgoracle.model.InputPrompt
import mtgoracle.model.Prompt
import mtgoracle.model.SeatAction
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
 *    the EDT against `IGameController` (selectCard / selectPlayer /
 *    selectButtonOk / selectButtonCancel), which is exactly what Forge's own
 *    board does on a click. The Input releases its latch; the engine resumes.
 * 2. **Direct dialogs** (`getChoices`, `confirm`, `getAbilityToPlay`, ...).
 *    Forge calls these synchronously — from the game thread, or from the EDT
 *    in the middle of a click — and wants a value back. We publish a
 *    [ChoicePrompt]/[ConfirmPrompt] and block *that* thread on a future the
 *    UI completes. The Compose thread never blocks.
 *
 * Methods that ask for a decision we have no UI for yet answer automatically
 * and write `UNHANDLED` to the game log, so a game never hangs on them.
 */
class SeatGui(
    private val recorder: GameRecorder,
    private val edt: ForgeEdt,
    /** Turn-structure stops for the seat: where it gets priority with an empty stack. */
    private val seatStops: Set<PhaseType> = setOf(PhaseType.MAIN1, PhaseType.MAIN2),
    private val opponentStops: Set<PhaseType> = setOf(PhaseType.END_OF_TURN),
) : AbstractGuiGame(), GameSeat {

    private val boardFlow = MutableStateFlow<BoardState?>(null)
    private val promptFlow = MutableStateFlow<Prompt?>(null)
    override val board: StateFlow<BoardState?> get() = boardFlow
    override val prompt: StateFlow<Prompt?> get() = promptFlow

    private val cardViews = ConcurrentHashMap<Int, CardView>()
    private val playerViews = ConcurrentHashMap<Int, PlayerView>()
    private val snapshots = Snapshots(cardViews, playerViews)
    private val promptIds = AtomicLong()

    @Volatile private var seatController: PlayerControllerHuman? = null
    @Volatile private var seatPlayerIds: Set<Int> = emptySet()
    @Volatile private var dirty = true
    @Volatile private var finished = false

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

    init {
        edt.afterEachTask { refresh() }
    }

    // --- GameSeat: answers from the UI or the scripted seat ------------------

    override fun answer(promptId: Long, action: SeatAction) {
        val current = promptFlow.value
        if (current == null || current.id != promptId) {
            SpikeLog.warn("stale answer $action for prompt #$promptId (current ${current?.id})")
            return
        }
        recorder.seat("ANSWER #$promptId $action${describe(action)}")
        when (current) {
            is InputPrompt -> edt.later { applyGesture(action) }
            is ChoicePrompt, is ConfirmPrompt -> synchronized(dialogs) {
                dialogs.firstOrNull { it.prompt.id == promptId }?.reply?.complete(action)
            }
        }
    }

    private fun describe(action: SeatAction): String = when (action) {
        is SeatAction.ClickCard -> cardViews[action.cardId]?.let { " = ${it.currentState.name}" } ?: " = unknown card"
        is SeatAction.ClickPlayer -> playerViews[action.playerId]?.let { " = ${it.name}" } ?: " = unknown player"
        is SeatAction.Choose -> (promptFlow.value as? ChoicePrompt)?.let { p ->
            " = " + action.indices.joinToString(", ") { p.options.getOrElse(it) { "?" } }
        } ?: ""
        else -> ""
    }

    /** On the EDT, as Forge's own board would on a click. */
    private fun applyGesture(action: SeatAction) {
        val controller = seatController ?: return
        when (action) {
            is SeatAction.ClickCard -> {
                val view = cardViews[action.cardId]
                if (view == null) {
                    SpikeLog.warn("click on unknown card ${action.cardId}")
                } else if (!controller.selectCard(view, null, null)) {
                    recorder.seat("  (the ${promptFlow.value?.let { (it as? InputPrompt)?.inputName }} ignored the click)")
                }
            }
            is SeatAction.ClickPlayer -> playerViews[action.playerId]?.let { controller.selectPlayer(it, null) }
            SeatAction.Ok -> controller.selectButtonOk()
            SeatAction.Cancel -> controller.selectButtonCancel()
            is SeatAction.Choose, is SeatAction.Confirm -> SpikeLog.warn("$action is not a gesture")
        }
    }

    // --- prompt and board publication (EDT) ----------------------------------

    private fun refresh() {
        if (dirty) snapshot()
        publishInputPrompt()
    }

    private fun snapshot() {
        val view = gameView ?: return
        dirty = false
        try {
            boardFlow.value = snapshots.build(view, seatPlayerIds, { mayView(it) }, recorder.recentLog(60), finished)
        } catch (e: RuntimeException) {
            // The engine thread changed a collection mid-read; the next tick retries.
            dirty = true
            SpikeLog.debug("snapshot retried: $e")
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
        )
        if (current is InputPrompt && current.copy(id = 0, selectableElsewhere = emptyList()) == candidate) return
        snapshot() // the engine is parked on this Input: the board is consistent now
        publish(candidate.copy(id = promptIds.incrementAndGet(), selectableElsewhere = selectableElsewhere()))
    }

    /** Selectable cards outside hand and battlefield — the zones the board makes clickable. */
    private fun selectableElsewhere(): List<CardState> {
        val shown = boardFlow.value?.players.orEmpty().flatMap { it.hand.orEmpty() + it.battlefield }.map { it.id }.toSet()
        return selectableIds.filter { it !in shown }.mapNotNull { id -> cardViews[id]?.let { snapshots.card(it) } }
    }

    private fun publish(prompt: Prompt) {
        promptFlow.value = prompt
        val detail = when (prompt) {
            is InputPrompt -> "${prompt.kind}/${prompt.inputName} [ok=${prompt.okLabel.ifBlank { "-" }}${if (prompt.okEnabled) "" else "(off)"}" +
                " cancel=${prompt.cancelLabel.ifBlank { "-" }}${if (prompt.cancelEnabled) "" else "(off)"}]" +
                " selectable=${prompt.selectableCardIds.size} actionable=${prompt.actionableCardIds.size}" +
                (if (prompt.selectableElsewhere.isEmpty()) "" else " elsewhere=${prompt.selectableElsewhere.map { it.name }}")
            is ChoicePrompt -> "CHOICE min=${prompt.min} max=${prompt.max} options=${prompt.options}"
            is ConfirmPrompt -> "CONFIRM [${prompt.yesLabel}/${prompt.noLabel}]"
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
    private fun awaitDialog(make: (Long) -> Prompt, fallback: SeatAction): SeatAction {
        if (finished) return fallback
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

    private fun <T> choose(message: String, min: Int, max: Int, items: List<T>, label: (T) -> String): List<T> {
        if (items.isEmpty()) return emptyList()
        if (min < 0) { // a reveal: show it, nothing to pick
            awaitDialog({ ChoicePrompt(it, message, items.map(label), -1, -1) }, SeatAction.Choose(emptyList()))
            return emptyList()
        }
        if (min >= items.size && max >= items.size) return items // all of them, no decision
        val defaultPick = SeatAction.Choose((0 until min).toList())
        val action = awaitDialog({ ChoicePrompt(it, message, items.map(label), min, max) }, defaultPick)
        val picked = (action as? SeatAction.Choose)?.indices?.distinct()?.filter { it in items.indices }
        if (picked == null || picked.size < min || picked.size > max) {
            SpikeLog.warn("invalid choice $action for '$message' (min=$min max=$max); taking the first $min")
            return items.take(min)
        }
        return picked.map { items[it] }
    }

    private fun unhandled(method: String, detail: String) {
        recorder.seat("UNHANDLED $method: $detail")
        SpikeLog.warn("UNHANDLED IGuiGame.$method: $detail")
    }

    // --- lifecycle -----------------------------------------------------------

    override fun openView(myPlayers: TrackableCollection<PlayerView>?) {
        seatPlayerIds = myPlayers?.map { it.id }?.toSet().orEmpty()
        gameView?.game?.let { recorder.attach(it) }
        dirty = true
    }

    override fun setOriginalGameController(player: PlayerView, gameController: IGameController) {
        super.setOriginalGameController(player, gameController)
        if (gameController is PlayerControllerHuman && gameController !is WatchLocalGame) {
            seatController = gameController
        }
    }

    override fun updateCurrentPlayer(player: PlayerView?) {}

    override fun finishGame() {
        finished = true
        dirty = true
        recorder.note("finishGame: ${gameView?.let { if (it.outcome?.isDraw == true) "draw" else "${it.winningPlayerName} won" }}")
        synchronized(dialogs) { dialogs.forEach { it.reply.complete(SeatAction.Cancel) } }
        edt.later { }
    }

    override fun afterGameEnd() {
        super.afterGameEnd()
        finished = true
        dirty = true
    }

    // --- Input feedback (EDT) ------------------------------------------------

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

    override fun isUiSetToSkipPhase(playerTurn: PlayerView?, phase: PhaseType?): Boolean {
        val seatsTurn = playerTurn != null && playerTurn.id in seatPlayerIds
        return if (seatsTurn) phase !in seatStops else phase !in opponentStops
    }

    override fun flashIncorrectAction() = recorder.seat("  (Forge flagged the last action as incorrect)")
    override fun alertUser() {}
    override fun showCombat() { dirty = true }
    override fun updatePhase(saveState: Boolean) { dirty = true }
    override fun updateTurn(player: PlayerView?) { dirty = true }
    override fun updatePlayerControl() { dirty = true }
    override fun updateStack() { dirty = true }
    override fun updateZones(zonesToUpdate: Iterable<PlayerZoneUpdate>?) { dirty = true }
    override fun updateCards(cards: Iterable<CardView>?) { dirty = true }
    override fun updateManaPool(manaPoolUpdate: Iterable<PlayerView>?) { dirty = true }
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

    override fun tempShowZones(controller: PlayerView?, zonesToUpdate: Iterable<PlayerZoneUpdate>): Iterable<PlayerZoneUpdate> = zonesToUpdate
    override fun hideZones(controller: PlayerView?, zonesToUpdate: Iterable<PlayerZoneUpdate>?) {}
    override fun openZones(controller: PlayerView?, zones: Collection<ZoneType>?, players: Map<PlayerView, Any>?, backupLastZones: Boolean) =
        PlayerZoneUpdates()
    override fun restoreOldZones(playerView: PlayerView?, playerZoneUpdates: PlayerZoneUpdates?) {}

    // --- direct dialogs ------------------------------------------------------

    override fun message(message: String?, title: String?) = recorder.note("MESSAGE ${title.orEmpty()}: ${message.orEmpty()}")

    override fun showErrorDialog(message: String?, title: String?) {
        recorder.note("ERROR DIALOG ${title.orEmpty()}: ${message.orEmpty()}")
        SpikeLog.warn("Forge error dialog: $title: $message")
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
        if (!inputOptions.isNullOrEmpty()) {
            return choose("${title.orEmpty()}: ${message.orEmpty()}", 1, 1, inputOptions) { it }.firstOrNull()
        }
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

    override fun <T : Any?> order(title: String?, top: String?, remainingObjectsMin: Int, remainingObjectsMax: Int, sourceChoices: MutableList<T>, destChoices: MutableList<T>?, referenceCard: CardView?, sideboardingMode: Boolean, showRememberCheckbox: Boolean): IGuiGame.OrderResult<T> {
        unhandled("order", "'$title' kept Forge's order: $sourceChoices")
        return IGuiGame.OrderResult(sourceChoices, false)
    }

    override fun getAbilityToPlay(hostCard: CardView?, abilities: MutableList<SpellAbilityView>, triggerEvent: ITriggerEvent?): SpellAbilityView? {
        val playable = abilities.filter { it.canPlay() }
        if (playable.isEmpty()) return null
        if (playable.size == 1 && !playable[0].promptIfOnlyPossibleAbility()) return playable[0]
        val name = hostCard?.currentState?.name ?: "card"
        return choose("$name: choose an ability", 0, 1, playable) { it.description }.firstOrNull()
    }

    override fun chooseSingleEntityForEffect(title: String, optionList: List<GameEntityView>, delayedReveal: DelayedReveal?, isOptional: Boolean): GameEntityView? {
        delayedReveal?.let { unhandled("chooseSingleEntityForEffect", "delayed reveal of ${it.cards.size} cards shown only in the log") }
        return choose(title, if (isOptional) 0 else 1, 1, optionList) { it.toString() }.firstOrNull()
    }

    override fun chooseEntitiesForEffect(title: String, optionList: List<GameEntityView>, min: Int, max: Int, delayedReveal: DelayedReveal?): MutableList<GameEntityView> {
        delayedReveal?.let { unhandled("chooseEntitiesForEffect", "delayed reveal of ${it.cards.size} cards shown only in the log") }
        return choose(title, min, max, optionList) { it.toString() }.toMutableList()
    }

    override fun manipulateCardList(title: String?, cards: Iterable<CardView>, manipulable: Iterable<CardView>?, toTop: Boolean, toBottom: Boolean, toAnywhere: Boolean): MutableList<CardView> {
        unhandled("manipulateCardList", "'$title' left as is")
        return cards.toMutableList()
    }

    override fun assignCombatDamage(attacker: CardView?, blockers: MutableList<CardView>, damage: Int, defender: GameEntityView?, overrideOrder: Boolean, maySkip: Boolean): MutableMap<CardView?, Int> {
        // Lethal to each blocker in order, the rest to the defender (null key) or the last blocker.
        val result = LinkedHashMap<CardView?, Int>()
        var left = damage
        for (blocker in blockers) {
            val lethal = maxOf(0, blocker.currentState.toughness - blocker.damage)
            val dealt = minOf(left, lethal)
            if (dealt > 0) result[blocker] = dealt
            left -= dealt
        }
        if (left > 0) {
            val sink = if (defender != null) null else blockers.last()
            result[sink] = (result[sink] ?: 0) + left
        }
        unhandled("assignCombatDamage", "auto lethal-in-order: ${result.entries.joinToString { "${it.key?.currentState?.name ?: "defender"}=${it.value}" }}")
        return result
    }

    override fun assignGenericAmount(effectSource: CardView?, target: MutableMap<Any, Int>, amount: Int, atLeastOne: Boolean, amountLabel: String?): MutableMap<Any, Int> {
        val keys = target.keys.toList()
        val result = LinkedHashMap<Any, Int>()
        if (atLeastOne) keys.forEach { result[it] = 1 }
        val rest = amount - result.values.sum()
        if (keys.isNotEmpty()) result[keys[0]] = (result[keys[0]] ?: 0) + rest
        unhandled("assignGenericAmount", "auto: $amount ${amountLabel.orEmpty()} -> $result")
        return result
    }

    override fun sideboard(sideboard: CardPool?, main: CardPool?, message: String?): MutableList<PaperCard>? {
        unhandled("sideboard", "kept the main deck")
        return null
    }
}
