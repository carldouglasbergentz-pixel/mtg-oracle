package mtgoracle.data

import mtgoracle.core.analysis.DeckList
import mtgoracle.core.deck.DeckParser
import java.io.File
import java.security.MessageDigest

/**
 * A folder of decklist `.txt` files as reference decks for the archetype
 * analysis (analyse_archetype.read_dir). Identical files collapse into one
 * deck named after the first: three of the twelve sample lists were
 * byte-for-byte duplicates, and counting them twice skews every "played in N
 * lists". Sideboards are dropped, as for a stored deck.
 */
object ReferenceLists {
    data class Entry(val deck: DeckList, val files: List<File>, val digest: String)

    fun read(dir: File, keepDuplicates: Boolean = false): List<Entry> {
        require(dir.isDirectory) { "not a directory: $dir" }
        val seen = linkedMapOf<String, Entry>()
        val files = dir.listFiles { f -> f.isFile && f.extension.equals("txt", ignoreCase = true) }.orEmpty()
            .sortedBy { it.name.lowercase() }
        for (file in files) {
            val entry = readFile(file) ?: continue
            val key = if (keepDuplicates) file.nameWithoutExtension else entry.digest
            val earlier = seen[key]
            seen[key] = earlier?.copy(files = earlier.files + file) ?: entry
        }
        return seen.values.toList()
    }

    /** One decklist file, or null when it has no card lines. */
    fun readFile(file: File): Entry? {
        val raw = file.readBytes()
        val rows = DeckParser.parse(String(raw, Charsets.UTF_8).removePrefix("﻿"))
        if (rows.isEmpty()) return null
        val cards = linkedMapOf<String, Int>()
        rows.filter { it.section != "sideboard" }.forEach { cards.merge(it.name, it.quantity, Int::plus) }
        val digest = MessageDigest.getInstance("MD5").digest(raw).joinToString("") { "%02x".format(it) }
        return Entry(DeckList(file.nameWithoutExtension, cards), listOf(file), digest)
    }
}
