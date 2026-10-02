package mtgoracle.ui.board

import kotlin.math.ceil
import mtgoracle.ui.kit.FrameTier

/**
 * Where every card of one player's battlefield goes — computed, not flowed,
 * so it can be tested and so nothing moves unless the plan changes.
 *
 * A half is a stack of *bands*, each one card lane high with a label line
 * above it. The type zones fill them from the midline out: creatures,
 * planeswalkers and battles, artifacts and enchantments, and lands at the
 * player's edge. Creatures are anchored to the midline and lands to the
 * edge, so neither moves when the other changes; the zones between follow
 * the creatures.
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

/** What a zone holds: its slots, each [widthCols] wide (a frame plus its turn shift). */
data class ZoneContent(val kind: ZoneKind, val widths: List<Int>)

/** A slot's place in its band: column [x], and the columns of it left showing (all of it unless overlapped). */
data class SlotPlace(val zone: ZoneKind, val index: Int, val band: Int, val x: Int, val visible: Int)

/** A zone's run within a band: where its label goes, and how many of its cards are scrolled out of view. */
data class Segment(val zone: ZoneKind, val band: Int, val x: Int, val width: Int, val hiddenRight: Int)

data class HalfPlan(
    val bands: Int, val slots: List<SlotPlace>, val segments: List<Segment>, val scrolls: Set<Int>,
    /** The first band of the lands group (drawn from the player's edge inwards); [bands] when the half has no lands. */
    val landsFrom: Int = bands,
) {
    fun place(zone: ZoneKind, index: Int): SlotPlace? = slots.firstOrNull { it.zone == zone && it.index == index }
}

/**
 * A zone column's rows: the fixed parts (library, the two list headers, the
 * hand, the life block with its pool) always; the stop ladder whole when
 * there is room, else one line; then the graveyard and exile lists get what
 * is left, up to what they hold. Nothing is ever drawn over anything: a
 * column shorter than even the minimum scrolls instead.
 */
data class ZoneColumnPlan(val ladderRows: Int, val graveyardRows: Int, val exileRows: Int, val spare: Int)

const val LADDER_FULL = 7
/** Library, the graveyard and exile headers, the hand line (revealed cards on it too), the life block with its pool. */
const val ZONE_FIXED_ROWS = 1 + 1 + 1 + 1 + 4
const val MAX_GRAVEYARD_ROWS = 6
const val MAX_EXILE_ROWS = 3

fun planZoneColumn(rows: Int, graveyard: Int, exile: Int, ladderWanted: Boolean): ZoneColumnPlan {
    var left = rows - ZONE_FIXED_ROWS - 1 - 1 - 1 // one line each, at least: ladder, graveyard, exile
    var ladder = 1
    if (ladderWanted && left >= LADDER_FULL - 1) { ladder = LADDER_FULL; left -= LADDER_FULL - 1 }
    val g = 1 + maxOf(0, minOf(left, minOf(graveyard, MAX_GRAVEYARD_ROWS) - 1)).also { left -= it }
    val e = 1 + maxOf(0, minOf(left, minOf(exile, MAX_EXILE_ROWS) - 1)).also { left -= it }
    return ZoneColumnPlan(ladder, g, e, maxOf(0, left))
}

/** How the table is sized: each half's frame tier and rows, and the hand pane's rows. */
data class TablePlan(val nearTier: FrameTier, val farTier: FrameTier, val handRows: Int, val farRows: Int, val nearRows: Int)

/**
 * The frame sizes and heights for the table, out of [rows] (the halves and
 * the hand pane). The largest frames that let every card show without
 * overlapping win: combinations are tried by how much they give up in all,
 * then by how evenly, then with your own side kept larger. The hand follows
 * your side's tier. The halves are even while both fit in one; a half that
 * needs more takes it from the other, which keeps only what it uses. When no
 * combination fits, the smallest frames overlap.
 *
 * [farWants] / [nearWants] are the rows a half needs at a tier, border
 * included; [handRows] the hand pane's.
 */
fun planTable(
    rows: Int, tiers: List<FrameTier>, handRows: (FrameTier) -> Int,
    farWants: (FrameTier) -> Int, nearWants: (FrameTier) -> Int, farFloor: Int, nearFloor: Int,
): TablePlan {
    // Each half sized by its own cards, as MTGO does: a sparse half takes large frames beside a crowded one, whoever's it is.
    val combos = tiers.flatMap { near -> tiers.map { far -> near to far } }
        .sortedWith(compareBy({ it.first.ordinal + it.second.ordinal }, { maxOf(it.first.ordinal, it.second.ordinal) }, { it.first.ordinal }))
    for ((near, far) in combos) {
        val hand = handRows(near)
        val f = maxOf(farFloor, farWants(far))
        val n = maxOf(nearFloor, nearWants(near))
        if (hand + f + n <= rows) {
            // Even halves while both fit in one, so a half's edge (where its lands sit) holds still as cards
            // come and go; only a half that outgrows its share takes rows from the other.
            val halves = rows - hand
            val even = halves / 2
            return when {
                f <= even && n <= halves - even -> TablePlan(near, far, hand, even, halves - even)
                n > halves - even -> TablePlan(near, far, hand, halves - n, n)
                else -> TablePlan(near, far, hand, f, halves - f)
            }
        }
    }
    // Nothing fits: the smallest frames, the halves as even as their floors allow, and overlap does the rest.
    val t = tiers.last()
    val hand = handRows(t)
    val left = rows - hand
    val far = minOf(maxOf(farFloor, farWants(t)), left / 2).coerceAtLeast(minOf(farFloor, left / 2))
    return TablePlan(t, t, hand, far, left - far)
}

