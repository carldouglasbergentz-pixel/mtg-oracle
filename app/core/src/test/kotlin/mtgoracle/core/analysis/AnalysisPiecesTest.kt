package mtgoracle.core.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The analysis engine's pieces without a database: Python's numbers, the draw maths, and the rules that settled a card. */
class AnalysisPiecesTest {

    @Test
    fun `numbers format and sum as Python's do`() {
        assertEquals("0.2", Py.fixed(0.25, 1), "half to even on the exact value, where Java's %.1f says 0.3")
        assertEquals("0.3", Py.fixed(0.35, 1), "0.35 is 0.34999... as a double")
        assertEquals("-0", Py.fixed(-0.4, 0, plus = true), "the sign survives rounding to zero")
        assertEquals("+3", Py.fixed(2.6, 0, plus = true))
        assertTrue(1.0 / Py.round(-0.00004, 4) < 0, "round keeps the negative zero")
        assertEquals(1.0, Py.sum(List(10) { 0.1 }), "compensated, as sum() is since Python 3.12")
        assertEquals("-2.5", Py.general(-2.5))
        assertEquals("3", Py.general(3.0))
        assertEquals("'Blue Moon'", Py.repr("Blue Moon"))
        assertEquals("\"Thassa's Oracle\"", Py.repr("Thassa's Oracle"))
    }

    @Test
    fun `the draw maths is exact and the on-curve odds only grow`() {
        assertEquals(0.07, Probability.holdAny(1, 7, 100), 1e-12)
        assertEquals(1.0, Probability.holdAny(40, 70, 100), "more cards seen than misses")
        assertEquals(0.0, Probability.holdAtLeast(3, 4, 10, 100))
        val curve = Probability.curve(mapOf(1 to 4, 2 to 6, 3 to 5), lands = 38, rocks = 2, turns = (1..8).toList())
        assertTrue(curve.values.zipWithNext().all { (a, b) -> b >= a }, curve.toString())
        val ceiling = Probability.ceiling(15, (1..8).toList())
        assertTrue(curve.all { (t, p) -> p <= ceiling.getValue(t) }, "holding one is the ceiling on casting one")
        assertEquals(1.0, Probability.holdAny(1, 200, 60), "a small deck late on has been seen whole")
    }

    @Test
    fun `on curve is Moxfield's number when rocks are left out`() {
        // Elminster Boomer Wizard on Moxfield, 2026-09-30: 43 land sources in a 99-card library,
        // "a 99.17% chance of playing these on curve" for the one-drops, on the draw.
        assertEquals(0.9917, Probability.manaOnTurn(lands = 43, rocks = 0, turn = 1, cost = 1, onPlay = false, deckSize = 99), 5e-5)
        assertEquals(1.0, Probability.manaOnTurn(lands = 0, rocks = 0, turn = 1, cost = 0))
        val without = Probability.manaOnTurn(40, 0, 3, 3)
        val with = Probability.manaOnTurn(38, 2, 3, 3)
        assertEquals(without, with, 1e-12, "on curve a rock pays like a land: cost N on turn N needs N sources either way")
        assertTrue(Probability.manaOnTurn(40, 0, 3, 3, onPlay = false) > without, "the draw sees one card more")
    }

    private fun card(name: String, cost: String, mv: Int, type: String, text: String, layout: String = "normal") =
        CardFacts(name, cost, mv, type, text, layout = layout)

    @Test
    fun `what a card really costs`() {
        val force = card("Force of Will", "{3}{U}{U}", 5, "Instant",
            "You may pay 1 life and exile a blue card from your hand rather than pay this spell's mana cost.\nCounter target spell.")
        assertEquals(Cost(5, 0, "free alternative cost"), Costs.effective(force))
        val dig = card("Dig Through Time", "{6}{U}{U}", 8, "Instant", "Delve\nLook at the top seven cards of your library.")
        assertEquals(2, Costs.effective(dig).effective, "delve pays the generic part")
        val probe = card("Gitaxian Probe", "{U/P}", 1, "Sorcery", "Look at target player's hand.\nDraw a card.")
        assertEquals(0, Costs.effective(probe).effective)
        val terminus = card("Terminus", "{4}{W}{W}", 6, "Sorcery", "Put all creatures on the bottom of their owners' libraries.\nMiracle {W}")
        val t = Costs.effective(terminus)
        assertEquals(6, t.effective, "miracle is an upside, never the norm")
        assertEquals(1, t.alternative)
    }

    @Test
    fun `tags replace the text rules, and burn that only reaches a face is no removal`() {
        val spike = card("Lava Spike", "{R}", 1, "Sorcery — Arcane", "Lava Spike deals 3 damage to target player or planeswalker.")
        val tagged = Roles.classify(spike, tags = setOf("burn player", "spot removal", "burn planeswalker"))
        assertEquals("burn", tagged.primary)
        assertEquals("tagged", tagged.source)
        val ritual = card("Dark Ritual", "{B}", 1, "Instant", "Add {B}{B}{B}.")
        assertEquals("ritual", Roles.classify(ritual, tags = setOf("ritual", "adds multiple mana")).primary,
            "one-shot mana must never look like a mana base")
        val unknown = card("Nothing Much", "{2}", 2, "Artifact", "This artifact is blue.")
        assertTrue(Roles.classify(unknown).lowConfidence, "a derived fall-through to utility is worth a look")
    }
}
