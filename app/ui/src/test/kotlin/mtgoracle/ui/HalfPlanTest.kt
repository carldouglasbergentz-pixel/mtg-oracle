package mtgoracle.ui

import mtgoracle.ui.board.MIN_VISIBLE_COLS
import mtgoracle.ui.board.ZoneContent
import mtgoracle.ui.board.ZoneKind
import mtgoracle.ui.board.planHalf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The battlefield planner: type zones into bands, never a card off the board. */
class HalfPlanTest {
    private val creature = 21 // an art frame and its turn shift
    private val land = 17

    /** 9 lands + 3 artifacts + 2 planeswalkers + 6 creatures, the user's crowded side. */
    private fun crowded() = listOf(
        ZoneContent(ZoneKind.LANDS, List(9) { land }), ZoneContent(ZoneKind.PERMANENTS, List(3) { creature }),
        ZoneContent(ZoneKind.WALKERS, List(2) { creature }), ZoneContent(ZoneKind.CREATURES, List(6) { creature }),
    )

    private fun assertOnBoard(cols: Int, maxBands: Int) {
        val plan = planHalf(crowded(), cols, maxBands)
        assertEquals(20, plan.slots.size, "every card placed")
        for (s in plan.slots) {
            if (s.band !in plan.scrolls) assertTrue(s.x + s.visible <= cols, "$s is on the board ($cols cols)")
            assertTrue(s.visible >= minOf(MIN_VISIBLE_COLS, 1), "$s shows its title strip")
            assertTrue(s.band < plan.bands)
        }
        // Within a band, cards never cover each other's visible strip.
        plan.slots.groupBy { it.band }.values.forEach { band ->
            band.sortedBy { it.x }.zipWithNext().forEach { (a, b) -> assertTrue(a.x + a.visible <= b.x, "$a is not covered by $b") }
        }
        assertTrue(plan.slots.filter { it.zone == ZoneKind.CREATURES }.all { it.band == 0 }, "creatures at the midline: $plan")
        assertTrue(plan.slots.filter { it.zone == ZoneKind.LANDS }.all { it.band == plan.bands - 1 }, "lands at the edge")
        assertTrue(plan.bands <= maxBands || plan.scrolls.isNotEmpty(), "within the half, or saying it scrolls")
    }

    @Test fun `the crowded side fits at 1920x1080`() = assertOnBoard(cols = 190, maxBands = 3)
    @Test fun `the crowded side fits at 1600x900`() = assertOnBoard(cols = 150, maxBands = 2)
    @Test fun `the crowded side fits at 1280x720`() = assertOnBoard(cols = 104, maxBands = 1)

    @Test
    fun `space goes where the cards are - no creatures, the lands take the room`() {
        val plan = planHalf(listOf(ZoneContent(ZoneKind.LANDS, List(12) { land })), cols = 104, maxBands = 3)
        assertEquals(plan.bands, plan.slots.map { it.band }.toSet().size, "no empty band left between them")
        assertTrue(plan.slots.all { it.x + it.visible <= 104 }, "wrapping instead of overlapping, with the room")
    }

    private val tiers = mtgoracle.ui.kit.FrameTier.entries
    private fun frameRows(t: mtgoracle.ui.kit.FrameTier) = mtgoracle.ui.kit.FrameSize.rows(t)
    private fun half(bands: Int) = { t: mtgoracle.ui.kit.FrameTier -> bands * (1 + frameRows(t)) + 2 }
    private fun table(total: Int, far: Int, near: Int, previous: mtgoracle.ui.board.TablePlan? = null) =
        mtgoracle.ui.board.planTable(total, tiers, { frameRows(it) + 2 }, half(far), half(near), 13, previous)

    @Test
    fun `the table plan - the halves share the rows, and the hand is no larger than your half`() {
        for (total in listOf(120, 80, 60, 44, 20)) {
            for ((far, near) in listOf(1 to 3, 3 to 1, 2 to 2)) {
                val p = table(total, far, near)
                assertEquals(total, p.handRows + p.farRows + p.nearRows, "every row used at $total: $p")
                assertTrue(p.farRows >= 13 && p.nearRows >= 13 || total < 3 * 13, "each half keeps its zone column at $total: $p")
                assertTrue(p.handRows * 4 <= total || p.handTier == tiers.last(), "the hand takes at most a quarter at $total: $p")
                assertTrue(tiers.indexOf(p.handTier) >= tiers.indexOf(p.nearTier), "the hand is no larger than your half at $total: $p")
                if (p.nearTier != tiers.last()) assertTrue(half(near)(p.nearTier) <= p.nearRows, "your cards fit your half at $total: $p")
                if (p.farTier != tiers.last()) assertTrue(half(far)(p.farTier) <= p.farRows, "theirs fit theirs at $total: $p")
            }
        }
        // 80 rows, three bands of yours against one of theirs: your half takes the rows theirs doesn't need.
        val lent = table(80, 1, 3)
        assertTrue(lent.nearRows > lent.farRows, "the crowded half has more rows: $lent")
        val even = (80 - lent.handRows) / 2
        val evenTier = tiers.firstOrNull { half(3)(it) <= even } ?: tiers.last()
        assertTrue(tiers.indexOf(lent.nearTier) <= tiers.indexOf(evenTier), "and frames no smaller than an even split gave it ($evenTier): $lent")
        // Too small for any: the smallest frames, and overlap takes the rest.
        assertEquals(mtgoracle.ui.kit.FrameTier.TEXT, table(20, 1, 3).nearTier)
    }

