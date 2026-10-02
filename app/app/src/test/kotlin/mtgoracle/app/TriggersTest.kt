package mtgoracle.app

import mtgoracle.core.model.BoardState
import mtgoracle.core.model.ChoicePrompt
import mtgoracle.core.model.ConfirmPrompt
import mtgoracle.core.model.InputKind
import mtgoracle.core.model.InputPrompt
import mtgoracle.core.model.NumberPrompt
import mtgoracle.core.model.OrderPrompt
import mtgoracle.core.model.Prompt
import mtgoracle.core.model.SeatAction
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Our own triggered abilities, in real games answered by clicking the board.
 * Until 2026-09-29 every one of them was dropped: Forge asks the GUI which
 * ability to play for each trigger it puts on the stack, and ours answered
 * null (SeatGui.getAbilityToPlay). Each case here must reach the stack, ask
 * whatever it has to ask, and resolve — and nothing may be decided for us.
 */
class TriggersTest {
    private fun library(vararg top: String, rest: String = "Island", n: Int = 20) = (top.toList() + List(n) { rest }).joinToString(";")

    private fun ours(phase: String = "MAIN1", hand: String = "", battlefield: String = "", graveyard: String = "", humanLibrary: String = library(), ai: String = "") = listOf(
        "turn=3", "activeplayer=human", "activephase=$phase", "removesummoningsickness=true", "humanlife=20", "ailife=20",
        "humanhand=$hand", "humanbattlefield=$battlefield", "humangraveyard=$graveyard", "humanlibrary=$humanLibrary",
        "aihand=", "aibattlefield=$ai", "ailibrary=${library(rest = "Swamp")}",
    )

    private fun BoardState.me() = seat!!
    private fun BoardState.inHand(name: String) = me().hand.firstOrNull { it.name == name }
    private fun BoardState.mine(name: String) = me().battlefield.firstOrNull { it.name == name || it.name == "$name Token" }
    private fun priority(p: Prompt) = p is InputPrompt && p.kind == InputKind.PRIORITY
    private fun ourMain(p: Prompt, b: BoardState) = priority(p) && b.stack.isEmpty() && b.activePlayerId == b.me().id
    /** Surveil 1 asks "top of library or graveyard?" as a two-button confirm. */
    private fun surveil(p: Prompt) = p is InputPrompt && p.kind == InputKind.CONFIRM && p.cancelLabel == "Graveyard"

    /** Every scenario ends here: the trigger reached the stack, and nothing was answered for us. */
    private fun Scenario.assertTriggered(card: String) {
        val log = logText()
        assertContains(log, "You triggered $card", message = "the trigger went on the stack")
        assertFalse("UNHANDLED" in log, "nothing was decided for us:\n" + log.lines().filter { "UNHANDLED" in it }.joinToString("\n"))
        assertEquals(null, match.seat.warning.value)
    }

    @Test
    fun `a mandatory ETB played from hand - Meticulous Archive surveils, and we choose the graveyard`() {
        Scenario("trigger-archive-hand", ours(hand = "Meticulous Archive", humanLibrary = library("Opt"))) { p, b, _ ->
            when {
                ourMain(p, b) && b.inHand("Meticulous Archive") != null -> SeatAction.ClickCard(b.inHand("Meticulous Archive")!!.id)
                surveil(p) -> SeatAction.Cancel // surveil: Opt to the graveyard
                priority(p) -> SeatAction.Ok
                else -> null
            }
        }.use { s ->
            s.playUntil { s.board.me().graveyard.any { it.name == "Opt" } }
            s.assertTriggered("Meticulous Archive")
            val asked = s.answered.first { surveil(it.first) }
            assertContains(asked.first.message, "Opt", message = "the surveil prompt named the top card")
            assertEquals(SeatAction.Cancel, asked.second)
        }
    }

