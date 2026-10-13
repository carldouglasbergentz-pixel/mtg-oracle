package mtgoracle.ui

import mtgoracle.core.model.InputKind
import mtgoracle.core.model.InputPrompt
import mtgoracle.ui.board.Lanes
import mtgoracle.ui.board.Looks
import mtgoracle.ui.kit.CardMode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tokens alike pile once there are five, as lands do: thirteen Angels took a
 * row of their own. Fewer lie one by one, one that differs (tapped) lies
 * apart, and while attackers or blockers are declared, or in combat, each
 * is a card of its own to pick or to block.
 */
class TokenPileTest {
    private fun angels(n: Int, from: Int = 300) = (0 until n).map { card(from + it, "Angel Token", creature = true).copy(isToken = true) }
    private val pilot = card(299, "Pilot Token", creature = true).copy(isToken = true)
    private fun looks(prompt: InputPrompt? = null) = Looks(prompt, emptySet(), CardMode.ART, {}, {})
    private fun creatures(cards: List<mtgoracle.core.model.CardState>, prompt: InputPrompt? = null) =
        Lanes(cards, looks(prompt)).creatures.map { it.card.name to it.cards.size }

    @Test
    fun `five tokens alike are one pile, in the place of the first`() {
        assertEquals(listOf("Pilot Token" to 1, "Angel Token" to 13), creatures(listOf(pilot) + angels(13)))
        assertEquals(List(4) { "Angel Token" to 1 }, creatures(angels(4)), "four lie one by one")
    }

    @Test
    fun `one that differs lies apart, and a pile too small for itself comes apart`() {
        val six = angels(6)
        assertEquals(listOf("Angel Token" to 5, "Angel Token" to 1), creatures(six.mapIndexed { i, c -> if (i == 5) c.copy(tapped = true) else c }))
        assertEquals(List(6) { "Angel Token" to 1 }, creatures(six.mapIndexed { i, c -> if (i < 2) c.copy(attacking = true) else c }),
            "two attack: they lie apart, and the four left are too few for a pile")
    }

    @Test
    fun `while attackers or blockers are declared, every token is its own`() {
        for (kind in listOf(InputKind.ATTACK, InputKind.BLOCK)) {
            val prompt = InputPrompt(1, "Declare", kind, "InputAttack", 0, "OK", "", true, false, emptySet(), emptySet())
            assertEquals(List(8) { "Angel Token" to 1 }, creatures(angels(8), prompt), "$kind")
        }
    }
}
