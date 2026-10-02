package mtgoracle.data

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** The user's own database, opened for reading in the tests, refuses a write instead of taking it. */
class ReadOnlyFixtureTest {
    @Test
    fun `a write through the read-only fixture fails`() {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        val db = DbFixture.readOnly()
        // A write that changes nothing even if it got through: the version it already has.
        val version = db.read { c -> c.createStatement().use { st -> st.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) } } }
        assertFailsWith<SQLException> { db.write { it.createStatement().execute("PRAGMA user_version = $version") } }
    }
}