    @Test
    fun `a mandatory ETB fetched - Flooded Strand finds Meticulous Archive, it surveils, and we keep the card on top`() {
        Scenario("trigger-archive-fetched", ours(battlefield = "Flooded Strand", humanLibrary = library("Opt", "Meticulous Archive"))) { p, b, _ ->
            when {
                ourMain(p, b) && b.mine("Flooded Strand") != null -> SeatAction.ClickCard(b.mine("Flooded Strand")!!.id)
                p is ChoicePrompt && p.options.any { "Meticulous Archive" in it.label } -> SeatAction.Choose(listOf(p.options.indexOfFirst { "Meticulous Archive" in it.label }))
                surveil(p) -> SeatAction.Ok // surveil: keep it on top
                priority(p) -> SeatAction.Ok
                else -> null
            }
        }.use { s ->
            s.playUntil { "surveiled 1 card" in s.logText() }
            s.assertTriggered("Meticulous Archive")
            // The fetch shuffled the library: whatever is on top, we were asked about it, and kept it.
            assertTrue(s.answered.any { (p, a) -> surveil(p) && a == SeatAction.Ok }, "we were asked, and kept the card on top")
            assertEquals(listOf("Flooded Strand"), s.board.me().graveyard.map { it.name }, "nothing surveiled away")
        }
    }

    @Test
    fun `a targeted ETB - Snapcaster Mage gives Lightning Bolt flashback, the target picked on the board`() {
        Scenario("trigger-snapcaster", ours(hand = "Snapcaster Mage", battlefield = "Island;Island", graveyard = "Lightning Bolt")) { p, b, _ ->
            val bolt = b.me().graveyard.firstOrNull { it.name == "Lightning Bolt" }
            when {
                ourMain(p, b) && b.inHand("Snapcaster Mage") != null -> SeatAction.ClickCard(b.inHand("Snapcaster Mage")!!.id)
                p is InputPrompt && p.kind == InputKind.TARGET && bolt != null -> SeatAction.ClickCard(bolt.id)
                p is ChoicePrompt && p.options.any { "Lightning Bolt" in it.label } -> SeatAction.Choose(listOf(p.options.indexOfFirst { "Lightning Bolt" in it.label }))
                priority(p) -> SeatAction.Ok
                else -> null
            }
        }.use { s ->
            s.playUntil { "Resolve Stack: Snapcaster Mage" in s.logText() && s.logText().contains("triggered Snapcaster Mage") }
            s.assertTriggered("Snapcaster Mage")
            assertContains(s.logText(), "targeting [Lightning Bolt")
        }
    }

    @Test
    fun `an optional trigger - Eternal Witness asks first, and on yes returns Lightning Bolt`() {
        Scenario("trigger-witness", ours(hand = "Eternal Witness", battlefield = "Forest;Forest;Forest", graveyard = "Lightning Bolt")) { p, b, _ ->
            val bolt = b.me().graveyard.firstOrNull { it.name == "Lightning Bolt" }
            when {
                ourMain(p, b) && b.inHand("Eternal Witness") != null -> SeatAction.ClickCard(b.inHand("Eternal Witness")!!.id)
                p is ConfirmPrompt -> SeatAction.Confirm(true)
                p is InputPrompt && p.kind == InputKind.CONFIRM -> SeatAction.Ok
                p is InputPrompt && p.kind == InputKind.TARGET && bolt != null -> SeatAction.ClickCard(bolt.id)
                p is ChoicePrompt && p.options.any { "Lightning Bolt" in it.label } -> SeatAction.Choose(listOf(p.options.indexOfFirst { "Lightning Bolt" in it.label }))
                priority(p) -> SeatAction.Ok
                else -> null
            }
        }.use { s ->
            s.playUntil { s.board.inHand("Lightning Bolt") != null }
            s.assertTriggered("Eternal Witness")
            assertTrue(s.answered.any { (p, _) -> p is ConfirmPrompt || (p as? InputPrompt)?.kind == InputKind.CONFIRM }, "the 'may' was asked")
        }
    }

