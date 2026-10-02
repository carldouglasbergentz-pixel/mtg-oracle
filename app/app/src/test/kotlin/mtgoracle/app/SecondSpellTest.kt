package mtgoracle.app

import mtgoracle.core.model.ChoicePrompt
import mtgoracle.core.model.InputKind
import mtgoracle.core.model.InputPrompt
import mtgoracle.core.model.SeatAction
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * "Whenever you cast your second spell each turn" (Jori En, Cori-Steel
 * Cutter) triggers on the second spell: the user's Flow State, then Sleight
 * of Hand, on turn 24 of a real game, triggered neither.
 */
class SecondSpellTest {
    private fun library(card: String) = List(20) { card }.joinToString(";")

    /**
     * The user's turns 22 to 24: two spells (the triggers fire), a channel
     * cancelled at its payment, the opponent's turn, then Flow State and
     * Sleight of Hand.
     */
    @Test
    fun `the second-spell triggers still fire two turns after they last did`() {
        val state = listOf(
            "turn=3", "activeplayer=human", "activephase=MAIN1", "removesummoningsickness=true", "humanlife=20", "ailife=20",
            "humanhand=Opt;Ponder;Otawara, Soaring City;Flow State;Sleight of Hand",
            "humanbattlefield=Jori En, Ruin Diver;Cori-Steel Cutter;Island;Island;Island;Island;Island;Mountain",
            "humanlibrary=${library("Island")}",
            "aihand=", "aibattlefield=Grizzly Bears", "ailibrary=${library("Swamp")}",
        )
        var channelled = false
        Scenario("second-spell-later", state) { prompt, board, _ ->
            val hand = board.seat!!.hand
            fun inHand(name: String) = hand.firstOrNull { it.name == name }
            // The turn as the prompt states it: a staged board reads turn 1 until its first update.
            val turn = Regex("""Turn: (\d+) \(You\)""").find(prompt.message)?.groupValues?.get(1)?.toInt()
            val next = if (turn == 3) listOf("Opt", "Ponder").firstNotNullOfOrNull(::inHand)
                else if (turn == 5) listOf("Flow State", "Sleight of Hand").firstNotNullOfOrNull(::inHand) else null
            when {
                prompt is InputPrompt && prompt.kind == InputKind.PRIORITY && board.stack.isEmpty() && next != null -> SeatAction.ClickCard(next.id)
                prompt is InputPrompt && prompt.kind == InputKind.PRIORITY && board.stack.isEmpty() && turn == 3 && !channelled ->
                    inHand("Otawara, Soaring City")?.let { SeatAction.ClickCard(it.id) } ?: SeatAction.Ok
                prompt is ChoicePrompt && prompt.labels.any { it.startsWith("Channel") } -> { channelled = true; SeatAction.Choose(listOf(prompt.labels.indexOfFirst { it.startsWith("Channel") })) }
                prompt is InputPrompt && prompt.kind == InputKind.TARGET -> board.players.first { !it.isSeat }.battlefield.first().let { SeatAction.ClickCard(it.id) }
                prompt is InputPrompt && prompt.kind == InputKind.PAY_MANA && "Channel" in prompt.message -> SeatAction.Cancel
                prompt is InputPrompt && prompt.kind == InputKind.PAY_MANA -> SeatAction.Ok
                prompt is InputPrompt && prompt.kind == InputKind.ATTACK -> SeatAction.Ok
                prompt is InputPrompt && prompt.kind == InputKind.PRIORITY -> SeatAction.Ok
                prompt is ChoicePrompt && prompt.options.isNotEmpty() -> SeatAction.Choose(List(maxOf(1, prompt.min)) { it })
                else -> null
            }
        }.use { s ->
            s.playUntil(timeoutMillis = 120_000) { "Resolve Stack: Sleight of Hand" in s.logText() || s.board.turn > 5 }
            val log = s.logText()
            val turn5 = log.substringAfter("Turn: Turn 5 (You)", "")
            assertTrue(channelled, "the channel was started")
            assertTrue("You triggered Jori En, Ruin Diver" in log.substringBefore("Turn: Turn 4"), "turn 3's Ponder triggered")
            assertTrue("You cast Sleight of Hand" in turn5, "both were cast on turn 5")
            assertTrue("You triggered Jori En, Ruin Diver" in turn5, "Jori En on turn 5: ${turn5.lines().filter { " LOG " in it || " SEAT " in it }.takeLast(30).joinToString("\n")}")
            assertTrue("You triggered Cori-Steel Cutter" in turn5, "Cori-Steel Cutter on turn 5")
        }
    }