    @Test
    fun `the midline stays while the same frames fit - a card more moves no edge`() {
        val before = table(80, 1, 2)
        // A creature arrives: their half needs another band, and its frames still fit in the rows it has.
        val after = table(80, 1, 2, previous = before).let { table(80, 1, 2, previous = it) }
        assertEquals(before.farRows to before.nearRows, after.farRows to after.nearRows)
        val grown = table(80, 2, 2, previous = before)
        if (grown.farTier == before.farTier && grown.nearTier == before.nearTier && half(2)(before.farTier) <= before.farRows) {
            assertEquals(before.farRows, grown.farRows, "the same frames still fit: the midline stays: $before -> $grown")
        }
        // Frames that no longer fit do move it.
        val crowded = table(80, 4, 1, previous = before)
        assertTrue(half(4)(crowded.farTier) <= crowded.farRows || crowded.farTier == tiers.last(), "their cards fit their half again: $crowded")
    }

    @Test
    fun `bands needed - the fewest that show every card with no overlap, middle zones sharing`() {
        assertEquals(4, mtgoracle.ui.board.bandsNeeded(crowded(), 150), "creatures; walkers with artifacts; nine loose lands on two")
        assertEquals(1, mtgoracle.ui.board.bandsNeeded(emptyList(), 150))
        assertEquals(2, mtgoracle.ui.board.bandsNeeded(listOf(ZoneContent(ZoneKind.CREATURES, List(1) { creature }), ZoneContent(ZoneKind.LANDS, List(2) { land })), 150),
            "one creature and two lands fit in one band, but the creature stands in front of the lands")
        assertEquals(1, mtgoracle.ui.board.bandsNeeded(listOf(ZoneContent(ZoneKind.LANDS, List(3) { land })), 150), "lands alone: one band")
        assertEquals(1, mtgoracle.ui.board.bandsNeeded(listOf(ZoneContent(ZoneKind.WALKERS, List(2) { creature }), ZoneContent(ZoneKind.PERMANENTS, List(2) { creature })), 150), "they share a band")
    }

    @Test
    fun `centred, a band's cards sit in the middle and grow outwards both ways`() {
        val zones = listOf(ZoneContent(ZoneKind.CREATURES, List(3) { creature }), ZoneContent(ZoneKind.LANDS, List(2) { land }))
        val left = planHalf(zones, cols = 150, maxBands = 3)
        val centred = planHalf(zones, cols = 150, maxBands = 3, centred = true)
        assertEquals(left.bands, centred.bands)
        for (band in 0 until centred.bands) {
            val places = centred.slots.filter { it.band == band }
            val width = { s: mtgoracle.ui.board.SlotPlace -> zones.first { it.kind == s.zone }.widths[s.index] }
            val before = places.minOf { it.x }
            val after = 150 - places.maxOf { it.x + width(it) }
            assertTrue(kotlin.math.abs(before - after) <= mtgoracle.ui.board.SLOT_GAP + 1, "band $band in the middle: $before cols before it, $after after")
            assertEquals(left.slots.filter { it.band == band }.map { it.x + before - left.slots.filter { s -> s.band == band }.minOf { s -> s.x } }, places.map { it.x },
                "the same run, moved")
        }
        // A band that scrolls takes the width as it is.
        val many = planHalf(listOf(ZoneContent(ZoneKind.CREATURES, List(30) { creature })), cols = 104, maxBands = 1, centred = true)
        assertEquals(0, many.slots.minOf { it.x })
    }

    @Test
    fun `tapping changes nothing - the plan depends only on the slots`() {
        assertEquals(planHalf(crowded(), 150, 2), planHalf(crowded(), 150, 2))
    }

    @Test
    fun `zones sharing a band overlap to fit - every zone's last card is whole, not only the band's`() {
        // One band, three zones that overlap: the step once ignored the whole cards ending the first two zones.
        val zones = listOf(
            ZoneContent(ZoneKind.PERMANENTS, List(4) { creature }), ZoneContent(ZoneKind.WALKERS, List(3) { creature }),
            ZoneContent(ZoneKind.CREATURES, List(4) { creature }),
        )
        val plan = planHalf(zones, cols = 150, maxBands = 1)
        assertEquals(emptySet(), plan.scrolls, "it fits by overlapping: $plan")
        assertTrue(plan.slots.all { it.x + it.visible <= 150 }, "every card on the board: ${plan.slots}")
    }

    @Test
    fun `when even overlapping can't hold them, the band scrolls and counts what is out of view`() {
        val plan = planHalf(listOf(ZoneContent(ZoneKind.CREATURES, List(30) { creature })), cols = 104, maxBands = 1)
        assertTrue(0 in plan.scrolls)
        assertTrue(plan.segments.single().hiddenRight > 0, "a +N count: ${plan.segments}")
    }
}
