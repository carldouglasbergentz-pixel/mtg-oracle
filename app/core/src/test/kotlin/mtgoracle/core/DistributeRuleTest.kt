package mtgoracle.core

import mtgoracle.core.model.BoardRef
import mtgoracle.core.model.DistributePrompt
import mtgoracle.core.model.DistributeTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** What a damage split may be (DistributePrompt.problem): the total, at least one each, trample's lethal first, the old assignment order. */
class DistributeRuleTest {
    private val bears = listOf(DistributeTarget("Grizzly Bears", BoardRef.Card(1), 2), DistributeTarget("Grizzly Bears", BoardRef.Card(2), 2))
    private val player = DistributeTarget("AI", BoardRef.Player(9), null)
    private fun prompt(total: Int, excess: Int? = null, inOrder: Boolean = false, atLeastOne: Boolean = false, targets: List<DistributeTarget> = bears) =
        DistributePrompt(1, "split", targets, total, atLeastOne, List(targets.size) { 0 }, excess, inOrder)

    @Test
    fun `the whole amount, and at least one each when asked`() {
        assertEquals("assigned 3 of 4", prompt(4).problem(listOf(1, 2)))
        assertEquals(null, prompt(4).problem(listOf(4, 0)), "without an order, all to one blocker is fine (CR 510.1c)")
        assertNotNull(prompt(4, atLeastOne = true).problem(listOf(4, 0)))
    }

    @Test
    fun `an answer from across a network table can't deal more than there is`() {
        val trample = prompt(5, excess = 2, targets = bears + player)
        // Int.MAX twice and 5 sums to 3 in Int: each blocker would have taken 2^31 - 1.
        assertNotNull(trample.problem(listOf(Int.MAX_VALUE, Int.MAX_VALUE, 5)))
        assertNotNull(prompt(3, targets = bears + player).problem(listOf(-100, 0, 103)), "a negative amount pays for more elsewhere")
        assertNotNull(prompt(4).problem(listOf(5, -1)))
    }

    @Test
    fun `a trampler's excess reaches the player only once every blocker has lethal`() {
        val trample = prompt(6, excess = 2, targets = bears + player)
        assertNotNull(trample.problem(listOf(1, 1, 4)), "short of lethal on both Bears")
        assertNotNull(trample.problem(listOf(2, 1, 3)), "short on the second")
        assertEquals(null, trample.problem(listOf(2, 2, 2)))
        assertEquals(null, trample.problem(listOf(6, 0, 0)), "excess may stay on a blocker")
        assertEquals(null, trample.problem(listOf(3, 3, 0)))
    }

    @Test
    fun `a target takes no more than its most - two mana of different colors`() {
        val colours = listOf("W", "U", "B").map { DistributeTarget(it, null, null, max = 1) }
        val split = prompt(2, targets = colours)
        assertNotNull(split.problem(listOf(2, 0, 0)), "{W}{W} is not two different colors")
        assertEquals(null, split.problem(listOf(1, 1, 0)))
    }

    @Test
    fun `in the old assignment order each blocker has lethal before the next gets any`() {
        val ordered = prompt(4, inOrder = true)
        assertNotNull(ordered.problem(listOf(1, 3)))
        assertEquals(null, ordered.problem(listOf(2, 2)))
        assertEquals(null, ordered.problem(listOf(4, 0)))
    }
}
