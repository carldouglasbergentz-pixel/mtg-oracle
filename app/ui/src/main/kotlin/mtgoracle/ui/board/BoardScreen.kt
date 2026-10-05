package mtgoracle.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import mtgoracle.ui.kit.PaneDrag
import mtgoracle.ui.kit.PaneEdge
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.flow.MutableStateFlow
import mtgoracle.core.model.BoardRef
import mtgoracle.core.model.BoardState
import mtgoracle.core.model.ChoicePrompt
import mtgoracle.core.model.DistributePrompt
import mtgoracle.core.model.GameSeat
import mtgoracle.core.model.InputKind
import mtgoracle.core.model.InputPrompt
import mtgoracle.core.model.OrderPrompt
import mtgoracle.core.model.PhaseStops
import mtgoracle.core.model.SideboardPrompt
import mtgoracle.core.model.Prompt
import mtgoracle.ui.kit.Border
import mtgoracle.ui.kit.BoxPane
import mtgoracle.ui.kit.ControlButton
import mtgoracle.ui.kit.CardFace
import mtgoracle.ui.kit.CardMode
import mtgoracle.ui.kit.ClickRegistry
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.FrameSize
import mtgoracle.ui.kit.FrameTier
import mtgoracle.ui.kit.GridText
import mtgoracle.ui.kit.HIDDEN_FACE
import mtgoracle.ui.kit.LocalClickRegistry
import mtgoracle.ui.kit.StatusLine
import mtgoracle.ui.kit.ZoomPane
import mtgoracle.ui.kit.cellHeight
import mtgoracle.ui.kit.cellWidth
import mtgoracle.ui.kit.face
import mtgoracle.ui.kit.fit
import mtgoracle.ui.kit.region
import mtgoracle.ui.kit.wrap
import mtgoracle.ui.theme.Cells
import mtgoracle.ui.theme.LocalCells
import mtgoracle.ui.theme.Palette
import kotlin.math.roundToInt

const val SIDE_COLS = 46
/** Cells a pane edge moves per Ctrl+←/→. */
const val PANE_STEP = 2

/**
 * The game board for one seat (docs/app-design.md "The game board").
 *
 * A table seen from your chair (Table.kt): the opponent's half across the
 * midline, yours below it with your hand at your edge, each with its zone
 * column on the left; the prompt under it all. On the right: the zoom pane
 * and the log. Key hints at the bottom. While anything is on the stack, the
 * stack box floats over the table (StackBox.kt), and the midline names its
 * top item and any combat. Every click and key becomes a [UiEvent] for [reduce];
 * answers go to the seat.
 */
