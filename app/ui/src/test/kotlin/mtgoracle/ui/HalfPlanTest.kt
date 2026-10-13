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
    private fun half(bands: Int) = { t: mtgoracle.ui.kit.FrameTier -> bands * (1 + frameRows(t)) }
    private fun table(total: Int, far: Int, near: Int) =
        mtgoracle.ui.board.planTable(total, tiers, { frameRows(it) + 2 }, half(far), half(near), 13)

    @Test
    fun `the table plan - even halves always, each with the largest frames its own cards fit, the hand no larger than yours`() {
        for (total in listOf(120, 80, 60, 44, 20)) {
            val sparse = table(total, 1, 3)
            val crowded = table(total, 3, 1)
            for (p in listOf(sparse, crowded, table(total, 2, 2))) {
                assertTrue(kotlin.math.abs(p.farRows - p.nearRows) <= 1, "the halves are even at $total rows: $p")
                assertEquals(total, p.handRows + p.farRows + p.nearRows)
                assertTrue(p.handRows * 4 <= total || p.handTier == tiers.last(), "the hand takes at most a quarter at $total: $p")
                val floor = tiers.indexOf(mtgoracle.ui.board.HAND_FLOOR)
                assertTrue(tiers.indexOf(p.handTier) >= minOf(tiers.indexOf(p.nearTier), floor), "the hand is no larger than your half, or the floor, at $total: $p")
            }
            // The hand follows your half's frames, so an edge moves only when those change size, never with a card that doesn't.
            if (sparse.nearTier == crowded.nearTier) assertEquals(sparse.farRows, crowded.farRows, "no edge moved, at $total rows")
            assertTrue(half(3)(sparse.nearTier) <= sparse.nearRows || sparse.nearTier == tiers.last(), "the crowded half's cards fit its half: $sparse")
        }
        // 80 rows: one band of theirs, three of yours: one size for the table, the one your three bands fit.
        val roomy = table(80, 1, 3)
        assertEquals(roomy.nearTier, roomy.farTier, "a card is the same size on either side: $roomy")
        assertTrue(half(3)(roomy.nearTier) <= roomy.nearRows, "the size the crowded half fits: $roomy")
        // A crowded half of compact frames: the hand keeps the normal ones, where the window has room for them.
        val crowdedHand = table(60, 1, 3)
        assertTrue(crowdedHand.nearTier.ordinal > mtgoracle.ui.kit.FrameTier.FULL.ordinal, "the half is crowded: $crowdedHand")
        assertEquals(mtgoracle.ui.kit.FrameTier.FULL, crowdedHand.handTier, "the hand stops at the floor: $crowdedHand")
        // Too small for any: the smallest frames, and overlap takes the rest.
        assertEquals(mtgoracle.ui.kit.FrameTier.TEXT, table(20, 1, 3).nearTier)
    }

    @Test
    fun `bands needed - the fewest that show every card with no overlap, middle zones sharing`() {
        assertEquals(3, mtgoracle.ui.board.bandsNeeded(crowded(), 150), "creatures; the back row - nine lands, then artifacts and walkers - on two")
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
    fun `a back row of two lines is laid in blocks - the planeswalkers grow inwards in their own, the lands stay on the edge`() {
        // Eight land piles, Shorikai, and four planeswalkers (Elminster, a Jace token, Chandra, Teferi): one line is too wide.
        val zones = listOf(ZoneContent(ZoneKind.CREATURES, List(6) { land }), ZoneContent(ZoneKind.LANDS, List(8) { land }),
            ZoneContent(ZoneKind.PERMANENTS, List(1) { land }), ZoneContent(ZoneKind.WALKERS, List(4) { land }))
        val plan = planHalf(zones, cols = 220, maxBands = 3, centred = true)
        val edge = plan.bands - 1
        assertTrue(plan.slots.filter { it.zone == ZoneKind.LANDS }.all { it.band == edge }, "the lands on the edge line: $plan")
        val walkers = plan.slots.filter { it.zone == ZoneKind.WALKERS }
        assertEquals(setOf(edge, edge - 1), walkers.map { it.band }.toSet(), "the planeswalkers on two lines: $walkers")
        val left = walkers.minOf { it.x }
        val right = walkers.maxOf { it.x + it.visible }
        assertTrue(plan.slots.filter { it.zone != ZoneKind.WALKERS && it.band >= edge - 1 }.none { it.x + it.visible > left && it.x < right },
            "in a block of their own, nothing else under or over them: $plan")
        assertEquals(1, plan.segments.count { it.zone == ZoneKind.WALKERS }, "one label over the block")
        assertTrue(plan.slots.all { it.visible == land }, "nothing overlapped")
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
