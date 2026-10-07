package mtgoracle.data.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.sql.Connection

/**
 * `cards.games` as the games of every printing of a card, not of the one the
 * oracle export chose: Gush's chosen printing lacks MTGO, Pyrogoyf's has only
 * Arena. The printings source gathers them from every printing, digital ones
 * included, and adds them here; the cards source merges its printing's games
 * into the stored ones. A card's games only grow: a game a card has left is
 * kept, which is rare and needs no table of its own to undo.
 */
internal object CardGames {
    fun parse(csv: String?): Set<String> = csv.orEmpty().split(',').filter { it.isNotEmpty() }.toSet()

    /** Sorted, as `game:` matches it with LIKE (no game's name is inside another's). */
    fun csv(games: Set<String>): String = games.sorted().joinToString(",")

    /** The games of a stream of printings, per card name; tokens and art cards are no card's. */
    class Collector {
        val byName = HashMap<String, MutableSet<String>>()

        fun add(printing: JsonObject) {
            if (printing.py("layout") in CardsIngest.SKIPPED_LAYOUTS) return
            val name = printing.py("name") as? String ?: return
            val games = (printing["games"] as? JsonArray)?.mapNotNull { it.asText() }.orEmpty()
            if (games.isNotEmpty()) byName.getOrPut(name) { HashSet() } += games
        }
    }

    /** Every card's stored games, by name. */
    fun stored(conn: Connection): Map<String, Set<String>> = conn.createStatement().use { st ->
        st.executeQuery("SELECT name, games FROM cards").use { rs -> buildMap { while (rs.next()) put(rs.getString(1), parse(rs.getString(2))) } }
    }

    /** Adds [byName]'s games to the cards of those names; a name with no card is skipped. The cards that gained one. */
    fun add(conn: Connection, byName: Map<String, Set<String>>): Int {
        val stored = stored(conn)
        var widened = 0
        conn.prepareStatement("UPDATE cards SET games = ? WHERE name = ?").use { update ->
            for ((name, games) in byName) {
                val have = stored[name] ?: continue
                if (have.containsAll(games)) continue
                update.bind(csv(have + games), name)
                update.addBatch()
                if (++widened % 5_000 == 0) update.executeBatch()
            }
            update.executeBatch()
        }
        return widened
    }
}
