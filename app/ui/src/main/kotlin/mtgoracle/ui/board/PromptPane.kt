package mtgoracle.ui.board

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import mtgoracle.ui.kit.FrameSize
import mtgoracle.ui.kit.CardMode
import mtgoracle.ui.kit.CardFrame
import mtgoracle.ui.kit.CardFace
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import mtgoracle.core.model.BoardState
import mtgoracle.core.model.ChoicePrompt
import mtgoracle.core.model.ConfirmPrompt
import mtgoracle.core.model.DistributePrompt
import mtgoracle.core.model.InputKind
import mtgoracle.core.model.InputPrompt
import mtgoracle.core.model.NumberPrompt
import mtgoracle.core.model.OrderPrompt
import mtgoracle.core.model.Prompt
import mtgoracle.core.model.StackKind
import mtgoracle.core.model.SideboardPrompt
import mtgoracle.ui.kit.CardChip
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.Emphasis
import mtgoracle.ui.kit.GridText
import mtgoracle.ui.kit.cellHeight
import mtgoracle.ui.kit.BUTTON_ROWS
import mtgoracle.ui.kit.BigButton
import mtgoracle.ui.kit.ControlButton
import mtgoracle.ui.kit.cellWidth
import mtgoracle.ui.kit.clickTarget
import mtgoracle.ui.kit.face
import mtgoracle.ui.kit.fit
import mtgoracle.ui.kit.region
import mtgoracle.ui.kit.wrap
import mtgoracle.ui.theme.LocalCells
import mtgoracle.ui.theme.Palette

/**
 * The prompt box's fixed rows, in the left column between the two players'
 * zones (as MTGO puts it): the scrolling content, then two rows of buttons
 * (they wrap when the column is narrow) and one hint row, in its border.
 */
const val PROMPT_CONTENT_ROWS = 8
const val PROMPT_BUTTON_LINES = 2
const val PROMPT_ROWS = PROMPT_CONTENT_ROWS + PROMPT_BUTTON_LINES * BUTTON_ROWS + 1 + 2 // + buttons, hint, and the border

/** A prompt's button, a cell apart from the next; disabled ones are drawn dim and can't be clicked. */
@Composable
fun GridButton(label: String, target: ClickTarget, enabled: Boolean, onClick: (ClickTarget) -> Unit) {
    BigButton(label, target, enabled, onClick)
    GridText(" ")
}

/**
 * Everything the seat is being asked, in fixed rows: the question and its
 * options scroll inside [contentRows]; the buttons always sit in the same
 * rows (two lines of them, wrapping), and the hint on the one below —
 * whatever the prompt.
 *
 * Drawn twice for a question the choice box answers (Prompt.boxed): in the
 * box, which [answers] it, and in the left column, which only says it and
 * where to answer, so no option or button is clickable in two places.
 * [regionPrefix] names its regions (`prompt-content`, `choice-content`).
 * [cardOptions]: a choice among cards shows their frames ([cardMode]), and
 * [onCardHover] hears the one under the mouse.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PromptBody(
    prompt: Prompt?,
    board: BoardState?,
    interaction: Interaction,
    cols: Int,
    onClick: (ClickTarget) -> Unit,
    onHover: (ClickTarget?) -> Unit,
    /** Drawn at the right end of the hint row (the board's `[ concede ]`). */
    trailing: (@Composable () -> Unit)? = null,
    answers: Boolean = true,
    contentRows: Int = PROMPT_CONTENT_ROWS,
    regionPrefix: String = "prompt",
    cardOptions: Boolean = false,
    cardMode: CardMode = CardMode.ART,
    onCardHover: (CardFace) -> Unit = {},
) {
    val state = if (prompt != null && interaction.promptId == prompt.id) interaction else Interaction.start(prompt)
    Column(Modifier.fillMaxWidth()) {
        // The floating-mana warning takes the prompt's own rows, so nothing around it moves.
        val pending = state.pendingPass
        Box(Modifier.fillMaxWidth().cellHeight(contentRows).region("$regionPrefix-content")) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (pending != null) wrap(pending.question(), cols).forEach { GridText(fit(it, cols), color = Palette.accent, bold = true) }
                else if (!answers && prompt != null) {
                    wrap(prompt.message.trim(), cols).forEach { GridText(fit(it, cols)) }
                    GridText(fit("answer in the box", cols), color = Palette.accent)
                }
                else Content(prompt, board, state, cols, contentRows, onClick, onHover, if (cardOptions) cardMode else null, onCardHover)
            }
        }
        FlowRow(Modifier.fillMaxWidth().cellHeight(PROMPT_BUTTON_LINES * BUTTON_ROWS).region("$regionPrefix-buttons")) {
            if (pending != null) { GridButton("Pass", ManaWarningTargets.PASS, true, onClick); GridButton("Stay", ManaWarningTargets.STAY, true, onClick) }
            else if (answers) Buttons(prompt, state, onClick)
        }
        Row(Modifier.fillMaxWidth().cellHeight(1)) {
            val hintText = if (pending != null) "Enter: pass, and the mana is lost · Esc: stay, with priority and the mana" else hint(prompt)
            GridText(fit(hintText, cols - if (trailing != null) 14 else 0), color = Palette.dim)
            trailing?.invoke()
        }
    }
}

