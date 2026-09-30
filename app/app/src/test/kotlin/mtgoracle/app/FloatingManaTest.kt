package mtgoracle.app

import androidx.compose.ui.input.key.Key
import mtgoracle.core.model.BoardState
import mtgoracle.core.model.InputKind
import mtgoracle.core.model.InputPrompt
import mtgoracle.core.model.PhaseStops
import mtgoracle.core.model.Prompt
import mtgoracle.core.model.SeatAction
import mtgoracle.core.model.Step
import mtgoracle.core.seat.Policy
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Floating mana is a stop, in real Forge games. The user's case: the AI cast
 * Blood Moon, they floated {W}{W} in response to cast Get Lost after it
 * resolved, and passed. The AI passed after it resolved, there was no stop in
 * the AI's main phase, and the phase ended with the mana, the user never
 * asked. And the warning for a pass that would lose mana.
 */
class FloatingManaTest {
    private fun BoardState.ai() = players.first { !it.isSeat }
    private fun BoardState.me() = seat!!
    private fun priority(p: Prompt?) = (p as? InputPrompt)?.kind == InputKind.PRIORITY
    private fun Scenario.priorityNow() = priority(match.seat.prompt.value)
    private fun library(card: String) = List(20) { card }.joinToString(";")
    private val warning = "is floating and empties when this step ends — pass anyway?"
    /** No stop anywhere in the AI's turn: only its spells and our pool can give us priority there. */
    private val noStopsInTheirTurn = PhaseStops(ownTurn = setOf(Step.MAIN1, Step.MAIN2), opponentTurn = emptySet())

    private val bloodMoon = listOf(
        "turn=3", "activeplayer=ai", "activephase=MAIN1", "humanlife=20", "ailife=20",
        "humanhand=Get Lost", "humanbattlefield=Plains;Plains;Plains", "humanlibrary=${library("Plains")}",
        "aihand=Blood Moon", "aibattlefield=Mountain;Mountain;Mountain", "ailibrary=${library("Mountain")}",
    )

    /** With Blood Moon on the stack, tap Plains until {W}{W} floats. */
    private fun floatTwo(b: BoardState): SeatAction? {
        if (b.me().manaPool == "W2") return null
        return b.me().battlefield.firstOrNull { it.name == "Plains" && !it.tapped }?.let { SeatAction.ClickCard(it.id) }
    }

    private fun onStack(b: BoardState, name: String) = b.stack.any { it.sourceName == name }

    @Test
    fun `the user's case - floated in response to Blood Moon, we get priority in the AI's main phase with the mana, and cast Get Lost from it`() {
        var passedWithMana = false
        var heldIn: Triple<String, Boolean, String>? = null
        val policy: Policy = { p, b, _ ->
            val me = b.me()
            when {
                // Get Lost: target Blood Moon, pay {W}{W} from the pool and {1} from the last Plains.
                p is InputPrompt && p.kind == InputKind.TARGET -> b.ai().battlefield.firstOrNull { it.name == "Blood Moon" }?.let { SeatAction.ClickCard(it.id) }
                p is InputPrompt && p.kind == InputKind.PAY_MANA ->
                    if (me.manaPool.contains('W')) SeatAction.UseMana('W')
                    else me.battlefield.firstOrNull { it.name == "Plains" && !it.tapped }?.let { SeatAction.ClickCard(it.id) }
                !priority(p) -> null
                onStack(b, "Blood Moon") -> floatTwo(b) ?: SeatAction.Ok.also { passedWithMana = true }
                passedWithMana && heldIn == null && b.stack.isEmpty() -> {
                    heldIn = Triple(b.phaseKey, b.activePlayerId == b.ai().id, me.manaPool)
                    me.hand.first { it.name == "Get Lost" }.let { SeatAction.ClickCard(it.id) }
                }
                onStack(b, "Get Lost") -> SeatAction.Ok
                else -> null
            }
        }
        Scenario("floating-blood-moon", bloodMoon, policy = policy).use { s ->
            s.match.seat.setStops(noStopsInTheirTurn)
            s.playUntil(timeoutMillis = 120_000) { s.board.ai().graveyard.any { it.name == "Blood Moon" } }
            val (step, aisTurn, pool) = assertNotNull(heldIn, "we got priority after Blood Moon resolved")
            assertTrue(aisTurn, "in the AI's turn")
            assertTrue(step == "MAIN1" || step == "MAIN2", "in its main phase, where no stop is set: $step")
            assertEquals("W2", pool, "with the {W}{W} still floating")
            assertContains(s.logText(), "FLOATING MANA: priority in")
            s.png("floating-blood-moon-destroyed")
        }
    }

