package mtgoracle.ui.board

import kotlin.math.ceil
import mtgoracle.ui.kit.FrameTier

/**
 * Where every card of one player's battlefield goes — computed, not flowed,
 * so it can be tested and so nothing moves unless the plan changes.
 *
 * A half is a stack of *bands*, each one card lane high with a label line
 * above it, in two rows as MTGO and Arena lay a table: the creatures at the
 * midline, and the back row at the player's own edge: the lands, then the
 * artifacts and enchantments, then the planeswalkers and battles, building
 * out to the right in the same row. The creatures are anchored to the
 * midline and the back row to the edge, so neither moves when the other
 * changes; the rows to spare lie between them.
 *
 * Space goes where the cards are: a zone takes as many bands as its cards
 * need, the middle zones share a band when there aren't enough, and a band
 * with more cards than width overlaps them (each keeps its title strip
 * showing and clickable). Only when even overlapping can't hold them does
 * the band scroll sideways, with a `+N ▸` count of those out of view.
 */
enum class ZoneKind(val label: String) {
    CREATURES("creatures"), WALKERS("planeswalkers · battles"), PERMANENTS("artifacts · enchantments"), LANDS("lands")
}

/**
 * What a zone holds: its slots, each so many columns wide (a frame plus its
 * turn shift). [splits] are the slots split off an earlier one's pile (a
 * tapped Island beside the untapped ones, a picked one): not counted when a
 * band is centred, so tapping or picking one moves nothing else.
 */
data class ZoneContent(val kind: ZoneKind, val widths: List<Int>, val splits: Set<Int> = emptySet())

/** A slot's place in its band: column [x], and the columns of it left showing (all of it unless overlapped). */
data class SlotPlace(val zone: ZoneKind, val index: Int, val band: Int, val x: Int, val visible: Int)

/** A zone's run within a band: where its label goes, and how many of its cards are scrolled out of view. */
data class Segment(val zone: ZoneKind, val band: Int, val x: Int, val width: Int, val hiddenRight: Int)

/** The back row, in its order from the left: the lands, then the other noncreature permanents building out to the right. */
val BACK_ROW = listOf(ZoneKind.LANDS, ZoneKind.PERMANENTS, ZoneKind.WALKERS)

data class HalfPlan(
    val bands: Int, val slots: List<SlotPlace>, val segments: List<Segment>, val scrolls: Set<Int>,
    /** The first band of the back row (drawn from the player's edge inwards); [bands] when the half has none. */
    val landsFrom: Int = bands,
) {
    fun place(zone: ZoneKind, index: Int): SlotPlace? = slots.firstOrNull { it.zone == zone && it.index == index }
}

/**
 * A zone column's rows: the fixed parts (library, the two list headers, the
 * hand, the life block with its pool) always; the stop ladder whole when
 * there is room, else one line; then the graveyard and exile lists get what
 * is left, up to what they hold (the whole of either pops out in a panel).
 * Nothing is ever drawn over anything: a column shorter than even the
 * minimum scrolls instead.
 */
data class ZoneColumnPlan(val ladderRows: Int, val graveyardRows: Int, val exileRows: Int, val spare: Int)

const val LADDER_FULL = 7
/** Library, the graveyard and exile headers, the hand line (revealed cards on it too), the life block with its pool. */
const val ZONE_FIXED_ROWS = 1 + 1 + 1 + 1 + 4
const val MAX_GRAVEYARD_ROWS = 12
const val MAX_EXILE_ROWS = 6

fun planZoneColumn(rows: Int, graveyard: Int, exile: Int, ladderWanted: Boolean): ZoneColumnPlan {
    var left = rows - ZONE_FIXED_ROWS - 1 - 1 - 1 // one line each, at least: ladder, graveyard, exile
    var ladder = 1
    if (ladderWanted && left >= LADDER_FULL - 1) { ladder = LADDER_FULL; left -= LADDER_FULL - 1 }
    val g = 1 + maxOf(0, minOf(left, minOf(graveyard, MAX_GRAVEYARD_ROWS) - 1)).also { left -= it }
    val e = 1 + maxOf(0, minOf(left, minOf(exile, MAX_EXILE_ROWS) - 1)).also { left -= it }
    return ZoneColumnPlan(ladder, g, e, maxOf(0, left))
}

