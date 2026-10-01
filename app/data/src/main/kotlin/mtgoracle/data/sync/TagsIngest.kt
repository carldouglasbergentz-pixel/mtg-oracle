package mtgoracle.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.sql.Connection

/**
 * Scryfall Tagger's community labels into `card_oracle_tags`
 * (sync_oracle_tags.invert): direct taggings only, since the parent graph was
 * measured and costs more verdicts than it buys.
 */
internal object OracleTagsIngest {
    /** oracle id -> (label, weight), in the export's order. */
    fun invert(file: File): LinkedHashMap<String, MutableList<Pair<String, String>>> {
        val byCard = LinkedHashMap<String, MutableList<Pair<String, String>>>()
        for (tag in jsonLines(file)) {
            val label = tag.py("label") as? String
            if (label.isNullOrEmpty()) continue
            for (t in tag.array("taggings") ?: JsonArray(emptyList())) {
                val tagging = t as? JsonObject ?: continue
                val oid = tagging.py("oracle_id") as? String
                if (!oid.isNullOrEmpty()) byCard.getOrPut(oid) { mutableListOf() } += label to tagging.text("weight")
            }
        }
        return byCard
    }

    /** Replaces the table; a tagged card this database lacks is dropped. Returns the rows written. */
    fun write(conn: Connection, byCard: Map<String, List<Pair<String, String>>>): Int {
        val names = HashMap<String, String>()
        conn.createStatement().use { st ->
            st.executeQuery("SELECT oracle_id, name FROM cards WHERE oracle_id IS NOT NULL").use { rs -> while (rs.next()) names[rs.getString(1)] = rs.getString(2) }
        }
        conn.createStatement().use { it.executeUpdate("DELETE FROM card_oracle_tags") }
        var rows = 0
        conn.prepareStatement("INSERT OR IGNORE INTO card_oracle_tags (card_name, tag, weight) VALUES (?, ?, ?)").use { st ->
            for ((oid, taggings) in byCard) {
                val name = names[oid] ?: continue
                taggings.forEach { (label, weight) -> st.bind(name, label, weight); st.addBatch() }
                rows += taggings.size
                if (rows % 20_000 < taggings.size) st.executeBatch()
            }
            st.executeBatch()
        }
        return rows
    }
}

/**
 * Each card's oracle text and type line into `card_tags` (types, keywords)
 * and `card_abilities` (tag_cards.py): deterministic regexes. A mana ability
 * follows CR 605.1a/b: it adds mana, has no target, and is not loyalty.
 */
internal object CardTagger {
    /** CR 702 keywords and common mechanics; longest first, so a phrase matches before its words. */
    private val KEYWORDS: List<String> = listOf(
        "first strike", "double strike", "split second", "living weapon", "totem armor", "umbra armor", "cumulative upkeep",
        "level up", "jump-start", "battle cry", "deathtouch", "defender", "flash", "flying", "haste", "hexproof",
        "indestructible", "lifelink", "menace", "reach", "shroud", "trample", "vigilance", "ward", "fear", "intimidate",
        "horsemanship", "prowess", "cascade", "dredge", "convoke", "delve", "flashback", "unearth", "exalted", "infect",
        "evoke", "persist", "undying", "annihilator", "affinity", "storm", "cycling", "echo", "madness", "buyback",
        "kicker", "retrace", "rebound", "morph", "megamorph", "suspend", "overload", "emerge", "embalm", "eternalize",
        "escape", "foretell", "mutate", "companion", "adventure", "disturb", "daybound", "nightbound", "cleave", "channel",
        "boast", "devoid", "fabricate", "crew", "partner", "prowl", "bushido", "changeling", "decayed", "graft",
        "hideaway", "improvise", "ingest", "melee", "mentor", "metalcraft", "miracle", "myriad", "ninjutsu", "offering",
        "outlast", "populate", "prototype", "reconfigure", "renown", "riot", "scavenge", "spectacle", "squad", "training",
        "vanishing", "venture", "discover", "bargain", "saga", "impending", "offspring", "plot", "compleated", "encore",
        "casualty", "craft", "toxic", "exploit", "extort", "explore", "evolve", "dash", "bestow", "entwine", "epic",
        "fading", "conspire", "splice", "transmute", "wither", "sunburst", "replicate", "phasing", "modular", "ripple",
        "haunt", "shadow", "banding", "landfall", "provoke", "regenerate", "amplify", "gravestorm", "forecast",
        "flanking", "cipher", "dethrone", "devour", "exert", "fuse", "connive", "afflict", "aftermath", "enlist", "blitz",
        "protection", "landwalk", "forestwalk", "islandwalk", "mountainwalk", "plainswalk", "swampwalk",
    ).distinct().sortedByDescending { it.length }

    private val SUPERTYPES = setOf("Legendary", "Basic", "Snow", "World", "Ongoing", "Host", "Elite", "Token")

    private val KEYWORD = pyRegex("(?<![A-Za-z])(" + KEYWORDS.joinToString("|") { Regex.escape(it) } + ")(?![A-Za-z])", ignoreCase = true)
    private val REMINDER = pyRegex("""\s*\([^)]*\)\s*""")
    private val FACE_SEPARATOR = pyRegex("""\s*//\s*""")
    private val TRIGGER = pyRegex("""^(?:at\s|when(?:ever)?\s)""", ignoreCase = true)
    private val LOYALTY = pyRegex("""^[+\-−]?\d+\s*:""")
    private val PRODUCES_MANA = pyRegex("""\bAdd\b[^.\n]*?(?:\bmana\b|\{[WUBRGCXSPYHAE0-9/]+\})""", ignoreCase = true)
    private val HAS_TARGET = pyRegex("""\btarget\b""", ignoreCase = true)
    private val SYMBOL = pyRegex("""\{[^}]+\}""")
    private val FROM = pyRegex("""\bfrom\s+\w+(?:\s+\w+)?\b""", ignoreCase = true)
    private val DASHED = pyRegex("""[—\-]\s*\w+(?:walk)?""")
    private val PUNCTUATION = pyRegex("""[\s,;:\-—]+""")
    private val WHITESPACE = Regex("""[\s\u001C-\u001F\u0085]+""")

