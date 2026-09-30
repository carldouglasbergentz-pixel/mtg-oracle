package mtgoracle.core

import mtgoracle.core.model.PhaseStops
import mtgoracle.core.model.Step
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhaseStopsTest {
    @Test
    fun `round-trips through the settings form`() {
        val stops = PhaseStops.DEFAULT.toggled(seatsTurn = true, Step.UPKEEP).toggled(seatsTurn = false, Step.END_OF_TURN)
        assertEquals(stops, PhaseStops.parse(stops.serialise()))
        assertTrue(stops.stopsAt(seatsTurn = true, Step.UPKEEP))
        assertFalse(stops.stopsAt(seatsTurn = false, Step.END_OF_TURN))
    }

    @Test
    fun `junk or an old file falls back to the defaults, unknown steps are dropped`() {
        assertEquals(PhaseStops.DEFAULT, PhaseStops.parse(null))
        assertEquals(PhaseStops.DEFAULT, PhaseStops.parse("garbage"))
        assertEquals(setOf(Step.MAIN1), PhaseStops.parse("own=MAIN1,WARP_STEP;opponent=").ownTurn)
    }
}
