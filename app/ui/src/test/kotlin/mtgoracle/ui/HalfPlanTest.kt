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
    fun `the table plan - the largest frames that show every card, each half only what it needs`() {
        val tiers = mtgoracle.ui.kit.FrameTier.entries
        val rows = { t: mtgoracle.ui.kit.FrameTier -> mtgoracle.ui.kit.FrameSize.rows(t) }
        fun half(bands: Int) = { t: mtgoracle.ui.kit.FrameTier -> bands * (1 + rows(t)) + 2 }
        // Room for everything at full size: full size, and the spare shared.
        val roomy = mtgoracle.ui.board.planTable(80, tiers, { rows(it) + 2 }, half(1), half(3), 13, 13)
        assertEquals(mtgoracle.ui.kit.FrameTier.FULL to mtgoracle.ui.kit.FrameTier.FULL, roomy.nearTier to roomy.farTier)
        assertEquals(80, roomy.handRows + roomy.farRows + roomy.nearRows)
        // 1600x900's 44 rows: compact frames on our side, the opponent's half no bigger than its floor.
        val tight = mtgoracle.ui.board.planTable(44, tiers, { rows(it) + 2 }, half(1), half(3), 13, 13)
        assertEquals(mtgoracle.ui.kit.FrameTier.COMPACT, tight.nearTier, "$tight")
        assertTrue(tight.nearRows >= half(3)(tight.nearTier), "our three bands fit: $tight")
        assertTrue(tight.farRows <= 14, "the opponent gives up what it doesn't need: $tight")
        // Too small for any: the smallest frames, and overlap takes the rest.
        val tiny = mtgoracle.ui.board.planTable(20, tiers, { rows(it) + 2 }, half(1), half(3), 13, 13)
        assertEquals(mtgoracle.ui.kit.FrameTier.TEXT, tiny.nearTier)
    }

    @Test
    fun `bands needed - the fewest that show every card with no overlap, middle zones sharing`() {
        assertEquals(4, mtgoracle.ui.board.bandsNeeded(crowded(), 150), "creatures; walkers with artifacts; nine loose lands on two")
        assertEquals(1, mtgoracle.ui.board.bandsNeeded(emptyList(), 150))
        assertEquals(1, mtgoracle.ui.board.bandsNeeded(listOf(ZoneContent(ZoneKind.WALKERS, List(2) { creature }), ZoneContent(ZoneKind.PERMANENTS, List(2) { creature })), 150), "they share a band")
    }

    @Test
    fun `tapping changes nothing - the plan depends only on the slots`() {
        assertEquals(planHalf(crowded(), 150, 2), planHalf(crowded(), 150, 2))
    }

    @Test
    fun `when even overlapping can't hold them, the band scrolls and counts what is out of view`() {
        val plan = planHalf(listOf(ZoneContent(ZoneKind.CREATURES, List(30) { creature })), cols = 104, maxBands = 1)
        assertTrue(0 in plan.scrolls)
        assertTrue(plan.segments.single().hiddenRight > 0, "a +N count: ${plan.segments}")
    }
}