    private fun stripReminders(text: String): String = REMINDER.replace(text, " ").pyStrip()

    private fun abilityLines(text: String): List<String> =
        FACE_SEPARATOR.replace(text, "\n").split("\n").map(::stripReminders).filter { it.isNotEmpty() }

    /** A line of keywords and their small modifiers (`Flying, haste`, `Ward {2}`, `Protection from red`). */
    private fun keywordOnly(line: String): Boolean {
        if ('.' in line || HAS_TARGET.containsMatchIn(line)) return false
        if (!KEYWORD.containsMatchIn(line)) return false
        var rest = KEYWORD.replace(line, "")
        rest = SYMBOL.replace(rest, "")
        rest = FROM.replace(rest, "")
        rest = DASHED.replace(rest, "")
        rest = PUNCTUATION.replace(rest, "")
        return rest.codePointCount(0, rest.length) <= 3
    }

    private data class Ability(val type: String, val cost: String?, val effect: String)

    private fun classify(line: String): Ability = when {
        LOYALTY.find(line) != null -> Ability("loyalty", line.substringBefore(':').pyStrip(), line.substringAfter(':', "").pyStrip())
        ':' in line -> Ability("activated", line.substringBefore(':').pyStrip(), line.substringAfter(':', "").pyStrip())
        TRIGGER.find(line) != null -> Ability("triggered", null, line)
        keywordOnly(line) -> Ability("keyword", null, line)
        else -> Ability("static", null, line)
    }

    private fun typeTags(typeLine: String): List<Pair<String, String>> {
        if (typeLine.isEmpty()) return emptyList()
        val (left, right) = if (" — " in typeLine) typeLine.substringBefore(" — ") to typeLine.substringAfter(" — ") else typeLine to ""
        fun words(s: String) = s.split(WHITESPACE).filter { it.isNotEmpty() }
        return words(left).map { it.lowercase() to if (it in SUPERTYPES) "supertype" else "type" } + words(right).map { it.lowercase() to "subtype" }
    }

    private fun keywordTags(text: String): Set<String> = KEYWORD.findAll(stripReminders(text)).map { it.groupValues[1].lowercase() }.toSet()

    /** The faces to tag: card_faces' own text and types when it has them, else the card's. */
    private fun faces(oracleText: String, typeLine: String, facesJson: String?): List<Pair<String, String>> {
        val parsed = facesJson?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() as? JsonArray }
        val faces = parsed?.mapNotNull { f -> (f as? JsonObject)?.let { it.text("oracle_text") to it.text("type_line") } }.orEmpty()
        return faces.ifEmpty { listOf(oracleText to typeLine) }
    }

    /** Wipes and rebuilds both tables from `cards`; returns (tag rows, ability rows) as stored. */
    fun retag(conn: Connection): Pair<Int, Int> {
        conn.createStatement().use { it.executeUpdate("DELETE FROM card_tags"); it.executeUpdate("DELETE FROM card_abilities") }
        val tag = conn.prepareStatement("INSERT OR IGNORE INTO card_tags (card_name, tag, category, source) VALUES (?, ?, ?, ?)")
        val ability = conn.prepareStatement(
            "INSERT INTO card_abilities (card_name, ability_index, ability_type, cost, effect, has_target, produces_mana, is_mana_ability, raw_text) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        )
        try {
            conn.createStatement().use { st ->
                st.executeQuery("SELECT name, oracle_text, type_line, card_faces FROM cards").use { rs ->
                    var batched = 0
                    while (rs.next()) {
                        val name = rs.getString(1)
                        val tags = linkedSetOf<Triple<String, String, String>>()
                        var index = 0
                        for ((text, type) in faces(rs.getString(2).orEmpty(), rs.getString(3).orEmpty(), rs.getString(4))) {
                            typeTags(type).forEach { (t, cat) -> tags += Triple(t, cat, "type_line") }
                            keywordTags(text).forEach { tags += Triple(it, "keyword", "regex") }
                            for (line in abilityLines(text)) {
                                val a = classify(line)
                                val hasTarget = HAS_TARGET.containsMatchIn(line)
                                val producesMana = PRODUCES_MANA.containsMatchIn(line)
                                val isMana = producesMana && !hasTarget && a.type in setOf("activated", "triggered")
                                ability.bind(name, index++, a.type, a.cost, a.effect, hasTarget, producesMana, isMana, line)
                                ability.addBatch()
                                batched++
                            }
                        }
                        tags.forEach { (t, cat, src) -> tag.bind(name, t, cat, src); tag.addBatch(); batched++ }
                        if (batched >= 20_000) { tag.executeBatch(); ability.executeBatch(); batched = 0 }
                    }
                }
            }
            tag.executeBatch(); ability.executeBatch()
        } finally {
            tag.close(); ability.close()
        }
        fun count(table: String) = conn.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM $table").use { it.next(); it.getInt(1) } }
        return count("card_tags") to count("card_abilities")
    }
}