    @Test
    fun `simultaneous triggers - Soul Warden and Ajani's Welcome both see the Bears, and we order them`() {
        Scenario("trigger-order", ours(hand = "Grizzly Bears", battlefield = "Forest;Forest;Soul Warden;Ajani's Welcome")) { p, b, _ ->
            when {
                ourMain(p, b) && b.inHand("Grizzly Bears") != null -> SeatAction.ClickCard(b.inHand("Grizzly Bears")!!.id)
                p is OrderPrompt -> SeatAction.Order(listOf(1, 0))
                priority(p) -> SeatAction.Ok
                else -> null
            }
        }.use { s ->
            s.playUntil { s.board.me().life == 22 }
            s.assertTriggered("Soul Warden")
            s.assertTriggered("Ajani's Welcome")
            assertNotNull(s.answered.firstOrNull { it.first is OrderPrompt }, "the order was ours to choose")
        }
    }

    @Test
    fun `an attack trigger - an animated Restless Anchorage attacks, makes a Map, and the Map explores`() {
        val state = ours(battlefield = "Restless Anchorage;Plains;Island;Island;Island;Grizzly Bears", humanLibrary = library("Opt"))
        var attacked = false
        Scenario("trigger-anchorage", state) { p, b, _ ->
            val anchorage = b.mine("Restless Anchorage")
            val map = b.mine("Map")
            val bears = b.mine("Grizzly Bears")
            when {
                // Main 1: animate it. Main 2: crack the Map onto the Bears.
                ourMain(p, b) && anchorage != null && !anchorage.isCreature && !attacked -> SeatAction.ClickCard(anchorage.id)
                ourMain(p, b) && map != null && b.step?.name == "MAIN2" -> SeatAction.ClickCard(map.id)
                p is ChoicePrompt && p.options.any { "until end of turn" in it.label.lowercase() } -> SeatAction.Choose(listOf(p.options.indexOfFirst { "until end of turn" in it.label.lowercase() }))
                p is InputPrompt && p.kind == InputKind.ATTACK && anchorage != null ->
                    if (!anchorage.attacking) SeatAction.ClickCard(anchorage.id).also { attacked = true } else SeatAction.Ok
                p is InputPrompt && p.kind == InputKind.TARGET && bears != null -> SeatAction.ClickCard(bears.id)
                p is ConfirmPrompt -> SeatAction.Confirm(true) // explore: Opt to the graveyard
                p is ChoicePrompt && !p.isReveal -> SeatAction.Choose(listOf(0))
                priority(p) -> SeatAction.Ok
                else -> null
            }
        }.use { s ->
            s.playUntil { s.board.mine("Map") != null }
            s.assertTriggered("Restless Anchorage")
            s.playUntil { s.board.mine("Grizzly Bears")?.counters?.isNotEmpty() == true }
            assertContains(s.logText(), "explores")
            assertTrue(s.answered.any { (p, _) -> p is ConfirmPrompt || (p is ChoicePrompt && p.options.any { "Opt" in it.label }) }, "explore asked about the revealed Opt")
            assertFalse("UNHANDLED" in s.logText())
        }
    }

    @Test
    fun `a miracle - Entreat the Angels drawn first, revealed, cast for X = 2, two Angels`() {
        val state = ours(phase = "UPKEEP", battlefield = "Plains;Plains;Plains;Plains", humanLibrary = library("Entreat the Angels"))
        Scenario("trigger-miracle", state) { p, _, _ ->
            when {
                p is ConfirmPrompt -> SeatAction.Confirm(true)
                p is InputPrompt && p.kind == InputKind.CONFIRM -> SeatAction.Ok
                p is NumberPrompt -> SeatAction.Number(2)
                p is ChoicePrompt && p.options.any { "Miracle" in it.label || "miracle" in it.label } ->
                    SeatAction.Choose(listOf(p.options.indexOfFirst { "iracle" in it.label }))
                priority(p) -> SeatAction.Ok
                else -> null
            }
        }.use { s ->
            s.playUntil { s.board.me().battlefield.count { it.name.contains("Angel") } == 2 }
            s.assertTriggered("Entreat the Angels")
            assertTrue(s.answered.any { it.first is NumberPrompt && it.second == SeatAction.Number(2) }, "X was ours to choose")
        }
    }

