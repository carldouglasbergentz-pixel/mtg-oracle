package mtgoracle.ui.board

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
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
import mtgoracle.ui.kit.CardMode
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
 * The prompt pane's fixed rows: the scrolling content, then the buttons and one hint row.
 * The big buttons took their second row from the content, not from the table: the crowded board must still fit 1280x720 (ZonesTest).
 */
const val PROMPT_CONTENT_ROWS = 4
const val PROMPT_ROWS = PROMPT_CONTENT_ROWS + BUTTON_ROWS + 1 + 2 // + buttons, hint, and the border

/** A prompt's button, a cell apart from the next; disabled ones are drawn dim and can't be clicked. */
@Composable
fun GridButton(label: String, target: ClickTarget, enabled: Boolean, onClick: (ClickTarget) -> Unit) {
    BigButton(label, target, enabled, onClick)
    GridText(" ")
}

/**
 * Everything the seat is being asked, in fixed rows: the question and its
 * options scroll inside [PROMPT_CONTENT_ROWS]; the buttons always sit on the
 * same row, and the hint on the one below — whatever the prompt.
 */
@Composable
fun PromptBody(
    prompt: Prompt?,
    board: BoardState?,
    interaction: Interaction,
    cols: Int,
    mode: CardMode,
    onClick: (ClickTarget) -> Unit,
    onHover: (ClickTarget?) -> Unit,
    /** Drawn at the right end of the hint row (the board's `[ concede ]`). */
    trailing: (@Composable () -> Unit)? = null,
) {
    val state = if (prompt != null && interaction.promptId == prompt.id) interaction else Interaction.start(prompt)
    Column(Modifier.fillMaxWidth()) {
        // The floating-mana warning takes the prompt's own rows, so nothing around it moves.
        val pending = state.pendingPass
        Box(Modifier.fillMaxWidth().cellHeight(PROMPT_CONTENT_ROWS).region("prompt-content")) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (pending != null) wrap(pending.question(), cols).forEach { GridText(fit(it, cols), color = Palette.accent, bold = true) }
                else Content(prompt, board, state, cols, onClick, onHover)
            }
        }
        Row(Modifier.fillMaxWidth().cellHeight(BUTTON_ROWS).region("prompt-buttons")) {
            if (pending != null) { GridButton("Pass", ManaWarningTargets.PASS, true, onClick); GridButton("Stay", ManaWarningTargets.STAY, true, onClick) }
            else Buttons(prompt, state, onClick)
        }
        Row(Modifier.fillMaxWidth().cellHeight(1)) {
            val hintText = if (pending != null) "Enter: pass, and the mana is lost · Esc: stay, with priority and the mana" else hint(prompt)
            GridText(fit(hintText, cols - if (trailing != null) 14 else 0), color = Palette.dim)
            trailing?.invoke()
        }
    }
}

@Composable
private fun Content(prompt: Prompt?, board: BoardState?, state: Interaction, cols: Int, onClick: (ClickTarget) -> Unit, onHover: (ClickTarget?) -> Unit) {
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
    action?.let { GridText(fit(it, cols), color = Palette.accent, bold = true) }
    val message = wrap(prompt.message.trim(), cols)
    message.forEach { GridText(fit(it, cols)) }
    // Options fill the rows left in columns, so a library search is seen at a glance; past that, it scrolls.
    val rowsLeft = maxOf(1, PROMPT_CONTENT_ROWS - message.size - (if (action != null) 1 else 0))
    when (prompt) {
        is InputPrompt -> if (prompt.selectableElsewhere.isNotEmpty()) {
            // Cards the board doesn't draw (a library being searched): as chips, hover for the card.
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                GridText("choose from: ", color = Palette.dim)
                prompt.selectableElsewhere.forEach { card -> CardChip(card.face(), Emphasis.SELECTABLE, ClickTarget.Card(card.id), onClick, onHover); GridText(" ") }
            }
        }
        is ConfirmPrompt -> {}
        is SideboardPrompt -> sideboardValidity(prompt, state.deck).let { (line, ok) -> GridText(fit(line, cols), color = if (ok) Palette.foreground else Palette.accent, bold = !ok) }
        is ChoicePrompt -> OptionGrid(prompt.labels, cols, rowsLeft) { i, width ->
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
                    GridText("[-]", Modifier.clickTarget(ClickTarget.Less(i), onClick), color = Palette.background, background = Palette.foreground)
                    GridText(" %3d ".format(state.amounts.getOrElse(i) { 0 }), bold = true)
                    GridText("[+]", Modifier.clickTarget(ClickTarget.More(i), onClick), color = Palette.background, background = Palette.foreground)
                    GridText(target.lethal?.let { "  lethal $it" } ?: "", color = Palette.dim)
                }
            }
            val sum = state.amounts.sum()
            GridText(fit("assigned $sum of ${prompt.total}", cols), color = if (sum == prompt.total) Palette.foreground else Palette.accent)
        }
        is NumberPrompt -> Row {
            ControlButton("[-]", ClickTarget.Less(0), true, onClick)
            GridText(" %4s ".format(state.digits.ifEmpty { prompt.default().toString() }), bold = true)
            ControlButton("[+]", ClickTarget.More(0), true, onClick)
            GridText("   ${prompt.min}–${if (prompt.max == Int.MAX_VALUE) "any" else prompt.max}" + (prompt.note?.let { " · $it" } ?: ""), color = Palette.dim)
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
        is DistributePrompt -> GridButton("Done", ClickTarget.Done, state.amounts.sum() == prompt.total, onClick)
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
        val width = minOf(room, maxOf(20, (labels.maxOfOrNull { it.length } ?: 0) + 9 + 1))
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
        else "choose ${if (prompt.min == prompt.max) "${prompt.min}" else "${prompt.min}–${prompt.max}"} · click, or 1–9 · Enter done"
    is OrderPrompt -> "click in order, ${prompt.firstLabel} first; unpicked keep their order after · Enter done · Esc clear"
    is DistributePrompt -> "1–9 pick a row · + / - adjust · Enter done"
    is NumberPrompt -> "type a number · + / - · Enter OK · Esc cancel"
    is SideboardPrompt -> "click a card to move one copy between deck and sideboard · Enter done"
}
