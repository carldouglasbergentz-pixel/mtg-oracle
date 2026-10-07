package mtgoracle.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import mtgoracle.ui.kit.Border
import mtgoracle.ui.kit.ClickRegistry
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.GridText
import mtgoracle.ui.kit.LinkButton
import mtgoracle.ui.kit.WrapText
import mtgoracle.ui.kit.boxBorder
import mtgoracle.ui.kit.cellWidth
import mtgoracle.ui.kit.chromeShape
import mtgoracle.ui.kit.region
import mtgoracle.ui.theme.LocalCells
import mtgoracle.ui.theme.Palette
import kotlin.math.roundToInt

/** One stop of the tour: the region it points at (none: the middle of the window), and what it says. */
data class TourStop(val region: String?, val title: String, val text: String)

/** The library's tour, first time only and from the checklist's "Show me around". */
val LIBRARY_TOUR = listOf(
    TourStop(null, "Welcome to MTG Oracle",
        "Every card, ruling and rule, your decks with their analysis, and games against Forge's AI, on your own machine. " +
            "Four things to know, then the checklist in the middle takes you to your first game."),
    TourStop("library-decks", "Your decks",
        "By folder. A click shows a deck in the middle with its analysis; a double-click (or Enter) opens it to build. Right-click a deck or a folder for the rest."),
    TourStop("toolbar", "The toolbar",
        "New deck, and Import: a list (Moxfield, Archidekt, MTGO, Arena) or a .mtgoracle package from the clipboard. Play opens the lobby, Sync fetches the card data, Guide brings this back."),
    TourStop("command-line", "The command line",
        ": or Ctrl+K. Type a card's name, or search in Scryfall's style (t:instant c:u mv<=2), or ask for a rule (rule 702.19). `help` lists every command."),
    TourStop("library-zoom", "The zoom pane",
        "Hover any card, anywhere (a deck, a search, the log, the board), and it is shown here, art and text."),
)

/** Columns the tour's box takes. */
private const val BOX_COLS = 56

/**
 * The tour over the library: a box beside what each stop points at, the
 * target outlined, Next and Skip. Regions are read where they were drawn
 * ([registry]); a stop whose region isn't on screen sits in the middle.
 */
@Composable
internal fun TourOverlay(stops: List<TourStop>, registry: ClickRegistry, onDone: () -> Unit) {
    var at by remember { mutableIntStateOf(0) }
    val stop = stops.getOrNull(at) ?: return
    val cells = LocalCells.current
    var origin by remember { mutableStateOf(Rect.Zero) }
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { origin = it.boundsInWindow() }) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val target = stop.region?.let { registry[ClickTarget.Control("region:$it")] }?.translate(-origin.left, -origin.top)
        // The target outlined: the box points at it.
        if (target != null) with(LocalDensity.current) {
            Box(
                Modifier.offset { IntOffset(target.left.roundToInt(), target.top.roundToInt()) }
                    .size(target.width.toDp(), target.height.toDp()).boxBorder(null, border = Border.DOUBLE, color = Palette.accent),
            )
        }
        val boxW = BOX_COLS * cells.width
        // Beside the target: right of it when there is room, else left of it, else over it; the middle with none.
        val x = when {
            target == null -> (w - boxW) / 2
            target.right + boxW + cells.width < w -> target.right + cells.width
            target.left - boxW - cells.width > 0 -> target.left - boxW - cells.width
            else -> ((target.left + target.right) / 2 - boxW / 2).coerceIn(0f, maxOf(0f, w - boxW))
        }
        val y = when {
            target == null -> h / 3
            target.height < h / 3 -> (target.bottom + cells.height).coerceAtMost(h - 12 * cells.height)
            else -> target.top + 2 * cells.height
        }.coerceAtLeast(0f)
        val pad = with(LocalDensity.current) { cells.width.toDp() }
        Box(
            Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }.cellWidth(BOX_COLS)
                .chromeShape().background(Palette.background).boxBorder("${at + 1} of ${stops.size}", border = Border.DOUBLE, color = Palette.accent)
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                .region("tour"),
        ) {
            Column(Modifier.padding(start = pad, end = pad, top = with(LocalDensity.current) { cells.height.toDp() }, bottom = with(LocalDensity.current) { cells.height.toDp() })) {
                WrapText(stop.title, bold = true)
                WrapText(stop.text)
                GridText("")
                Row {
                    val last = at == stops.lastIndex
                    LinkButton(if (last) "[ Done ]" else "[ Next ]", ClickTarget.Control("tour:next")) { if (last) onDone() else at++ }
                    if (!last) { GridText("  "); LinkButton("[ Skip the tour ]", ClickTarget.Control("tour:skip")) { onDone() } }
                }
            }
        }
    }
}