    @Test
    fun `a delayed trigger - Mana Drain counters the AI's Hill Giant, and our next main phase adds four colourless`() {
        val state = listOf(
            "turn=3", "activeplayer=ai", "activephase=MAIN1", "removesummoningsickness=true", "humanlife=20", "ailife=20",
            "humanhand=Mana Drain", "humanbattlefield=Island;Island", "humanlibrary=${library()}",
            "aihand=Hill Giant", "aibattlefield=Mountain;Mountain;Mountain;Mountain", "ailibrary=${library(rest = "Mountain")}",
        )
        Scenario("trigger-mana-drain", state) { p, b, _ ->
            val drain = b.inHand("Mana Drain")
            when {
                priority(p) && drain != null && b.stack.any { it.sourceName == "Hill Giant" } -> SeatAction.ClickCard(drain.id)
                p is ChoicePrompt && p.options.any { "Hill Giant" in it.label } -> SeatAction.Choose(listOf(p.options.indexOfFirst { "Hill Giant" in it.label }))
                priority(p) -> SeatAction.Ok
                else -> null
            }
        }.use { s ->
            s.playUntil(timeoutMillis = 120_000) { s.board.me().manaPool.contains("C4") }
            s.assertTriggered("Mana Drain")
            assertEquals("MAIN1", s.board.phaseKey, "at the beginning of our next main phase")
            assertEquals(4, s.board.turn, "the very next one")
            assertTrue(s.board.players.first { !it.isSeat }.graveyard.any { it.name == "Hill Giant" }, "and the Giant was countered")
            assertTrue(s.board.trail.any { it.text == "Mana Drain: +{C}×4" }, "the trail says where the mana came from: ${s.board.trail.map { it.text }}")
            assertContains(s.screenText(), "{C}×4", message = "the pool shows it")
        }
    }

    @Test
    fun `a next-upkeep trigger - Summoner's Pact asks us to pay, and paying keeps us in the game`() {
        val state = ours(hand = "Summoner's Pact", battlefield = "Forest;Forest;Forest;Forest", humanLibrary = library("Grizzly Bears"))
        Scenario("trigger-pact", state) { p, b, _ ->
            when {
                ourMain(p, b) && b.inHand("Summoner's Pact") != null && b.turn <= 3 -> SeatAction.ClickCard(b.inHand("Summoner's Pact")!!.id)
                p is ChoicePrompt && p.options.any { "Grizzly Bears" in it.label } -> SeatAction.Choose(listOf(p.options.indexOfFirst { "Grizzly Bears" in it.label }))
                p is ConfirmPrompt -> SeatAction.Confirm(true)
                p is InputPrompt && p.kind == InputKind.CONFIRM -> SeatAction.Ok
                priority(p) -> SeatAction.Ok
                else -> null
            }
        }.use { s ->
            s.playUntil(timeoutMillis = 120_000) { "Resolve Stack: At the beginning of your next upkeep" in s.logText() || s.logText().contains("You triggered Summoner's Pact") && s.board.step?.name == "DRAW" }
            s.assertTriggered("Summoner's Pact")
            assertFalse(s.board.me().hasLost, "we paid")
            assertTrue(s.answered.any { (p, _) -> p is ConfirmPrompt || (p as? InputPrompt)?.kind in setOf(InputKind.CONFIRM, InputKind.PAY_MANA) }, "the payment was ours to make")
        }
    }

