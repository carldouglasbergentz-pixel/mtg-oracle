package mtgoracle.ui.board

import androidx.compose.ui.geometry.Rect
import mtgoracle.ui.kit.ClickRegistry
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.theme.Cells
import kotlin.math.abs

/** A place for a floating box, in cells: its top-left column and row. */
typealias CellPlace = Pair<Int, Int>

/**
 * Where a floating box of [cols] × [rows] cells opens: at the first of
 * [preferred] that covers nothing in the first list of [avoid], else at the
 * free place nearest the first of [preferred] with its corner in
 * [colRange] × [rowRange]; failing that, the same with the next, shorter
 * list of [avoid] (what must stay in view however crowded the table is);
 * and failing all, the first of [preferred]. The choice box sat on a
 * Mountain beside the Sol Ring it asked about, with the table's top half empty.
 */
fun placeFloating(
    cols: Int, rows: Int, cells: Cells,
    preferred: List<CellPlace>, colRange: IntRange, rowRange: IntRange,
    avoid: List<List<Rect>>,
): CellPlace {
    fun at(p: CellPlace) = Rect(p.first * cells.width, p.second * cells.height, (p.first + cols) * cells.width, (p.second + rows) * cells.height)
    // Inside the room: below a card at the table's edge is over the hand.
    fun inRoom(p: CellPlace) = (if (colRange.isEmpty()) p.first else p.first.coerceIn(colRange)) to (if (rowRange.isEmpty()) p.second else p.second.coerceIn(rowRange))
    val wanted = preferred.map(::inRoom)
    val anchor = wanted.first()
    // Every place in the room, nearest the anchor first (a row is about twice a cell's width).
    val grid = if (colRange.isEmpty() || rowRange.isEmpty()) emptyList() else
        rowRange.flatMap { r -> colRange.map { c -> c to r } }.sortedBy { (c, r) -> abs(c - anchor.first) + 2 * abs(r - anchor.second) }
    for (keepClear in avoid) {
        fun free(p: CellPlace) = at(p).let { box -> keepClear.none { it.overlaps(box) } }
        wanted.firstOrNull(::free)?.let { return it }
        grid.firstOrNull(::free)?.let { return it }
    }
    return anchor
}

/** Where every card on the table is drawn (not the hand, not a zone column's names). */
internal fun tableCards(registry: ClickRegistry, table: Rect): List<Rect> =
    registry.targets.filterIsInstance<ClickTarget.Card>().mapNotNull { registry[it] }.filter { it.width > 0 && it.overlaps(table) }

/** The floating stack box (or its folded bar) where it is drawn, if it is. */
internal fun stackBoxRects(registry: ClickRegistry): List<Rect> =
    listOf("region:stack-box", "region:stack-bar").mapNotNull { registry[ClickTarget.Control(it)] }
