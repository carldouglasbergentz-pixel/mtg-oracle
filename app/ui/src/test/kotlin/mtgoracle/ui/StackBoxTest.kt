package mtgoracle.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import mtgoracle.core.model.BoardRef
import mtgoracle.core.model.ChoiceOption
import mtgoracle.core.model.ChoicePrompt
import mtgoracle.core.model.InputKind
import mtgoracle.core.model.InputPrompt
import mtgoracle.core.model.SeatAction
import mtgoracle.ui.board.BoardLayout
import mtgoracle.ui.board.BoardScreen
import mtgoracle.ui.board.StackPlacement
import mtgoracle.ui.board.clampCells
import mtgoracle.ui.board.placeStackBox
import mtgoracle.ui.kit.ArtImages
import mtgoracle.ui.kit.CardMode
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.LocalArt
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The floating stack box: over the table, never in its layout, never over what you must click. */
class StackBoxTest {
    private val art = ArtImages(FakeArt(File(pngDir, "fake-art")))
    private val box = ClickTarget.Control("region:stack-box")
    private val bar = ClickTarget.Control("region:stack-bar")

    private fun board(seat: FakeSeat, layout: BoardLayout = BoardLayout(), mode: CardMode = CardMode.ART, onLayout: (BoardLayout) -> Unit = {}) =
        OffscreenDriver(1800, 1600) {
            CompositionLocalProvider(LocalArt provides art) { BoardScreen(seat, "MTG Oracle", mode, layout = layout, onLayoutChange = onLayout) }
        }

    private fun OffscreenDriver.regions(): Map<String, Rect> = registry.targets.filterIsInstance<ClickTarget.Control>()
        .filter { it.name.startsWith("region:") && !it.name.startsWith("region:stack-b") }
        .associate { it.name to registry[it]!! }

    /** A row of creatures across the table, the last ones where the box floats by default. */
    private fun crowded() = sampleBoard().let { b ->
        val ai = b.players.first { !it.isSeat }
        val more = (0 until 7).map { card(60 + it, "Goblin $it", creature = true, cost = "{R}") }
        b.copy(players = b.players.map { if (it.id == ai.id) it.copy(battlefield = it.battlefield + more) else it })
    }

    @Test
    fun `the box takes no layout space - no region moves when it appears, folds or opens`() {
        val seat = FakeSeat(quietBoard(), null)
        board(seat).use { d ->
            d.settle(5)
            val before = d.regions()
            assertNull(d.registry[box], "no box on an empty stack")
            seat.board.value = sampleBoard()
            d.settle(5)
            assertNotNull(d.registry[box], "the box appears with the stack")
            assertEquals(before, d.regions(), "the board did not move")
            d.savePng(File(pngDir, "stack-box.png"))
            d.key(Key.S)
            assertNull(d.registry[box], "S folds it")
            assertNotNull(d.registry[bar], "to the one-line bar")
            assertTrue("stack: 2" in d.text.all())
            d.savePng(File(pngDir, "stack-bar.png"))
            assertEquals(before, d.regions())
            d.key(Key.S)
            assertNotNull(d.registry[box], "S opens it again")
            seat.board.value = quietBoard()
            d.settle(5)
            assertNull(d.registry[box], "and it goes when the stack empties")
            assertEquals(before, d.regions())
        }
    }

    @Test
    fun `a folded box stays folded when the stack fills again`() {
        var saved = BoardLayout()
        val seat = FakeSeat(sampleBoard(), null)
        board(seat, onLayout = { saved = it }).use { d ->
            d.settle(5)
            d.key(Key.S)
            assertTrue(saved.stackCollapsed, "folding is the viewer's choice, persisted")
            seat.board.value = quietBoard(); d.settle(3)
            seat.board.value = sampleBoard(); d.settle(5)
            assertNull(d.registry[box])
            assertNotNull(d.registry[bar])
        }
    }

    @Test
    fun `a legal target under the box moves it across the midline, and back when the prompt is gone`() {
        val seat = FakeSeat(crowded(), null)
        board(seat).use { d ->
            d.settle(5)
            val covered = (60..66).firstOrNull { id -> d.registry[ClickTarget.Card(id)]!!.overlaps(d.registry[box]!!) }
            assertNotNull(covered, "one of the far creatures is under the box where it floats by default")
            val home = d.registry[box]!!
            seat.prompt.value = InputPrompt(5, "Choose a target", InputKind.TARGET, "InputSelectTargets", 0, "", "Cancel", false, true, setOf(covered), emptySet())
            d.settle(6)
            val moved = assertNotNull(d.registry[box], "moved, not folded: there is room across the midline")
            assertFalse(moved.overlaps(d.registry[ClickTarget.Card(covered)]!!), "the target is uncovered")
            d.savePng(File(pngDir, "stack-box-moved.png"))
            assertTrue(d.click(ClickTarget.Card(covered)), "and clickable")
            assertEquals(listOf(SeatAction.ClickCard(covered)), seat.answers.map { it.second })
            seat.prompt.value = null
            d.settle(6)
            assertEquals(home, d.registry[box], "restored afterwards")
        }
    }

