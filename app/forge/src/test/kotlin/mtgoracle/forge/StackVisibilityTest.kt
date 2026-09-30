package mtgoracle.forge

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What an item on the stack may say about a source the seat can't see where it is now. */
class StackVisibilityTest {

    @Test
    fun `an ability names its source even from a hidden zone, unless the source is face down`() {
        // Hawkeye's "explosive" trigger resolved with Hawkeye already Condemned into the library.
        assertTrue(abilityNamesItsSource(isAbility = true, sourceFaceDown = false))
        assertFalse(abilityNamesItsSource(isAbility = true, sourceFaceDown = true))
    }

    @Test
    fun `a spell follows its card's own visibility`() {
        assertFalse(abilityNamesItsSource(isAbility = false, sourceFaceDown = false))
    }
}
