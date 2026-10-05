package mtgoracle.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** A release's version, numbers only, as the tags name them: `v0.1.0`. */
data class ReleaseVersion(val parts: List<Int>) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int =
        parts.zip(other.parts).firstOrNull { (a, b) -> a != b }?.let { (a, b) -> a.compareTo(b) } ?: parts.size.compareTo(other.parts.size)

    override fun toString(): String = parts.joinToString(".")

    companion object {
        fun parse(text: String?): ReleaseVersion? {
            val parts = text?.trim()?.removePrefix("v")?.split('.')?.map { it.toIntOrNull() ?: return null } ?: return null
            return if (parts.size == 3) ReleaseVersion(parts) else null
        }
    }
}

/** A file on a release, as the API names it: [apiUrl] is where it is fetched with the token. */
data class ReleaseAsset(val name: String, val apiUrl: String, val size: Long)

/** The newest release: its version, the package zip, and the zip's SHA-256 file. */
data class Release(val version: ReleaseVersion, val zip: ReleaseAsset, val sha256: ReleaseAsset)

/** Something an update can't get past; the message says what, for the output. */
class UpdateRefused(message: String) : RuntimeException(message)

/**
 * The release's updates, from the private repo's GitHub releases: what the
 * newest is, its download (checked against the SHA-256 published beside it),
 * and the swap. The swap is a script run once the app has closed. It replaces
 * the program ([install]'s `app\`, `runtime\` and launchers) and never touches
 * `data\`: the decks, games and settings stay.
 *
 * The token is the GitHub CLI's (`gh auth token`, from the keyring it was
 * logged in with): nothing secret is kept by the app, and a machine without a
 * logged-in `gh` simply checks nothing. The token goes to api.github.com
 * only, never to the storage a download is redirected to, and is never logged.
 */
