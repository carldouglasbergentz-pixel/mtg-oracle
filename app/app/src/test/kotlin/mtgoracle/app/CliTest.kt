package mtgoracle.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mtgoracle.data.DbFixture
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The command line on a copy: text is the app's own output, `--json` parses
 * and carries what a script needs, a bad command is the usage and 64.
 */
class CliTest {
    private lateinit var data: File

    @AfterTest fun close() { if (this::data.isInitialized) data.deleteRecursively() }

    private fun paths(): AppPaths {
        assumeTrue(DbFixture.available, "needs data/mtg.db")
        data = DbFixture.copy().parentFile
        return AppPaths(data, File(System.getProperty("mtgoracle.forgeAssets")))
    }

    private fun run(paths: AppPaths, vararg args: String): Pair<Int, String> {
        val buffer = ByteArrayOutputStream()
        val code = Cli.run(paths, args.toList(), PrintStream(buffer, true, Charsets.UTF_8))
        return code to buffer.toString(Charsets.UTF_8)
    }

    @Test
    fun `text is the app's output and json carries the data`() {
        val paths = paths()
        val (code, text) = run(paths, "card", "Sol", "Ring")
        assertEquals(0, code)
        assertTrue("Sol Ring" in text && "IS MANA ABILITY" in text, text)

        val card = Json.parseToJsonElement(run(paths, "card", "sol ring", "--json").second).jsonObject
        assertEquals("Sol Ring", card["name"]!!.jsonPrimitive.content)
        assertTrue(card["legalities"]!!.jsonObject.isNotEmpty())

        val search = Json.parseToJsonElement(run(paths, "search", "t:instant", "c:u", "mv=0", "game:paper", "--json").second).jsonObject
        assertTrue(search["total"]!!.jsonPrimitive.content.toInt() > 0)
        assertTrue(search["rows"]!!.jsonArray.any { it.jsonObject["name"]!!.jsonPrimitive.content == "Pact of Negation" })

        val decks = Json.parseToJsonElement(run(paths, "deck", "list", "--json").second) as JsonArray
        assertTrue(decks.isNotEmpty())
        val two = decks.take(2).map { it.jsonObject["name"]!!.jsonPrimitive.content }
        val versus = run(paths, "compare", *two[0].split(' ').toTypedArray(), "--against", *two[1].split(' ').toTypedArray(), "--json").second
        val cmp = Json.parseToJsonElement(versus).jsonObject
        assertEquals(two[0], cmp["subject"]!!.jsonPrimitive.content, versus)
        assertTrue(cmp.keys.containsAll(listOf("roles", "mana_sources", "avg_mv", "curve_delta", "missing", "unique", "nearest")), "analyse_archetype's comparison shape")

        val profile = Json.parseToJsonElement(run(paths, "profile", *two[0].split(' ').toTypedArray(), "--json").second).jsonObject
        val list = (profile["lists"] as JsonArray).single().jsonObject
        assertTrue(list.keys.containsAll(listOf("counts", "role_mv", "curve", "on_curve", "ceiling", "avg_mv")))
        assertTrue(profile["conventions"] is JsonObject)

        val prune = Json.parseToJsonElement(run(paths, "prune", "--json").second).jsonObject
        assertTrue(prune["deleted"]!!.jsonObject.isEmpty(), "a dry run")

        assertTrue("Deck" in run(paths, "deck", "export", *two[0].split(' ').toTypedArray()).second)
        assertEquals(64, run(paths, "nosuch").first)
    }

    @Test
    fun `without a database a lookup says how to get one, and help needs none`() {
        data = kotlin.io.path.createTempDirectory("mtg-oracle-cli-empty-").toFile()
        val paths = AppPaths(data, File(System.getProperty("mtgoracle.forgeAssets")))
        assertEquals(0, run(paths, "help").first)
        assertEquals(2, run(paths, "card", "Sol", "Ring").first)
        assertTrue(!File(data, "mtg.db").exists(), "a lookup does not create it")
    }
}
