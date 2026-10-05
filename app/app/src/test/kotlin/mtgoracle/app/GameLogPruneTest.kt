package mtgoracle.app

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** Game logs no longer pile up: the newest are kept, by the time in their names, and nothing else in the folder is touched. */
class GameLogPruneTest {
    @Test
    fun `the newest logs stay, the oldest go, and other files are left alone`() {
        val dir = Files.createTempDirectory("game-logs-").toFile()
        try {
            val names = listOf("20261001-090000-000.log", "20261003-120000-000.log", "20261002-100000-000.log", "20261005-140054-705.log")
            names.forEach { dir.resolve(it).writeText("log") }
            dir.resolve("notes.txt").writeText("mine")
            assertEquals(2, Sessions(null, dir).pruneLogs(keep = 2))
            assertEquals(listOf("20261003-120000-000.log", "20261005-140054-705.log", "notes.txt"), dir.list()!!.sorted())
            assertEquals(0, Sessions(null, dir).pruneLogs(keep = 2), "nothing more to go")
        } finally {
            dir.deleteRecursively()
        }
    }
}