    @Test
    fun `a delayed trigger with a choice - Teferi's +1 untaps the two lands we pick at the next end step`() {
        val lands = "Plains|Tapped;Island|Tapped;Island|Tapped;Island|Tapped;Plains|Tapped"
        val picked = mutableSetOf<Int>()
        Scenario("trigger-teferi", ours(battlefield = "Teferi, Hero of Dominaria|Counters:LOYALTY=4;$lands")) { p, b, _ ->
            val teferi = b.mine("Teferi, Hero of Dominaria")
            when {
                // Once: the +1 draws, so an empty hand means it has not been used yet.
                ourMain(p, b) && teferi != null && b.me().hand.isEmpty() -> SeatAction.ClickCard(teferi.id)
                p is ChoicePrompt && p.options.any { it.label.startsWith("+1") } -> SeatAction.Choose(listOf(p.options.indexOfFirst { it.label.startsWith("+1") }))
                // "Untap up to two lands" chooses at resolution, not as targets.
                p is InputPrompt && p.kind == InputKind.SELECT_CARDS && "untap" in p.message -> {
                    val next = b.me().battlefield.firstOrNull { it.isLand && it.tapped && it.id !in picked && it.id in p.selectableCardIds }
                    if (picked.size < 2 && next != null) SeatAction.ClickCard(next.id).also { picked += next.id } else SeatAction.Ok
                }
                priority(p) -> SeatAction.Ok
                else -> null
            }
        }.use { s ->
            s.playUntil { "Resolve Stack: At the beginning of the next end step" in s.logText() }
            s.assertTriggered("Teferi, Hero of Dominaria")
            assertEquals(picked, s.board.me().battlefield.filter { it.isLand && !it.tapped }.map { it.id }.toSet(), "exactly the two we picked")
        }
    }

    @Test
    fun `a targeted ETB, cast - Solitude exiles the AI's Hill Giant, the target ours to pick`() {
        Scenario("trigger-solitude", ours(hand = "Solitude", battlefield = "Plains;Plains;Plains;Plains;Plains", ai = "Hill Giant")) { p, b, _ ->
            val giant = b.players.first { !it.isSeat }.battlefield.firstOrNull { it.name == "Hill Giant" }
            when {
                ourMain(p, b) && b.inHand("Solitude") != null -> SeatAction.ClickCard(b.inHand("Solitude")!!.id)
                p is ChoicePrompt && p.options.any { "Evoke" in it.label } -> SeatAction.Choose(listOf(p.options.indexOfFirst { "Evoke" !in it.label }))
                p is InputPrompt && p.kind == InputKind.TARGET && giant != null && giant.id in p.selectableCardIds -> SeatAction.ClickCard(giant.id)
                priority(p) -> SeatAction.Ok
                else -> null
            }
        }.use { s ->
            s.playUntil { s.board.players.first { !it.isSeat }.exile.any { it.name == "Hill Giant" } }
            s.assertTriggered("Solitude")
            assertTrue(s.answered.any { (p, _) -> (p as? InputPrompt)?.kind == InputKind.TARGET }, "the target was ours to pick")
            assertEquals(23, s.board.players.first { !it.isSeat }.life, "its controller gains life equal to its power")
        }
    }