    @Test
    fun `the placement rule - where it is, else across the midline, else folded`() {
        val mid = 500f
        val wanted = Rect(100f, 450f, 400f, 550f)
        val above = Rect(100f, 350f, 400f, 490f)
        val below = Rect(100f, 510f, 400f, 650f)
        assertEquals(StackPlacement.AS_SET, placeStackBox(wanted, above, below, mid, listOf(Rect(500f, 0f, 600f, 100f))))
        assertEquals(StackPlacement.ABOVE_MIDLINE, placeStackBox(wanted, above, below, mid, listOf(Rect(150f, 540f, 200f, 600f))))
        assertEquals(StackPlacement.BELOW_MIDLINE, placeStackBox(Rect(100f, 300f, 400f, 480f), above, below, mid, listOf(Rect(150f, 400f, 200f, 470f))))
        assertEquals(StackPlacement.FOLDED, placeStackBox(wanted, above, below, mid, listOf(Rect(150f, 380f, 200f, 620f))))
        assertEquals(0, clampCells(-5, 10, 100)); assertEquals(90, clampCells(95, 10, 100)); assertEquals(0, clampCells(3, 200, 100))
    }

    @Test
    fun `dragging the box by its top edge moves it, and the place it was put persists`() {
        var saved = BoardLayout()
        val seat = FakeSeat(sampleBoard(), null)
        val dragged = board(seat, onLayout = { saved = it }).use { d ->
            d.settle(5)
            val start = d.registry[box]!!
            assertTrue(d.drag(ClickTarget.Control("region:stack-box-handle"), Offset(-280f, -320f)))
            val first = d.registry[box]!!
            assertNotEquals(start, first)
            // A second drag starts from where the first left it.
            assertTrue(d.drag(ClickTarget.Control("region:stack-box-handle"), Offset(140f, 0f)))
            val end = d.registry[box]!!
            assertEquals(first.left + 140f, end.left, 8f, "moved on from the first drop: $first -> $end")
            assertEquals(first.top, end.top, 1f)
            assertNotNull(saved.stackCol, "the new place went to the app to keep")
            end
        }
        board(seat, layout = saved).use { d ->
            d.settle(5)
            assertEquals(dragged, d.registry[box], "a new board opens the box where it was left")
        }
        // A remembered place off a smaller window is pulled back inside it.
        OffscreenDriver(1400, 1000) {
            CompositionLocalProvider(LocalArt provides art) { BoardScreen(seat, "MTG Oracle", CardMode.TEXT, layout = BoardLayout(stackCol = 900, stackRow = 400)) }
        }.use { d ->
            d.settle(5)
            val r = d.registry[box]!!
            assertTrue(r.right <= 1400f && r.bottom <= 1000f && r.left >= 0f && r.top >= 0f, "clamped: $r")
        }
    }

    @Test
    fun `clicking an item in the floating box picks that stack object`() {
        val prompt = ChoicePrompt(3, "Counterspell: choose a target", listOf(ChoiceOption("Lightning Bolt", BoardRef.StackItem(90))), 1, 1)
        val seat = FakeSeat(sampleBoard(), prompt)
        board(seat).use { d ->
            d.settle(5)
            val item = assertNotNull(d.registry[ClickTarget.StackItem(90)])
            assertTrue(item.overlaps(d.registry[box]!!), "the item is the one in the box")
            assertTrue(d.click(ClickTarget.StackItem(90)))
            assertEquals(listOf(SeatAction.Choose(listOf(0))), seat.answers.map { it.second })
        }
    }

    @Test
    fun `the box says who, what kind, the text and the targets - and the target is marked on the table`() {
        val seat = FakeSeat(sampleBoard(), InputPrompt(1, "Priority: You", InputKind.PRIORITY, "InputPassPriority", 0, "OK", "End Turn", true, true, emptySet(), emptySet()))
        board(seat, mode = CardMode.TEXT).use { d ->
            d.settle(5)
            val text = d.text.all()
            assertTrue("▼ You · spell · top" in text || "v You · spell · top" in text, text)
            assertTrue("AI · spell" in text)
            assertTrue("→ Grizzly Bears" in text)
            assertTrue(text.lines().any { it.trim() == "◄" || it.trim() == "<" }, "Grizzly Bears carries the target mark:\n$text")
            assertTrue("played Swamp" in text && "life 15→14" in text, "the trail on the midline")
            d.savePng(File(pngDir, "stack-box-text.png"))
        }
    }
}
