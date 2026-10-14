package mtgoracle.app

import mtgoracle.core.model.ChoicePrompt
import mtgoracle.core.model.InputKind
import mtgoracle.core.model.InputPrompt
import mtgoracle.core.model.OrderPrompt
import mtgoracle.core.model.SeatAction
import mtgoracle.forge.Log
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Jace, the Mind Sculptor's 0 puts two cards back "in any order", as
 * Brainstorm does, and asks the order (CR 401.4). Forge's script lacked
 * Reorder$ True: the two went back as picked, unasked. Fixed in our Forge
 * build (Card-Forge/forge#12262) until a Forge release has it.
 */
class PutBackOrderTest {
    @AfterTest fun cleanUp() { Log.toFile(null) }

    @Test
    fun `Jace's 0 asks the order of the two cards put back`() {
        val state = listOf(
            "turn=3", "activeplayer=human", "activephase=MAIN1", "removesummoningsickness=true", "humanlife=20", "ailife=20",
            "humanhand=Opt;Counterspell", "humanbattlefield=Jace, the Mind Sculptor|Counters:LOYALTY=3",
            "humanlibrary=" + List(20) { "Island" }.joinToString(";"),
            "aihand=", "aibattlefield=", "ailibrary=" + List(20) { "Swamp" }.joinToString(";"),
        )
        var activated = false
        var picked = 0
        val asked = mutableListOf<OrderPrompt>()
        Scenario("put-back-order", state) { p, b, _ ->
            val me = b.seat!!
            when {
                p is InputPrompt && p.kind == InputKind.PRIORITY && !activated -> { activated = true; SeatAction.ClickCard(me.battlefield.first { it.name == "Jace, the Mind Sculptor" }.id) }
                p is ChoicePrompt && p.labels.any { it.startsWith("0:") } -> SeatAction.Choose(listOf(p.labels.indexOfFirst { it.startsWith("0:") }))
                p is InputPrompt && p.kind == InputKind.SELECT_CARDS && picked < 2 -> {
                    picked++
                    SeatAction.ClickCard(me.hand.first { it.name == if (picked == 1) "Opt" else "Counterspell" }.id)
                }
                p is OrderPrompt -> { asked += p; SeatAction.Order(listOf(1, 0)) }
                else -> null
            }
        }.use { s ->
            s.playUntil { asked.isNotEmpty() || (picked == 2 && s.board.stack.isEmpty() && s.board.seat!!.hand.none { it.name == "Opt" }) }
            assertEquals(1, asked.size, "asked the order of the two put back")
            assertTrue(asked.single().items.map { it.label }.let { l -> l.any { "Opt" in it } && l.any { "Counterspell" in it } }, "${asked.single().items}")
        }
    }
}
