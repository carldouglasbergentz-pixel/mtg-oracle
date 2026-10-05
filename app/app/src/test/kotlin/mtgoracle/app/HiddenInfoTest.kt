package mtgoracle.app

import androidx.compose.ui.input.key.Key
import mtgoracle.core.model.BoardState
import mtgoracle.core.model.ChoicePrompt
import mtgoracle.core.model.InputKind
import mtgoracle.core.model.InputPrompt
import mtgoracle.core.model.SeatAction
import mtgoracle.core.play.GameMode
import mtgoracle.ui.kit.CardMode
import mtgoracle.ui.kit.ClickTarget
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Hidden information, in real Forge games rendered offscreen: the opponent's
 * hand, face-down permanents and library never reach the screen — not the
 * board, not the zoom pane, not the log — until Forge reveals them to us.
 */
class HiddenInfoTest {
    private fun BoardState.ai() = players.first { !it.isSeat }
    private fun ourPriority(s: Scenario) = (s.match.seat.prompt.value as? InputPrompt)?.kind == InputKind.PRIORITY && s.board.activePlayerId == s.board.seat?.id

    @Test
    fun `the opponent's hand, face-down creature and library are never drawn, anywhere`() {
        Scenario("hidden-info", StagedBoards.hiddenInfo, mode = CardMode.ART) { _, _, _ -> null }.use { s ->
            s.playUntil { ourPriority(s) }
            val ai = s.board.ai()
            assertEquals(3, ai.handCount)
            assertTrue(ai.hand.all { it.hidden && it.name.isEmpty() && it.imageKey == null }, "the seat receives backs only")
            val faceDown = ai.battlefield.single { it.faceDown }
            assertEquals("Face-down", faceDown.name)
            // Hover everything the opponent has on the table: the zoom pane must not learn more either.
            ai.battlefield.forEach { s.hover(ClickTarget.Card(it.id)) }
            val text = s.screenText()
            for (name in StagedBoards.hiddenNames) assertFalse(name in text, "'$name' appeared on screen")
            assertContains(text, "hand 3 [#][#][#]")
            assertContains(text, "Akroma, Angel of Fury", message = "our own face-down creature we may look at")
            s.png("hidden-info")
        }
    }

    @Test
    fun `Thoughtseize shows the revealed hand, and what stayed in it is known there after`() {
        var revealedText = ""
        Scenario("reveal", StagedBoards.reveal, mode = CardMode.ART) { prompt, board, _ ->
            when {
                prompt is InputPrompt && prompt.kind == InputKind.PRIORITY && board.stack.isEmpty() ->
                    board.seat!!.hand.firstOrNull { it.name == "Thoughtseize" }?.let { SeatAction.ClickCard(it.id) } ?: SeatAction.Ok
                prompt is InputPrompt && prompt.kind == InputKind.TARGET -> SeatAction.ClickPlayer(board.ai().id)
                prompt is ChoicePrompt && prompt.labels.any { "Time Walk" in it } -> SeatAction.Choose(listOf(prompt.labels.indexOfFirst { "Time Walk" in it }))
                else -> null
            }
        }.use { s ->
            // Hold the reveal open: stop at the discard choice and look.
            s.playUntil { (s.match.seat.prompt.value as? ChoicePrompt)?.labels?.any { "Time Walk" in it } == true }
            revealedText = s.screenText()
            s.png("reveal-active")
            assertContains(revealedText, "Time Walk")
            assertContains(revealedText, "Black Lotus", message = "Thoughtseize reveals the whole hand")
            // Now answer it (discard Time Walk) and let Thoughtseize resolve.
            s.playUntil { s.board.ai().graveyard.any { it.name == "Time Walk" } && s.match.seat.prompt.value is InputPrompt }
            // A player at the table remembers the hand they saw (KnownInHand), until it loses a card unseen.
            val ai = s.board.ai()
            assertEquals(listOf("Black Lotus"), ai.hand.map { it.name }, "the card left in the hand stays known: ${ai.hand}")
            assertFalse(ai.hand.single().hidden)
            s.png("reveal-over")
        }
    }

    @Test
    fun `watching AI vs AI hides both hands until H is pressed`() {
        Scenario("watch", StagedBoards.watch, mode = CardMode.ART, gameMode = GameMode.AI_VS_AI) { _, _, _ -> null }.use { s ->
            s.waitFor { s.match.seat.board.value?.players?.all { it.handCount >= 2 } == true }
            assertTrue(s.match.seat.canShowAllHands && !s.match.seat.showAllHands.value, "off by default")
            val hidden = s.screenText()
            for (name in StagedBoards.watchHands) assertFalse(name in hidden, "'$name' shown with hands hidden")
            s.png("watch-hands-hidden")
            s.key(Key.H)
            s.waitFor { s.match.seat.showAllHands.value && s.board.players.all { p -> p.hand.none { it.hidden } } }
            val shown = s.screenText()
            assertTrue(StagedBoards.watchHands.count { it in shown } >= 3, "the hands are drawn once H is pressed")
            s.png("watch-hands-shown")
        }
    }
}