/** How the table is sized: each half's frame tier and rows, and the hand pane's tier and rows. */
data class TablePlan(val nearTier: FrameTier, val farTier: FrameTier, val handTier: FrameTier, val handRows: Int, val farRows: Int, val nearRows: Int)

/** The smallest frames the hand steps down to with a crowded table: the cards you hold stay readable. */
val HAND_FLOOR = FrameTier.FULL

/** The most of the table the hand pane takes: a quarter. */
private const val HAND_SHARE = 4

/**
 * The frame sizes and heights for the table, out of [rows] (the halves and
 * the hand pane). [tiers] runs from the largest frames to the smallest.
 *
 * The halves are always the same size, a straight 50/50, and only the cards
 * shrink: the whole table takes the largest frames that show both halves'
 * cards, each in its own half, so a card is the same size on either side.
 * Picked half by half, a side of one row was drawn large and overlapped
 * while the other side's two rows went compact. Each half keeps at least
 * [halfFloor] rows.
 *
 * The hand takes the largest frames that leave it at most a quarter of the
 * table, and never larger than your half's: the cards you hold are not
 * drawn bigger than the ones you play. But never smaller than [HAND_FLOOR]
 * either, where the window has room for it: a crowded table took the hand
 * down to the compact frames with it. When no frames fit, the smallest
 * overlap.
 *
 * [farWants] / [nearWants] are the rows a half needs at a tier; [handRows]
 * the hand pane's.
 */
fun planTable(
    rows: Int, tiers: List<FrameTier>, handRows: (FrameTier) -> Int,
    farWants: (FrameTier) -> Int, nearWants: (FrameTier) -> Int, halfFloor: Int,
): TablePlan {
    fun halves(hand: FrameTier): TablePlan {
        val handRowsHere = handRows(hand)
        val far = (rows - handRowsHere) / 2
        val near = rows - handRowsHere - far
        fun tierFor(rowsHere: Int, wants: (FrameTier) -> Int) = tiers.firstOrNull { wants(it) <= rowsHere } ?: tiers.last()
        // One size for the table: the smaller of what each half fits.
        val table = tiers[maxOf(tiers.indexOf(tierFor(near, nearWants)), tiers.indexOf(tierFor(far, farWants)))]
        return TablePlan(table, table, hand, handRowsHere, far, near)
    }
    val hands = tiers.filter { handRows(it) * HAND_SHARE <= rows && (rows - handRows(it)) / 2 >= halfFloor }.ifEmpty { listOf(tiers.last()) }
    // The largest hand no larger than the near half's frames, or the floor; a smaller hand leaves the halves more rows, so look on down.
    val floor = tiers.indexOf(HAND_FLOOR).takeIf { it >= 0 } ?: tiers.lastIndex
    return hands.map(::halves).firstOrNull { tiers.indexOf(it.handTier) >= minOf(tiers.indexOf(it.nearTier), floor) } ?: halves(hands.last())
}

/**
 * The fewest bands that show every one of [zones]' cards, each overlapped
 * no further than to two thirds of it and nothing scrolled, as planHalf
 * would pack them, with
 * the back row on bands of its own: creatures stand in front of it. Sized by
 * its own cards, a sparse half got one band's rows, and its creatures and
 * lands shared it. An empty half needs one.
 */
fun bandsNeeded(zones: List<ZoneContent>, cols: Int, most: Int = 6): Int {
    for (b in 1..most) {
        val plan = planHalf(zones, cols, b)
        val backBands = plan.slots.filter { it.zone in BACK_ROW }.map { it.band }.toSet()
        val mixed = plan.slots.any { it.zone !in BACK_ROW && it.band in backBands }
        // Stacked a little, as MTGO lays a long row, before a row of its own: the twelfth land took the whole table down a size.
        val shown = plan.slots.all { s -> s.visible * VISIBLE_SHARE_OF >= zones.first { it.kind == s.zone }.widths[s.index] * VISIBLE_SHARE }
        if (plan.bands <= b && !mixed && plan.scrolls.isEmpty() && shown) return b
    }
    return most
}

/** The least of a card an overlapped row may leave showing, as a fraction ([VISIBLE_SHARE] / [VISIBLE_SHARE_OF]): its name and most of its art. */
const val VISIBLE_SHARE = 2
const val VISIBLE_SHARE_OF = 3

