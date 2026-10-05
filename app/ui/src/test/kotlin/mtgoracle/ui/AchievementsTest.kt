package mtgoracle.ui

import mtgoracle.core.play.MatchResult
import mtgoracle.core.play.Winner
import mtgoracle.ui.board.MatchStatus
import mtgoracle.ui.board.ResultPanel
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Forge's achievements, which it shows as a pop-up the app had nowhere to put, are in the result panel. */
class AchievementsTest {
    @Test
    fun `the result panel lists the achievements earned in the match`() {
        val won = MatchResult(Winner.ME, turns = 9, durationMs = 1, summary = "you won", matchOver = true)
        val status = MatchStatus("best of 1", 1, listOf(won), betweenGames = true, over = true,
            achievements = listOf("Overkill: Win a game with opponent at -5 life"))
        OffscreenDriver(900, 400) { ResultPanel(status, {}) }.use { d ->
            d.settle(3)
            val text = d.text.all()
            assertTrue("achievement: Overkill: Win a game with opponent at -5 life" in text, text)
            assertTrue("you won the match" in text, text)
            d.savePng(File(pngDir, "result-achievements.png"))
        }
    }
}
