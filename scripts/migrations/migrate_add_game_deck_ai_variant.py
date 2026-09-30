"""Idempotent migration: record whether seat A played its AI copy.

    games.deck_ai_variant  INTEGER  1 when `deck` played its AI copy (the
                                    deck's forge_substitutions applied), else
                                    0. The human always plays the deck as
                                    built; a simulation may give both AIs
                                    their copies. Every row from before is 0.

`opponent_ai_variant` already says the same of the opponent. The Kotlin app
codes against this name — don't rename it.

Run:
    python scripts/migrations/migrate_add_game_deck_ai_variant.py
"""
import sqlite3
from pathlib import Path

DB_PATH = Path(__file__).parent.parent.parent / "data" / "mtg.db"

# Mirrored in scripts/init_db.py.
COLUMN = ("deck_ai_variant", "INTEGER NOT NULL DEFAULT 0")


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
        column, declaration = COLUMN
        if _exists(cur, "table", "games") and not _has_column(cur, "games", column):
            cur.execute(f"ALTER TABLE games ADD COLUMN {column} {declaration}")
            changes.append(f"games.{column}")
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
