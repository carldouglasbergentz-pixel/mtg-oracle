package mtgoracle.app

import androidx.compose.ui.input.key.Key
import mtgoracle.core.model.BoardState
import mtgoracle.core.model.ChoicePrompt
import mtgoracle.core.model.DistributePrompt
import mtgoracle.core.model.InputKind
import mtgoracle.core.model.InputPrompt
import mtgoracle.core.model.NumberPrompt
import mtgoracle.core.model.OrderPrompt
import mtgoracle.core.model.SeatAction
import mtgoracle.core.model.Step
import mtgoracle.core.seat.Policy
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Every prompt type the spike auto-answered or never reached, in a real Forge
 * game, answered by clicking (or keying) the real board. Each scenario sets the
 * situation with a Forge GameState, so it happens every time.
 */
class PromptScenariosTest {
    private fun library(card: String, n: Int = 20) = List(n) { card }.joinToString(";")

    /** Human's precombat main phase on turn 3, with [hand] and [battlefield]; the AI holds nothing. */
    private fun mainPhase(hand: String, battlefield: String, humanLibrary: String = library("Island"), ai: String = "") = listOf(
        // Lives too: the pinned Forge sets an unlisted life to -1, which ends the game at once.
        "turn=3", "activeplayer=human", "activephase=MAIN1", "removesummoningsickness=true", "humanlife=20", "ailife=20",
        "humanhand=$hand", "humanbattlefield=$battlefield", "humanlibrary=$humanLibrary",
        "aihand=", "aibattlefield=$ai", "ailibrary=${library("Swamp")}",
    )

    private fun BoardState.inHand(name: String) = seat?.hand?.firstOrNull { it.name == name }
    private fun BoardState.ai() = players.first { !it.isSeat }

    /** Casts [card] at the first priority where it's in hand; the rest to [then]. */
    private fun casting(card: String, then: Policy): Policy = { prompt, board, attempt ->
        val inHand = board.inHand(card)
        if (prompt is InputPrompt && prompt.kind == InputKind.PRIORITY && inHand != null && board.stack.isEmpty()) SeatAction.ClickCard(inHand.id)
        else then(prompt, board, attempt)
    }

    @Test
    fun `London mulligan - mulligan once, keep six, put one card on the bottom by clicking it`() {
        var mulligans = 0
        Scenario("london-mulligan", null) { prompt, board, _ ->
            when {
                prompt is InputPrompt && prompt.kind == InputKind.MULLIGAN -> if (mulligans++ == 0) SeatAction.Cancel else SeatAction.Ok
                prompt is InputPrompt && prompt.kind == InputKind.SELECT_CARDS && prompt.inputName == "InputLondonMulligan" ->
                    if (prompt.okEnabled) SeatAction.Ok else SeatAction.ClickCard(board.seat!!.hand!!.first().id)
                else -> null
            }
        }.use { s ->
            s.playUntil { " kept a hand of 6" in s.logText() }
            val log = s.logText()
            assertContains(log, "InputLondonMulligan")
            assertContains(log, "You has kept a hand of 6")
            assertTrue(s.answered.any { (p, a) -> (p as? InputPrompt)?.inputName == "InputLondonMulligan" && a is SeatAction.ClickCard })
        }
    }

    @Test
    fun `picking from a pile - Gush returns two Islands, each clicked on the table`() {
        val clicked = mutableListOf<Int>()
        Scenario("gush-pile", mainPhase("Gush", "Island;Island;Mountain")) { prompt, board, _ ->
            val islands = board.seat!!.battlefield.filter { it.name == "Island" }.map { it.id }
            when {
                prompt is ChoicePrompt -> SeatAction.Choose(listOf(prompt.options.indexOfFirst { "Island" in it.label || "rather" in it.label }.coerceAtLeast(0)))
                prompt is InputPrompt && prompt.kind == InputKind.PRIORITY && board.stack.isEmpty() -> board.inHand("Gush")?.let { SeatAction.ClickCard(it.id) } ?: SeatAction.Ok
                prompt is InputPrompt && prompt.kind == InputKind.SELECT_CARDS && prompt.okEnabled -> SeatAction.Ok
                prompt is InputPrompt && prompt.kind == InputKind.SELECT_CARDS ->
                    islands.firstOrNull { it !in clicked }?.let { clicked += it; SeatAction.ClickCard(it) }
                prompt is InputPrompt && prompt.kind == InputKind.PRIORITY -> SeatAction.Ok
                else -> null
            }
        }.use { s ->
            s.playUntil { s.board.seat!!.hand.count { it.name == "Island" } == 2 }
            assertEquals(2, clicked.size, "both Islands were clicked on the board, the second out of the pile the first had left")
            s.png("scenario-gush-pile")
        }
    }

    @Test
    fun `X cost - Blaze announces X through the number prompt, then targets the AI`() {
        Scenario("x-cost", mainPhase("Blaze", "Mountain;Mountain;Mountain;Mountain")) { prompt, board, _ ->
            when (prompt) {
                is NumberPrompt -> SeatAction.Number(3)
                is InputPrompt -> when (prompt.kind) {
                    InputKind.PRIORITY -> board.inHand("Blaze")?.let { SeatAction.ClickCard(it.id) } ?: SeatAction.Ok
                    InputKind.TARGET -> SeatAction.ClickPlayer(board.ai().id)
                    else -> null
                }
                else -> null
            }
        }.use { s ->
            s.playUntil { s.board.ai().life == 17 }
            assertTrue(s.answered.any { it.first is NumberPrompt && it.second == SeatAction.Number(3) })
            s.png("scenario-x-cost")
        }
    }

