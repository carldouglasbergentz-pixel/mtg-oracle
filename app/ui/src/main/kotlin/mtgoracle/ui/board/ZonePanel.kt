package mtgoracle.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import mtgoracle.core.model.CardState
import mtgoracle.core.model.PlayerState
import mtgoracle.ui.kit.Border
import mtgoracle.ui.kit.CardFrame
import mtgoracle.ui.kit.CardMode
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.FrameSize
import mtgoracle.ui.kit.GridText
import mtgoracle.ui.kit.boxBorder
import mtgoracle.ui.kit.cells
import mtgoracle.ui.kit.chromeShape
import mtgoracle.ui.kit.clickTarget
import mtgoracle.ui.kit.face
import mtgoracle.ui.kit.region
import mtgoracle.ui.theme.LocalCells
import mtgoracle.ui.theme.Palette

/** A zone a floating panel shows: a player's graveyard, or their exile (with the command zone, as the zone column lists it). */
enum class PileZone(val word: String) { GRAVEYARD("graveyard"), EXILE("exile") }

/** Which panel: the side of the table it belongs to, and the zone. */
data class ZonePanelKey(val far: Boolean, val zone: PileZone) {
    val id: String get() = "${if (far) "far" else "near"}-${zone.word}"
    /** The zone column's header for this zone: a click opens or closes the panel. */
    val toggle: ClickTarget get() = ClickTarget.Control("zone-panel:$id")
    val close: ClickTarget get() = ClickTarget.Control("zone-panel-close:$id")
}

/** A zone's cards as its zone column lists them: the graveyard's top card first; the command zone, then exile. */
fun panelCards(player: PlayerState, zone: PileZone): List<CardState> = when (zone) {
    PileZone.GRAVEYARD -> player.graveyard.asReversed()
    PileZone.EXILE -> player.command + player.exile
}

/** A panel's cards from the top of its cascade down: the graveyard's oldest first, so the card put there last lies whole at the bottom. */
fun cascadeCards(player: PlayerState, zone: PileZone): List<CardState> = panelCards(player, zone).let { if (zone == PileZone.GRAVEYARD) it.asReversed() else it }

/** Rows of a card the next one leaves showing: its top edge, where its name is. */
const val CASCADE_STRIP = 1

/** A panel's width in cells: one card, in the table's card-shaped frame, and the border. */
fun zonePanelCols(mode: CardMode): Int = 2 + FrameSize.cols(FrameSize.tierOf(mode), land = true)

/** A panel's height in cells: the cascade (a strip a card, the last whole) and the border, at most [maxRows] (then it scrolls). */
fun zonePanelRows(count: Int, mode: CardMode, maxRows: Int): Int =
    minOf(maxRows, 2 + maxOf(0, count - 1) * CASCADE_STRIP + FrameSize.rows(mode)).coerceAtLeast(3)

/**
 * A player's graveyard or exile, popped out over the table as MTGO does:
 * a cascade down, each card over the one before with its name showing, the
 * last put there whole at the bottom. A name is hovered and clicked as the
 * card. It floats like the stack
 * box (takes no layout space), is dragged by its top edge and closed by the
 * `×` at its right end, or by the zone column's header that opened it. A
 * card in it clicks and hovers as on the table, so a prompt that wants a
 * card from the graveyard can be answered here. It shows only what the
 * snapshot holds: a face-down exiled card is its back.
 */
@Composable
fun ZonePanel(
    key: ZonePanelKey,
    player: PlayerState,
    looks: Looks,
    rows: Int,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cells = LocalCells.current
    val density = LocalDensity.current
    val cards = cascadeCards(player, key.zone)
    val cols = zonePanelCols(looks.mode)
    val inner = cols - 2
    // One card wide: the zone and its count, and whose when it is not yours; the whole top edge but the × is the handle.
    val title = "${key.zone.word} ${cards.size}" + if (player.isSeat) "" else " · ${player.name.substringBefore(" (")}"
    Box(
        modifier.cells(cols, rows).chromeShape().background(Palette.background)
            .boxBorder(title, right = "×", border = Border.DOUBLE, color = Palette.accent)
            // Solid to the pointer as well as the eye: a click on the panel never reaches the card beneath.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .region("zone-panel-${key.id}"),
    ) {
        // The top edge is the handle, but for its last cells: the `×` (the border's right label ends a cell from the corner).
        val drag by rememberUpdatedState(onDrag)
        val dragEnd by rememberUpdatedState(onDragEnd)
        Box(Modifier.cells(cols - 4, 1).region("zone-panel-${key.id}-handle").pointerInput(Unit) {
            detectDragGestures(onDragEnd = { dragEnd() }, onDragCancel = { dragEnd() }) { change, amount -> change.consume(); drag(amount) }
        })
        Box(Modifier.offset(x = with(density) { ((cols - 4) * cells.width).toDp() }).cells(4, 1).clickTarget(key.close, looks.onClick))
        Box(Modifier.padding(with(density) { cells.width.toDp() }, with(density) { cells.height.toDp() }).cells(inner, rows - 2)) {
            if (cards.isEmpty()) GridText("(empty)", color = Palette.dim)
            val tier = FrameSize.tierOf(looks.mode)
            val frameRows = FrameSize.rows(tier)
            // Opened at the bottom, where the card put there last lies whole.
            val scroll = rememberScrollState()
            LaunchedEffect(cards.size) { scroll.scrollTo(scroll.maxValue) }
            Box(Modifier.verticalScroll(scroll)) {
                Box(Modifier.cells(inner, maxOf(0, cards.size - 1) * CASCADE_STRIP + frameRows)) {
                    cards.forEachIndexed { i, card ->
                        val last = i == cards.lastIndex
                        val y = with(density) { (i * CASCADE_STRIP * cells.height).toDp() }
                        // Each card over the one before; only the last is clicked by its frame, the others by their strip.
                        Box(Modifier.offset(y = y).zIndex(i.toFloat())) {
                            CardFrame(
                                card.face(), looks.mode, looks.emphasis(card), if (last) ClickTarget.Card(card.id) else null, looks.onClick, looks.onHover,
                                land = true, mark = looks.mark(card), tier = tier,
                            )
                        }
                        if (!last) Box(
                            Modifier.offset(y = y).zIndex(1000f + i).cells(inner, CASCADE_STRIP)
                                .clickTarget(ClickTarget.Card(card.id), looks.onClick, looks.onHover),
                        )
                    }
                }
            }
        }
    }
}