@Composable
fun BoardScreen(
    seat: GameSeat?,
    title: String,
    mode: CardMode,
    extraHints: List<Pair<String, String>> = emptyList(),
    notice: String? = null,
    onExtraKey: (Key) -> Boolean = { false },
    /** The viewer's arrangement (stack box position, folded or not); changes go to [onLayoutChange] to persist. */
    layout: BoardLayout = BoardLayout(),
    onLayoutChange: (BoardLayout) -> Unit = {},
    /** The match this game belongs to, and what its panels do; null for a board with no match around it. */
    match: MatchStatus? = null,
    matchControls: MatchControls? = null,
) {
    val none = remember { MutableStateFlow<BoardState?>(null) }
    val noPrompt = remember { MutableStateFlow<Prompt?>(null) }
    val noStops = remember { MutableStateFlow(PhaseStops.DEFAULT) }
    val noYield = remember { MutableStateFlow<String?>(null) }
    val board by (seat?.board ?: none).collectAsState()
    val prompt by (seat?.prompt ?: noPrompt).collectAsState()
    val stops by (seat?.stops ?: noStops).collectAsState()
    val yieldStatus by (seat?.yieldStatus ?: noYield).collectAsState()
    val warning by (seat?.warning ?: noYield).collectAsState()
    val noHands = remember { MutableStateFlow(false) }
    val showAllHands by (seat?.showAllHands ?: noHands).collectAsState()
    var interaction by remember { mutableStateOf(Interaction()) }
    var zoom by remember { mutableStateOf<CardFace?>(null) }
    var arrangement by remember { mutableStateOf(layout) }
    // The window's width in cells as last laid out: read by the keys, so not state (it is written while composing).
    val lastTotal = remember { intArrayOf(Int.MAX_VALUE) }
    val focus = remember { FocusRequester() }
    // The stack box needs to know where the cards are even in the real window, where nobody else asks.
    val registry = LocalClickRegistry.current ?: remember { ClickRegistry() }

    fun arrange(next: BoardLayout) { arrangement = next; onLayoutChange(next) }
    fun send(event: UiEvent) {
        val current = prompt
        val out = reduce(interaction, current, event, manaAtRisk(current, board))
        interaction = out.state
        if (current != null) out.action?.let { seat?.answer(current.id, it) }
        out.command?.let { seat?.command(it) }
    }
    var menuOpen by remember { mutableStateOf(false) }
    val showResult = match != null && match.betweenGames && prompt !is SideboardPrompt
    val onClick: (ClickTarget) -> Unit = { target ->
        when {
            target is ClickTarget.Stop -> seat?.setStops(stops.toggled(target.seatsTurn, target.step))
            target == ClickTarget.Control("stack-bar") -> arrange(arrangement.copy(stackCollapsed = false))
            target == MatchTargets.OPEN_MENU -> menuOpen = matchControls != null
            target == MatchTargets.CANCEL -> menuOpen = false
            target == MatchTargets.CONCEDE_GAME -> { menuOpen = false; matchControls?.onConcedeGame?.invoke() }
            target == MatchTargets.LEAVE -> { menuOpen = false; matchControls?.onLeaveMatch?.invoke() }
            target == MatchTargets.CONTINUE -> matchControls?.onContinue?.invoke()
            target == MatchTargets.LOBBY -> matchControls?.onBackToLobby?.invoke()
            else -> send(UiEvent.Click(target))
        }
    }
    /** The keys of the match panels, before the board's own: true when one was used. */
    fun matchKey(key: Key, ctrl: Boolean): Boolean {
        if (matchControls == null) return false
        val gamesLeft = (match?.gamesInMatch ?: 1) > 1
        return when {
            menuOpen -> {
                when {
                    key == Key.Escape -> menuOpen = false
                    gamesLeft && key == Key.One -> onClick(MatchTargets.CONCEDE_GAME)
                    key == (if (gamesLeft) Key.Two else Key.One) -> onClick(MatchTargets.LEAVE)
                }
                true // the menu has the keyboard while it is open
            }
            ctrl && key == Key.Q -> { menuOpen = !showResult; true }
            showResult && (key == Key.Enter || key == Key.NumPadEnter) -> { onClick(if (match!!.over) MatchTargets.LOBBY else MatchTargets.CONTINUE); true }
            showResult && key == Key.Escape && match!!.over -> { onClick(MatchTargets.LOBBY); true }
            // Esc with nothing to cancel opens the menu; with a prompt that cancels, it cancels.
            key == Key.Escape && !showResult && interaction.pendingPass == null && prompt.let { it == null || (it is InputPrompt && !it.cancelEnabled) } -> { menuOpen = true; true }
            else -> false
        }
    }
    val onHover: (ClickTarget?) -> Unit = { target -> faceFor(target, board, prompt)?.let { zoom = it } }

    CompositionLocalProvider(LocalClickRegistry provides registry) {
        BoxWithConstraints(
            Modifier.fillMaxSize().background(Palette.surface)
                .focusRequester(focus).focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if (matchKey(event.key, event.isCtrlPressed)) return@onPreviewKeyEvent true
                    // A searchable choice ("choose a card name") takes what is typed before the board's letter keys do.
                    // A key with no character (Shift, the arrows, F2) reports 0xFFFF; Delete is 127.
                    val typed = event.utf16CodePoint.takeIf { it in 32 until 0xFFFF && it != 127 }?.toChar()?.takeIf { !it.isISOControl() && Character.isDefined(it) }
                    if (typed != null && !event.isCtrlPressed && (prompt as? ChoicePrompt)?.searchable == true) {
                        interaction = typeInto(interaction, prompt, typed); return@onPreviewKeyEvent true
                    }
                    if (onExtraKey(event.key)) return@onPreviewKeyEvent true
                    if (event.key == Key.H && seat?.canShowAllHands == true) { seat.setShowAllHands(!showAllHands); return@onPreviewKeyEvent true }
                    if (event.key == Key.S) { arrange(arrangement.copy(stackCollapsed = !arrangement.stackCollapsed)); return@onPreviewKeyEvent true }
                    if (event.key == Key.R && !event.isCtrlPressed) { arrange(arrangement.copy(rotateTapped = !arrangement.rotateTapped)); return@onPreviewKeyEvent true }
                    // Ctrl+←/→ moves the right column's edge, with Shift the zone columns' edge: the border goes the arrow's way.
                    if (event.isCtrlPressed && (event.key == Key.DirectionLeft || event.key == Key.DirectionRight)) {
                        val step = if (event.key == Key.DirectionRight) PANE_STEP else -PANE_STEP
                        // Clamped as kept, as a drag is: unclamped, the kept width ran past the bound, and the other arrow seemed dead as long.
                        arrange((if (event.isShiftPressed) arrangement.copy(zoneCols = arrangement.zoneCols + step) else arrangement.copy(sideCols = arrangement.sideCols - step)).clampedTo(lastTotal[0]))
                        return@onPreviewKeyEvent true
                    }
                    val key = event.key.toUiKey() ?: return@onPreviewKeyEvent false
                    send(UiEvent.Key(key)); true
                },
        ) {
            val cells = LocalCells.current
            val totalCols = cells.cols(constraints.maxWidth.toFloat())
            lastTotal[0] = totalCols
            val totalRows = cells.rows(constraints.maxHeight.toFloat())
            // While an edge is dragged its pane follows the mouse; the width is kept when the drag ends.
            // The board recomposes when a drag crosses a cell, not on every pixel of it.
            var zoneDragPx by remember { mutableStateOf(0f) }
            var sideDragPx by remember { mutableStateOf(0f) }
            val zoneDragCols by remember(cells.width) { derivedStateOf { (zoneDragPx / cells.width).roundToInt() } }
            val sideDragCols by remember(cells.width) { derivedStateOf { (sideDragPx / cells.width).roundToInt() } }
            val panes = arrangement.copy(
                zoneCols = arrangement.zoneCols + zoneDragCols,
                sideCols = arrangement.sideCols - sideDragCols,
            ).clampedTo(totalCols)
            val zoneDrag = PaneDrag({ zoneDragPx += it }) { zoneDragPx = 0f; arrange(arrangement.copy(zoneCols = panes.zoneCols, sideCols = panes.sideCols)) }
            val sideDrag = PaneDrag({ sideDragPx += it }) { sideDragPx = 0f; arrange(arrangement.copy(zoneCols = panes.zoneCols, sideCols = panes.sideCols)) }
            val sideCols = panes.sideCols
            val leftCols = totalCols - sideCols
            val b = board
            val looks = Looks(prompt, highlightedRefs(prompt), mode, onClick, onHover,
                fresh = b?.freshCardIds.orEmpty(), targeted = b?.stackTargets.orEmpty(), rotate = arrangement.rotateTapped)
            var midline by remember { mutableStateOf(Rect.Zero) }
            var header by remember { mutableStateOf(Rect.Zero) }
            var table by remember { mutableStateOf(Rect.Zero) }
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    // Fixed regions: the header, each half, the midline rule, hand and prompt panes of set
                    // heights. Nothing inside any of them can resize a sibling.
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        // The news at the top, where its changes don't flicker in the middle of the table.
                        Header(title, b, leftCols, Modifier.region("header").onGloballyPositioned { val r = it.boundsInWindow(); if (r != header) header = r })
                        val sideboarding = prompt as? SideboardPrompt
                        if (b == null || b.sides == null) {
                            BoxPane("board", Modifier.weight(1f).fillMaxWidth()) { GridText(fit(if (b == null) "Starting Forge…" else "Waiting for both players…", leftCols - 2), color = Palette.dim) }
                        } else if (sideboarding != null) {
                            // Between games the table is the deck: sideboarding takes its place.
                            val state = if (interaction.promptId == sideboarding.id) interaction else Interaction.start(sideboarding)
                            SideboardView(sideboarding, state.deck, onClick, Modifier.weight(1f).fillMaxWidth())
                        } else {
                            // Across the table: the opponent (or, watching, player one) above the midline, you below.
                            val (far, near) = b.sides!!
                            val seatStops = if (b.seat != null) stops else null
                            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                                // The frame sizes of both halves and the hand (planTable): even halves, each with the
                                // largest frames that show its own cards, and the hand sized by the window.
                                val fieldCols = leftCols - panes.zoneCols - 2
                                val attach = { p: mtgoracle.core.model.PlayerState -> p.battlefield.any { it.attachedToId != null } }
                                fun wants(p: mtgoracle.core.model.PlayerState, t: FrameTier, handLane: Boolean): Int =
                                    bandsNeeded(zoneContents(Lanes(p.battlefield, looks), t), fieldCols) * bandRows(t, attach(p)) + 2 + emblemRows(p) +
                                        if (handLane) FrameSize.rows(t) else 0
                                val tiers = if (mode == CardMode.ART) FrameTier.entries else listOf(FrameTier.TEXT)
                                val plan = planTable(cells.rows(constraints.maxHeight.toFloat()) - MIDLINE_ROWS, tiers, handRows = { FrameSize.rows(it) + 2 },
                                    farWants = { wants(far, it, handLane = showAllHands) }, nearWants = { wants(near, it, handLane = false) },
                                    halfFloor = ZONE_COLUMN_MIN_ROWS)
                                val farLooks = looks.sized(plan.farTier, attach(far))
                                val nearLooks = looks.sized(plan.nearTier, attach(near))
                                Column(Modifier.fillMaxSize()) {
                                    Column(Modifier.fillMaxWidth().cellHeight(plan.farRows + MIDLINE_ROWS + plan.nearRows).onGloballyPositioned { val r = it.boundsInWindow(); if (r != table) table = r }) {
                                        Half(far, b, far = true, stops = seatStops, showHand = showAllHands, looks = farLooks, modifier = Modifier.cellHeight(plan.farRows).region("far-half"),
                                            zoneCols = panes.zoneCols, zoneDrag = zoneDrag)
                                        MidRule(leftCols, Modifier.region("midline").onGloballyPositioned { val r = it.boundsInWindow(); if (r != midline) midline = r })
                                        Half(near, b, far = false, stops = seatStops, showHand = false, looks = nearLooks, modifier = Modifier.cellHeight(plan.nearRows).region("near-half"),
                                            zoneCols = panes.zoneCols, zoneDrag = zoneDrag)
                                    }
                                    val showNearHand = b.seat != null || showAllHands
                                    BoxPane(if (showNearHand) "hand (${near.handCount})" else "hand ${near.handCount} (hidden: H shows)",
                                        Modifier.fillMaxWidth().weight(1f).region("hand")) {
                                        if (showNearHand) HandLane(near, nearLooks.sized(plan.handTier, false))
                                    }
                                }
                            }
                        }
                        BoxPane("prompt" + ((prompt as? InputPrompt)?.let { " · ${it.kind.name.lowercase()}" } ?: ""),
                            Modifier.fillMaxWidth().cellHeight(PROMPT_ROWS).region("prompt"),
                            border = if (prompt != null) Border.DOUBLE else Border.SINGLE,
                            borderColor = if (prompt != null) Palette.accent else Palette.dim) {
                            PromptBody(prompt, board, interaction, leftCols - 2, onClick, onHover,
                                trailing = if (matchControls != null && !showResult) ({ ControlButton("[ concede ]", MatchTargets.OPEN_MENU, true, onClick) }) else null)
                        }
                    }
                    Box(Modifier.cellWidth(sideCols).fillMaxHeight()) {
                        Column(Modifier.fillMaxSize()) {
                            ZoomPane(zoom, sideCols, imageRows = 20, textMode = mode == CardMode.TEXT, modifier = Modifier.fillMaxWidth().region("zoom"))
                            LogPane(board, sideCols, Modifier.weight(1f).region("log"))
                        }
                        // Its left border is the handle.
                        PaneEdge(sideDrag, "side-edge", Modifier.align(Alignment.CenterStart))
                    }
                }
                val watchHint = if (seat?.canShowAllHands == true) listOf("H" to if (showAllHands) "hide hands" else "show hands") else emptyList()
                val stackHint = listOf("S" to if (arrangement.stackCollapsed) "open stack" else "fold stack", "R" to if (arrangement.rotateTapped) "tapped: turned" else "tapped: upright")
                val matchHint = if (matchControls != null) listOf("Ctrl+Q" to "concede") else emptyList()
                StatusLine(hints(prompt) + stackHint + matchHint + watchHint + extraHints, notice ?: yieldStatus, totalCols, Modifier.region("status"), warning = warning)
            }
            val sides = b?.sides
            if (b != null && sides != null && b.stack.isNotEmpty() && midline != Rect.Zero && prompt !is SideboardPrompt) {
                StackLayer(b, sides.first.id, prompt, mode, cells, registry, arrangement, ::arrange, midline, header, table, leftCols, totalCols, totalRows, onClick, onHover)
            }
            // Over everything: the result between games, and the way out of the game.
            if (showResult) ResultPanel(match!!, onClick, Modifier.align(Alignment.Center))
            if (menuOpen && matchControls != null) ConcedeMenu(match, onClick, Modifier.align(Alignment.Center))
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/**
 * The stack box, floating. Where the viewer put it (or centred on the
 * midline over the table) — unless that covers a card the current prompt
 * wants clicked: then across the midline, or folded to its bar. Dragged by
 * its top edge; the new place is the viewer's, persisted, and kept inside
 * the window when it shrinks.
 */
