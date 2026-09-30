package mtgoracle.ui

import androidx.compose.runtime.CompositionLocalProvider
import mtgoracle.core.deck.Deck
import mtgoracle.core.deck.DeckCard
import mtgoracle.core.deck.DeckSummary
import mtgoracle.core.model.BoardRef
import mtgoracle.core.model.ChoiceOption
import mtgoracle.core.model.ChoicePrompt
import mtgoracle.core.model.DistributePrompt
import mtgoracle.core.model.DistributeTarget
import mtgoracle.core.model.InputKind
import mtgoracle.core.model.InputPrompt
import mtgoracle.core.model.NumberPrompt
import mtgoracle.core.model.OrderPrompt
import mtgoracle.core.model.Prompt
import mtgoracle.core.model.SeatAction
import mtgoracle.core.model.SeatCommand
import mtgoracle.core.model.Step
import mtgoracle.ui.board.BoardScreen
import mtgoracle.ui.kit.ArtImages
import mtgoracle.ui.kit.CardMode
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.ui.kit.LocalArt
import mtgoracle.ui.library.LibraryScreen
import androidx.compose.ui.input.key.Key
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The real board composable, offscreen: rendered, clicked where each control
 * is drawn, and keyed with real key events. PNGs land in build/test-png.
 */
class OffscreenBoardTest {
    private val art = ArtImages(FakeArt(File(pngDir, "fake-art")))

    private fun board(seat: FakeSeat, mode: CardMode = CardMode.ART) = OffscreenDriver(1800, 1600) {
        CompositionLocalProvider(LocalArt provides art) { BoardScreen(seat, "MTG Oracle", mode) }
    }

    @Test
    fun `the board draws every zone, and each card, player and button is clickable where drawn`() {
        val prompt = InputPrompt(1, "Priority: You", InputKind.PRIORITY, "InputPassPriority", 0, "OK", "End Turn", true, true, emptySet(), setOf(20))
        val seat = FakeSeat(sampleBoard(), prompt)
        board(seat).use { d ->
            d.settle(5)
            for (id in listOf(20, 21, 22, 10, 11, 12, 13, 14, 15, 16, 40, 41, 42)) assertNotNull(d.registry[ClickTarget.Card(id)], "card $id is on screen")
            assertNotNull(d.registry[ClickTarget.StackItem(90)], "the stack entry")
            assertTrue(d.click(ClickTarget.Card(20)))
            assertTrue(d.click(ClickTarget.Player(2)))
            assertTrue(d.click(ClickTarget.Ok))
            assertEquals(listOf(SeatAction.ClickCard(20), SeatAction.ClickPlayer(2), SeatAction.Ok), seat.answers.map { it.second })
            d.savePng(File(pngDir, "board-art.png"))
        }
        board(seat, CardMode.TEXT).use { it.settle(5); it.savePng(File(pngDir, "board-text.png")) }
    }

    @Test
    fun `real key presses reach the board - F2 F3 F4 F6, Enter and Esc`() {
        val prompt = InputPrompt(1, "Priority", InputKind.PRIORITY, "InputPassPriority", 0, "OK", "End Turn", true, true, emptySet(), emptySet())
        val seat = FakeSeat(sampleBoard(), prompt)
        board(seat).use { d ->
            d.settle(5)
            listOf(Key.F2, Key.F3, Key.F4, Key.F6).forEach { d.key(it) }
            d.key(Key.Enter, 10)
            d.key(Key.Escape)
            assertEquals(listOf(SeatCommand.PASS, SeatCommand.CANCEL_YIELDS, SeatCommand.END_TURN, SeatCommand.SKIP_TURN), seat.commands)
            assertEquals(listOf(SeatAction.Ok, SeatAction.Cancel), seat.answers.map { it.second })
        }
    }

    @Test
    fun `the zone columns - graveyard and exile hover into the zoom pane and click when legal, stop ladders toggle`() {
        val prompt = InputPrompt(1, "Priority", InputKind.PRIORITY, "InputPassPriority", 0, "OK", "End Turn", true, true, setOf(31), setOf(30))
        val seat = FakeSeat(sampleBoard(), prompt)
        board(seat).use { d ->
            d.settle(5)
            for (id in listOf(30, 31, 32, 43)) assertNotNull(d.registry[ClickTarget.Card(id)], "zone card $id is on screen")
            assertTrue(d.hover(ClickTarget.Card(43)))
            assertTrue("Test text for Thoughtseize." in d.text.all(), "the opponent's graveyard zooms")
            assertTrue(d.click(ClickTarget.Card(31)), "a legal pick in the graveyard")
            assertEquals(listOf(SeatAction.ClickCard(31)), seat.answers.map { it.second })
            for (seatsTurn in listOf(true, false)) for (step in Step.entries) assertNotNull(d.registry[ClickTarget.Stop(seatsTurn, step)], "ladder $seatsTurn $step")
            assertTrue(d.click(ClickTarget.Stop(false, Step.END_OF_TURN)))
            assertTrue(!seat.stops.value.stopsAt(false, Step.END_OF_TURN), "their-turn ladder is in the opponent's column")
        }
    }

