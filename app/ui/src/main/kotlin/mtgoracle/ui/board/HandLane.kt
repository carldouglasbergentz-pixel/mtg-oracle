package mtgoracle.ui.board

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import mtgoracle.core.model.PlayerState
import mtgoracle.ui.kit.CardFrame
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.FrameSize
import mtgoracle.ui.kit.GridText
import mtgoracle.ui.kit.cellHeight
import mtgoracle.ui.kit.cells
import mtgoracle.ui.kit.clickTarget
import mtgoracle.ui.kit.face
import mtgoracle.ui.theme.LocalCells
import mtgoracle.ui.theme.Palette

/**
 * A hand as one lane: your own (or, watching with hands shown, the far
 * player's). Too many cards overlap as a band of the table does; past that it
 * scrolls. A card dragged sideways moves within the hand ([arrangeHand]): the
 * order is this screen's alone, and a click still plays the card.
 */
@Composable
fun HandLane(player: PlayerState, looks: Looks) {
    BoxWithConstraints(Modifier.fillMaxWidth().cellHeight(FrameSize.rows(looks.tier))) {
        val cells = LocalCells.current
        val density = LocalDensity.current
        val cols = cells.cols(constraints.maxWidth.toFloat())
        val width = FrameSize.cols(looks.tier) + 1
        var placed by remember { mutableStateOf(emptyList<Int>()) }
        val byId = player.hand.associateBy { it.id }
        val hand = arrangeHand(placed, player.hand.map { it.id }).map { byId.getValue(it) }
        val plan = planHalf(listOf(ZoneContent(ZoneKind.CREATURES, hand.map { width })), cols, 1)
        var hovered by remember { mutableStateOf<Int?>(null) }
        var pointed by remember { mutableStateOf<Int?>(null) } // the card whose strip the mouse is on
        // The dragged card and how far: the distance is read only where it is drawn, so a drag recomposes nothing.
        var dragging by remember { mutableStateOf<Int?>(null) }
        val dragPx = remember { mutableFloatStateOf(0f) }
        val contentCols = maxOf(cols, plan.slots.maxOfOrNull { it.x + width } ?: 0)
        Box(Modifier.fillMaxSize().then(if (0 in plan.scrolls) Modifier.horizontalScroll(rememberScrollState()) else Modifier)) {
            Box(Modifier.cells(contentCols, FrameSize.rows(looks.tier))) {
                plan.slots.forEachIndexed { order, place ->
                    val card = hand[place.index]
                    key(card.id) {
                        val overlapped = place.visible < width
                        val onHover: (ClickTarget?) -> Unit = { hovered = card.id; looks.onHover(it) }
                        val x = with(density) { (place.x * cells.width).toDp() }
                        val drag = Modifier.handDrag(
                            onStart = { dragging = card.id; dragPx.floatValue = 0f },
                            onDrag = { dragPx.floatValue += it },
                            onEnd = { dropped ->
                                if (dropped) {
                                    val centres = hand.indices.map { i -> plan.slots.first { it.index == i }.let { s -> (s.x + s.visible / 2f) * cells.width } }
                                    val at = dropIndex(centres, place.index, centres[place.index] + dragPx.floatValue)
                                    placed = moveInHand(hand.map { it.id }, card.id, at)
                                }
                                dragging = null; dragPx.floatValue = 0f
                            },
                        )
                        val lifted = dragging == card.id
                        val follow = Modifier.graphicsLayer { translationX = if (lifted) dragPx.floatValue else 0f }
                        Box(Modifier.offset(x = x).zIndex(if (lifted) 3000f else if (hovered == card.id) 1000f else order.toFloat()).then(follow)
                            .then(if (overlapped) Modifier else drag)) {
                            CardFrame(card.face(), looks.mode, looks.emphasis(card), if (overlapped) null else ClickTarget.Card(card.id), looks.onClick, onHover,
                                mark = looks.mark(card), tier = looks.tier, hovered = pointed == card.id)
                        }
                        if (overlapped) Box(Modifier.offset(x = x).zIndex(if (lifted) 3001f else 2000f + order).then(follow).then(drag)
                            .cells(place.visible, FrameSize.rows(looks.tier))
                            .clickTarget(ClickTarget.Card(card.id), looks.onClick, onHover, mark = false, onHoverChange = { on -> pointed = pointedAfter(pointed, card.id, on) }))
                    }
                }
            }
        }
        plan.segments.firstOrNull { it.hiddenRight > 0 }?.let { GridText(" +${it.hiddenRight} ▸", Modifier.align(Alignment.TopEnd), color = Palette.accent, background = Palette.background, bold = true) }
    }
}

/**
 * A sideways drag, outside the card's click: past the touch slop it takes the
 * movement, so the tap underneath is cancelled and the card isn't played.
 * [onEnd] says whether the drag ended (a drop) or was cancelled.
 */
@Composable
private fun Modifier.handDrag(onStart: () -> Unit, onDrag: (Float) -> Unit, onEnd: (dropped: Boolean) -> Unit): Modifier {
    val start by rememberUpdatedState(onStart)
    val move by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onEnd)
    return pointerInput(Unit) {
        detectHorizontalDragGestures(onDragStart = { start() }, onDragEnd = { end(true) }, onDragCancel = { end(false) }) { change, dx ->
            change.consume()
            move(dx)
        }
    }
}
