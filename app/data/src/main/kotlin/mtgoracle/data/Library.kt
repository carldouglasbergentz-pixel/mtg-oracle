package mtgoracle.data

import mtgoracle.core.deck.CardInfo
import mtgoracle.core.deck.Deck
import mtgoracle.core.deck.DeckCard
import mtgoracle.core.deck.DeckSummary
import mtgoracle.core.deck.Folder
import mtgoracle.core.deck.Substitution
import java.sql.ResultSet

/** Folders, decks and AI substitutions, read-only. The TUI edits them; this app only plays them. */
class Library(private val db: MtgDb) {

    fun folders(): List<Folder> = db.read { conn ->
        conn.prepareStatement("SELECT id, name, format FROM deck_folders ORDER BY name COLLATE NOCASE").use { st ->
            st.executeQuery().use { rs -> rs.rows { Folder(getInt("id"), getString("name"), getString("format")) } }
        }
    }

    /** Every deck, foldered ones by folder then name, unsorted ones last. */
    fun decks(): List<DeckSummary> = db.read { conn ->
        conn.prepareStatement(
            """
            SELECT d.id, d.name, d.format, d.folder_id, f.name AS folder_name,
                   COALESCE((SELECT SUM(quantity) FROM deck_cards dc
                             WHERE dc.deck_id = d.id AND dc.is_sideboard = 0), 0) AS card_count
            FROM decks d LEFT JOIN deck_folders f ON f.id = d.folder_id
            ORDER BY f.name IS NULL, f.name COLLATE NOCASE, d.name COLLATE NOCASE
            """.trimIndent(),
        ).use { st ->
            st.executeQuery().use { rs ->
                rs.rows {
                    DeckSummary(
                        id = getInt("id"), name = getString("name"), format = getString("format"),
                        folderId = getObject("folder_id")?.let { (it as Number).toInt() },
                        folderName = getString("folder_name"), cardCount = getInt("card_count"),
                    )
                }
            }
        }
    }

    /** The deck with its cards (plus what `cards` knows about each) and its AI substitutions; null if gone. */
    fun deck(id: Int): Deck? = db.read { conn ->
        val head = conn.prepareStatement(
            "SELECT d.id, d.name, d.format, d.pool_id, f.name AS folder_name FROM decks d LEFT JOIN deck_folders f ON f.id = d.folder_id WHERE d.id = ?",
        ).use { st ->
            st.setInt(1, id)
            st.executeQuery().use { rs -> if (rs.next()) Head(rs.getString("name"), rs.getString("format"), rs.getString("folder_name"), rs.getObject("pool_id")?.let { (it as Number).toInt() }) else null }
        } ?: return@read null
        val cards = conn.prepareStatement(
            """
            SELECT dc.card_name, dc.quantity, dc.is_commander, dc.is_sideboard, dc.set_code, dc.collector_number,
                   c.name AS known, c.mana_cost, c.type_line, c.oracle_text, c.power, c.toughness
            FROM deck_cards dc LEFT JOIN cards c ON c.name = dc.card_name
            WHERE dc.deck_id = ?
            ORDER BY dc.is_commander DESC, dc.is_sideboard, dc.card_name COLLATE NOCASE
            """.trimIndent(),
        ).use { st ->
            st.setInt(1, id)
            st.executeQuery().use { rs ->
                rs.rows {
                    DeckCard(
                        name = getString("card_name"), quantity = getInt("quantity"),
                        isCommander = getInt("is_commander") != 0, isSideboard = getInt("is_sideboard") != 0,
                        setCode = getString("set_code")?.takeIf { it.isNotBlank() },
                        collectorNumber = getString("collector_number")?.takeIf { it.isNotBlank() },
                        info = getString("known")?.let {
                            CardInfo(getString("mana_cost"), getString("type_line"), getString("oracle_text"), getString("power"), getString("toughness"))
                        },
                    )
                }
            }
        }
        val subs = conn.prepareStatement(
            "SELECT card_name, substitute FROM forge_substitutions WHERE deck_id = ? ORDER BY card_name COLLATE NOCASE",
        ).use { st ->
            st.setInt(1, id)
            st.executeQuery().use { rs -> rs.rows { Substitution(getString("card_name"), getString("substitute")) } }
        }
        val considering = conn.prepareStatement(
            """
            SELECT dc.card_name, dc.quantity, c.name AS known, c.mana_cost, c.type_line, c.oracle_text, c.power, c.toughness
            FROM deck_considering dc LEFT JOIN cards c ON c.name = dc.card_name
            WHERE dc.deck_id = ? ORDER BY dc.card_name COLLATE NOCASE
            """.trimIndent(),
        ).use { st ->
            st.setInt(1, id)
            st.executeQuery().use { rs ->
                rs.rows {
                    DeckCard(
                        name = getString("card_name"), quantity = getInt("quantity"), isCommander = false, isSideboard = false,
                        info = getString("known")?.let { CardInfo(getString("mana_cost"), getString("type_line"), getString("oracle_text"), getString("power"), getString("toughness")) },
                    )
                }
            }
        }
        Deck(id, head.name, head.format, head.folder, cards, subs, considering, head.poolId)
    }

    private data class Head(val name: String, val format: String?, val folder: String?, val poolId: Int?)
}

internal inline fun <T> ResultSet.rows(read: ResultSet.() -> T): List<T> = buildList { while (next()) add(read()) }
