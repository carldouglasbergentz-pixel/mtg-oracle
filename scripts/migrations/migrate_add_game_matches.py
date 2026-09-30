"""Idempotent migration: group the app's `games` rows into matches.

    games.match_id      TEXT     the same value on every game of one match;
                                 the app generates it. NULL — every row from
                                 before this migration — is a single-game
                                 match.
    games.game_no       INTEGER  1, 2, 3 ... within the match
    games.match_format  TEXT     'bo1' / 'bo3' / 'bo5'
    games.conceded      INTEGER  1 when the game ended by concession, else 0

The Kotlin app codes against these names — don't rename them.

Run:
    python scripts/migrations/migrate_add_game_matches.py
"""
import sqlite3
from pathlib import Path

DB_PATH = Path(__file__).parent.parent.parent / "data" / "mtg.db"

# (column, declaration), in this order. Mirrored in scripts/init_db.py.
COLUMNS = (
    ("match_id", "TEXT"),
    ("game_no", "INTEGER"),
    ("match_format", "TEXT CHECK (match_format IN ('bo1', 'bo3', 'bo5'))"),
    ("conceded", "INTEGER NOT NULL DEFAULT 0"),
)
INDEX_NAME = "idx_games_match"
INDEX_SQL = f"CREATE INDEX {INDEX_NAME} ON games(match_id)"


def _exists(cur, kind: str, name: str) -> bool:
    cur.execute("SELECT 1 FROM sqlite_master WHERE type = ? AND name = ?",
                (kind, name))
    return cur.fetchone() is not None


def _has_column(cur, table: str, column: str) -> bool:
    cur.execute(f"PRAGMA table_info({table})")
    return any(row[1] == column for row in cur.fetchall())


def main() -> None:
    if not DB_PATH.exists():
        print(f"ERR Database not found at {DB_PATH}.")
        return
    conn = sqlite3.connect(DB_PATH)
    try:
        cur = conn.cursor()
        changes = []
        # `games` itself is migrate_add_printings_and_games' job, which
        # self_heal runs first.
        if _exists(cur, "table", "games"):
            for column, declaration in COLUMNS:
                if not _has_column(cur, "games", column):
                    cur.execute(f"ALTER TABLE games ADD COLUMN {column} {declaration}")
                    changes.append(f"games.{column}")
            if not _exists(cur, "index", INDEX_NAME):
                cur.execute(INDEX_SQL)
                changes.append(INDEX_NAME)
        conn.commit()
    finally:
        conn.close()
    if changes:
        print("OK Migration applied:")
        for c in changes:
            print(f"   - {c}")
    else:
        print("OK Schema already up to date.")


if __name__ == "__main__":
    main()
