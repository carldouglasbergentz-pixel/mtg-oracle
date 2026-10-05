package mtgoracle.forge

import forge.game.GameLogEntryType
import mtgoracle.core.model.CardState
import mtgoracle.core.model.LogKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The log pane's lines: Forge's ids out, the cards named marked (only those
 * the log has named openly, never a rules word that is also a card), and
 * each line's kind.
 */
class LogLinesTest {
    private val printed = setOf("Swamp", "Counterspell", "Opt", "Exile", "Jori En, Ruin Diver", "Chandra, Torch of Defiance", "Thoughtseize", "Farewell", "Jori En")
    private val ids = mapOf(159 to "Swamp", 12 to "Opt", 28 to "Farewell", 170 to "Thoughtseize", 205 to "Orc Army")
    private fun printedCard(name: String) = CardState(0, name, "", "", null, null, null, false, false, false, false, false, false, 0, false, null, "")
    private val players = listOf("You", "AI (Jori En)")
    private fun lines() = LogLines(nameOf = { ids[it] }, oracle = { if (it in printed) printedCard(it) else null })

    private fun mark(text: String, cards: List<mtgoracle.core.model.LogCard>) = cards.map { text.substring(it.start, it.end) }

    @Test
    fun `an id goes, and the card it named is marked with it`() {
        val line = lines().forge(GameLogEntryType.MANA, "Swamp (159) - {T}: Add {B}.", players)
        assertEquals("Swamp - {T}: Add {B}.", line.text)
        assertEquals(LogKind.MANA, line.kind)
        val swamp = line.cards.single()
        assertEquals(Triple(0, 5, 159), Triple(swamp.start, swamp.end, swamp.id))
        assertNotNull(swamp.card, "the card as printed, for the zoom pane")
    }

    @Test
    fun `a card named openly once is marked where the log names it again, without an id`() {
        val log = lines()
        log.forge(GameLogEntryType.STACK_ADD, "You cast Counterspell targeting [Opt (12)]", players)
        val countered = log.ours(LogKind.COUNTERED, "Counterspell countered Opt.", players)
        assertEquals(listOf("Counterspell", "Opt"), mark(countered.text, countered.cards))
    }

    @Test
    fun `a rules word that is also a card is not marked, nor a name inside a player's`() {
        val log = lines()
        log.ours(LogKind.ZONE, "Jori En: graveyard → your hand.", players, names = listOf("Jori En"))
        val dies = log.forge(GameLogEntryType.ZONE_CHANGE, "Swamp (159) was put into Exile from Battlefield.", players)
        assertEquals(listOf("Swamp"), mark(dies.text, dies.cards), "Exile was never named as a card")
        val land = log.forge(GameLogEntryType.LAND, "AI (Jori En) played Swamp (159)", players)
        assertEquals(listOf("Swamp"), mark(land.text, land.cards), "the player's name is no card")
    }

    @Test
    fun `a possessive and brackets are not part of the name, and a gone token keeps no id`() {
        val log = lines()
        log.forge(GameLogEntryType.STACK_ADD, "You cast Chandra, Torch of Defiance", players)
        val line = log.ours(LogKind.COUNTERED, "Chandra, Torch of Defiance's ability left the stack without resolving.", players)
        assertEquals(listOf("Chandra, Torch of Defiance"), mark(line.text, line.cards))
        val token = log.forge(GameLogEntryType.COMBAT, "You didn't block Orc Army Token (205).", players)
        assertEquals("You didn't block Orc Army Token.", token.text, "the id goes even when the name before it is not the card's")
        assertEquals(emptyList(), token.cards)
    }

    @Test
    fun `life says which way it went, and a resolution that reveals is a reveal`() {
        val log = lines()
        val lost = log.forge(GameLogEntryType.LIFE, "Life: You 20 > 18", players)
        assertEquals("You 20 > 18", lost.text)
        assertEquals(LogKind.LIFE_LOST, lost.kind)
        assertEquals(LogKind.LIFE_GAINED, log.forge(GameLogEntryType.LIFE, "Life: You 18 > 21", players).kind)
        val seize = log.forge(GameLogEntryType.STACK_RESOLVE, "Thoughtseize (170) - You reveals their hand. AI (Jori En) chooses a nonland card from it.", players)
        assertEquals(LogKind.REVEAL, seize.kind)
        assertEquals(listOf("Thoughtseize"), mark(seize.text, seize.cards))
        assertEquals(LogKind.DISCARD, log.forge(GameLogEntryType.DISCARD, "You discards Farewell (28).", players).kind)
        assertNull(log.forge(GameLogEntryType.PHASE, "Your Upkeep step", players).cards.firstOrNull())
    }

    @Test
    fun `lines count up`() {
        val log = lines()
        val a = log.forge(GameLogEntryType.TURN, "Turn 1 (You)", players)
        val b = log.ours(LogKind.DRAW, "You draw a card.", players)
        assertEquals(a.seq + 1, b.seq)
    }
}
