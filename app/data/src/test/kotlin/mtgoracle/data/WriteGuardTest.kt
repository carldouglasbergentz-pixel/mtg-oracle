package mtgoracle.data

import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the tests' guard on the real database rests on: a write is seen, a read-only open is not. */
class WriteGuardTest {
    @Test
    fun `a database opened for writing is seen, one opened read-only is not`() {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        val copy = DbFixture.copy() // migrated: written
        try {
            assertTrue(MtgDb.openedForWriting(copy))
            MtgDb(DbFixture.realDb, writable = false).read { it.createStatement().use { st -> st.executeQuery("SELECT 1").close() } }
            assertFalse(MtgDb.openedForWriting(DbFixture.realDb))
        } finally {
            copy.parentFile.deleteRecursively()
        }
    }
}
