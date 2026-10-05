package mtgoracle.data

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Only the tests' own old folders go: a fresh one, another program's, and a file stay. */
class StaleTestDirsTest {
    private val tmp = Files.createTempDirectory("stale-sweep-").toFile()
    @AfterTest fun clean() { tmp.deleteRecursively() }

    @Test
    fun `old mtg-oracle folders go, everything else stays`() {
        val day = 24 * 3600 * 1000L
        val now = System.currentTimeMillis()
        fun dir(name: String, age: Long) = tmp.resolve(name).also { it.mkdirs(); it.resolve("mtg.db").writeText("x"); it.setLastModified(now - age) }
        dir("mtg-oracle-kt-test-1", 3 * day)
        dir("mtg-oracle-update-2", 2 * day)
        dir("mtg-oracle-kt-test-3", 3600 * 1000L) // an hour old: a run going on now
        dir("other-program-4", 5 * day)
        tmp.resolve("mtg-oracle-note.txt").writeText("a file, not a folder")
        assertEquals(2, StaleTestDirs.sweep(tmp, now))
        assertEquals(listOf("mtg-oracle-kt-test-3", "mtg-oracle-note.txt", "other-program-4"), tmp.list()!!.sorted())
    }
}