    @Test
    fun `a channel cancelled at its payment leaves the second-spell triggers working`() {
        val state = listOf(
            "turn=3", "activeplayer=human", "activephase=MAIN1", "removesummoningsickness=true", "humanlife=20", "ailife=20",
            "humanhand=Otawara, Soaring City;Flow State;Sleight of Hand",
            "humanbattlefield=Jori En, Ruin Diver;Cori-Steel Cutter;Island;Island;Island;Island;Mountain",
            "humanlibrary=${library("Island")}",
            "aihand=", "aibattlefield=Grizzly Bears", "ailibrary=${library("Swamp")}",
        )
        var channelled = false
        Scenario("channel-cancelled", state) { prompt, board, _ ->
            val hand = board.seat!!.hand
            val next = listOf("Flow State", "Sleight of Hand").firstNotNullOfOrNull { name -> hand.firstOrNull { it.name == name } }
            when {
                prompt is InputPrompt && prompt.kind == InputKind.PRIORITY && board.stack.isEmpty() && !channelled ->
                    hand.first { it.name == "Otawara, Soaring City" }.let { SeatAction.ClickCard(it.id) }
                prompt is ChoicePrompt && prompt.labels.any { it.startsWith("Channel") } -> { channelled = true; SeatAction.Choose(listOf(prompt.labels.indexOfFirst { it.startsWith("Channel") })) }
                prompt is InputPrompt && prompt.kind == InputKind.TARGET -> board.players.first { !it.isSeat }.battlefield.first().let { SeatAction.ClickCard(it.id) }
                prompt is InputPrompt && prompt.kind == InputKind.PAY_MANA && "Channel" in prompt.message -> SeatAction.Cancel
                prompt is InputPrompt && prompt.kind == InputKind.PRIORITY && board.stack.isEmpty() && next != null -> SeatAction.ClickCard(next.id)
                prompt is InputPrompt && prompt.kind == InputKind.PRIORITY -> SeatAction.Ok
                prompt is InputPrompt && prompt.kind == InputKind.PAY_MANA -> SeatAction.Ok
                prompt is ChoicePrompt && prompt.options.isNotEmpty() -> SeatAction.Choose(List(maxOf(1, prompt.min)) { it })
                else -> null
            }
        }.use { s ->
            s.playUntil { "Resolve Stack: Sleight of Hand" in s.logText() || s.board.turn > 3 }
            val log = s.logText()
            assertTrue(channelled, "the channel was started")
            assertTrue("You cast Sleight of Hand" in log, "both were cast")
            assertTrue("You triggered Jori En, Ruin Diver" in log, "Jori En: ${log.lines().filter { " LOG " in it || " SEAT " in it }.takeLast(30).joinToString("\n")}")
            assertTrue("You triggered Cori-Steel Cutter" in log, "Cori-Steel Cutter")
        }
    }

    @Test
    fun `Flow State then Sleight of Hand triggers Jori En and Cori-Steel Cutter`() {
        val state = listOf(
            "turn=3", "activeplayer=human", "activephase=MAIN1", "removesummoningsickness=true", "humanlife=20", "ailife=20",
            "humanhand=Otawara, Soaring City;Flow State;Sleight of Hand",
            "humanbattlefield=Jori En, Ruin Diver;Cori-Steel Cutter;Island;Island;Island;Mountain",
            "humangraveyard=Opt;Ponder", "humanlibrary=${library("Island")}",
            "aihand=", "aibattlefield=", "ailibrary=${library("Swamp")}",
        )
        // As on the user's turn: Otawara played as a land (a choice between it and its channel), then the two spells.
        val order = listOf("Otawara, Soaring City", "Flow State", "Sleight of Hand")
        Scenario("second-spell", state) { prompt, board, _ ->
            val next = order.firstNotNullOfOrNull { name -> board.seat!!.hand.firstOrNull { it.name == name } }
            when {
                prompt is InputPrompt && prompt.kind == InputKind.PRIORITY && board.stack.isEmpty() && next != null -> SeatAction.ClickCard(next.id)
                prompt is InputPrompt && prompt.kind == InputKind.PRIORITY -> SeatAction.Ok
                prompt is InputPrompt && prompt.kind == InputKind.PAY_MANA -> SeatAction.Ok
                prompt is ChoicePrompt && prompt.labels.any { it == "Play land" } -> SeatAction.Choose(listOf(prompt.labels.indexOf("Play land")))
                prompt is ChoicePrompt && prompt.options.isNotEmpty() -> SeatAction.Choose(List(maxOf(1, prompt.min)) { it })
                else -> null
            }
        }.use { s ->
            s.playUntil { "Resolve Stack: Sleight of Hand" in s.logText() || s.board.turn > 3 }
            val log = s.logText()
            assertTrue("You cast Sleight of Hand" in log, "both were cast")
            assertTrue("You triggered Jori En, Ruin Diver" in log, "Jori En: ${log.lines().filter { " LOG " in it }.takeLast(20).joinToString("\n")}")
            assertTrue("You triggered Cori-Steel Cutter" in log, "Cori-Steel Cutter")
            assertTrue("You: earlier spells this turn, as Forge counts them: 1 (Flow State)" in log, "the game log shows Forge's count")
        }
    }
}
