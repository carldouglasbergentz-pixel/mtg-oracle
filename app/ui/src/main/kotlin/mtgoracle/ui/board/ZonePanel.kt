package mtgoracle.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
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

/** A panel's cards, in the zone column's order: the graveyard's top card first; the command zone, then exile. */
fun panelCards(player: PlayerState, zone: PileZone): List<CardState> = when (zone) {
    PileZone.GRAVEYARD -> player.graveyard.asReversed()
    PileZone.EXILE -> player.command + player.exile
}

/** The most frames a panel lays side by side. */
const val PANEL_PER_ROW = 4

/** A panel's width in cells for [count] cards: one frame a card up to [PANEL_PER_ROW], the border, a cell between. */
fun zonePanelCols(count: Int, mode: CardMode): Int {
    val across = count.coerceIn(1, PANEL_PER_ROW)
    return 2 + across * (FrameSize.cols(mode) + 1) - 1
}

/** A panel's height in cells: its rows of frames and the border, at most [maxRows] (then it scrolls). */
fun zonePanelRows(count: Int, mode: CardMode, maxRows: Int): Int {
    val lines = maxOf(1, (count + PANEL_PER_ROW - 1) / PANEL_PER_ROW)
    return minOf(maxRows, 2 + lines * FrameSize.rows(mode)).coerceAtLeast(3)
}

/**
 * A player's graveyard or exile, popped out over the table: every card in
 * its frame, as the zone column only names them. It floats like the stack
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
    val cards = panelCards(player, key.zone)
    val cols = zonePanelCols(cards.size, looks.mode)
    val inner = cols - 2
    val who = if (player.isSeat) "your" else "${player.name.substringBefore(" (")}'s"
    Box(
        modifier.cells(cols, rows).chromeShape().background(Palette.background)
            .boxBorder("$who ${key.zone.word} · ${cards.size}", right = "drag · ×", border = Border.DOUBLE, color = Palette.accent)
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
            Column(Modifier.verticalScroll(rememberScrollState())) {
                cards.chunked(PANEL_PER_ROW).forEach { line ->
                    Row {
                        line.forEachIndexed { i, card ->
                            if (i > 0) Spacer(Modifier.width(with(density) { cells.width.toDp() }))
                            CardFrame(
                                card.face(), looks.mode, looks.emphasis(card), ClickTarget.Card(card.id), looks.onClick, looks.onHover,
                                mark = looks.mark(card), tier = FrameSize.tierOf(looks.mode),
                            )
                        }
                    }
                }
            }
        }
    }
}
