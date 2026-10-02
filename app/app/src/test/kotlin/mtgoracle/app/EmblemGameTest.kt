package mtgoracle.app

import mtgoracle.core.model.BoardState
import mtgoracle.core.model.ChoicePrompt
import mtgoracle.core.model.InputKind
import mtgoracle.core.model.InputPrompt
import mtgoracle.core.model.Prompt
import mtgoracle.core.model.SeatAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An emblem in a real game reaches the board as one: Elspeth's ultimate puts
 * it in the command zone, and the snapshot carries it in the player's
 * `emblems` (Forge's own flag, `CardView.isEmblem`), not with the commanders.
 */
class EmblemGameTest {
    private fun priority(p: Prompt) = p is InputPrompt && p.kind == InputKind.PRIORITY
    private fun BoardState.me() = seat!!

    @Test
    fun `Elspeth's ultimate gives an emblem, and the board has it as one`() {
        val setup = listOf(
            "turn=3", "activeplayer=human", "activephase=MAIN1", "removesummoningsickness=true", "humanlife=20", "ailife=20",
            "humanhand=", "humanbattlefield=Elspeth, Knight-Errant|Counters:LOYALTY=8;Plains", "humangraveyard=",
            "humanlibrary=" + List(20) { "Plains" }.joinToString(";"),
            "aihand=", "aibattlefield=", "ailibrary=" + List(20) { "Swamp" }.joinToString(";"),
        )
        Scenario("emblem-elspeth", setup) { p, b, _ ->
            val elspeth = b.me().battlefield.firstOrNull { it.name == "Elspeth, Knight-Errant" }
            when {
                priority(p) && b.stack.isEmpty() && b.activePlayerId == b.me().id && elspeth != null && b.me().emblems.isEmpty() -> SeatAction.ClickCard(elspeth.id)
                p is ChoicePrompt && p.options.any { it.label.startsWith("-8") } -> SeatAction.Choose(listOf(p.options.indexOfFirst { it.label.startsWith("-8") }))
                priority(p) -> SeatAction.Ok
                else -> null
            }
        }.use { s ->
            s.playUntil { s.board.me().emblems.isNotEmpty() }
            val emblem = s.board.me().emblems.single()
            assertTrue("Elspeth" in emblem.name && "indestructible" in emblem.text.lowercase(), "the emblem, named and with its text: $emblem")
            assertEquals(emptyList(), s.board.me().command.map { it.name }, "not in the command list with the commanders")
            assertEquals(emblem, s.board.card(emblem.id), "found by id, for the zoom pane")
        }
    }
}