/**
 * The fewest bands that show every one of [zones]' cards at full width —
 * nothing overlapped, nothing scrolled — as planHalf would pack them
 * (middle zones sharing a band where they fit). An empty half needs one.
 */
fun bandsNeeded(zones: List<ZoneContent>, cols: Int, most: Int = 6): Int {
    for (b in 1..most) {
        val plan = planHalf(zones, cols, b)
        if (plan.bands <= b && plan.scrolls.isEmpty() && plan.slots.all { s -> s.visible == zones.first { it.kind == s.zone }.widths[s.index] }) return b
    }
    return most
}

/** The fewest columns an overlapped card keeps showing: enough for its name's start. */
const val MIN_VISIBLE_COLS = 7
/** Columns between slots, and between two zones sharing a band. */
const val SLOT_GAP = 1
const val ZONE_GAP = 3

/**
 * Plans [zones] (any order; empty ones take no space) into a half [cols]
 * wide with room for [maxBands] bands. Bands are numbered from the midline
 * (0) to the player's edge.
 */
fun planHalf(zones: List<ZoneContent>, cols: Int, maxBands: Int): HalfPlan {
    val order = ZoneKind.entries
    val present = zones.filter { it.widths.isNotEmpty() }.sortedBy { order.indexOf(it.kind) }
    if (present.isEmpty() || cols <= 0) return HalfPlan(0, emptyList(), emptyList(), emptySet())
    fun widthOf(z: List<ZoneContent>) = z.sumOf { zc -> zc.widths.sumOf { it + SLOT_GAP } } + ZONE_GAP * (z.size - 1)
    fun need(z: List<ZoneContent>) = maxOf(1, ceil(widthOf(z) / cols.toDouble()).toInt())

    // Groups share bands; start with each zone on its own.
    val groups = present.map { mutableListOf(it) }.toMutableList()
    val caps = MutableList(groups.size) { Int.MAX_VALUE }
    fun bandsOf(i: Int) = minOf(need(groups[i]), caps[i])
    val limit = maxOf(1, maxBands)
    while (groups.indices.sumOf { bandsOf(it) } > limit) {
        // First: let neighbours share a band where they fit side by side (the middle zones before lands or creatures).
        val merge = (0 until groups.size - 1)
            .filter { need(groups[it] + groups[it + 1]) <= bandsOf(it) + bandsOf(it + 1) - 1 }
            .minByOrNull { i -> if (groups[i].any { it.kind == ZoneKind.LANDS } || groups[i + 1].any { it.kind == ZoneKind.LANDS }) 1 else 0 }
        if (merge != null) {
            groups[merge].addAll(groups.removeAt(merge + 1)); caps[merge] = Int.MAX_VALUE; caps.removeAt(merge + 1); continue
        }
        // Then: take a band from the zone with the most, overlapping its cards instead.
        val widest = groups.indices.maxBy { bandsOf(it) }
        if (bandsOf(widest) > 1) { caps[widest] = bandsOf(widest) - 1; continue }
        // Every group on one band and still too many: neighbours share and overlap.
        if (groups.size > 1) {
            val i = (0 until groups.size - 1).minBy { widthOf(groups[it] + groups[it + 1]) }
            groups[i].addAll(groups.removeAt(i + 1)); caps[i] = 1; caps.removeAt(i + 1); continue
        }
        break
    }

    // Bands from the midline: the groups before lands go from 0 up; the lands group sits at the edge.
    val counts = groups.indices.map { bandsOf(it) }
    val total = maxOf(counts.sum(), 1)
    val firstBand = IntArray(groups.size)
    var next = 0
    groups.indices.forEach { i -> firstBand[i] = next; next += counts[i] }
    // Nonland bands run on from the midline; the lands group comes last and is drawn from the player's
    // edge inwards (landsFrom), so free rows lie between the two, never between the lands and the edge.
    val bands = total
    val landsGroup = groups.indexOfLast { g -> g.any { it.kind == ZoneKind.LANDS } }

    val slots = mutableListOf<SlotPlace>()
    val segments = mutableListOf<Segment>()
    val scrolls = mutableSetOf<Int>()
    groups.forEachIndexed { g, group ->
        // The group's slots in order, split over its bands as evenly as they go.
        val items = group.flatMap { zc -> zc.widths.mapIndexed { i, w -> Triple(zc.kind, i, w) } }
        val perBand = splitEvenly(items.map { it.third }, counts[g], cols)
        var start = 0
        perBand.forEachIndexed { b, n ->
            val band = firstBand[g] + b
            val here = items.subList(start, start + n)
            start += n
            layBand(here, band, cols, slots, segments, scrolls)
        }
    }
    // The lands group is the last one; when it shares its band with other zones (a crowded half), that band is at the edge too.
    val landsFrom = if (landsGroup >= 0) firstBand[landsGroup] else bands
    return HalfPlan(bands, slots, segments, scrolls, landsFrom)
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