    @Test
    fun `a targeted ETB, evoked - Solitude's two triggers are ours to order, and the Giant is exiled`() {
        Scenario("trigger-solitude-evoke", ours(hand = "Solitude;Swords to Plowshares", ai = "Hill Giant")) { p, b, _ ->
            val giant = b.players.first { !it.isSeat }.battlefield.firstOrNull { it.name == "Hill Giant" }
            val swords = b.inHand("Swords to Plowshares")
            when {
                ourMain(p, b) && b.inHand("Solitude") != null -> SeatAction.ClickCard(b.inHand("Solitude")!!.id)
                p is ChoicePrompt && p.options.any { "Evoke" in it.label } -> SeatAction.Choose(listOf(p.options.indexOfFirst { "Evoke" in it.label }))
                p is ChoicePrompt && p.options.any { "Swords to Plowshares" in it.label } -> SeatAction.Choose(listOf(p.options.indexOfFirst { "Swords to Plowshares" in it.label }))
                p is InputPrompt && p.kind == InputKind.SELECT_CARDS -> if (swords != null && swords.id in p.selectableCardIds) SeatAction.ClickCard(swords.id) else SeatAction.Ok
                p is OrderPrompt -> SeatAction.Order(listOf(p.items.indexOfFirst { "exile" in it.label.lowercase() }.coerceAtLeast(0)))
                p is InputPrompt && p.kind == InputKind.TARGET && giant != null && giant.id in p.selectableCardIds -> SeatAction.ClickCard(giant.id)
                p is InputPrompt && p.kind == InputKind.TARGET -> SeatAction.Ok
                priority(p) -> SeatAction.Ok
                else -> null
            }
        }.use { s ->
            s.playUntil { s.board.players.first { !it.isSeat }.exile.any { it.name == "Hill Giant" } && s.board.me().graveyard.any { it.name == "Solitude" } }
            s.assertTriggered("Solitude")
            assertNotNull(s.answered.firstOrNull { it.first is OrderPrompt }, "the evoke sacrifice and the ETB were ours to order")
            assertTrue(s.board.me().exile.any { it.name == "Swords to Plowshares" }, "the pitched card paid the evoke")
        }
    }

    @Test
    fun `paying from the pool - Dark Ritual's mana spent on Duress by clicking the pool`() {
        var paid = false
        Scenario("pool-pay", ours(hand = "Dark Ritual;Duress", battlefield = "Swamp")) { p, b, _ ->
            when {
                ourMain(p, b) && b.inHand("Dark Ritual") != null -> SeatAction.ClickCard(b.inHand("Dark Ritual")!!.id)
                ourMain(p, b) && b.inHand("Duress") != null && b.me().manaPool.contains("B3") -> SeatAction.ClickCard(b.inHand("Duress")!!.id)
                p is InputPrompt && p.kind == InputKind.PAY_MANA && b.me().manaPool.isNotEmpty() -> SeatAction.UseMana('B').also { paid = true }
                p is InputPrompt && p.kind == InputKind.TARGET -> SeatAction.ClickPlayer(b.players.first { !it.isSeat }.id)
                priority(p) -> SeatAction.Ok
                else -> null
            }
        }.use { s ->
            s.playUntil { "cast Duress" in s.logText() }
            assertTrue(paid, "the payment prompt came, and we paid from the pool")
            assertTrue(s.board.me().manaPool.contains("B2"), "two of the three left: ${s.board.me().manaPool}")
            s.png("pool-paid")
        }
    }

    @Test
    fun `a may-cast offer - Bloodbraid Elf cascades into Lightning Bolt, which we cast for free at the AI`() {
        Scenario("trigger-cascade", ours(hand = "Bloodbraid Elf", battlefield = "Mountain;Mountain;Forest;Forest", humanLibrary = library("Lightning Bolt"))) { p, b, _ ->
            when {
                ourMain(p, b) && b.inHand("Bloodbraid Elf") != null -> SeatAction.ClickCard(b.inHand("Bloodbraid Elf")!!.id)
                p is ConfirmPrompt -> SeatAction.Confirm(true)
                p is InputPrompt && p.kind == InputKind.CONFIRM -> SeatAction.Ok
                p is InputPrompt && p.kind == InputKind.TARGET -> SeatAction.ClickPlayer(b.players.first { !it.isSeat }.id)
                priority(p) -> SeatAction.Ok
                else -> null
            }
        }.use { s ->
            s.playUntil { s.board.players.first { !it.isSeat }.life == 17 }
            s.assertTriggered("Bloodbraid Elf")
            assertContains(s.logText(), "cast Lightning Bolt")
        }
    }
}
