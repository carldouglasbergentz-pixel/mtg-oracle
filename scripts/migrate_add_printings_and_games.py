"""Idempotent migration: the printing a deck row uses, and the app's games.

    deck_cards.set_code / .collector_number
        The printing the user chose, as Scryfall spells it (`c18`, `263`;
        `76★`, `DDN-64`). NULL means "no printing chosen": Forge and the app
        show their default art. Taken from the paste (`1 Sol Ring (C18) 263`)
        and written back by the export, so export -> import keeps it.
    deck_changes.set_code_before / collector_number_before /
                 set_code_after / collector_number_after
        A printing change is a content change: it gets a revision row like a
        quantity change (with qty_before = qty_after), and undo restores it.
    games
        One row per game the Kotlin app plays. Written by the app, read by
        both. The deck ids go NULL when a deck is deleted and the names stay,
        so a game's record outlives the deck (as in `forge_matches`).

`games` references `decks`; `decks._rw` turns foreign keys on.

Run:
    python scripts/migrate_add_printings_and_games.py
"""
import sqlite3
from pathlib import Path

DB_PATH = Path(__file__).parent.parent / "data" / "mtg.db"

# (table, column, type). Mirrored in scripts/init_db.py.
COLUMNS = (
    ("deck_cards", "set_code", "TEXT"),
    ("deck_cards", "collector_number", "TEXT"),
    ("deck_changes", "set_code_before", "TEXT"),
    ("deck_changes", "collector_number_before", "TEXT"),
    ("deck_changes", "set_code_after", "TEXT"),
    ("deck_changes", "collector_number_after", "TEXT"),
)
# The Kotlin app codes against this table exactly — don't rename anything.
GAMES_SQL = """
    CREATE TABLE games (
        id INTEGER PRIMARY KEY,
        played_at TEXT NOT NULL,
        mode TEXT NOT NULL CHECK (mode IN ('human_vs_ai', 'ai_vs_ai')),
        deck_id INTEGER REFERENCES decks(id) ON DELETE SET NULL,
        deck_name TEXT NOT NULL,
        opponent_deck_id INTEGER REFERENCES decks(id) ON DELETE SET NULL,
        opponent_name TEXT NOT NULL,
        opponent_ai_variant INTEGER NOT NULL DEFAULT 0,
        seed INTEGER,
        winner TEXT CHECK (winner IN ('me', 'opponent', 'draw')),
        turns INTEGER,
        duration_ms INTEGER,
        forge_version TEXT,
        log_path TEXT
    )
"""
INDEXES = {
    "idx_games_deck": "CREATE INDEX idx_games_deck ON games(deck_id)",
    "idx_games_opponent_deck": "CREATE INDEX idx_games_opponent_deck "
                               "ON games(opponent_deck_id)",
}


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
        for table, column, kind in COLUMNS:
            # A table an earlier migration hasn't created yet is its job;
            # self_heal runs this after decks and deck history.
            if _exists(cur, "table", table) and not _has_column(cur, table, column):
                cur.execute(f"ALTER TABLE {table} ADD COLUMN {column} {kind}")
                changes.append(f"{table}.{column}")
        if _exists(cur, "table", "decks") and not _exists(cur, "table", "games"):
            cur.execute(GAMES_SQL)
            changes.append("games")
        if _exists(cur, "table", "games"):
            for name, sql in INDEXES.items():
                if not _exists(cur, "index", name):
                    cur.execute(sql)
                    changes.append(name)
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
