package mtgoracle.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mtgoracle.model.BoardState
import mtgoracle.model.ChoicePrompt
import mtgoracle.model.ConfirmPrompt
import mtgoracle.model.GameSeat
import mtgoracle.model.InputPrompt
import mtgoracle.model.Prompt
import mtgoracle.model.SeatAction

/** Two tones and one accent (docs/app-design.md). */
object Palette {
    val background = Color(0xFF111315)
    val foreground = Color(0xFFD7D7D2)
    val dim = Color(0xFF7C8084)
    val accent = Color(0xFFE2B350)
}

val gridStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    lineHeight = 16.sp,
    color = Palette.foreground,
)

/** The live board for one seat: collects its flows and turns clicks into answers. */
@Composable
fun LiveBoardScreen(seat: GameSeat?, title: String, onLaidOut: ((LaidOutGrid) -> Unit)? = null) {
    val board by (seat?.board ?: remember { kotlinx.coroutines.flow.MutableStateFlow<BoardState?>(null) }).collectAsState()
    val prompt by (seat?.prompt ?: remember { kotlinx.coroutines.flow.MutableStateFlow<Prompt?>(null) }).collectAsState()
    var chosen by remember(prompt?.id) { mutableStateOf(emptySet<Int>()) }

    fun onTarget(target: ClickTarget) {
        val p = prompt ?: return
        val action: SeatAction? = when (p) {
            is InputPrompt -> when (target) {
                is ClickTarget.Card -> SeatAction.ClickCard(target.id)
                is ClickTarget.Player -> SeatAction.ClickPlayer(target.id)
                ClickTarget.Ok -> SeatAction.Ok
                ClickTarget.Cancel -> SeatAction.Cancel
                else -> null
            }
            is ConfirmPrompt -> when (target) {
                ClickTarget.Ok -> SeatAction.Confirm(true)
                ClickTarget.Cancel -> SeatAction.Confirm(false)
                else -> null
            }
            is ChoicePrompt -> when (target) {
                is ClickTarget.Option ->
                    if (p.max > 1) { chosen = if (target.index in chosen) chosen - target.index else chosen + target.index; null }
                    else SeatAction.Choose(listOf(target.index))
                ClickTarget.Done -> SeatAction.Choose(if (p.isReveal) emptyList() else chosen.sorted())
                else -> null
            }
        }
        if (action != null && seat != null) seat.answer(p.id, action)
    }

    fun onKey(key: Key): Boolean {
        val p = prompt ?: return false
        val target = when (key) {
            Key.Enter, Key.NumPadEnter -> if (p is ChoicePrompt) ClickTarget.Done else ClickTarget.Ok
            Key.Escape -> ClickTarget.Cancel
            else -> DIGITS[key]?.let { ClickTarget.Option(it) }
        } ?: return false
        onTarget(target)
        return true
    }

    BoardFrame(board, prompt, chosen, title, ::onTarget, ::onKey, onLaidOut = onLaidOut)
}

private val DIGITS = mapOf(
    Key.One to 0, Key.Two to 1, Key.Three to 2, Key.Four to 3, Key.Five to 4,
    Key.Six to 5, Key.Seven to 6, Key.Eight to 7, Key.Nine to 8,
)

/** Where a grid ended up on screen: lets an offscreen driver click a span like a mouse would. */
class LaidOutGrid(val grid: TextGrid, val promptId: Long?, val cellWidth: Float, val layout: TextLayoutResult, val originPx: Float) {
    fun centreOf(span: Span): androidx.compose.ui.geometry.Offset = androidx.compose.ui.geometry.Offset(
        originPx + (span.start + 0.5f) * cellWidth,
        originPx + (layout.getLineTop(span.line) + layout.getLineBottom(span.line)) / 2,
    )
}

/** Stateless: measures the character cell, lays the grid out to the window's width. */
@Composable
fun BoardFrame(
    board: BoardState?,
    prompt: Prompt?,
    chosen: Set<Int>,
    title: String,
    onTarget: (ClickTarget) -> Unit = {},
    onKey: (Key) -> Boolean = { false },
    scrollable: Boolean = true,
    onLaidOut: ((LaidOutGrid) -> Unit)? = null,
) {
    val measurer = rememberTextMeasurer()
    val paddingPx = with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.toPx() }
    val cellWidth = remember(measurer) { measurer.measure("M".repeat(100), gridStyle).size.width / 100f }
    val focus = remember { FocusRequester() }
    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.background).padding(8.dp)) {
        val columns = (constraints.maxWidth / cellWidth).toInt()
        val grid = remember(board, prompt, chosen, columns) { BoardText.render(board, prompt, columns, chosen, title) }
        var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
        val base = Modifier
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { it.type == KeyEventType.KeyDown && onKey(it.key) }
            .pointerInput(grid) {
                detectTapGestures { offset ->
                    val l = layout ?: return@detectTapGestures
                    val line = l.getLineForVerticalPosition(offset.y)
                    val column = (offset.x / cellWidth).toInt()
                    grid.targetAt(line, column)?.let(onTarget)
                }
            }
        BasicText(
            text = annotate(grid),
            style = gridStyle,
            softWrap = false,
            onTextLayout = { layout = it; onLaidOut?.invoke(LaidOutGrid(grid, prompt?.id, cellWidth, it, paddingPx)) },
            modifier = if (scrollable) base.verticalScroll(rememberScrollState()) else base,
        )
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

private fun annotate(grid: TextGrid): AnnotatedString = buildAnnotatedString {
    val lineStarts = IntArray(grid.lines.size)
    var offset = 0
    grid.lines.forEachIndexed { i, line ->
        lineStarts[i] = offset
        append(line)
        if (i < grid.lines.lastIndex) append('\n')
        offset += line.length + 1
    }
    for (span in grid.spans) {
        val style = when (span.emphasis) {
            Emphasis.NONE -> null
            Emphasis.DIM -> SpanStyle(color = Palette.dim)
            Emphasis.HEADER -> SpanStyle(fontWeight = FontWeight.Bold)
            Emphasis.SELECTABLE -> SpanStyle(color = Palette.accent, fontWeight = FontWeight.Bold)
            Emphasis.ACTIONABLE -> SpanStyle(color = Palette.accent)
            Emphasis.BUTTON -> SpanStyle(color = Palette.background, background = Palette.foreground)
        } ?: continue
        val start = lineStarts[span.line] + span.start
        addStyle(style, start, lineStarts[span.line] + span.end)
    }
}