    @Test
    fun `the opponent's hand is backs and a count, and nothing names it`() {
        val seat = FakeSeat(sampleBoard(), null)
        board(seat).use { d ->
            d.settle(5)
            for (id in -1 downTo -5) assertEquals(null, d.registry[ClickTarget.Card(id)], "no frame for a hidden hand card")
            assertTrue(d.hover(ClickTarget.Card(-6)), "a hidden exile card is a back you can hover")
            val text = d.text.all()
            assertTrue("hand 5 [#][#][#][#][#]" in text, "count and backs")
            assertTrue("hidden card" in text, "hovering a back shows only that")
            assertTrue("face-down" !in text, "and a hidden card is not called face-down (only one that is face down)")
        }
    }

    @Test
    fun `a decision Forge made for us is never silent - the status line says so`() {
        val seat = FakeSeat(sampleBoard(), null)
        board(seat).use { d ->
            d.settle(3)
            assertTrue("auto-answered" !in d.text.all())
            seat.warning.value = "auto-answered IGuiGame.sideboard: kept the main deck (see the game log)"
            d.settle(3)
            assertTrue("! auto-answered IGuiGame.sideboard: kept the main deck" in d.text.all(), d.text.all())
        }
    }

    @Test
    fun `a phase stop toggles by clicking the stops bar`() {
        val seat = FakeSeat(sampleBoard(), null)
        board(seat).use { d ->
            assertTrue(seat.stops.value.stopsAt(true, Step.MAIN1))
            assertTrue(d.click(ClickTarget.Stop(true, Step.MAIN1)))
            assertTrue(d.click(ClickTarget.Stop(false, Step.UPKEEP)))
            assertTrue(!seat.stops.value.stopsAt(true, Step.MAIN1) && seat.stops.value.stopsAt(false, Step.UPKEEP))
        }
    }

    /** Each dialog kind answered through the driver's click plan, as the scripted seat does. */
    @Test
    fun `every dialog kind can be answered by clicking`() {
        val cases: List<Pair<Prompt, SeatAction>> = listOf(
            ChoicePrompt(2, "Counterspell: choose a target", listOf(ChoiceOption("Lightning Bolt", BoardRef.StackItem(90))), 1, 1) to SeatAction.Choose(listOf(0)),
            ChoicePrompt(3, "Choose two", List(3) { ChoiceOption("option $it") }, 2, 2) to SeatAction.Choose(listOf(2, 0)),
            OrderPrompt(4, "Order the cards on top", List(3) { ChoiceOption("card $it") }, "top") to SeatAction.Order(listOf(1, 2, 0)),
            DistributePrompt(5, "Avatar of Hope: assign 4 damage", listOf(DistributeTarget("Grizzly Bears", BoardRef.Card(41), 2), DistributeTarget("Grizzly Bears", BoardRef.Card(12), 2)), 4, false, listOf(2, 2)) to SeatAction.Distribute(listOf(4, 0)),
            NumberPrompt(6, "Announce X", 0, 5, true) to SeatAction.Number(3),
            mtgoracle.core.model.ConfirmPrompt(7, "Play first?", "Play", "Draw") to SeatAction.Confirm(false),
        )
        for ((prompt, want) in cases) {
            val seat = FakeSeat(sampleBoard(), prompt)
            board(seat).use { d ->
                val clicks = assertNotNull(d.perform(prompt, want), "the board can express $want for ${prompt::class.simpleName}")
                assertEquals(listOf(prompt.id to want), seat.answers, "clicks $clicks")
                d.savePng(File(pngDir, "prompt-${prompt::class.simpleName!!.lowercase()}-${prompt.id}.png"))
            }
        }
    }

    @Test
    fun `the library shows decks, selects on click, and draws a deck in text and art`() {
        val decks = listOf(DeckSummary(1, "Rakdos Midrange", "canlander", 1, "Canadian Highlander", 100), DeckSummary(2, "UW Draw Go", "canlander", 1, "Canadian Highlander", 100))
        val deck = Deck(1, "Rakdos Midrange", "canlander", "Canadian Highlander", listOf(
            DeckCard("Lightning Bolt", 1, false, false, "m10", "146", mtgoracle.core.deck.CardInfo("{R}", "Instant", "Lightning Bolt deals 3 damage to any target.", null, null)),
            DeckCard("Swamp", 8, false, false, info = mtgoracle.core.deck.CardInfo("", "Basic Land — Swamp", "", null, null)),
        ))
        for (mode in CardMode.entries) {
            var selected = 1
            OffscreenDriver(1800, 1000) {
                CompositionLocalProvider(LocalArt provides art) {
                    LibraryScreen(decks, selected, deck, { "c:${it.name}" }, mode, null, { selected = it }, {}, {}, {}, {})
                }
            }.use { d ->
                d.settle(5)
                val rect = d.registry[ClickTarget.Control("deck:2")]
                assertTrue(d.click(ClickTarget.Control("deck:2")))
                assertEquals(2, selected, "deck:2 at $rect; registry ${d.registry.targets.filterIsInstance<ClickTarget.Control>().take(12)}")
                d.savePng(File(pngDir, "library-${mode.name.lowercase()}.png"))
            }
        }
    }
}
