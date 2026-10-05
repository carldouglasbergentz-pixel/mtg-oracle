package mtgoracle.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import mtgoracle.core.library.LibraryPackage
import mtgoracle.core.library.PackageManifest
import mtgoracle.core.library.PackagedCard
import mtgoracle.core.library.PackagedChange
import mtgoracle.core.library.PackagedCombo
import mtgoracle.core.library.PackagedConsidering
import mtgoracle.core.library.PackagedDeck
import mtgoracle.core.library.PackagedGame
import mtgoracle.core.library.PackagedRevision
import mtgoracle.core.library.PackagedSubstitution
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** A file that is no package this app can read: why, in a sentence for the status line. */
class PackageRefused(message: String) : Exception(message)

/**
 * The `.mtgoracle` file: a zip of four JSON documents (manifest, decks,
 * games, combos), written and read here and nowhere else. Reading refuses a
 * file that isn't one (no manifest, a newer format, broken JSON, an entry
 * past [MAX_ENTRY_BYTES]) before anything is written.
 */
object PackageFile {
    private const val MANIFEST = "manifest.json"
    private const val DECKS = "decks.json"
    private const val GAMES = "games.json"
    private const val COMBOS = "combos.json"
    /** No library is near this; a zip claiming more is not one of ours (or a zip bomb). */
    private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
    private val json = Json { prettyPrint = true }

