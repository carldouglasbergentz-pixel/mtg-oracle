package mtgoracle.data

import java.io.File
import java.time.Duration

/**
 * The tests' own temporary folders from earlier runs, cleared once per test
 * JVM. A test deletes its folder when it ends, but on Windows a database file
 * an open SQLite connection still holds can't be deleted, and the copies of
 * the user's database (380 MB each) piled up in Temp: 1,083 folders and 1 GB
 * by 2026-10-05. Only `mtg-oracle-*` folders older than [AGE] go; the app
 * itself writes nothing to Temp, so the prefix is the tests' alone.
 */
object StaleTestDirs {
    private val AGE: Duration = Duration.ofHours(24)
    private var swept = false

    /** [sweep] of the system's Temp, the first time a test JVM asks. */
    @Synchronized
    fun sweepOnce() {
        if (swept) return
        swept = true
        sweep(File(System.getProperty("java.io.tmpdir")), System.currentTimeMillis())
    }

    /** Deletes the `mtg-oracle-*` folders in [tmp] older than [AGE] at [now]; how many went. */
    fun sweep(tmp: File, now: Long): Int =
        tmp.listFiles { f -> f.isDirectory && f.name.startsWith("mtg-oracle-") && now - f.lastModified() > AGE.toMillis() }.orEmpty()
            .count { it.deleteRecursively() }
}
