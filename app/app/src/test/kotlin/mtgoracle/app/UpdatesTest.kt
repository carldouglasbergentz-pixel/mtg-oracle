package mtgoracle.app

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The release's updates: the newest version from a GitHub-shaped API (a local
 * server here), the download checked against its SHA-256, a zip that names a
 * path outside its folder refused, and the swap script run for real: the
 * program replaced, data\ untouched, the old one kept aside.
 */
class UpdatesTest {
    private val dir = createTempDirectory("mtg-oracle-update-").toFile()
    private var server: HttpServer? = null

    @AfterTest fun close() { server?.stop(0); dir.deleteRecursively() }

    @Test
    fun `versions compare as numbers, and only a tag of three numbers is one`() {
        assertTrue(ReleaseVersion.parse("v0.10.0")!! > ReleaseVersion.parse("0.9.3")!!)
        assertEquals(ReleaseVersion.parse("0.1.0"), ReleaseVersion.parse("v0.1.0"))
        assertNull(ReleaseVersion.parse("v1.2"))
        assertNull(ReleaseVersion.parse("nightly"))
    }

    /** A real program, so the script's start of the new launcher runs and ends at once. */
    private val program: ByteArray = File(System.getenv("SystemRoot"), "System32\\hostname.exe").readBytes()

    /** A zip with [entries] as name to text, and [launchers] as the release's two exes. */
    private fun zip(file: File, vararg entries: Pair<String, String>, launchers: Boolean = true) = file.also { f ->
        ZipOutputStream(f.outputStream()).use { out ->
            entries.forEach { (name, text) -> out.putNextEntry(ZipEntry(name)); out.write(text.toByteArray()); out.closeEntry() }
            if (launchers) listOf("MTG Oracle/MTG Oracle.exe", "MTG Oracle/mtg.exe").forEach { out.putNextEntry(ZipEntry(it)); out.write(program); out.closeEntry() }
        }
    }

    /** A GitHub-shaped API: the latest release, its assets behind a redirect to "storage", which must get no token. */
    private fun serve(tag: String, zip: File, sha: String): String {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${http.address.port}"
        val name = "MTG-Oracle-${tag.removePrefix("v")}-windows-x64.zip"
        http.createContext("/repos/") { ex ->
            val ok = ex.requestHeaders.getFirst("Authorization") == "Bearer test-token"
            val body = """{"tag_name":"$tag","assets":[{"name":"$name","url":"$base/assets/1","size":${zip.length()}},{"name":"$name.sha256","url":"$base/assets/2","size":80}]}"""
            ex.sendResponseHeaders(if (ok) 200 else 401, body.length.toLong()); ex.responseBody.use { it.write(body.toByteArray()) }
        }
        http.createContext("/assets/") { ex ->
            val id = ex.requestURI.path.substringAfterLast('/')
            // As GitHub does: asked for anything but the bytes alone, it describes the asset in JSON.
            if (ex.requestHeaders["Accept"] != listOf("application/octet-stream")) {
                val json = """{"name":"asset $id","state":"uploaded"}"""
                ex.sendResponseHeaders(200, json.length.toLong()); ex.responseBody.use { it.write(json.toByteArray()) }
            } else { ex.responseHeaders.add("Location", "$base/storage/$id"); ex.sendResponseHeaders(302, -1); ex.close() }
        }
        http.createContext("/storage/") { ex ->
            check(ex.requestHeaders.getFirst("Authorization") == null) { "the token went to storage" }
            val bytes = if (ex.requestURI.path.endsWith("/1")) zip.readBytes() else "$sha  $name\n".toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
        }
        http.start()
        server = http
        return base
    }

    private fun install(): File = dir.resolve("MTG Oracle").apply {
        resolve("app").mkdirs(); resolve("app/marker.txt").writeText("old")
        resolve("runtime").mkdirs(); resolve("runtime/marker.txt").writeText("old")
        resolve("data").mkdirs(); resolve("data/mtg.db").writeText("the user's decks")
        // A real program in the launchers' places, so the script's start of the new one runs and ends.
        listOf("MTG Oracle.exe", "mtg.exe").forEach { resolve(it).writeBytes(program) }
    }