    fun write(file: File, pkg: LibraryPackage) {
        file.parentFile?.mkdirs()
        val part = File(file.parentFile, file.name + ".part")
        ZipOutputStream(part.outputStream().buffered()).use { zip ->
            fun put(name: String, element: JsonElement) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(json.encodeToString(JsonElement.serializer(), element).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put(MANIFEST, manifest(pkg.manifest))
            put(DECKS, JsonArray(pkg.decks.map(::deck)))
            put(GAMES, JsonArray(pkg.games.map(::game)))
            put(COMBOS, JsonArray(pkg.combos.map(::combo)))
        }
        // Whole or not at all: a half-written package never has the name.
        if (file.exists()) file.delete()
        check(part.renameTo(file)) { "could not write $file" }
    }

    fun read(file: File): LibraryPackage {
        if (!file.isFile) throw PackageRefused("no such file: $file")
        val entries = HashMap<String, String>()
        try {
            ZipInputStream(file.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name !in setOf(MANIFEST, DECKS, GAMES, COMBOS)) continue
                    val bytes = zip.readNBytes((MAX_ENTRY_BYTES + 1).toInt())
                    if (bytes.size > MAX_ENTRY_BYTES) throw PackageRefused("${file.name}: ${entry.name} is larger than a library would be")
                    entries[entry.name] = bytes.toString(Charsets.UTF_8)
                }
            }
        } catch (e: java.util.zip.ZipException) {
            throw PackageRefused("${file.name} is not a package (not a zip: ${e.message})")
        }
        val manifestText = entries[MANIFEST] ?: throw PackageRefused("${file.name} is not an MTG Oracle package (no manifest)")
        return try {
            val manifest = manifest(Json.parseToJsonElement(manifestText).jsonObject)
            if (manifest.version > PackageManifest.VERSION) throw PackageRefused("${file.name} was written by a newer MTG Oracle (package format ${manifest.version}; this one reads ${PackageManifest.VERSION}): update first")
            fun list(name: String) = entries[name]?.let { Json.parseToJsonElement(it).jsonArray }.orEmpty().map { it.jsonObject }
            LibraryPackage(manifest, list(DECKS).map(::deck), list(GAMES).map(::game), list(COMBOS).map(::combo))
        } catch (e: PackageRefused) {
            throw e
        } catch (e: Exception) {
            throw PackageRefused("${file.name} is damaged: ${e.message}")
        }
    }

    // --- to JSON ---

    private fun manifest(m: PackageManifest) = buildJsonObject {
        put("format", "mtg-oracle-package"); put("version", m.version); put("app", m.app); put("schema", m.schema)
        put("exported_at", m.exportedAt); put("scope", m.scope)
    }

    private fun deck(d: PackagedDeck) = buildJsonObject {
        put("folder", d.folder); put("folder_format", d.folderFormat); put("name", d.name); put("format", d.format)
        put("description", d.description); put("created_at", d.createdAt); put("updated_at", d.updatedAt)
        put("cards", buildJsonArray {
            d.cards.forEach { c ->
                add(buildJsonObject {
                    put("name", c.name); put("quantity", c.quantity); put("category", c.category); put("commander", c.commander)
                    put("sideboard", c.sideboard); put("added_at", c.addedAt); put("set_code", c.setCode); put("collector_number", c.collectorNumber)
                })
            }
        })
        put("considering", buildJsonArray { d.considering.forEach { c -> add(buildJsonObject { put("name", c.name); put("quantity", c.quantity); put("added_at", c.addedAt) }) } })
        put("substitutions", buildJsonArray { d.substitutions.forEach { s -> add(buildJsonObject { put("card", s.card); put("substitute", s.substitute); put("added_at", s.addedAt) }) } })
        put("history", buildJsonArray {
            d.history.forEach { r ->
                add(buildJsonObject {
                    put("at", r.at); put("action", r.action); put("note", r.note)
                    put("changes", buildJsonArray {
                        r.changes.forEach { c ->
                            add(buildJsonObject {
                                put("card", c.card); put("section", c.section); put("before", c.before); put("after", c.after)
                                put("set_before", c.setBefore); put("number_before", c.numberBefore); put("set_after", c.setAfter); put("number_after", c.numberAfter)
                            })
                        }
                    })
                })
            }
        })
    }

    private fun game(g: PackagedGame) = buildJsonObject {
        put("played_at", g.playedAt); put("mode", g.mode); put("deck", g.deck); put("deck_name", g.deckName)
        put("opponent_deck", g.opponentDeck); put("opponent_name", g.opponentName); put("opponent_ai_variant", g.opponentAiVariant)
        put("seed", g.seed); put("winner", g.winner); put("turns", g.turns); put("duration_ms", g.durationMs); put("forge_version", g.forgeVersion)
        put("match_id", g.matchId); put("game_no", g.gameNo); put("match_format", g.matchFormat); put("conceded", g.conceded); put("deck_ai_variant", g.deckAiVariant)
    }

    private fun combo(c: PackagedCombo) = buildJsonObject {
        put("name", c.name); put("color_identity", c.colorIdentity); put("description", c.description); put("added_at", c.addedAt)
        put("cards", buildJsonArray { c.cards.forEach { (name, qty) -> add(buildJsonObject { put("name", name); put("quantity", qty) }) } })
    }

    // --- from JSON ---

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
    private fun JsonObject.need(key: String): String = str(key) ?: throw PackageRefused("a field is missing: $key")
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.bool(key: String): Boolean = str(key) == "true"
    private fun JsonObject.list(key: String): List<JsonObject> = (this[key] as? JsonArray).orEmpty().map { it.jsonObject }

    private fun manifest(o: JsonObject): PackageManifest {
        if (o.str("format") != "mtg-oracle-package") throw PackageRefused("not an MTG Oracle package (format '${o.str("format")}')")
        return PackageManifest(o["version"]?.jsonPrimitive?.int ?: 0, o.str("app").orEmpty(), o.int("schema") ?: 0, o.str("exported_at").orEmpty(), o.str("scope").orEmpty())
    }

    private fun deck(o: JsonObject) = PackagedDeck(
        folder = o.str("folder"), folderFormat = o.str("folder_format"), name = o.need("name"), format = o.str("format"),
        description = o.str("description"), createdAt = o.str("created_at"), updatedAt = o.str("updated_at"),
        cards = o.list("cards").map { c ->
            PackagedCard(c.need("name"), c.int("quantity") ?: 1, c.str("category"), c.bool("commander"), c.bool("sideboard"),
                c.str("added_at"), c.str("set_code"), c.str("collector_number"))
        },
        considering = o.list("considering").map { c -> PackagedConsidering(c.need("name"), c.int("quantity") ?: 1, c.str("added_at")) },
        substitutions = o.list("substitutions").map { s -> PackagedSubstitution(s.need("card"), s.need("substitute"), s.str("added_at")) },
        history = o.list("history").map { r ->
            PackagedRevision(r.need("at"), r.need("action"), r.str("note"), r.list("changes").map { c ->
                PackagedChange(c.need("card"), c.need("section"), c.int("before") ?: 0, c.int("after") ?: 0,
                    c.str("set_before"), c.str("number_before"), c.str("set_after"), c.str("number_after"))
            })
        },
    )

    private fun game(o: JsonObject) = PackagedGame(
        playedAt = o.need("played_at"), mode = o.need("mode"), deck = o.str("deck"), deckName = o.need("deck_name"),
        opponentDeck = o.str("opponent_deck"), opponentName = o.need("opponent_name"), opponentAiVariant = o.int("opponent_ai_variant"),
        seed = o.long("seed"), winner = o.str("winner"), turns = o.int("turns"), durationMs = o.long("duration_ms"), forgeVersion = o.str("forge_version"),
        matchId = o.str("match_id"), gameNo = o.int("game_no"), matchFormat = o.str("match_format"), conceded = o.int("conceded"), deckAiVariant = o.int("deck_ai_variant"),
    )

    private fun combo(o: JsonObject) = PackagedCombo(
        o.str("name"), o.str("color_identity"), o.need("description"), o.str("added_at"),
        o.list("cards").map { it.need("name") to (it.int("quantity") ?: 1) },
    )
}
