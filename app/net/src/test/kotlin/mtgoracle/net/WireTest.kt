package mtgoracle.net

import mtgoracle.core.deck.AiCopy
import mtgoracle.core.deck.Deck
import mtgoracle.core.deck.DeckCard
import mtgoracle.core.model.BoardRef
import mtgoracle.core.model.BoardState
import mtgoracle.core.model.CardState
import mtgoracle.core.model.ChoiceOption
import mtgoracle.core.model.ChoicePrompt
import mtgoracle.core.model.CombatLine
import mtgoracle.core.model.ConfirmPrompt
import mtgoracle.core.model.DeckEntry
import mtgoracle.core.model.DistributePrompt
import mtgoracle.core.model.DistributeTarget
import mtgoracle.core.model.InputKind
import mtgoracle.core.model.InputPrompt
import mtgoracle.core.model.LogCard
import mtgoracle.core.model.LogKind
import mtgoracle.core.model.LogLine
import mtgoracle.core.model.NumberPrompt
import mtgoracle.core.model.OrderPrompt
import mtgoracle.core.model.PhaseStops
import mtgoracle.core.model.PlayerState
import mtgoracle.core.model.SeatAction
import mtgoracle.core.model.SeatCommand
import mtgoracle.core.model.SideboardPrompt
import mtgoracle.core.model.StackEntry
import mtgoracle.core.model.StackKind
import mtgoracle.core.model.Step
import mtgoracle.core.model.TrailEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Every message, and every kind of prompt and answer in them, comes out of the wire as it went in. */
class WireTest {
    private fun card(id: Int, name: String, hidden: Boolean = false) = if (hidden) CardState.back(id) else CardState(
        id = id, name = name, manaCost = "{1}{R}", typeLine = "Creature — Goblin", power = 2, toughness = 1, loyalty = null,
        isLand = false, isCreature = true, tapped = true, summoningSick = false, attacking = true, blocking = false, damage = 1,
        isToken = false, attachedToId = null, text = "Haste\nWhen it dies — draw.", imageKey = "printing:c18/263/Sol Ring",
        counters = "+1/+1 x2", chosen = "Human", castLocked = true,
    )

    private val log = listOf(
        LogLine(1, LogKind.TURN, "Turn 1 (Alice)"),
        LogLine(2, LogKind.CAST, "Alice cast Shock targeting Bob", listOf(LogCard(11, 16, "Shock", 7, card(7, "Shock")))),
        LogLine(3, LogKind.REVEAL, "Shown to you: Island (in Alice's hand)", seenBy = setOf(2)),
    )

    private val board = BoardState(
        turn = 3, phase = "Main phase, precombat", phaseKey = "MAIN1", activePlayerId = 1, activePlayerName = "Alice",
        players = listOf(
            PlayerState(1, "Alice", 18, isAi = false, isSeat = true, hasPriority = true, hasLost = false, handCount = 2,
                hand = listOf(card(10, "Goblin Piker"), card(11, "Mountain")), libraryCount = 50, battlefield = listOf(card(12, "Raging Goblin")),
                graveyard = listOf(card(13, "Shock")), exile = emptyList(), manaPool = "R2", manaEmpties = false, poison = 1,
                command = listOf(card(14, "Jori En, Ruin Diver")), emblems = listOf(card(15, "Emblem"))),
            PlayerState(2, "Bob", 20, isAi = false, isSeat = false, hasPriority = false, hasLost = false, handCount = 1,
                hand = listOf(card(900, "", hidden = true)), libraryCount = 52, battlefield = emptyList(), graveyard = emptyList(),
                exile = listOf(CardState.back(901, faceDown = true)), manaPool = ""),
        ),
        stack = listOf(StackEntry(20, "Shock deals 2 damage to any target.", "Shock", "Alice", sourceCardId = 13, imageKey = "k", controllerId = 1,
            kind = StackKind.TRIGGERED, targetNames = listOf("Bob"), targets = listOf(BoardRef.Player(2), BoardRef.Card(12), BoardRef.StackItem(20)),
            source = card(13, "Shock"))),
        combat = listOf(CombatLine(12, "Bob", listOf(30, 31))),
        log = log, gameOver = false, result = null,
        trail = listOf(TrailEntry(5, 1, "cast Shock", setOf(13))), decisionSeq = 4,
    )

    private val deck = AiCopy.asBuilt(Deck(3, "Red", "duel", null, listOf(DeckCard("Mountain", 24, false, false), DeckCard("Shock", 4, false, false))))