/** The fewest columns an overlapped card keeps showing: enough for its name's start. */
const val MIN_VISIBLE_COLS = 7
/** Columns between slots, and between two zones sharing a band. */
const val SLOT_GAP = 1
const val ZONE_GAP = 3

/**
 * Plans [zones] (any order; empty ones take no space) into a half [cols]
 * wide with room for [maxBands] bands. Bands are numbered from the midline
 * (0) to the player's edge. [centred], each band's cards sit in the middle
 * of its width and grow outwards both ways, as the table is built from the
 * middle; otherwise from the left (the hand).
 */
fun planHalf(zones: List<ZoneContent>, cols: Int, maxBands: Int, centred: Boolean = false): HalfPlan {
    val present = zones.filter { it.widths.isNotEmpty() }
    if (present.isEmpty() || cols <= 0) return HalfPlan(0, emptyList(), emptyList(), emptySet())
    fun widthOf(z: List<ZoneContent>) = z.sumOf { zc -> zc.widths.sumOf { it + SLOT_GAP } } + ZONE_GAP * (z.size - 1)
    fun need(z: List<ZoneContent>) = maxOf(1, ceil(widthOf(z) / cols.toDouble()).toInt())

    // Two groups, as two rows: the creatures, and the back row in its order (lands first). Crowded, they share bands.
    val groups = listOf(present.filter { it.kind !in BACK_ROW }, BACK_ROW.mapNotNull { k -> present.firstOrNull { it.kind == k } })
        .filter { it.isNotEmpty() }.map { it.toMutableList() }.toMutableList()
    val caps = MutableList(groups.size) { Int.MAX_VALUE }
    fun bandsOf(i: Int) = minOf(need(groups[i]), caps[i])
    val limit = maxOf(1, maxBands)
    while (groups.indices.sumOf { bandsOf(it) } > limit) {
        // First: take a band from the row with the most, overlapping its cards instead. The creatures and the back
        // row share a band only when each is down to one and there are still too many.
        val widest = groups.indices.maxBy { bandsOf(it) }
        if (bandsOf(widest) > 1) { caps[widest] = bandsOf(widest) - 1; continue }
        // Every group on one band and still too many: neighbours share and overlap.
        if (groups.size > 1) {
            val i = (0 until groups.size - 1).minBy { widthOf(groups[it] + groups[it + 1]) }
            groups[i].addAll(groups.removeAt(i + 1)); caps[i] = 1; caps.removeAt(i + 1); continue
        }
        break
    }

    // Bands from the midline: the creatures from 0 up; the back row, last, sits at the edge.
    val counts = groups.indices.map { bandsOf(it) }
    val total = maxOf(counts.sum(), 1)
    val firstBand = IntArray(groups.size)
    var next = 0
    groups.indices.forEach { i -> firstBand[i] = next; next += counts[i] }
    val bands = total
    val backGroup = groups.indexOfLast { g -> g.any { it.kind in BACK_ROW } }
    // The back row is drawn from the player's edge inwards (landsFrom); when it shares a band with the creatures (a crowded half), that band is at the edge too.
    val landsFrom = if (backGroup >= 0) firstBand[backGroup] else bands

    val slots = mutableListOf<SlotPlace>()
    val segments = mutableListOf<Segment>()
    val scrolls = mutableSetOf<Int>()
    // The back row's bands when laid out as blocks (backBlocks), centred together on its edge band.
    var blockBands = IntRange.EMPTY
    groups.forEachIndexed { g, group ->
        if (g == backGroup && counts[g] > 1 && group.all { it.kind in BACK_ROW }) {
            val edge = firstBand[g] + counts[g] - 1
            backBlocks(group, counts[g], edge, cols)?.let { laid ->
                slots += laid.slots; segments += laid.segments; scrolls += laid.scrolls
                if (laid.joined) blockBands = firstBand[g]..edge
                return@forEachIndexed
            }
        }
        // The group's slots in order, split over its bands as evenly as they go.
        val items = group.flatMap { zc -> zc.widths.mapIndexed { i, w -> Triple(zc.kind, i, w) } }
        val perBand = splitEvenly(items.map { it.third }, counts[g], cols)
        var start = 0
        perBand.forEachIndexed { b, n ->
            // The back row fills from the player's edge in: its first cards, the lands, in the outermost band.
            val band = if (g == backGroup && group.all { it.kind in BACK_ROW }) firstBand[g] + counts[g] - 1 - b else firstBand[g] + b
            val here = items.subList(start, start + n)
            start += n
            layBand(here, band, cols, slots, segments, scrolls)
        }
    }
    if (!centred) return HalfPlan(bands, slots, segments, scrolls, landsFrom)
    // Each band's run of cards moved to the middle; a band that overlaps or scrolls already takes the width.
    // Centred on the run without its split-off piles, so a pile that splits or merges keeps the run's start;
    // the run still ends inside the band.
    fun content(s: SlotPlace) = present.first { it.kind == s.zone }
    fun shiftOf(b: Int): Int {
        val used = (slots.filter { it.band == b }.map { it.x + content(it).widths[it.index] } + segments.filter { it.band == b }.map { it.x + it.width }).maxOrNull() ?: cols
        val split = slots.filter { it.band == b && it.index in content(it).splits }.sumOf { content(it).widths[it.index] + SLOT_GAP }
        return if (b in scrolls) 0 else ((cols - (used - split)) / 2).coerceAtMost(cols - used).coerceAtLeast(0)
    }
    // The back row's blocks move as one, by its edge band, which holds every block's first line.
    val shift = (0 until bands).associateWith { b -> if (b in blockBands) shiftOf(blockBands.last) else shiftOf(b) }
    return HalfPlan(
        bands, slots.map { it.copy(x = it.x + shift.getValue(it.band)) }, segments.map { it.copy(x = it.x + shift.getValue(it.band)) }, scrolls, landsFrom,
    )
}