    @Test
    fun `a newer release is found, downloaded, checked, unpacked and swapped in, and data stays`() {
        val install = install()
        val zip = zip(dir.resolve("new.zip"),
            "MTG Oracle/app/marker.txt" to "new", "MTG Oracle/runtime/marker.txt" to "new", "MTG Oracle/data/mtg.db" to "a stranger's decks")
        val base = serve("v0.2.0", zip, Updates.sha256(zip))
        val updates = Updates(install, ReleaseVersion.parse("0.1.0")!!, token = { "test-token" }, api = base)
        val release = assertNotNull(updates.newer(), "0.2.0 is newer than 0.1.0")
        assertNull(Updates(install, ReleaseVersion.parse("0.2.0")!!, token = { "test-token" }, api = base).newer(), "and not newer than itself")

        val program = updates.download(release)
        assertEquals("new", program.resolve("app/marker.txt").readText())
        // A process that has ended stands for the app having closed.
        val ended = ProcessBuilder("cmd.exe", "/c", "exit").start().also { it.waitFor() }.pid()
        val script = updates.applyScript(program, ended)
        val run = ProcessBuilder("cmd.exe", "/c", script.path).redirectErrorStream(true).redirectOutput(dir.resolve("swap.log")).start()
        if (!run.waitFor(60, TimeUnit.SECONDS)) { run.destroyForcibly(); kotlin.test.fail("the swap didn't finish: ${dir.resolve("swap.log").readText()}") }
        assertEquals(0, run.exitValue(), dir.resolve("swap.log").readText())
        assertEquals("new", install.resolve("app/marker.txt").readText(), "the program is the new one")
        assertEquals("new", install.resolve("runtime/marker.txt").readText())
        assertEquals("the user's decks", install.resolve("data/mtg.db").readText(), "data\\ untouched, even by a zip that holds one")
        assertEquals("old", install.resolve("update/old/app/marker.txt").readText(), "the old one kept aside")

        // The next update clears update/ again, the old launchers in it read-only as jpackage makes them.
        install.resolve("update/old/MTG Oracle.exe").setReadOnly()
        assertTrue(Updates.clear(install.resolve("update")), "read-only files cleared too")
    }

    @Test
    fun `a download that isn't what the release says is refused`() {
        val install = install()
        val zip = zip(dir.resolve("new.zip"), "MTG Oracle/app/marker.txt" to "new", "MTG Oracle/runtime/marker.txt" to "new")
        val base = serve("v0.2.0", zip, "0".repeat(64))
        val updates = Updates(install, ReleaseVersion.parse("0.1.0")!!, token = { "test-token" }, api = base)
        val refused = assertFailsWith<UpdateRefused> { updates.download(updates.newer()!!) }
        assertTrue("SHA-256" in refused.message!!, refused.message)
        assertEquals("old", install.resolve("app/marker.txt").readText(), "nothing installed")
    }

    @Test
    fun `a zip naming a path outside its folder is refused, and no login is said`() {
        val evil = zip(dir.resolve("evil.zip"), "MTG Oracle/app/x.txt" to "x", "../../outside.txt" to "escaped", launchers = false)
        assertFailsWith<UpdateRefused> { Updates.unzip(evil, dir.resolve("into")) }
        assertFalse(dir.resolve("outside.txt").exists() || dir.parentFile.resolve("outside.txt").exists(), "nothing written outside")
        val noLogin = assertFailsWith<UpdateRefused> { Updates(install(), ReleaseVersion.parse("0.1.0")!!, token = { null }, api = "http://127.0.0.1:1").latest() }
        assertTrue("gh auth login" in noLogin.message!!)
    }
}