@Composable
private fun StackLayer(
    b: BoardState, farId: Int, prompt: Prompt?, mode: CardMode, cells: Cells, registry: ClickRegistry,
    arrangement: BoardLayout, arrange: (BoardLayout) -> Unit, midline: Rect, header: Rect, table: Rect,
    leftCols: Int, totalCols: Int, totalRows: Int,
    onClick: (ClickTarget) -> Unit, onHover: (ClickTarget?) -> Unit,
) {
    var drag by remember { mutableStateOf(Offset.Zero) }
    var dragging by remember { mutableStateOf(false) }
    // The cards' places are known only after a frame; look again once the prompt's layout has settled.
    var settled by remember { mutableIntStateOf(0) }
    LaunchedEffect(prompt?.id, b.stack.size) { withFrameNanos { }; settled++ }

    val rows = stackBoxRows(b.stack.size, mode, maxRows = maxOf(5, cells.rows(table.height)))
    val midRow = cells.rows(midline.top)
    // By default at the table's right end: lanes fill from the left, so that is where cards are fewest,
    // and the midline's trail, which reads from the left, stays in view.
    val col = clampCells(arrangement.stackCol ?: (leftCols - STACK_BOX_COLS - 1), STACK_BOX_COLS, totalCols)
    val row = clampCells(arrangement.stackRow ?: (midRow + MIDLINE_ROWS / 2 - rows / 2), rows, totalRows)
    fun at(c: Int, r: Int) = Rect(c * cells.width, r * cells.height, (c + STACK_BOX_COLS) * cells.width, (r + rows) * cells.height)
    val placement = if (dragging) StackPlacement.AS_SET else settled.let {
        placeStackBox(at(col, row), above = at(col, clampCells(midRow - rows, rows, totalRows)),
            below = at(col, clampCells(midRow + MIDLINE_ROWS, rows, totalRows)), midlineY = midline.center.y, legal = legalRects(prompt, registry))
    }
    if (arrangement.stackCollapsed || placement == StackPlacement.FOLDED) {
        // On the header's rule line, at its right end: no card is ever drawn there.
        val barCols = stackBarText(b.stack.size).length
        StackBar(b.stack.size, onClick, Modifier.offset { IntOffset(((leftCols - barCols - 1) * cells.width).roundToInt(), header.top.roundToInt()) })
        return
    }
    val shownRow = when (placement) {
        StackPlacement.ABOVE_MIDLINE -> clampCells(midRow - rows, rows, totalRows)
        StackPlacement.BELOW_MIDLINE -> clampCells(midRow + MIDLINE_ROWS, rows, totalRows)
        else -> row
    }
    StackBox(
        b, farId, mode, picks = highlightedRefs(prompt), rows = rows, onClick = onClick, onHover = onHover,
        onDrag = { dragging = true; drag += it },
        onDragEnd = {
            val c = clampCells(((col * cells.width + drag.x) / cells.width).roundToInt(), STACK_BOX_COLS, totalCols)
            val r = clampCells(((shownRow * cells.height + drag.y) / cells.height).roundToInt(), rows, totalRows)
            drag = Offset.Zero
            dragging = false
            arrange(arrangement.copy(stackCol = c, stackRow = r))
        },
        modifier = Modifier.offset { IntOffset((col * cells.width + drag.x).roundToInt(), (shownRow * cells.height + drag.y).roundToInt()) },
    )
}

