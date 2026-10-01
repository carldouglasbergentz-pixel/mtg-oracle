package mtgoracle.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration

/** One entry of Scryfall's bulk-data index: when it last changed, and where its gzipped JSON Lines are. */
data class Bulk(val updatedAt: String?, val jsonlUri: String?)

/**
 * Where the sources come from. [HttpUpstream] is the network; a test serves
 * files instead, so no test talks to Scryfall, Wizards or Spellbook.
 */
interface Upstream {
    /** Scryfall's bulk-data index, by type (`oracle_cards`, `rulings`, `oracle_tags`). */
    fun scryfallBulk(): Map<String, Bulk>
    /** The Comprehensive Rules page, whose HTML links the current rules text. */
    fun rulesPage(): String
    fun bytes(url: String): ByteArray
    /** Spellbook's export's ETag, else its Last-Modified; empty when neither is known. */
    fun spellbookMarker(): String
    /** [url] streamed to [target], replacing it only once the download is complete. */
    fun download(url: String, target: File)

    companion object {
        const val SCRYFALL_BULK = "https://api.scryfall.com/bulk-data"
        const val RULES_PAGE = "https://magic.wizards.com/en/rules"
        const val SPELLBOOK = "https://json.commanderspellbook.com/variants.json"
    }
}

/**
 * The network, politely: Scryfall asks for a User-Agent and an Accept header
 * on its API, and Wizards' page sends a stripped page (no links) to a
 * non-browser agent, so that one request says it is a browser.
 */
class HttpUpstream : Upstream {
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(30)).build()

    private fun request(url: String, agent: String = AGENT, accept: String? = null, method: String = "GET"): HttpRequest =
        HttpRequest.newBuilder(URI(url.replace(" ", "%20"))).timeout(Duration.ofMinutes(10)).header("User-Agent", agent)
            .apply { accept?.let { header("Accept", it) } }
            .method(method, HttpRequest.BodyPublishers.noBody()).build()

    private fun <T> send(request: HttpRequest, body: HttpResponse.BodyHandler<T>): HttpResponse<T> =
        client.send(request, body).also { check(it.statusCode() in 200..299) { "${request.method()} ${request.uri()} answered ${it.statusCode()}" } }

    override fun scryfallBulk(): Map<String, Bulk> {
        val index = Json.parseToJsonElement(send(request(Upstream.SCRYFALL_BULK, accept = "application/json"), HttpResponse.BodyHandlers.ofString()).body()) as JsonObject
        return (index["data"] as? JsonArray).orEmpty().mapNotNull { e ->
            val entry = e as? JsonObject ?: return@mapNotNull null
            (entry.py("type") as? String)?.let { it to Bulk(entry.py("updated_at") as? String, entry.py("jsonl_download_uri") as? String) }
        }.toMap()
    }

    override fun rulesPage(): String = send(request(Upstream.RULES_PAGE, agent = BROWSER, accept = "text/html"), HttpResponse.BodyHandlers.ofString()).body()

    override fun bytes(url: String): ByteArray = send(request(url, agent = BROWSER, accept = "text/plain"), HttpResponse.BodyHandlers.ofByteArray()).body()

    override fun spellbookMarker(): String {
        val response = client.send(request(Upstream.SPELLBOOK, method = "HEAD"), HttpResponse.BodyHandlers.discarding())
        if (response.statusCode() !in 200..299) return ""
        return response.headers().firstValue("ETag").orElse("").ifEmpty { response.headers().firstValue("Last-Modified").orElse("") }
    }

    override fun download(url: String, target: File) {
        target.parentFile.mkdirs()
        val partial = File(target.parentFile, target.name + ".part")
        send(request(url), HttpResponse.BodyHandlers.ofFile(partial.toPath()))
        Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private companion object {
        const val AGENT = "mtg-oracle/1.0 (desktop deck tool; +https://github.com/carldouglasbergentz-pixel)"
        const val BROWSER = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