    private val prompts = listOf(
        InputPrompt(1, "Priority", InputKind.PRIORITY, "InputPassPriority", 42, "OK", "End Turn", true, false,
            setOf(1, 2), setOf(3), listOf(card(40, "Island")), setOf(2), cancelUndoes = true),
        ChoicePrompt(2, "Choose one", listOf(ChoiceOption("Draw three cards."), ChoiceOption("Bolt", BoardRef.Card(12), card(12, "Bolt"))), 0, 1),
        ChoicePrompt(3, "Shown to you", listOf(ChoiceOption("Island", card = card(41, "Island"))), -1, -1),
        ConfirmPrompt(4, "Pay 2 life?", "Yes", "No"),
        OrderPrompt(5, "Order the triggers", listOf(ChoiceOption("A"), ChoiceOption("B", BoardRef.StackItem(20))), "first to resolve"),
        DistributePrompt(6, "Assign 4", listOf(DistributeTarget("Bear", BoardRef.Card(30), lethal = 2), DistributeTarget("Bob", BoardRef.Player(2), null, max = 3)),
            total = 4, atLeastOne = true, suggested = listOf(2, 2), excess = 1, inOrder = true),
        NumberPrompt(7, "X", 0, 5, cancellable = true, suggested = 5, note = "max affordable 5"),
        SideboardPrompt(8, "Sideboard", listOf(DeckEntry("Shock", 4)), listOf(DeckEntry("Pyroblast", 2)), minMain = 60),
    )

    private val actions = listOf(
        SeatAction.Sideboard(mapOf("Shock" to 3, "Pyroblast" to 1)), SeatAction.UseMana('R'), SeatAction.ClickCard(12), SeatAction.ClickPlayer(2),
        SeatAction.Ok, SeatAction.Cancel, SeatAction.Choose(listOf(0, 2)), SeatAction.Confirm(false), SeatAction.Order(listOf(1, 0)),
        SeatAction.Distribute(listOf(2, 2)), SeatAction.Number(null), SeatAction.Number(3),
    )

    private fun roundTrip(m: HostMessage) = assertEquals(m, Wire.host(Wire.encode(m)).also { assertFalse('\n' in Wire.encode(m), "one line") })
    private fun roundTrip(m: GuestMessage) = assertEquals(m, Wire.guest(Wire.encode(m)).also { assertFalse('\n' in Wire.encode(m), "one line") })

    @Test
    fun `every host message comes out as it went in`() {
        listOf(
            HostMessage.Hello(PROTOCOL_VERSION, "0.3.0+abc", "Douglas"), HostMessage.Accepted("Bob (2)"), HostMessage.Refused("Your deck has no cards."),
            HostMessage.Board(board.copy(log = emptyList())), HostMessage.Board(null), HostMessage.Log(-1, log), HostMessage.Log(2, emptyList()),
            HostMessage.Ask(null), HostMessage.Stops(PhaseStops(setOf(Step.MAIN1), emptySet())), HostMessage.YieldStatus("yielding"), HostMessage.YieldStatus(null),
            HostMessage.Warning("auto-answered"), HostMessage.End("Alice won the match"),
        ).forEach(::roundTrip)
        prompts.forEach { roundTrip(HostMessage.Ask(it)) }
        assertEquals(board, (Wire.host(Wire.encode(HostMessage.Board(board))) as HostMessage.Board).board, "a whole board, log and all")
    }

    @Test
    fun `every guest message and every answer comes out as it went in`() {
        listOf(
            GuestMessage.Hello(PROTOCOL_VERSION, "0.3.0", "Bob", deck), GuestMessage.SetStops(PhaseStops.DEFAULT),
            GuestMessage.Concede, GuestMessage.Leave,
        ).forEach(::roundTrip)
        SeatCommand.entries.forEach { roundTrip(GuestMessage.Command(it)) }
        actions.forEach { roundTrip(GuestMessage.Answer(99, it)) }
    }

    @Test
    fun `a line that is no message of this protocol is a WireError`() {
        listOf("", "not json", "{}", """{"type":"teleport"}""", """{"type":"answer","promptId":1}""", Wire.encode(HostMessage.Ask(null)).dropLast(3))
            .forEach { line -> assertFailsWith<WireError>(line) { Wire.guest(line) } }
        assertFailsWith<WireError> { Wire.host(Wire.encode(GuestMessage.Leave)) }
    }

    @Test
    fun `defaults stay off the wire`() {
        val plain = CardState.back(1)
        val line = Wire.encode(HostMessage.Board(board.copy(players = board.players.map { it.copy(hand = List(10) { plain }) }, log = emptyList())))
        assertFalse("\"faceDown\":false" in line || "\"counters\":\"\"" in line, line.take(400))
        assertTrue("\"hidden\":true" in line)
    }
}