@Composable
private fun Content(
    prompt: Prompt?, board: BoardState?, state: Interaction, cols: Int, contentRows: Int, onClick: (ClickTarget) -> Unit, onHover: (ClickTarget?) -> Unit,
    /** Cards among the options are drawn in frames of this mode; null, by name. */
    cardMode: CardMode? = null, onCardHover: (CardFace) -> Unit = {},
) {
    if (prompt == null) {
        val text = when {
            board == null -> "Starting the game…"
            board.gameOver -> "Game over: ${board.result}."
            board.seat == null -> "Watching AI vs AI."
            else -> "Waiting for the opponent…"
        }
        GridText(fit(text, cols), color = Palette.dim)
        return
    }
    // MTGO's stop on an opponent's action says what they did, before Forge's own line.
    val action = opponentAction(prompt, board)
    // Wrapped in the narrow column, as the question is: cut, "AI activated Polluted Delta — respond?" lost its last words.
    val actionLines = action?.let { wrap(it, cols) }.orEmpty()
    actionLines.forEach { GridText(fit(it, cols), color = Palette.accent, bold = true) }
    val message = wrap(prompt.message.trim(), cols)
    message.forEach { GridText(fit(it, cols)) }
    // Options fill the rows left in columns, so a library search is seen at a glance; past that, it scrolls.
    val rowsLeft = maxOf(1, contentRows - message.size - actionLines.size)
    when (prompt) {
        is InputPrompt -> if (prompt.selectableElsewhere.isNotEmpty()) {
            // Cards the board doesn't draw (a library being searched, Future Sight's top card): as chips, hover for the card.
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                GridText(if (prompt.kind == InputKind.PRIORITY) "playable from elsewhere: " else "choose from: ", color = Palette.dim)
                prompt.selectableElsewhere.forEach { card -> CardChip(card.face(), Emphasis.SELECTABLE, ClickTarget.Card(card.id), onClick, onHover); GridText(" ") }
            }
        }
        is ConfirmPrompt -> {}
        is SideboardPrompt -> sideboardValidity(prompt, state.deck).let { (line, ok) -> wrap(line, cols).forEach { GridText(fit(it, cols), color = if (ok) Palette.foreground else Palette.accent, bold = !ok) } }
        is ChoicePrompt -> if (cardMode != null && prompt.options.any { it.card != null }) CardOptions(prompt, state, cardMode, onClick, onCardHover)
        else if (prompt.searchable) SearchedOptions(prompt, state, cols, rowsLeft, onClick, onHover) else OptionGrid(prompt.labels, cols, rowsLeft) { i, width ->
            val mark = when {
                prompt.isReveal -> "  "
                prompt.max > 1 -> if (i in state.picks) "[x]" else "[ ]"
                else -> "  "
            }
            val line = fit(" $mark ${i + 1}. ${prompt.options[i].label}", width)
            if (prompt.isReveal) GridText(line)
            else GridText(line, Modifier.clickTarget(ClickTarget.Option(i), onClick, onHover), color = if (i in state.picks) Palette.accent else Palette.foreground)
        }
        is OrderPrompt -> OptionGrid(prompt.items.map { it.label }, cols, rowsLeft) { i, width ->
            val position = state.picks.indexOf(i).takeIf { it >= 0 }?.let { "(${it + 1})" } ?: " · "
            GridText(fit(" $position ${i + 1}. ${prompt.items[i].label}", width), Modifier.clickTarget(ClickTarget.Option(i), onClick, onHover),
                color = if (i in state.picks) Palette.accent else Palette.foreground)
        }
        is DistributePrompt -> {
            // The label column is as wide as the longest target, so the [-]/[+] line up; 30 cells stay for them.
            val labelCols = maxOf(8, minOf((prompt.targets.maxOfOrNull { it.label.length } ?: 0) + 6, cols - 30))
            prompt.targets.forEachIndexed { i, target ->
                Row {
                    GridText(if (i == state.row) ">" else " ", color = Palette.accent)
                    GridText(fit(" ${i + 1}. ${target.label}", labelCols), Modifier.clickTarget(ClickTarget.Option(i), onClick, onHover))
                    ControlButton("[-]", ClickTarget.Less(i), true, onClick)
                    GridText(" %3d ".format(state.amounts.getOrElse(i) { 0 }), bold = true)
                    ControlButton("[+]", ClickTarget.More(i), true, onClick)
                    GridText(target.lethal?.let { "  lethal $it" } ?: "", color = Palette.dim)
                }
            }
            // What still keeps Done off, or the count when nothing does.
            val problem = prompt.problem(state.amounts)
            GridText(fit(problem ?: "assigned ${state.amounts.sum()} of ${prompt.total}", cols), color = if (problem == null) Palette.foreground else Palette.accent)
        }
        is NumberPrompt -> Row {
            ControlButton("[-]", ClickTarget.Less(0), true, onClick)
            GridText(" %4s ".format(state.digits.ifEmpty { prompt.default().toString() }), bold = true)
            ControlButton("[+]", ClickTarget.More(0), true, onClick)
            GridText("   ${prompt.min}–${if (prompt.max == Int.MAX_VALUE) "any" else prompt.max}" + (prompt.note?.let { " · $it" } ?: ""), color = Palette.dim)
        }
    }
}