/**
 * The back row over [lines] bands as blocks, a zone each, side by side: the
 * lands on the edge line, and the other zones growing inwards in a block of
 * their own, as a table is laid: Elminster above Chandra in the
 * planeswalkers, not alone in a row with a label of its own. When the lands
 * alone are too wide they overlap on their line, each showing two thirds of
 * itself, rather than spill over. A zone's label sits over its block. Null
 * when even that is wider than [cols] (then the row is laid as before).
 */
private class BackLayout(val slots: List<SlotPlace>, val segments: List<Segment>, val scrolls: Set<Int>, val joined: Boolean)

private fun backBlocks(group: List<ZoneContent>, lines: Int, edgeBand: Int, cols: Int): BackLayout? {
    val depth = group.associate { it.kind to 1 }.toMutableMap()
    fun perLine(z: ZoneContent) = (z.widths.size + depth.getValue(z.kind) - 1) / depth.getValue(z.kind)
    fun widthOf(z: ZoneContent) = z.widths.take(perLine(z)).sumOf { it + SLOT_GAP }
    val lands = group.firstOrNull { it.kind == ZoneKind.LANDS }
    val others = group.filter { it.kind != ZoneKind.LANDS }
    val landFull = lands?.let(::widthOf) ?: 0
    val landW = lands?.widths?.maxOrNull() ?: 0
    // The narrowest the lands' line may be: the last whole, the others two thirds each.
    val landLeast = lands?.widths?.size?.let { n -> landW + SLOT_GAP + (n - 1) * ((landW * VISIBLE_SHARE + VISIBLE_SHARE_OF - 1) / VISIBLE_SHARE_OF) } ?: 0
    fun total(landsWidth: Int) = landsWidth + others.sumOf { widthOf(it) } - SLOT_GAP + ZONE_GAP * (group.size - 1)
    while (total(landFull) > cols) {
        // The other zones grow inwards, the widest first; the lands stay on the edge line.
        val grow = others.filter { depth.getValue(it.kind) < lines && perLine(it) > 1 }.maxByOrNull { perLine(it) } ?: break
        depth[grow.kind] = depth.getValue(grow.kind) + 1
    }
    val slots = mutableListOf<SlotPlace>()
    val segments = mutableListOf<Segment>()
    // The lands on the edge line, overlapped to [width] when they must be: the same step between each, the last whole.
    fun layLands(z: ZoneContent, x: Int, width: Int) {
        val step = if (width < landFull && z.widths.size > 1) (width - SLOT_GAP - landW) / (z.widths.size - 1) else null
        z.widths.forEachIndexed { i, w ->
            val at = if (step != null) x + i * step else x + z.widths.take(i).sumOf { it + SLOT_GAP }
            slots += SlotPlace(z.kind, i, edgeBand, at, if (step != null && i < z.widths.lastIndex) step else w)
        }
        segments += Segment(z.kind, edgeBand, x, width, 0)
    }
    val landsWidth = minOf(landFull, cols - total(0))
    if (lands != null && landsWidth < landLeast || lands == null && total(0) > cols) {
        // No room for the blocks beside the lands: the lands alone on the edge line, the other zones on the line above,
        // each under its label, each line centred by itself.
        if (lands == null || landLeast > cols || others.isEmpty()) return null
        layLands(lands, 0, minOf(landFull, cols))
        val scrolls = mutableSetOf<Int>()
        layBand(others.flatMap { zc -> zc.widths.mapIndexed { i, w -> Triple(zc.kind, i, w) } }, edgeBand - 1, cols, slots, segments, scrolls)
        return BackLayout(slots, segments, scrolls, joined = false)
    }
    var x = 0
    group.forEach { z ->
        val width = if (z === lands) landsWidth else widthOf(z)
        if (z === lands) layLands(z, x, width) else {
            val per = perLine(z)
            val used = (z.widths.size + per - 1) / per
            z.widths.forEachIndexed { i, w -> slots += SlotPlace(z.kind, i, edgeBand - i / per, x + z.widths.take(i % per).sumOf { it + SLOT_GAP }, w) }
            segments += Segment(z.kind, edgeBand - (used - 1), x, width, 0)
        }
        x += width - SLOT_GAP + ZONE_GAP
    }
    return BackLayout(slots, segments, emptySet(), joined = true)
}

