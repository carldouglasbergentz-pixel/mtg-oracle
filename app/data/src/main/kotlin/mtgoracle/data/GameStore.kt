package mtgoracle.data

import mtgoracle.core.play.DeckKey
import mtgoracle.core.play.GameMode
import mtgoracle.core.play.GameRecord
import mtgoracle.core.play.PlayedGame
import mtgoracle.core.play.Winner
import java.sql.Statement
import java.sql.Types

/**
 * The one table this app writes: one `games` row per finished game, in a
 * transaction, with parameterised SQL. The table itself is the
 * schema's ([Schema]).
 */
class GameStore(private val db: MtgDb) {

    /** Inserts [record]; returns the new row id. */
    fun insert(record: GameRecord): Long = db.write { conn ->
        conn.prepareStatement(
            """
            INSERT INTO games (played_at, mode, deck_id, deck_name, opponent_deck_id, opponent_name,
                               opponent_ai_variant, seed, winner, turns, duration_ms, forge_version, log_path,
                               match_id, game_no, match_format, conceded, deck_ai_variant)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { st ->
            st.setString(1, record.playedAt.toString()) // ISO-8601 UTC, e.g. 2026-09-29T08:10:00Z
            st.setString(2, record.mode.column)
            st.setNullableInt(3, record.deckId)
            st.setString(4, record.deckName)
            st.setNullableInt(5, record.opponentDeckId)
            st.setString(6, record.opponentName)
            st.setInt(7, if (record.opponentAiVariant) 1 else 0)
            record.seed.let { if (it == null) st.setNull(8, Types.INTEGER) else st.setLong(8, it) }
            record.winner.let { if (it == null) st.setNull(9, Types.VARCHAR) else st.setString(9, it.column) }
            st.setNullableInt(10, record.turns)
            record.durationMs.let { if (it == null) st.setNull(11, Types.INTEGER) else st.setLong(11, it) }
            st.setString(12, record.forgeVersion)
            st.setString(13, record.logPath)
            st.setString(14, record.matchId)
            st.setNullableInt(15, record.gameNo)
            st.setString(16, record.matchFormat?.column)
            st.setInt(17, if (record.conceded) 1 else 0)
            st.setInt(18, if (record.deckAiVariant) 1 else 0)
            st.executeUpdate()
            st.generatedKeys.use { keys -> keys.next(); keys.getLong(1) }
        }
    }

    /**
     * Every game, for the records: each deck by its current name while it
     * exists, else by the name the row kept, without the AI copy's " (AI)".
     */
    fun played(): List<PlayedGame> = db.read { conn ->
        conn.prepareStatement(
            """
            SELECT g.mode, g.deck_id, COALESCE(d.name, g.deck_name), g.opponent_deck_id, COALESCE(o.name, g.opponent_name), g.winner, g.turns
            FROM games g LEFT JOIN decks d ON d.id = g.deck_id LEFT JOIN decks o ON o.id = g.opponent_deck_id
            ORDER BY g.id
            """.trimIndent(),
        ).use { st ->
            st.executeQuery().use { rs ->
                rs.rows {
                    fun deck(idColumn: Int) = DeckKey(getObject(idColumn)?.let { getInt(idColumn) }, getString(idColumn + 1).removeSuffix(AI_SUFFIX))
                    PlayedGame(
                        mode = GameMode.entries.first { it.column == getString(1) },
                        deck = deck(2),
                        opponent = deck(4),
                        winner = getString(6)?.let { w -> Winner.entries.first { it.column == w } },
                        turns = getObject(7)?.let { getInt(7) },
                    )
                }
            }
        }
    }

    private companion object {
        const val AI_SUFFIX = " (AI)"
    }

    private fun java.sql.PreparedStatement.setNullableInt(index: Int, value: Int?) {
        if (value == null) setNull(index, Types.INTEGER) else setInt(index, value)
    }
}
