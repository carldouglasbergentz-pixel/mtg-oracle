package mtgoracle.data

import mtgoracle.core.deck.DeckRefusal
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The user's own combos (`user_combos` + `user_combo_cards`,
 * add_user_combo.py): the ones Spellbook doesn't list. They show in every
 * combo lookup beside Spellbook's. Ids are `user-NNN`, so they read apart
 * from Spellbook's `1234-5678` at a glance.
 */
class UserCombos(private val db: MtgDb, private val names: CardNames) {

    /**
     * Adds a combo of [cards] (each resolved, or nothing is written) that
     * does what [description] says; the colour identity is the cards',
     * WUBRG order as Spellbook writes it (`C` for none). Returns the new id.
     */
    fun add(cards: List<String>, description: String, name: String? = null): String {
        if (cards.size < 2) throw DeckRefusal(DeckRefusal.Kind.BAD_NAME, "a combo needs at least two cards")
        if (description.isBlank()) throw DeckRefusal(DeckRefusal.Kind.BAD_NAME, "say what the combo does")
        val unresolved = cards.filter { names.resolve(it) == null }
        if (unresolved.isNotEmpty()) throw DeckRefusal(DeckRefusal.Kind.CARD_NOT_FOUND, "card(s) not found: ${unresolved.joinToString(", ") { "'$it'" }}")
        val canonical = cards.map { names.resolve(it)!! }.distinctBy { it.lowercase() }
        return db.write { conn ->
            val used = conn.query("SELECT id FROM user_combos WHERE id LIKE 'user-%'") { getString(1) }.mapNotNull { it.substringAfter('-').toIntOrNull() }.toSet()
            val id = "user-%03d".format(generateSequence(1) { it + 1 }.first { it !in used })
            val identity = conn.query(
                "SELECT DISTINCT color_identity FROM cards WHERE name COLLATE NOCASE IN (${canonical.joinToString(",") { "?" }})", *canonical.toTypedArray(),
            ) { getString(1) }.flatMap { it.orEmpty().split(",") }.map { it.trim() }.toSet()
            val ci = "WUBRG".filter { it.toString() in identity }.ifEmpty { "C" }
            conn.update(
                "INSERT INTO user_combos (id, name, color_identity, description, added_at, added_by) VALUES (?, ?, ?, ?, ?, 'user')",
                id, name?.takeIf { it.isNotBlank() }, ci, description.trim(), Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
            )
            canonical.forEach { conn.update("INSERT INTO user_combo_cards (combo_id, card_name, quantity) VALUES (?, ?, 1)", id, it) }
            id
        }
    }

    /** Removes the user combo [id]; false when there is none. Spellbook's combos are not the user's to remove. */
    fun remove(id: String): Boolean = db.write { conn ->
        if (!id.startsWith("user-")) throw DeckRefusal(DeckRefusal.Kind.BAD_NAME, "'$id' is a Spellbook combo: only your own (user-NNN) can be removed")
        conn.update("DELETE FROM user_combo_cards WHERE combo_id = ?", id)
        conn.update("DELETE FROM user_combos WHERE id = ?", id) > 0
    }
}