/**
 * A choice among cards (a land from a fetch, a card from a library), in
 * their frames: a click picks one, as its row would; a picked one is marked
 * `√`. An option that is no card is its name.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CardOptions(prompt: ChoicePrompt, state: Interaction, mode: CardMode, onClick: (ClickTarget) -> Unit, onCardHover: (CardFace) -> Unit) {
    val gap = with(LocalDensity.current) { LocalCells.current.width.toDp() }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(gap)) {
        prompt.options.forEachIndexed { i, option ->
            val card = option.card
            if (card == null) GridText(" ${i + 1}. ${option.label} ", Modifier.clickTarget(ClickTarget.Option(i), onClick), color = if (i in state.picks) Palette.accent else Palette.foreground)
            else {
                val face = card.face()
                CardFrame(face, mode, Emphasis.SELECTABLE, ClickTarget.Option(i), onClick, { onCardHover(face) },
                    mark = PICKED_MARK.takeIf { i in state.picks }, tier = FrameSize.tierOf(mode))
            }
        }
    }
}

@Composable
private fun Buttons(prompt: Prompt?, state: Interaction, onClick: (ClickTarget) -> Unit) {
    when (prompt) {
        null -> {}
        is InputPrompt -> {
            if (prompt.okLabel.isNotBlank()) GridButton(prompt.okLabel, ClickTarget.Ok, prompt.okEnabled, onClick)
            if (prompt.cancelLabel.isNotBlank()) GridButton(prompt.cancelLabel, ClickTarget.Cancel, prompt.cancelEnabled, onClick)
        }
        is ConfirmPrompt -> { GridButton(prompt.yesLabel, ClickTarget.Ok, true, onClick); GridButton(prompt.noLabel, ClickTarget.Cancel, true, onClick) }
        is ChoicePrompt -> when {
            prompt.isReveal -> GridButton("OK", ClickTarget.Done, true, onClick)
            prompt.max > 1 -> GridButton("Done (${state.picks.size})", ClickTarget.Done, state.picks.size in prompt.min..prompt.max, onClick)
            prompt.min == 0 -> GridButton("None", ClickTarget.Done, true, onClick)
        }
        is OrderPrompt -> GridButton("Done", ClickTarget.Done, true, onClick)
        is DistributePrompt -> GridButton("Done", ClickTarget.Done, prompt.problem(state.amounts) == null, onClick)
        is NumberPrompt -> { GridButton("OK", ClickTarget.Done, true, onClick); if (prompt.cancellable) GridButton("Cancel", ClickTarget.Cancel, true, onClick) }
        is SideboardPrompt -> GridButton("Done", ClickTarget.Done, state.deck.values.sum() >= prompt.minMain, onClick)
    }
}

/** "main 60 (at least 60) · sideboard 15": whether the deck can be played, before Done is pressed. */
fun sideboardValidity(prompt: SideboardPrompt, deck: Map<String, Int>): Pair<String, Boolean> {
    val main = deck.values.sum()
    val side = (prompt.main + prompt.side).sumOf { it.count } - main
    val ok = main >= prompt.minMain
    return "main $main (at least ${prompt.minMain}) · sideboard $side" + (if (ok) "" else " · too few cards to play") to ok
}

