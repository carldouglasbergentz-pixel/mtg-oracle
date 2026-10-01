package mtgoracle.app

import mtgoracle.core.model.ChoicePrompt
import mtgoracle.core.model.InputKind
import mtgoracle.core.model.InputPrompt
import mtgoracle.core.model.NumberPrompt
import mtgoracle.core.model.SeatAction
import mtgoracle.forge.Log
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * "max affordable" on the X prompt: the cost actually being paid (a
 * miracle's, not the printed one) and the mana the seat really has (a
 * fetchland makes none). Both were wrong in the user's games: eight lands
 * with a fetchland among them, and Entreat the Angels cast by miracle.
 */
class AffordableXTest {
    @AfterTest fun cleanUp() { Log.toFile(null) }

    private fun state(battlefield: List<String>, hand: String, library: List<String>, phase: String = "MAIN1") = listOf(
        "turn=3", "activeplayer=human", "activephase=$phase", "removesummoningsickness=true", "humanlife=20", "ailife=20",
        "humanhand=$hand", "humanbattlefield=" + battlefield.joinToString(";"), "humanlibrary=" + library.joinToString(";"),
        "aihand=", "aibattlefield=Grizzly Bears", "ailibrary=" + List(20) { "Swamp" }.joinToString(";"),
    )

    private fun suggestion(name: String, startState: List<String>, castFromHand: String?): NumberPrompt {
        var seen: NumberPrompt? = null
        Scenario(name, startState) { p, b, _ ->
            when {
                p is NumberPrompt -> { seen = p; SeatAction.Number(0) }
                castFromHand != null && p is InputPrompt && p.kind == InputKind.PRIORITY && b.seat!!.hand.any { it.name == castFromHand } ->
                    SeatAction.ClickCard(b.seat!!.hand.first { it.name == castFromHand }.id)
                p is ChoicePrompt -> SeatAction.Choose(listOf(0))
                p is InputPrompt && p.kind == InputKind.CONFIRM -> SeatAction.Ok
                p is InputPrompt && p.kind == InputKind.PRIORITY -> SeatAction.Ok
                else -> null
            }
        }.use { s -> s.playUntil { seen != null } }
        return seen!!
    }

    private val plains = List(8) { "Plains" }

    @Test
    fun `X for a spell from hand pays the rest of its cost first`() {
        assertEquals(6, suggestion("x-wrath-8", state(plains, "Wrath of the Skies", List(20) { "Plains" }), "Wrath of the Skies").suggested, "8 Plains, {W}{W} of it for the rest")
        assertEquals(2, suggestion("x-entreat-hand", state(plains, "Entreat the Angels", List(20) { "Plains" }), "Entreat the Angels").suggested, "{X}{X}{W}{W}{W}: 5 for two X")
    }

    @Test
    fun `only lands that make mana count`() {
        // Six sources: Arid Mesa makes no mana, and a Saga with no lore counter has no mana ability.
        val lands = listOf("Plains", "Plains", "Scrubland", "Godless Shrine", "Caves of Koilos", "City of Brass", "Arid Mesa", "Urza's Saga")
        assertEquals(4, suggestion("x-wrath-mixed", state(lands, "Wrath of the Skies", List(20) { "Plains" }), "Wrath of the Skies").suggested)
    }

    @Test
    fun `a miracle's X is priced by the miracle cost`() {
        val miracle = suggestion("x-entreat-miracle", state(plains, "", listOf("Entreat the Angels") + List(20) { "Plains" }, phase = "UPKEEP"), null)
        assertEquals("Choose X for Entreat the Angels", miracle.message)
        assertEquals(6, miracle.suggested, "miracle {X}{W}{W} with 8 Plains")
    }
}
