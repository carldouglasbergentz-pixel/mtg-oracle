package mtgoracle.forge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Forge's achievement pop-up, its name (and rarity) over what it is for, as one line. */
class AchievementLineTest {
    @Test
    fun `the name without its rarity, then what it is for`() {
        assertEquals("Overkill: Win a game with opponent at -5 life", achievementLine("Overkill (Common)\nWin a game with opponent at -5 life"))
        assertEquals("Jace's Lobotomy: Win a game after activating Jace, the Mind Sculptor ultimate",
            achievementLine("Jace's Lobotomy\nWin a game after activating Jace, the Mind Sculptor ultimate"))
        assertEquals("Commander", achievementLine("Commander (Common)"))
        assertNull(achievementLine("  \n "))
    }
}
