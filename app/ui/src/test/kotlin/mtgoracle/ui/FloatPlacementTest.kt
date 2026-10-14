package mtgoracle.ui

import androidx.compose.ui.geometry.Rect
import mtgoracle.ui.board.placeFloating
import mtgoracle.ui.theme.Cells
import kotlin.test.Test
import kotlin.test.assertEquals

/** A floating box opens where it was wanted, else at the free place nearest it, else where it must not cover the least. */
class FloatPlacementTest {
    private val cells = Cells(10f, 20f)
    private fun cellRect(c: Int, r: Int, w: Int, h: Int) = Rect(c * 10f, r * 20f, (c + w) * 10f, (r + h) * 20f)

    @Test
    fun `the wanted place when free, else the nearest free one, else the next list's, else the wanted`() {
        val card = cellRect(10, 10, 5, 5)
        val place = { avoid: List<List<Rect>> -> placeFloating(4, 2, cells, listOf(10 to 10, 30 to 10), 0..40, 0..20, avoid) }
        assertEquals(10 to 10, place(listOf(emptyList())), "nothing in the way")
        assertEquals(30 to 10, place(listOf(listOf(card))), "the next wanted place")
        // Both wanted places taken: the free place nearest the first (just above the card: a row costs two columns, five lie right).
        assertEquals(10 to 8, place(listOf(listOf(card, cellRect(30, 10, 4, 2)))))
        val everywhere = cellRect(0, 0, 50, 30)
        assertEquals(30 to 10, place(listOf(listOf(everywhere), listOf(card))), "nothing free of all: free of what must stay seen")
        assertEquals(10 to 10, place(listOf(listOf(everywhere))), "nothing free at all: the wanted place")
        assertEquals(10 to 18, placeFloating(4, 2, cells, listOf(10 to 25), 0..40, 0..18, listOf(emptyList())), "kept inside the room")
    }
}