/** Where the cards and players the current prompt wants clicked are drawn. */
private fun legalRects(prompt: Prompt?, registry: ClickRegistry): List<Rect> {
    val cards: Set<Int> = when (prompt) {
        is InputPrompt -> prompt.selectableCardIds + prompt.actionableCardIds
        else -> highlightedRefs(prompt).filterIsInstance<BoardRef.Card>().map { it.id }.toSet()
    }
    val players = if ((prompt as? InputPrompt)?.kind == InputKind.TARGET) registry.targets.filterIsInstance<ClickTarget.Player>() else
        highlightedRefs(prompt).filterIsInstance<BoardRef.Player>().map { ClickTarget.Player(it.id) }
    return cards.mapNotNull { registry[ClickTarget.Card(it)] } + players.mapNotNull { registry[it] }
}

private fun hints(prompt: Prompt?): List<Pair<String, String>> = buildList {
    add("F2" to "pass"); add("F4" to "end turn"); add("F6" to "skip turn"); add("F3" to "cancel yields")
    if (prompt != null) { add("Enter" to "ok"); add("Esc" to "cancel") }
    if (prompt is ChoicePrompt || prompt is OrderPrompt || prompt is DistributePrompt) add("1-9" to "choose")
}

/** Board objects the current dialog's options stand for: they get the selectable look. */
private fun highlightedRefs(prompt: Prompt?): Set<BoardRef> = when (prompt) {
    is ChoicePrompt -> prompt.options.mapNotNull { it.ref }.toSet()
    is OrderPrompt -> prompt.items.mapNotNull { it.ref }.toSet()
    is DistributePrompt -> prompt.targets.mapNotNull { it.ref }.toSet()
    is InputPrompt -> prompt.selectableCardIds.map { BoardRef.Card(it) }.toSet()
    else -> emptySet()
}