/** How many of [widths] go on each of [bands] bands: fill each up to [cols], the last takes the rest. */
private fun splitEvenly(widths: List<Int>, bands: Int, cols: Int): List<Int> {
    if (bands <= 1) return listOf(widths.size)
    val out = mutableListOf<Int>()
    var i = 0
    repeat(bands - 1) {
        var used = 0
        var n = 0
        while (i + n < widths.size && used + widths[i + n] + SLOT_GAP <= cols) { used += widths[i + n] + SLOT_GAP; n++ }
        // Leave at least one card for each band still to come.
        n = n.coerceAtMost(widths.size - i - (bands - 1 - out.size)).coerceAtLeast(if (i < widths.size) 1 else 0)
        out += n; i += n
    }
    out += widths.size - i
    return out
}

/**
 * One band's cards, left to right, zone by zone: at full width if they fit,
 * else overlapped to fit, else overlapped as far as allowed and scrolling.
 */
private fun layBand(items: List<Triple<ZoneKind, Int, Int>>, band: Int, cols: Int, slots: MutableList<SlotPlace>, segments: MutableList<Segment>, scrolls: MutableSet<Int>) {
    if (items.isEmpty()) return
    val zonesHere = items.map { it.first }.distinct()
    val full = items.sumOf { it.third + SLOT_GAP } + ZONE_GAP * (zonesHere.size - 1)
    // The step between cards when they must overlap: the same for every card in the band. Each
    // zone's last card is shown whole, so the step shares out what those cards leave, not just the band's last.
    val lastOfZone = items.indices.filter { it == items.lastIndex || items[it + 1].first != items[it].first }.toSet()
    val stepped = items.size - lastOfZone.size
    val whole = lastOfZone.sumOf { items[it].third + SLOT_GAP } - SLOT_GAP
    val step: Int? = if (full <= cols || stepped == 0) null
        else maxOf(MIN_VISIBLE_COLS, (cols - whole - ZONE_GAP * (zonesHere.size - 1)) / stepped)
    var x = 0
    var zoneStart = 0
    var current = items.first().first
    fun closeSegment(kind: ZoneKind, end: Int) {
        val hidden = slots.count { it.band == band && it.zone == kind && it.x + minOf(it.visible, MIN_VISIBLE_COLS) > cols }
        segments += Segment(kind, band, zoneStart, end - zoneStart, hidden)
    }
    items.forEachIndexed { i, (kind, index, width) ->
        if (kind != current) { closeSegment(current, x); x += ZONE_GAP; zoneStart = x; current = kind }
        val advance = if (step == null || i in lastOfZone) width + SLOT_GAP else step
        slots += SlotPlace(kind, index, band, x, if (step == null || i in lastOfZone) width else step)
        x += advance
    }
    closeSegment(current, x)
    if (x - SLOT_GAP > cols) scrolls += band
}