/**
 * A [searchable] choice: the filter on its own line, then only the matches
 * that fit the rows left, in columns, each clicked as its own option.
 */
@Composable
private fun SearchedOptions(prompt: ChoicePrompt, state: Interaction, cols: Int, rows: Int, onClick: (ClickTarget) -> Unit, onHover: (ClickTarget?) -> Unit) {
    // Folded once per prompt, matched once per filter: not on every recomposition, over thirty thousand names.
    val folded = remember(prompt.id) { prompt.foldedLabels() }
    val matches = remember(prompt.id, state.filter) { prompt.matches(state.filter, folded) }
    val shownRows = maxOf(1, rows - 1)
    // OptionGrid's own column width (" [x] " and room), so exactly what fits is taken.
    val width = minOf(cols, maxOf(20, (matches.take(500).maxOfOrNull { prompt.options[it].label.length } ?: 0) + 9 + 1)).coerceAtLeast(1)
    val shown = matches.take(shownRows * maxOf(1, cols / width))
    val more = if (matches.size > shown.size) " · ${matches.size - shown.size} more: type to narrow" else ""
    GridText(fit("find: ${state.filter}_   ${matches.size} of ${prompt.options.size}$more", cols), color = Palette.accent, bold = true)
    OptionGrid(shown.map { prompt.options[it].label }, cols, shownRows) { i, w ->
        val option = shown[i]
        val mark = if (prompt.max > 1 && option in state.picks) "[x]" else "  "
        GridText(fit(" $mark ${prompt.options[option].label}", w), Modifier.clickTarget(ClickTarget.Option(option), onClick, onHover),
            color = if (option in state.picks) Palette.accent else Palette.foreground)
    }
}

