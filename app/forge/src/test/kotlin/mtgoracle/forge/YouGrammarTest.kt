package mtgoracle.forge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class YouGrammarTest {
    @Test
    fun `Forge's third person is said in the second for You`() {
        val cases = mapOf(
            "You has kept a hand of 7 cards" to "You have kept a hand of 7 cards",
            "You chooses a creature card from it." to "You choose a creature card from it.",
            "Each player sacrifices a creature. You controls Island" to "Each player sacrifices a creature. You control Island",
            "You puts it into your hand" to "You put it into your hand",
            "You gets an emblem" to "You get an emblem",
            "You draws 2 cards, then You discards a card." to "You draw 2 cards, then You discard a card.",
            "You searches your library" to "You search your library",
            "You sacrifices Polluted Delta; You loses 1 life and You gains 2 life" to "You sacrifice Polluted Delta; You lose 1 life and You gain 2 life",
            "You reveals their hand" to "You reveal their hand",
            "You pays {2}" to "You pay {2}",
            "You may sacrifice a land. If You does, You chooses one" to "You may sacrifice a land. If You do, You choose one",
            "(You copies it)" to "(You copy it)",
            "You is the monarch" to "You are the monarch",
            "You passes" to "You pass",
            "You goes first" to "You go first",
        )
        cases.forEach { (forge, said) -> assertEquals(said, YouGrammar.said(forge), forge) }
    }

    @Test
    fun `anything but You and a verb in -s is left alone`() {
        listOf(
            "AI (Rakdos) chooses a card", // another player is third person
            "You may pass",               // no -s verb
            "You Come to a River",        // a card's name: a capital after You
            "Your Upkeep step",           // not the word You
            "You've been caught",
        ).forEach { assertEquals(it, YouGrammar.said(it)) }
        assertNull(YouGrammar.base("pass"))
        assertNull(YouGrammar.base("may"))
        assertNull(YouGrammar.base("bonus"))
    }
}
