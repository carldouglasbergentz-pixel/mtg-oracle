package mtgoracle.ui

import mtgoracle.ui.kit.CardMode
import mtgoracle.ui.board.Lanes
import mtgoracle.ui.board.Looks
import mtgoracle.ui.kit.face
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What was chosen for a card shows on it: Cavern of Souls is no use to read
 * until you know which type it named, and two Caverns that named different
 * types are two piles, not one.
 */
class ChosenTest {
    private fun cavern(id: Int, type: String) = card(id, "Cavern of Souls", land = true).copy(chosen = type)

    @Test
    fun `the frame and the zoom say what was chosen`() {
        val face = cavern(1, "Human").face()
        assertTrue("[Human]" in face.stats, face.stats)
        assertTrue(face.text.startsWith("Chosen: Human"), face.text)
        val plain = card(2, "Island", land = true).face()
        assertTrue("[" !in plain.stats && !plain.text.startsWith("Chosen"), "no choice, no mark")
    }

    @Test
    fun `lands that chose differently are separate piles`() {
        val lanes = Lanes(listOf(cavern(1, "Human"), cavern(2, "Elf"), cavern(3, "Human")), Looks(null, emptySet(), CardMode.ART, {}, {}))
        assertEquals(listOf(listOf(1, 3), listOf(2)), lanes.lands.map { slot -> slot.cards.map { it.id } })
    }
}