    @Test
    fun `targets on the stack - Counterspell picks Lightning Bolt by clicking it in the floating stack box`() {
        Scenario("stack-target", mainPhase("Lightning Bolt;Counterspell", "Mountain;Island;Island")) { prompt, board, _ ->
            when {
                prompt is InputPrompt && prompt.kind == InputKind.PRIORITY && board.stack.isEmpty() -> board.inHand("Lightning Bolt")?.let { SeatAction.ClickCard(it.id) } ?: SeatAction.Ok
                prompt is InputPrompt && prompt.kind == InputKind.PRIORITY -> board.inHand("Counterspell")?.let { SeatAction.ClickCard(it.id) } ?: SeatAction.Ok
                prompt is InputPrompt && prompt.kind == InputKind.TARGET -> SeatAction.ClickPlayer(board.ai().id)
                prompt is ChoicePrompt -> SeatAction.Choose(listOf(prompt.options.indexOfFirst { it.label.contains("Lightning Bolt") }))
                else -> null
            }
        }.use { s ->
            s.playUntil { "Resolve Stack: Counterspell" in s.logText() || s.board.seat!!.graveyard.size >= 2 }
            val stackChoice = s.answered.first { it.first is ChoicePrompt }
            assertNotNull((stackChoice.first as ChoicePrompt).options.first { it.label.contains("Lightning Bolt") }.ref, "the option is a stack object")
            assertContains(s.logText(), "cast Counterspell targeting")
            assertEquals(20, s.board.ai().life, "Bolt was countered")
            s.png("scenario-stack-target")
        }
    }

    @Test
    fun `blockers and damage assignment - one Avatar blocks two Bears and splits its damage`() {
        val state = listOf(
            "turn=4", "activeplayer=ai", "activephase=COMBAT_DECLARE_ATTACKERS", "removesummoningsickness=true", "humanlife=20", "ailife=20",
            "humanhand=", "humanbattlefield=Avatar of Hope", "humanlibrary=${library("Island")}",
            "aihand=", "aibattlefield=Grizzly Bears|Attacking;Grizzly Bears|Attacking", "ailibrary=${library("Swamp")}",
        )
        // InputBlock: click an attacker to make it current, then your creature to block it.
        Scenario("block-and-damage", state) { prompt, board, _ ->
            when {
                prompt is InputPrompt && prompt.kind == InputKind.BLOCK -> {
                    val avatar = board.seat!!.battlefield.first { it.name == "Avatar of Hope" }
                    val unblocked = board.combat.filter { avatar.id !in it.blockerIds }.map { it.attackerId }
                    when {
                        unblocked.isEmpty() -> SeatAction.Ok
                        prompt.message.contains("(${unblocked.first()})") -> SeatAction.ClickCard(avatar.id)
                        else -> SeatAction.ClickCard(unblocked.first())
                    }
                }
                prompt is DistributePrompt -> SeatAction.Distribute(listOf(4, 0).take(prompt.targets.size))
                else -> null
            }
        }.use { s ->
            s.playUntil { s.board.ai().graveyard.isNotEmpty() }
            val split = s.answered.first { it.first is DistributePrompt }
            assertEquals(listOf(2, 2), (split.first as DistributePrompt).suggested, "Forge suggested lethal to each")
            assertEquals(1, s.board.ai().graveyard.count { it.name == "Grizzly Bears" }, "our 4/0 split was honoured: one Bear dies")
            assertTrue(s.answered.any { (p, _) -> (p as? InputPrompt)?.kind == InputKind.BLOCK })
            s.png("scenario-block-damage")
        }
    }

    @Test
    fun `ordering - Preordain scries 2, keeps both, orders them, and draws the one put first`() {
        Scenario("order-scry", mainPhase("Preordain", "Island", humanLibrary = "Forest;Plains;" + library("Island"))) { prompt, _, _ ->
            when (prompt) {
                is ChoicePrompt -> if (prompt.isReveal) null else SeatAction.Choose(emptyList()) // nothing to the bottom
                is OrderPrompt -> SeatAction.Order(listOf(prompt.items.indexOfFirst { it.label.startsWith("Plains") }))
                else -> null
            }
        }.use { s ->
            s.playUntil { s.board.seat?.hand?.any { it.name == "Plains" || it.name == "Forest" } == true }
            val order = s.answered.first { it.first is OrderPrompt }
            assertEquals(2, (order.first as OrderPrompt).items.size)
            assertTrue(s.board.inHand("Plains") != null, "we put Plains first, and Preordain drew it")
            s.png("scenario-order")
        }
    }

    @Test
    fun `F4 ends the turn and F3 cancels the yield, through the keyboard`() {
        Scenario("f-keys", mainPhase("", "Island")) { _, _, _ -> null }.use { s ->
            s.match.seat.setStops(s.match.seat.stops.value.copy(opponentTurn = setOf(Step.END_OF_TURN)))
            // Play through the opening prompts up to our first priority, then press F4 instead of answering.
            val ourPriority = { (s.match.seat.prompt.value as? InputPrompt)?.kind == InputKind.PRIORITY && s.board.activePlayerId == s.board.seat?.id }
            s.playUntil { ourPriority() }
            val deadline = System.currentTimeMillis() + 60_000
            val turn = s.board.turn
            s.key(Key.F4)
            while (!(s.board.turn > turn && (s.match.seat.prompt.value as? InputPrompt)?.kind == InputKind.PRIORITY) && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertTrue(s.board.turn > turn, "F4 passed the rest of our turn")
            assertEquals(Step.END_OF_TURN, s.board.step, "and we next stopped at the opponent's end step")
            assertContains(s.logText(), "COMMAND END_TURN")
            s.key(Key.F3)
            assertContains(s.logText(), "COMMAND CANCEL_YIELDS")
        }
    }
}
