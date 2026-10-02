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
    fun `space goes where the cards are - no creatures, the lands still sit at the edge and get the room`() {
        val plan = planHalf(listOf(ZoneContent(ZoneKind.LANDS, List(12) { land })), cols = 104, maxBands = 3)
        assertEquals(plan.bands, plan.slots.map { it.band }.toSet().size, "no empty band left between them")
        assertTrue(plan.slots.all { it.x + it.visible <= 104 }, "wrapping instead of overlapping, with the room")
    }

    @Test
    fun `the table plan - even halves always, each with the largest frames its own cards fit`() {
        val tiers = mtgoracle.ui.kit.FrameTier.entries
        val rows = { t: mtgoracle.ui.kit.FrameTier -> mtgoracle.ui.kit.FrameSize.rows(t) }
        fun half(bands: Int) = { t: mtgoracle.ui.kit.FrameTier -> bands * (1 + rows(t)) + 2 }
        fun plan(total: Int, far: Int, near: Int) = mtgoracle.ui.board.planTable(total, tiers, { rows(it) + 2 }, half(far), half(near), 13)
        for (total in listOf(120, 80, 60, 44, 20)) {
            val sparse = plan(total, 1, 3)
            val crowded = plan(total, 3, 1)
            for (p in listOf(sparse, crowded)) {
                assertTrue(kotlin.math.abs(p.farRows - p.nearRows) <= 1, "the halves are even at $total rows: $p")
                assertEquals(total, p.handRows + p.farRows + p.nearRows)
            }
            assertEquals(sparse.handRows to sparse.handTier, crowded.handRows to crowded.handTier, "the hand by the window alone, at $total rows")
            assertEquals(sparse.farRows, crowded.farRows, "and the halves too: cards coming and going move no edge")
            assertTrue(sparse.farTier.ordinal <= sparse.nearTier.ordinal, "the sparse half's frames are no smaller: $sparse")
            assertTrue(half(3)(sparse.nearTier) <= sparse.nearRows || sparse.nearTier == tiers.last(), "the crowded half's cards fit its half: $sparse")
        }
        // 80 rows: one band large or bigger, three bands smaller, the halves the same.
        val roomy = plan(80, 1, 3)
        assertTrue(roomy.farTier.ordinal < roomy.nearTier.ordinal, "the opponent's single band takes larger frames: $roomy")
        assertTrue(roomy.handRows * 4 <= 80, "the hand takes at most a quarter")
        // Too small for any: the smallest frames, and overlap takes the rest.
        assertEquals(mtgoracle.ui.kit.FrameTier.TEXT, plan(20, 1, 3).nearTier)
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