/**
 * [labels] as columns of [rows] lines, as many columns as fit [cols] (each
 * wide enough for the longest label, up to the whole width); a list longer
 * than that continues in a second band below, which the pane scrolls to.
 */
@Composable
private fun OptionGrid(labels: List<String>, cols: Int, rows: Int, cell: @Composable (index: Int, width: Int) -> Unit) {
    // The width actually given, not the one computed for the pane: a column that doesn't fit is clipped away.
    BoxWithConstraints {
        val room = minOf(cols, LocalCells.current.cols(constraints.maxWidth.toFloat()))
        // " [x] 12. " is nine cells before the label.
        val width = minOf(room, maxOf(20, (labels.maxOfOrNull { it.length } ?: 0) + 9 + 1)).coerceAtLeast(1) // a pane under a cell wide (a tiny window) divided by zero
        val perBand = rows * maxOf(1, room / width)
        Column {
            labels.indices.chunked(perBand).forEach { band ->
                Row { band.chunked(rows).forEach { column -> Column(Modifier.cellWidth(width)) { column.forEach { cell(it, width) } } } }
            }
        }
    }
}

/** "AI activated Polluted Delta — respond?" when you hold priority over something the other side put on the stack. */
fun opponentAction(prompt: Prompt?, board: BoardState?): String? {
    if ((prompt as? InputPrompt)?.kind != InputKind.PRIORITY || board == null) return null
    val top = board.stack.firstOrNull() ?: return null
    val seat = board.seat ?: return null
    if (top.controllerId == seat.id) return null
    val who = board.players.firstOrNull { it.id == top.controllerId }?.name?.substringBefore(" (") ?: top.controllerName
    val what = when (top.kind) {
        StackKind.SPELL -> "cast ${top.sourceName}"
        StackKind.ACTIVATED -> "activated ${top.sourceName}"
        StackKind.TRIGGERED -> "'s ${top.sourceName} triggered"
    }
    val targets = if (top.targetNames.isEmpty()) "" else " targeting ${top.targetNames.joinToString(", ")}"
    return (if (top.kind == StackKind.TRIGGERED) "$who$what" else "$who $what") + "$targets — respond?"
}

private fun hint(prompt: Prompt?): String = when (prompt) {
    null -> ""
    is InputPrompt -> {
        val what = when (prompt.kind) {
            InputKind.PRIORITY -> "click a card to play it · F2 pass · F4 end turn · F6 skip turn"
            InputKind.TARGET -> "click a highlighted card, or a player's name"
            InputKind.PAY_MANA -> "click a land or source to tap it, or Enter for ${prompt.okLabel}"
            InputKind.ATTACK -> "click your creatures to attack, Enter when done"
            InputKind.BLOCK -> "click an attacker, then your creature to block it; Enter when done"
            InputKind.SELECT_CARDS -> "click cards to pick them, Enter when done"
            else -> "click a card or a player"
        }
        "$what · Enter ${prompt.okLabel.ifBlank { "-" }} · Esc ${prompt.cancelLabel.ifBlank { "-" }}"
    }
    is ConfirmPrompt -> "Enter ${prompt.yesLabel} · Esc ${prompt.noLabel}"
    is ChoicePrompt -> if (prompt.isReveal) "shown for information · Enter to continue"
        else if (prompt.searchable) "type part of a name to narrow · Backspace erases · click one${if (prompt.max <= 1) ", or Enter for the first" else " · Enter done"}"
        else "choose ${if (prompt.min == prompt.max) "${prompt.min}" else "${prompt.min}–${prompt.max}"} · click, or 1–9 · Enter done"
    is OrderPrompt -> "click in order, ${prompt.firstLabel} first; unpicked keep their order after · Enter done · Esc clear"
    is DistributePrompt -> "1–9 pick a row · + / - adjust · Enter done"
    is NumberPrompt -> "type a number · + / - · Enter OK · Esc cancel"
    is SideboardPrompt -> "click a card to move one copy between deck and sideboard · Enter done"
}
