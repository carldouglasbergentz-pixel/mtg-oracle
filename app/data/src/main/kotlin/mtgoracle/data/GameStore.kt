package mtgoracle.data

import mtgoracle.core.play.GameRecord
import java.sql.Statement
import java.sql.Types

/**
 * The one table this app writes: one `games` row per finished game, in a
 * transaction, with parameterised SQL. The table itself is the Python
 * migration's (scripts/self_heal.py).
 */
class GameStore(private val db: MtgDb) {

    /** Inserts [record]; returns the new row id. */
    fun insert(record: GameRecord): Long = db.write { conn ->
        conn.prepareStatement(
            """
            INSERT INTO games (played_at, mode, deck_id, deck_name, opponent_deck_id, opponent_name,
                               opponent_ai_variant, seed, winner, turns, duration_ms, forge_version, log_path,
                               match_id, game_no, match_format, conceded)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            st.executeUpdate()
            st.generatedKeys.use { keys -> keys.next(); keys.getLong(1) }
        }
    }

    private fun java.sql.PreparedStatement.setNullableInt(index: Int, value: Int?) {
        if (value == null) setNull(index, Types.INTEGER) else setInt(index, value)
    }
}
