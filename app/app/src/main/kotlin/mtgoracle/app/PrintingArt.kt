package mtgoracle.app

import mtgoracle.core.art.ArtKind
import mtgoracle.core.art.CardArt
import mtgoracle.data.Printings
import mtgoracle.forge.ForgeCards
import mtgoracle.forge.Log
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Card art from Forge first, and from Scryfall for a printing Forge lacks:
 * a deck row's chosen printing, or a card on the board whose printing the
 * match marked (ForgeCards.printingKey). Scryfall's images are fetched only
 * when shown, one at a time, into [cacheDir], and kept. Scryfall's terms
 * hold as for Forge's images: the whole card is never cropped, and the art
 * crop is shown with its artist.
 */
class PrintingArt(
    private val forge: CardArt,
    private val printings: () -> Printings?,
    private val cacheDir: File,
    private val forgeHas: (name: String, setCode: String, collectorNumber: String?) -> Boolean = ForgeCards::hasPrinting,
    private val fetch: (url: String, target: File) -> Boolean = ::download,
) : CardArt {
    private val downloads = Executors.newSingleThreadExecutor { r -> Thread(r, "scryfall-art").apply { isDaemon = true } }
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val stored = ConcurrentHashMap<String, Printings.Stored>()

    /** Which printings come from Scryfall, per (name, set, number): a deck view asks for every card on every redraw. */
    private val scryfallChosen = ConcurrentHashMap<Triple<String, String, String>, Boolean>()
    @Volatile private var cachedFor: Printings? = null

    override fun keyFor(cardName: String, setCode: String?, collectorNumber: String?): String? {
        if (setCode == null) return forge.keyFor(cardName, null, collectorNumber)
        val table = printings()
        if (table !== cachedFor) { scryfallChosen.clear(); stored.clear(); cachedFor = table } // a sync rebuilt the lookup
        val fromScryfall = scryfallChosen.getOrPut(Triple(cardName, setCode, collectorNumber.orEmpty())) {
            !forgeHas(cardName, setCode, collectorNumber) && table?.find(cardName, setCode, collectorNumber) != null
        }
        return if (fromScryfall) ForgeCards.printingKey(cardName, setCode, collectorNumber) else forge.keyFor(cardName, setCode, collectorNumber)
    }

    /** The Scryfall printing behind a `printing:<set>/<number>/<name>` key, or null for Forge's own keys. */
    private fun storedFor(key: String): Printings.Stored? {
        if (!key.startsWith(PREFIX)) return null
        stored[key]?.let { return it }
        val (set, number, name) = key.removePrefix(PREFIX).split('/', limit = 3).takeIf { it.size == 3 } ?: return null
        return printings()?.find(name, set, number.ifEmpty { null })?.also { stored[key] = it }
    }

    private fun cacheFile(p: Printings.Stored, kind: ArtKind) = File(cacheDir, "${p.scryfallId}-${scryfallKind(kind)}.jpg")

    override fun file(key: String, kind: ArtKind): File? {
        if (!key.startsWith(PREFIX)) return forge.file(key, kind)
        return storedFor(key)?.let { cacheFile(it, kind) }?.takeIf { it.isFile }
    }

    override fun artist(key: String): String? =
        if (key.startsWith(PREFIX)) storedFor(key)?.artist?.takeIf { it.isNotBlank() } else forge.artist(key)

    override fun request(key: String, kind: ArtKind, onReady: () -> Unit) {
        if (!key.startsWith(PREFIX)) return forge.request(key, kind, onReady)
        val p = storedFor(key) ?: return
        val target = cacheFile(p, kind)
        if (target.isFile) { onReady(); return }
        if (!inFlight.add(target.name)) return
        downloads.execute {
            try {
                if (fetch(Printings.imageUrl(p.scryfallId, scryfallKind(kind)), target)) onReady()
            } finally {
                inFlight.remove(target.name)
            }
        }
    }

    companion object {
        private const val PREFIX = "printing:"

        /** The whole card large enough for the zoom pane; the art crop for the frames. */
        fun scryfallKind(kind: ArtKind) = if (kind == ArtKind.ART_CROP) "art_crop" else "large"

        private val client: HttpClient by lazy { HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build() }
        private var lastFetch = 0L

        /** One image from Scryfall's CDN, politely spaced, written whole or not at all. */
        @Synchronized
        private fun download(url: String, target: File): Boolean {
            val wait = lastFetch + 100 - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            return try {
                target.parentFile.mkdirs()
                val partial = File(target.parentFile, target.name + ".part")
                val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(60))
                    .header("User-Agent", "mtg-oracle/1.0 (desktop deck tool; +https://github.com/carldouglasbergentz-pixel)").GET().build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofFile(partial.toPath()))
                lastFetch = System.currentTimeMillis()
                if (response.statusCode() !in 200..299) { partial.delete(); Log.warn("Scryfall image $url answered ${response.statusCode()}"); return false }
                Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                true
            } catch (e: Exception) {
                Log.warn("Scryfall image $url: ${e.message}")
                false
            }
        }
    }
}
