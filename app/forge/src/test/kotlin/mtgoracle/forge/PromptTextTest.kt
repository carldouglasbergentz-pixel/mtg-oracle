package mtgoracle.forge

import kotlin.test.Test
import kotlin.test.assertEquals

/** Forge's prompt texts, as the seat shows them. */
class PromptTextTest {
    private val labels = listOf("Priority", "Turn", "Phase", "Stack")

    @Test
    fun `the turn, phase, priority and stack lines go, day or night and the storm count stay`() {
        assertEquals("", withoutTurnStatus("Priority: You\nTurn: 4 (AI (Swamp deck))\nPhase: End step\nStack: Empty", labels))
        assertEquals("It is night.\nStorm count: 2", withoutTurnStatus("Priority: You\nTurn: 9 (You)  [Night]\nPhase: Main phase, precombat\nStack: 1 to resolve\nStorm count: 2", labels))
        assertEquals("Mana will be lost: pass anyway?", withoutTurnStatus("Mana will be lost: pass anyway?", labels), "another message is left as it is")
    }

    @Test
    fun `a card option is its name, without Forge's id`() {
        assertEquals("Sylvan Library", withoutCardId("Sylvan Library (900)"))
        assertEquals("Face-down card", withoutCardId("Face-down card (H3)"))
        assertEquals("Fire // Ice", withoutCardId("Fire // Ice"))
    }
}
