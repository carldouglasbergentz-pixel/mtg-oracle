package mtgoracle.ui

import androidx.compose.runtime.CompositionLocalProvider
import mtgoracle.core.model.DeckEntry
import mtgoracle.core.model.SeatAction
import mtgoracle.core.model.SideboardPrompt
import mtgoracle.ui.board.BoardScreen
import mtgoracle.ui.board.MatchControls
import mtgoracle.ui.board.MatchStatus
import mtgoracle.ui.board.MatchTargets
import mtgoracle.ui.kit.ClickTarget
import mtgoracle.core.play.MatchResult
import mtgoracle.core.play.Winner
import androidx.compose.ui.input.key.Key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import mtgoracle.ui.kit.ArtImages
import mtgoracle.ui.kit.CardMode
import mtgoracle.ui.kit.LocalArt
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Sideboarding between games: the deck and sideboard side by side, a click moves one copy. */
class SideboardTest {
    private val art = ArtImages(FakeArt(File(pngDir, "fake-art")))

    @Test
    fun `a long deck swaps one card for one from the sideboard, by clicking`() {
        // A 100-card singleton list, longer than the screen: the rows below the fold must scroll into reach.
        val main = (1..92).map { DeckEntry("Card %03d".format(it), 1) } + DeckEntry("Mountain", 3) + DeckEntry("Swamp", 5)
        val prompt = SideboardPrompt(9, "You", main, listOf(DeckEntry("Duress", 1)), 60)
        val seat = FakeSeat(sampleBoard(), prompt)
        OffscreenDriver(1800, 1600) { CompositionLocalProvider(LocalArt provides art) { BoardScreen(seat, "MTG Oracle", CardMode.TEXT) } }.use { d ->
            d.settle(5)
            assertTrue("main 100 (at least 60) · sideboard 1" in d.text.all())
            val want = main.associate { it.name to it.count } + ("Card 090" to 0) + ("Duress" to 1)
            assertNotNull(d.perform(prompt, SeatAction.Sideboard(want)))
            val answer = seat.answers.single().second as SeatAction.Sideboard
            assertEquals(100, answer.main.values.sum())
            assertEquals(null, answer.main["Card 090"])
            assertEquals(1, answer.main["Duress"])
            d.savePng(File(pngDir, "sideboard.png"))
        }
    }

    @Test
    fun `between games the result panel continues, and Ctrl+Q opens the concede menu`() {
        val game1 = MatchResult(Winner.OPPONENT, 6, 1000, "AI won", gameNo = 1, conceded = true, wins = 0, losses = 1, matchOver = false)
        var continued = 0
        var conceded = 0
        val seat = FakeSeat(quietBoard(), null)
        var status by mutableStateOf(MatchStatus("best of 3", 3, listOf(game1), betweenGames = true, over = false))
        val controls = MatchControls({ continued++ }, { conceded++ }, {}, {})
        OffscreenDriver(1800, 1600) {
            CompositionLocalProvider(LocalArt provides art) { BoardScreen(seat, "MTG Oracle", CardMode.ART, match = status, matchControls = controls) }
        }.use { d ->
            d.settle(5)
            assertTrue("game 1 of best of 3: you conceded on turn 6" in d.text.all())
            d.savePng(File(pngDir, "match-result.png"))
            assertTrue(d.click(MatchTargets.CONTINUE))
            assertEquals(1, continued)
            status = status.copy(betweenGames = false)
            d.settle(3)
            d.key(Key.Q, ctrl = true)
            assertNotNull(d.registry[ClickTarget.Control("region:concede-menu")])
            d.savePng(File(pngDir, "concede-menu.png"))
            d.key(Key.One)
            assertEquals(1, conceded, "1: concede this game")
        }
    }
}