class Updates(
    private val install: File,
    private val current: ReleaseVersion,
    private val token: () -> String? = ::ghToken,
    private val api: String = "https://api.github.com",
    private val repo: String = REPO,
    private val http: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(20)).build(),
) {
    private val updateDir get() = install.resolve("update")
    /** The version running, for what the output says. */
    val running: ReleaseVersion get() = current

    /** The newest release when it is newer than this one; null when it isn't. */
    fun newer(): Release? = latest()?.takeIf { it.version > current }

    /** The newest release GitHub has (not a draft or prerelease), or null when there is none. */
    fun latest(): Release? {
        val auth = token() ?: throw UpdateRefused(NO_LOGIN)
        val response = http.send(apiRequest("$api/repos/$repo/releases/latest", auth).build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 404) return null
        if (response.statusCode() != 200) throw UpdateRefused("GitHub answered ${response.statusCode()} for the latest release")
        return parseRelease(response.body())
    }

    /**
     * [release]'s zip in `update\`, its SHA-256 checked against the file published
     * beside it, then unpacked in `update\new\`; the folder that holds the program.
     */
    fun download(release: Release, progress: (String) -> Unit = {}): File {
        val auth = token() ?: throw UpdateRefused(NO_LOGIN)
        if (updateDir.exists() && !clear(updateDir)) throw UpdateRefused("can't clear $updateDir")
        updateDir.mkdirs()
        val zip = updateDir.resolve(release.zip.name)
        progress("downloading ${release.zip.name} (${release.zip.size / 1_000_000} MB)")
        fetch(release.zip, auth, Duration.ofMinutes(10), HttpResponse.BodyHandlers.ofFile(zip.toPath()))
        val expected = String(fetch(release.sha256, auth, Duration.ofMinutes(1), HttpResponse.BodyHandlers.ofByteArray())).trim().substringBefore(' ').lowercase()
        val actual = sha256(zip)
        if (expected.length != 64 || expected != actual) throw UpdateRefused("the download's SHA-256 is $actual, the release says $expected: not installed")
        progress("unpacking ${release.version}")
        val unpacked = updateDir.resolve("new")
        unzip(zip, unpacked)
        return unpacked.resolve(PROGRAM).takeIf { it.resolve("app").isDirectory && it.resolve("runtime").isDirectory }
            ?: throw UpdateRefused("the zip holds no `$PROGRAM\\` with app\\ and runtime\\: not installed")
    }

    /**
     * The script that, once process [pid] (this app) has ended, moves the old
     * program aside, puts [program] in its place and starts it; on a failed copy
     * it puts the old one back. `data\` is never named, so never touched.
     */
    fun applyScript(program: File, pid: Long): File {
        val here = install.canonicalPath
        val from = program.canonicalPath
        // cmd expands % and ends a string at ", so a path holding either can't be written safely into the script.
        for (path in listOf(here, from)) if ('%' in path || '"' in path) throw UpdateRefused("the install path holds % or \": update by hand")
        val old = updateDir.resolve("old").canonicalPath
        val script = updateDir.resolve("apply-update.cmd")
        val parts = listOf("app", "runtime")
        val launchers = listOf("$PROGRAM.exe", "mtg.exe")
        script.writeText(buildString {
            appendLine("@echo off")
            appendLine("rem MTG Oracle's update: waits for the app to close, swaps the program, keeps data\\.")
            appendLine(":wait")
            appendLine("tasklist /FI \"PID eq $pid\" 2>nul | find \"$pid\" >nul && (timeout /t 1 /nobreak >nul & goto wait)")
            appendLine("mkdir \"$old\" 2>nul")
            parts.forEach { appendLine("move \"$here\\$it\" \"$old\\$it\" >nul || goto restore") }
            launchers.forEach { appendLine("move \"$here\\$it\" \"$old\\$it\" >nul || goto restore") }
            appendLine("robocopy \"$from\" \"$here\" /E /XD data /NFL /NDL /NJH /NJS /NP >nul")
            appendLine("if errorlevel 8 goto restore")
            // Only an exe that is there: `start` on a missing one raises a dialog that holds the script until closed.
            appendLine("if not exist \"$here\\$PROGRAM.exe\" goto restore")
            appendLine("start \"\" \"$here\\$PROGRAM.exe\"")
            appendLine("exit /b 0")
            appendLine(":restore")
            parts.forEach { appendLine("if exist \"$old\\$it\" (rmdir /s /q \"$here\\$it\" 2>nul & move \"$old\\$it\" \"$here\\$it\" >nul)") }
            launchers.forEach { appendLine("if exist \"$old\\$it\" move /y \"$old\\$it\" \"$here\\$it\" >nul") }
            appendLine("if exist \"$here\\$PROGRAM.exe\" start \"\" \"$here\\$PROGRAM.exe\"")
            appendLine("exit /b 1")
        }.replace("\n", "\r\n"))
        return script
    }

    /** Starts [script] apart from this process, which then closes for it to run. */
    fun launch(script: File) {
        // A child outlives this JVM on Windows: the script waits for the app to close, then swaps it.
        ProcessBuilder("cmd.exe", "/c", script.canonicalPath).directory(updateDir).start()
    }

    private fun apiRequest(url: String, auth: String) = HttpRequest.newBuilder(URI(url))
        .header("Authorization", "Bearer $auth")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .timeout(Duration.ofSeconds(30))

    /**
     * [asset]'s content: the API answers with it, or redirects to storage, which is then
     * asked without the token. Only `application/octet-stream` may be asked for: with
     * the API's own JSON type beside it, GitHub answers 200 with the asset's description.
     */
    private fun <T> fetch(asset: ReleaseAsset, auth: String, timeout: Duration, body: HttpResponse.BodyHandler<T>): T {
        val first = http.send(apiRequest(asset.apiUrl, auth).setHeader("Accept", "application/octet-stream").timeout(timeout).build(), body)
        if (first.statusCode() == 200) return first.body()
        if (first.statusCode() !in 300..399) throw UpdateRefused("GitHub answered ${first.statusCode()} for ${asset.name}")
        val location = URI(first.headers().firstValue("Location").orElseThrow { UpdateRefused("GitHub gave ${asset.name} no location") })
        // Never weaker than the API: against GitHub that is https; only a test's local server is plain http.
        if (location.scheme != "https" && location.scheme != URI(api).scheme) throw UpdateRefused("${asset.name} is offered over ${location.scheme}, not https: not downloaded")
        val second = http.send(HttpRequest.newBuilder(location).timeout(timeout).build(), body)
        if (second.statusCode() != 200) throw UpdateRefused("the download of ${asset.name} answered ${second.statusCode()}")
        return second.body()
    }

    companion object {
        /** The repository the releases are published in. */
        const val REPO = "carldouglasbergentz-pixel/mtg-oracle-private"
        const val NO_LOGIN = "no GitHub login to read the releases with: run `gh auth login` once (the repo is private)"
        /** The program's folder in the zip, and its window launcher's name. */
        const val PROGRAM = "MTG Oracle"

        /** The release in the API's answer, or null when it has no package zip and checksum to offer. */
        fun parseRelease(json: String): Release? {
            val release = Json.parseToJsonElement(json) as? JsonObject ?: return null
            val version = ReleaseVersion.parse(release["tag_name"]?.jsonPrimitive?.contentOrNull) ?: return null
            val assets = (release["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { a ->
                val name = a["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val url = a["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                ReleaseAsset(name, url, a["size"]?.jsonPrimitive?.longOrNull ?: 0)
            }
            val zip = assets.firstOrNull { it.name.endsWith("-windows-x64.zip") } ?: return null
            val sha = assets.firstOrNull { it.name == "${zip.name}.sha256" } ?: return null
            return Release(version, zip, sha)
        }

        /** [zip] into [into]; an entry whose path leaves [into] (`..\`) refuses the whole zip. */
        fun unzip(zip: File, into: File) {
            val root = into.canonicalFile
            ZipInputStream(zip.inputStream().buffered()).use { input ->
                generateSequence { input.nextEntry }.forEach { entry ->
                    val target = File(root, entry.name).canonicalFile
                    if (!target.toPath().startsWith(root.toPath())) throw UpdateRefused("the zip names a file outside its folder (${entry.name}): not installed")
                    if (entry.isDirectory) target.mkdirs() else { target.parentFile.mkdirs(); target.outputStream().use { input.copyTo(it) } }
                }
            }
        }

        /**
         * [dir] deleted, read-only files too: jpackage's launchers are read-only, and the last
         * update's `update\old\` holds them, so a plain delete left the next update stuck.
         */
        fun clear(dir: File): Boolean {
            dir.walkBottomUp().forEach { it.setWritable(true) }
            return dir.deleteRecursively()
        }

        fun sha256(file: File): String = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(1 shl 16)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** The GitHub CLI's token, or null when gh isn't there or isn't logged in. Fixed arguments: nothing of the user's reaches the command. */
        fun ghToken(): String? {
            val gh = listOf("gh", "${System.getenv("ProgramFiles") ?: "C:\\Program Files"}\\GitHub CLI\\gh.exe")
            for (exe in gh) {
                val token = runCatching {
                    val process = ProcessBuilder(exe, "auth", "token").redirectErrorStream(false).start()
                    val out = process.inputStream.bufferedReader().readText().trim()
                    if (process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() == 0) out.takeIf { it.isNotEmpty() } else null
                }.getOrNull()
                if (token != null) return token
            }
            return null
        }
    }
}
