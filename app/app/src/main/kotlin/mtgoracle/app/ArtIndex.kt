package mtgoracle.app

import mtgoracle.core.art.ArtKind
import mtgoracle.forge.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What the art layer has answered before, kept on disk: a deck row's key,
 * a key's image files and its artist. Forge takes 6 to 15 seconds to start,
 * and without it no key can be resolved, so every image already on disk
 * waited for it. With this, they show as the window opens.
 *
 * Only a cache: a missing or stale line costs a wait for Forge, never a
 * wrong answer once Forge is up (IndexedArt then asks Forge and Scryfall).
 *
 * A key can come from the other side of a network table, so nothing with a
 * control character is written (a `\r` would start a line of its own), and a
 * file is served only from under [roots], the app's own folders.
 */
class ArtIndex(private val file: File, private val roots: List<File> = emptyList()) {
    private val keys = ConcurrentHashMap<String, String>()
    private val files = ConcurrentHashMap<String, String>()
    private val artists = ConcurrentHashMap<String, String>()
    private val dirty = AtomicBoolean(false)
    private val saver = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "art-index").apply { isDaemon = true } }

    init { read() }

    private fun row(name: String, setCode: String?, collectorNumber: String?) = "$name\t${setCode.orEmpty()}\t${collectorNumber.orEmpty()}"
    private fun fileId(key: String, kind: ArtKind) = "$key\t${kind.name}"

    fun keyOf(name: String, setCode: String?, collectorNumber: String?): String? = keys[row(name, setCode, collectorNumber)]
    fun fileOf(key: String, kind: ArtKind): File? = files[fileId(key, kind)]?.let(::File)?.takeIf(::underRoots)

    /** Whether [file] lies under one of [roots] (no roots: anywhere, for the tests that keep files in their own folder). */
    private fun underRoots(file: File): Boolean = roots.isEmpty() || runCatching {
        val path = file.canonicalFile.toPath()
        roots.any { path.startsWith(it.canonicalFile.toPath()) }
    }.getOrDefault(false)
    fun artistOf(key: String): String? = artists[key]

    fun rememberKey(name: String, setCode: String?, collectorNumber: String?, key: String) =
        if (listOf(name, setCode.orEmpty(), collectorNumber.orEmpty(), key).all(::plain)) put(keys, row(name, setCode, collectorNumber), key) else Unit
    fun rememberFile(key: String, kind: ArtKind, file: File) = if (plain(key) && plain(file.path)) put(files, fileId(key, kind), file.path) else Unit
    fun rememberArtist(key: String, artist: String) = if (plain(key) && plain(artist)) put(artists, key, artist) else Unit

    /** A field that can be written as part of one line: no tab, no line break, no control character of any kind. */
    private fun plain(field: String) = field.none { it.isISOControl() }

    private fun put(map: ConcurrentHashMap<String, String>, id: String, value: String) {
        if (map.put(id, value) == value) return
        // A deck opening resolves a hundred cards at once: one write for all of them.
        if (dirty.compareAndSet(false, true)) saver.schedule(::write, 3, TimeUnit.SECONDS)
    }

    private fun read() {
        if (!file.isFile) return
        try {
            file.forEachLine(Charsets.UTF_8) { line ->
                val parts = line.split('\t')
                when (parts.firstOrNull()) {
                    "K" -> if (parts.size == 5) keys[parts.subList(1, 4).joinToString("\t")] = parts[4]
                    "F" -> if (parts.size == 4) files["${parts[1]}\t${parts[2]}"] = parts[3]
                    "A" -> if (parts.size == 3) artists[parts[1]] = parts[2]
                }
            }
        } catch (e: Exception) {
            Log.warn("art index $file unreadable, starting afresh: ${e.message}")
        }
    }

    /** Writes now what would be written within seconds anyway (the tests read it back). */
    internal fun flush() = write()

    /** Whole or not at all: written beside the file, then moved over it. */
    private fun write() {
        dirty.set(false)
        try {
            file.parentFile?.mkdirs()
            val partial = File(file.parentFile, file.name + ".part")
            partial.bufferedWriter(Charsets.UTF_8).use { out ->
                keys.forEach { (id, key) -> out.write("K\t$id\t$key\n") }
                files.forEach { (id, path) -> out.write("F\t$id\t$path\n") }
                artists.forEach { (key, artist) -> out.write("A\t$key\t$artist\n") }
            }
            Files.move(partial.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Log.warn("could not write the art index $file: ${e.message}")
        }
    }
}