    @Test
    fun `F6 with mana floating still stops before the step ends, and skips on once the mana is gone`() {
        val policy: Policy = { p, b, _ -> if (priority(p) && onStack(b, "Blood Moon")) floatTwo(b) else null }
        Scenario("floating-f6", bloodMoon, policy = policy).use { s ->
            // The AI's end step is a stop now: F6 has to be what skips it.
            s.match.seat.setStops(PhaseStops(ownTurn = setOf(Step.MAIN1, Step.MAIN2), opponentTurn = setOf(Step.END_OF_TURN)))
            s.playUntil(timeoutMillis = 120_000) { s.priorityNow() && onStack(s.board, "Blood Moon") && s.board.me().manaPool == "W2" }
            // (A staged game's board counts turns from 1 until the first real turn change: compare, don't expect 3.)
            val theirTurn = s.board.turn
            s.key(Key.F6) // skip the rest of the turn, with {W}{W} floating and Blood Moon on the stack
            s.waitFor(timeoutMillis = 60_000) { s.priorityNow() && s.board.stack.isEmpty() }
            val held = s.board
            assertEquals(held.ai().id, held.activePlayerId)
            assertEquals(theirTurn, held.turn, "still the AI's turn")
            assertEquals("W2", held.me().manaPool, "priority came back with the mana, before the step ended")
            assertTrue(held.ai().battlefield.any { it.name == "Blood Moon" }, "after Blood Moon resolved")
            assertContains(s.logText(), "FLOATING MANA: the end-of-turn yield is held until the pool empties")

            // Pass anyway: the warning, then the mana goes with the step, and F6 carries on.
            s.key(Key.Enter)
            assertContains(s.screenText(), "{W}{W} $warning")
            s.key(Key.Enter)
            val stoppedInTheirTurn = mutableListOf<String>()
            s.waitFor(timeoutMillis = 60_000) {
                val p = s.match.seat.prompt.value
                if (priority(p) && s.board.turn == theirTurn && s.board.me().manaPool.isEmpty()) stoppedInTheirTurn += s.board.phaseKey
                s.board.turn != theirTurn && s.priorityNow()
            }
            assertContains(s.logText(), "FLOATING MANA gone: the end-of-turn yield resumes")
            assertEquals(emptyList(), stoppedInTheirTurn.distinct(), "F6 skipped the rest of the AI's turn, its end-step stop included")
            assertEquals(s.board.me().id, s.board.activePlayerId, "the next stop is in our own turn")
        }
    }

    @Test
    fun `an explicit pass with mana floating and an empty stack asks first - Stay keeps priority, Pass loses the mana`() {
        val state = listOf(
            "turn=3", "activeplayer=human", "activephase=MAIN1", "humanlife=20", "ailife=20",
            "humanhand=", "humanbattlefield=Plains;Plains", "humanlibrary=${library("Plains")}",
            "aihand=", "aibattlefield=", "ailibrary=${library("Swamp")}",
        )
        var tapped = false
        val policy: Policy = { p, b, _ ->
            val plains = b.me().battlefield.firstOrNull { it.name == "Plains" && !it.tapped }
            if (priority(p) && b.phaseKey == "MAIN1" && !tapped && plains != null) SeatAction.ClickCard(plains.id).also { tapped = true } else null
        }
        Scenario("floating-warning", state, policy = policy).use { s ->
            s.playUntil { s.priorityNow() && s.board.me().manaPool == "W1" }
            val prompt = s.match.seat.prompt.value!!
            val before = s.layout()

            s.key(Key.Enter) // OK: pass
            val asked = s.screenText()
            assertContains(asked, "{W} $warning")
            assertContains(asked, "[ Pass ]"); assertContains(asked, "[ Stay ]")
            assertEquals(before, s.layout(), "the warning takes the prompt's own rows; nothing moves")
            s.png("floating-warning")
            assertEquals(prompt.id, s.match.seat.prompt.value?.id, "nothing was sent")

            s.key(Key.Escape) // Stay
            assertFalse(warning in s.screenText(), "the warning is gone")
            assertEquals(prompt.id, s.match.seat.prompt.value?.id, "priority is still ours")
            assertEquals("W1", s.board.me().manaPool, "and so is the mana")

            s.key(Key.Enter); s.key(Key.Enter) // pass, and pass anyway
            s.waitFor { s.board.me().manaPool.isEmpty() && s.board.phaseKey != "MAIN1" }

            // MAIN2 is a stop; the pool is empty there, so a pass goes straight through.
            s.waitFor { s.priorityNow() && s.board.phaseKey == "MAIN2" }
            val main2 = s.match.seat.prompt.value!!.id
            s.key(Key.Enter)
            assertFalse(warning in s.screenText(), "no warning with an empty pool")
            s.waitFor { s.match.seat.prompt.value?.id != main2 }
        }
    }
}
