package mtgoracle.app

import mtgoracle.core.model.InputKind
import mtgoracle.core.model.InputPrompt
import mtgoracle.core.model.SeatAction
import mtgoracle.forge.Log
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The user's case: Library of Alexandria tapped by mistake. Forge offers
 * "Undo (1)" on the cancel button, and the prompt says so (cancelUndoes),
 * so the board sends it as an undo instead of holding it behind the
 * floating-mana warning; the undo untaps the land and empties the pool.
 */
class UndoManaTest {
    @AfterTest fun cleanUp() { Log.toFile(null) }

    @Test
    fun `a mana ability tapped by mistake can be undone`() {
        val state = listOf(
            "turn=3", "activeplayer=human", "activephase=MAIN1", "removesummoningsickness=true", "humanlife=20", "ailife=20",
            "humanhand=" + List(7) { "Plains" }.joinToString(";"), "humanbattlefield=Library of Alexandria",
            "humanlibrary=" + List(20) { "Plains" }.joinToString(";"),
            "aihand=", "aibattlefield=", "ailibrary=" + List(20) { "Swamp" }.joinToString(";"),
        )
        var undoPrompt: InputPrompt? = null
        var undone = false
        Scenario("undo-mana", state) { p, b, _ ->
            val me = b.seat!!
            val library = me.battlefield.firstOrNull { it.name == "Library of Alexandria" } ?: return@Scenario null
            when {
                p !is InputPrompt || p.kind != InputKind.PRIORITY -> null
                undoPrompt == null && !library.tapped -> SeatAction.ClickCard(library.id) // the mistake
                undoPrompt == null && library.tapped -> { undoPrompt = p; SeatAction.Cancel }
                undoPrompt != null && !library.tapped -> { undone = me.manaPool.isEmpty(); null }
                else -> null
            }
        }.use { s -> s.playUntil { undone } }
        val prompt = undoPrompt!!
        assertTrue(prompt.cancelLabel.startsWith("Undo"), prompt.cancelLabel)
        assertEquals(true, prompt.cancelUndoes, "the prompt says Cancel is an undo here")
        assertTrue(undone, "the undo untapped Library of Alexandria and emptied the pool")
    }
}