private fun faceFor(target: ClickTarget?, board: BoardState?, prompt: Prompt?): CardFace? = when (target) {
    is ClickTarget.Card -> (board?.card(target.id) ?: (prompt as? InputPrompt)?.selectableElsewhere?.firstOrNull { it.id == target.id })?.face()
        ?: HIDDEN_FACE.takeIf { target.id < 0 } // a back: hovering it shows only that it is hidden
    // The item itself, in full: what the box's lines may cut short, the zoom pane shows.
    is ClickTarget.StackItem -> board?.stack?.firstOrNull { it.id == target.id }?.let { item ->
        item.face().copy(text = "${item.controllerName}: ${item.text}" + if (item.targetNames.isEmpty()) "" else "\n→ ${item.targetNames.joinToString(", ")}")
    }
    else -> null
}

@Composable
private fun LogPane(board: BoardState?, cols: Int, modifier: Modifier) {
    val inner = cols - 2
    BoxPane("log", modifier.fillMaxWidth()) {
        BoxWithConstraints {
            val rows = LocalCells.current.rows(constraints.maxHeight.toFloat()).coerceAtLeast(1)
            Column { board?.recentLog.orEmpty().flatMap { wrap(it, inner) }.takeLast(rows).forEach { GridText(fit(it, inner), color = Palette.dim) } }
        }
    }
}
