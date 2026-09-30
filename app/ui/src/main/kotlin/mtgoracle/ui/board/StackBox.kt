package mtgoracle.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import mtgoracle.core.model.BoardRef
import mtgoracle.core.model.BoardState
import mtgoracle.core.model.StackEntry
import mtgoracle.core.model.StackKind
import mtgoracle.ui.kit.Border
import mtgoracle.ui.kit.CardFace
import mtgoracle.ui.kit.CardFrame
import mtgoracle.ui.kit.CardMode
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.Emphasis
import mtgoracle.ui.kit.FrameSize
import mtgoracle.ui.kit.GridText
import mtgoracle.ui.kit.boxBorder
import mtgoracle.ui.kit.cells
import mtgoracle.ui.kit.clickTarget
import mtgoracle.ui.kit.face
import mtgoracle.ui.kit.fit
import mtgoracle.ui.kit.region
import mtgoracle.ui.kit.wrap
import mtgoracle.ui.theme.LocalCells
import mtgoracle.ui.theme.Palette

/** The floating stack box's width, in cells. */
const val STACK_BOX_COLS = 56

/** Rows the expanded box takes for [items] entries, at most [maxRows]: a frame per item, a line between, the border. */
fun stackBoxRows(items: Int, mode: CardMode, maxRows: Int): Int =
    minOf(maxRows, 2 + items * FrameSize.rows(mode) + maxOf(0, items - 1)).coerceAtLeast(3)

/** The folded bar's text; its width is the bar's width. */
fun stackBarText(items: Int) = " stack: $items · S "

/** What the source of a stack item shows: the card as it is now (a sacrificed fetch included), upright. */
fun StackEntry.face(): CardFace = source?.copy(tapped = false)?.face() ?: CardFace(sourceName, "", "", "", text, imageKey)

/**
 * The stack, drawn over the table: newest on top, each item its source's
 * card frame beside who put it there (▲ across the table, ▼ you), what kind
 * of object it is, its text and its targets. Solid, bordered, and draggable
 * by its top edge; clicking an item picks it when a prompt wants a stack
 * object. It takes no layout space — see BoardScreen for where it floats.
 */
@Composable
fun StackBox(
    board: BoardState,
    farId: Int,
    mode: CardMode,
    picks: Set<BoardRef>,
    rows: Int,
    onClick: (ClickTarget) -> Unit,
    onHover: (ClickTarget?) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cells = LocalCells.current
    val density = LocalDensity.current
    val inner = STACK_BOX_COLS - 2
    val frameCols = FrameSize.cols(mode)
    val textCols = inner - frameCols - 1
    val frameRows = FrameSize.rows(mode)
    Box(
        modifier.cells(STACK_BOX_COLS, rows).background(Palette.background)
            .boxBorder("stack · ${board.stack.size} · top first", right = "drag · S folds", border = Border.DOUBLE, color = Palette.accent)
            // Solid to the pointer as well as the eye: a click on the panel never reaches the card beneath.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .region("stack-box"),
    ) {
        // The top edge is the handle. The gesture outlives the recompositions it causes, so it reads the latest callbacks.
        val drag by rememberUpdatedState(onDrag)
        val dragEnd by rememberUpdatedState(onDragEnd)
        Box(Modifier.cells(STACK_BOX_COLS, 1).region("stack-box-handle").pointerInput(Unit) {
            detectDragGestures(onDragEnd = { dragEnd() }, onDragCancel = { dragEnd() }) { change, amount -> change.consume(); drag(amount) }
        })
        Box(Modifier.padding(with(density) { cells.width.toDp() }, with(density) { cells.height.toDp() }).cells(inner, rows - 2)) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                board.stack.forEachIndexed { i, item ->
                    if (i > 0) GridText("─".repeat(inner), color = Palette.dim)
                    val theirs = item.controllerId == farId
                    val pick = BoardRef.StackItem(item.id) in picks
                    Row(Modifier.cells(inner, frameRows).clickTarget(ClickTarget.StackItem(item.id), onClick, onHover)) {
                        CardFrame(item.face(), mode, if (pick) Emphasis.SELECTABLE else Emphasis.NONE)
                        Spacer(Modifier.width(with(density) { cells.width.toDp() }))
                        Column(Modifier.cells(textCols, frameRows)) {
                            val who = board.players.firstOrNull { it.id == item.controllerId }?.name?.substringBefore(" (") ?: item.controllerName
                            val head = "${if (theirs) "▲" else "▼"} $who · ${kindLabel(item.kind)}" + if (i == 0) " · top" else ""
                            GridText(fit(head, textCols), color = if (theirs) Palette.accent else Palette.foreground, bold = true)
                            val body = wrap(item.text, textCols)
                            val room = frameRows - 2
                            body.take(room).forEachIndexed { n, line -> GridText(fit(if (n == room - 1 && body.size > room) line.dropLast(1) + "…" else line, textCols)) }
                            repeat(maxOf(0, room - body.size)) { GridText("") }
                            val targets = if (item.targetNames.isEmpty()) "" else "→ " + item.targetNames.joinToString(", ")
                            GridText(fit(targets, textCols), color = Palette.accent, bold = true)
                        }
                    }
                }
            }
        }
    }
}

/** The folded stack: one line on the header's rule, where no card lies. Click (or S) to open it. */
@Composable
fun StackBar(items: Int, onClick: (ClickTarget) -> Unit, modifier: Modifier = Modifier) {
    GridText(stackBarText(items), modifier.clickTarget(ClickTarget.Control("stack-bar"), onClick).region("stack-bar"),
        color = Palette.background, background = Palette.accent, bold = true)
}

private fun kindLabel(kind: StackKind) = when (kind) {
    StackKind.SPELL -> "spell"
    StackKind.ACTIVATED -> "activated ability"
    StackKind.TRIGGERED -> "triggered ability"
}
