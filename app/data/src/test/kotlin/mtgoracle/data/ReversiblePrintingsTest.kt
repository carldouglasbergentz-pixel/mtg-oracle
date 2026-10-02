package mtgoracle.data

import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A reversible card's printings are named after both faces in the export
 * (`Hallowed Fountain // Hallowed Fountain`, `Bloomvine Regent // Claim
 * Territory // Bloomvine Regent`), so a lookup by the card's own name missed
 * them: 71 cards, among them Hallowed Fountain's ECL 347, had printings the
 * chooser never offered. On the user's database, which has the printings.
 */
class ReversiblePrintingsTest {
    @Test
    fun `a reversible printing is among the card's printings and found by set and number`() {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        val lookup = Lookup(DbFixture.readOnly())
        assumeTrue(!lookup.printings.isEmpty(), "the printings have been synced")
        val fountain = lookup.printings.forCard("Hallowed Fountain").map { it.printing.setCode to it.printing.collectorNumber }
        assertTrue("ecl" to "347" in fountain, "ECL 347 is offered: $fountain")
        assertTrue("ecl" to "265" in fountain, "and the ordinary one still: $fountain")
        assertNotNull(lookup.printings.find("Hallowed Fountain", "ecl", "347"))
        assertNotNull(lookup.printings.find("Bloomvine Regent", "tdm", "381"), "an omen card's reversible printing")
    }
}
