package mtgoracle.forge

import forge.ImageKeys
import forge.localinstance.properties.ForgeConstants
import forge.localinstance.properties.ForgePreferences.FPref
import forge.model.FModel
import forge.util.ImageFetcher
import forge.util.ImageUtil
import mtgoracle.core.art.ArtKind
import mtgoracle.core.art.CardArt
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Card images through Forge: its image keys, its Scryfall URL rules, its cache
 * layout (cache/pics/cards/<EDITION>/<name>.fullborder.jpg and .artcrop.jpg).
 *
 * Forge's [ImageFetcher] decides *what* to fetch and *where* it goes; the
 * subclass here only does the download: Scryfall URLs only (never Forge's
 * own hosts), one request at a time with Scryfall's requested pause between
 * them, and the bytes saved exactly as served — never re-encoded, cropped or
 * recoloured (Scryfall's image terms).
 */
class ForgeImages(private val edt: ForgeEdt) : CardArt {

    private val downloads = Executors.newSingleThreadExecutor { r -> Thread(r, "scryfall-download").apply { isDaemon = true } }
    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    val fetcher: ImageFetcher = object : ImageFetcher() {
        override fun getDownloadTask(urls: Array<String>, destPath: String, notifyObservers: Runnable): Runnable =
            Runnable { downloads.execute { if (download(urls, destPath)) edt.later(notifyObservers) } }
    }

    override fun keyFor(cardName: String, setCode: String?, collectorNumber: String?): String? =
        ForgeCards.paperCard(cardName, setCode, collectorNumber)?.getImageKey(false)

    override fun file(key: String, kind: ArtKind): File? {
        val path = relativePath(key) ?: return null
        return ImageKeys.getImageFile(if (kind == ArtKind.ART_CROP) path.replace(".full", ".artcrop") else path)
            ?.takeIf { it.isFile }
    }

    override fun artist(key: String): String? =
        runCatching { ImageUtil.getPaperCardFromImageKey(key)?.artist }.getOrNull()?.takeIf { it.isNotBlank() }

    override fun request(key: String, kind: ArtKind, onReady: () -> Unit) {
        if (file(key, kind) != null) { onReady(); return }
        edt.later {
            // Forge's fetcher reads the art format from the global preference, on
            // this thread, while it builds the URL and path; set it for this one call.
            val prefs = FModel.getPreferences()
            val before = prefs.getPref(FPref.UI_CARD_ART_FORMAT)
            prefs.setPref(FPref.UI_CARD_ART_FORMAT, if (kind == ArtKind.ART_CROP) "Crop" else "Full")
            try {
                fetcher.fetchImage(key) { onReady() }
            } finally {
                prefs.setPref(FPref.UI_CARD_ART_FORMAT, before)
            }
        }
    }

    /**
     * Fetches whatever of [keys] x [kinds] isn't cached, one download at a time.
     * Returns how many images are now on disk; [progress] hears (done, total).
     */
    fun prefetch(keys: Collection<String>, kinds: List<ArtKind> = ArtKind.entries, timeoutPerImage: Long = 30, progress: (Int, Int) -> Unit = { _, _ -> }): Int {
        val wanted = keys.distinct().flatMap { key -> kinds.map { key to it } }
        val done = AtomicInteger()
        for ((key, kind) in wanted) {
            if (file(key, kind) == null) {
                val latch = CountDownLatch(1)
                request(key, kind) { latch.countDown() }
                if (!latch.await(timeoutPerImage, TimeUnit.SECONDS)) Log.warn("no image for $key ($kind) after ${timeoutPerImage}s")
            }
            progress(done.incrementAndGet(), wanted.size)
        }
        return wanted.count { (key, kind) -> file(key, kind) != null }
    }

    private fun relativePath(key: String): String? {
        if (key.startsWith(ImageKeys.TOKEN_PREFIX)) return key
        val card = runCatching { ImageUtil.getPaperCardFromImageKey(key) }.getOrNull() ?: return null
        return if (key.endsWith(ImageKeys.BACKFACE_POSTFIX)) card.cardAltImageKey else card.cardImageKey
    }

    private fun download(urls: Array<String>, destPath: String): Boolean {
        for (url in urls) {
            val scryfall = url.startsWith(ForgeConstants.URL_PIC_SCRYFALL_DOWNLOAD) || url.startsWith("https://cards.scryfall.io")
            if (!scryfall) continue // Forge's own image hosts are off-limits (it disables them itself)
            // Forge's rule (SwingImageFetcher): Scryfall images are the full-border scans.
            val dest = File(if (url.startsWith(ForgeConstants.URL_PIC_SCRYFALL_DOWNLOAD)) destPath.replace(".full.jpg", ".fullborder.jpg") else destPath)
            try {
                Thread.sleep(SCRYFALL_PAUSE_MS)
                val request = HttpRequest.newBuilder(URI(url))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "image/*,*/*;q=0.8")
                    .timeout(Duration.ofSeconds(30))
                    .build()
                val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
                val type = response.headers().firstValue("Content-Type").orElse("")
                if (response.statusCode() != 200 || !type.startsWith("image/")) {
                    Log.debug("image $url: HTTP ${response.statusCode()} $type")
                    continue
                }
                dest.parentFile.mkdirs()
                val tmp = File(dest.path + ".tmp")
                tmp.writeBytes(response.body())
                Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
                return true
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            } catch (e: Exception) {
                Log.warn("image $url failed: $e")
            }
        }
        return false
    }

    private companion object {
        /** Scryfall asks for 50–100 ms between requests. */
        const val SCRYFALL_PAUSE_MS = 100L
        const val USER_AGENT = "MTGOracle/0.2 (personal deck tool; Forge 2.0.14 embedded)"
    }
}
