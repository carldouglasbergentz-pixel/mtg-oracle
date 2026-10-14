package mtgoracle.app

import mtgoracle.core.model.InputKind
import mtgoracle.core.model.InputPrompt
import mtgoracle.core.model.SeatAction
import mtgoracle.ui.board.headerNews
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * No one holds priority while attackers or blockers are declared, and the
 * header says so by naming no one: Forge's view still named the last player
 * to pass, so it read `priority: AI` while you declared your attack.
 */
class PriorityHeaderTest {
    private fun library(card: String) = List(20) { card }.joinToString(";")

    @Test
    fun `declaring attackers names no one with priority, and the priority after it names you`() {
        val state = listOf(
            "turn=3", "activeplayer=human", "activephase=MAIN1", "removesummoningsickness=true", "humanlife=20", "ailife=20",
            "humanhand=", "humanbattlefield=Grizzly Bears;Forest", "humanlibrary=${library("Forest")}",
            "aihand=", "aibattlefield=Swamp", "ailibrary=${library("Swamp")}",
        )
        val headers = mutableListOf<Pair<InputKind, String>>()
        Scenario("priority-attack", state) { prompt, board, _ ->
            val kind = (prompt as? InputPrompt)?.kind
            if (kind != null && board.activePlayerId == board.seat?.id) headers += kind to headerNews(board)
            when (kind) {
                InputKind.ATTACK, InputKind.PRIORITY -> SeatAction.Ok
                else -> null
            }
        }.use { s ->
            s.playUntil { headers.any { it.first == InputKind.ATTACK } && headers.last().first == InputKind.PRIORITY && "declare attackers" !in headers.last().second }
            val me = s.board.seat!!.name
            val attack = headers.filter { it.first == InputKind.ATTACK }
            assertTrue(attack.isNotEmpty(), "an attack was asked: $headers")
            attack.forEach { (_, h) -> assertTrue("priority:" !in h, "no one holds priority while attackers are declared: $h") }
            headers.filter { it.first == InputKind.PRIORITY }.forEach { (_, h) -> assertTrue("priority: $me" in h, "your priority says so: $h") }
        }
    }

    @Test
    fun `declaring blockers names no one with priority`() {
        val state = listOf(
            "turn=4", "activeplayer=ai", "activephase=COMBAT_DECLARE_ATTACKERS", "removesummoningsickness=true", "humanlife=20", "ailife=20",
            "humanhand=", "humanbattlefield=Grizzly Bears", "humanlibrary=${library("Island")}",
            "aihand=", "aibattlefield=Colossal Dreadmaw|Attacking", "ailibrary=${library("Swamp")}",
        )
        val headers = mutableListOf<String>()
        Scenario("priority-block", state) { prompt, board, _ ->
            when ((prompt as? InputPrompt)?.kind) {
                InputKind.BLOCK -> { headers += headerNews(board); SeatAction.Ok }
                InputKind.PRIORITY -> SeatAction.Ok
                else -> null
            }
        }.use { s ->
            s.playUntil { headers.isNotEmpty() }
            headers.forEach { assertTrue("priority:" !in it, "no one holds priority while blockers are declared: $it") }
        }
    }
}
