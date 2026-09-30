package mtgoracle.data

import mtgoracle.core.deck.Substitution
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * A deck's AI copy: `forge_substitutions`, the cards Forge's AI plays in
 * place of ones it can't (forge_data.py). Not the deck's content, so not in
 * its history; a substitution is judged by [DeckWriter.checkSwaps] first.
 */
class Substitutions(private val db: MtgDb) {

    fun list(deckId: Int): List<Substitution> = db.read { conn ->
        conn.query("SELECT card_name, substitute FROM forge_substitutions WHERE deck_id = ? ORDER BY card_name COLLATE NOCASE", deckId) {
            Substitution(getString(1), getString(2))
        }
    }

    /** Stores or replaces the substitute for [card]; returns the one it replaced, or null. */
    fun set(deckId: Int, card: String, substitute: String): String? = db.write { conn ->
        val now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
        val existing = conn.query("SELECT id, substitute FROM forge_substitutions WHERE deck_id = ? AND card_name = ? COLLATE NOCASE", deckId, card) {
            getLong(1) to getString(2)
        }.firstOrNull()
        if (existing != null) {
            conn.update("UPDATE forge_substitutions SET card_name = ?, substitute = ?, added_at = ? WHERE id = ?", card, substitute, now, existing.first)
        } else {
            conn.update("INSERT INTO forge_substitutions (deck_id, card_name, substitute, added_at) VALUES (?, ?, ?, ?)", deckId, card, substitute, now)
        }
        existing?.second
    }

    /** Drops the substitute for [card]; returns it, or null when there was none. */
    fun remove(deckId: Int, card: String): String? = db.write { conn ->
        val existing = conn.query("SELECT id, substitute FROM forge_substitutions WHERE deck_id = ? AND card_name = ? COLLATE NOCASE", deckId, card) {
            getLong(1) to getString(2)
        }.firstOrNull() ?: return@write null
        conn.update("DELETE FROM forge_substitutions WHERE id = ?", existing.first)
        existing.second
    }
}
