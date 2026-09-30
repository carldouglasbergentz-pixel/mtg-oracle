"""Idempotent migration: the Forge integration's two tables.

    forge_substitutions  per deck, a card Forge's AI can't pilot and what the
                         deck's AI copy plays instead. UNIQUE on
                         (deck_id, card_name COLLATE NOCASE), whose index also
                         serves the case-insensitive lookups.
    forge_matches        one row per simulated game, grouped by match_id.
                         The deck names are kept as they were run, and the
                         deck ids go NULL when a deck is deleted, so history
                         survives the deck.

Both reference `decks`; `decks._rw` and `forge_data` turn foreign keys on.

Run:
    python scripts/migrations/migrate_add_forge.py
"""
import sqlite3
from pathlib import Path

DB_PATH = Path(__file__).parent.parent.parent / "data" / "mtg.db"

# Mirrored in scripts/init_db.py — keep the two in step.
TABLES = {
    "forge_substitutions": """
        CREATE TABLE forge_substitutions (
            id INTEGER PRIMARY KEY,
            deck_id INTEGER NOT NULL
                REFERENCES decks(id) ON DELETE CASCADE,
            card_name TEXT NOT NULL,
            substitute TEXT NOT NULL,
            added_at TEXT NOT NULL,
            UNIQUE (deck_id, card_name COLLATE NOCASE)
        )
    """,
    "forge_matches": """
        CREATE TABLE forge_matches (
            id INTEGER PRIMARY KEY,
            match_id TEXT NOT NULL,
            played_at TEXT NOT NULL,
            deck_a TEXT NOT NULL,
            deck_b TEXT NOT NULL,
            deck_a_id INTEGER REFERENCES decks(id) ON DELETE SET NULL,
            deck_b_id INTEGER REFERENCES decks(id) ON DELETE SET NULL,
            ai_variant_a INTEGER NOT NULL DEFAULT 0 CHECK (ai_variant_a IN (0, 1)),
            ai_variant_b INTEGER NOT NULL DEFAULT 0 CHECK (ai_variant_b IN (0, 1)),
            game_type TEXT NOT NULL
                CHECK (game_type IN ('constructed', 'commander')),
            game_no INTEGER NOT NULL,
            winner TEXT NOT NULL CHECK (winner IN ('a', 'b', 'draw')),
            turns INTEGER,
            duration_ms INTEGER,
            forge_version TEXT,
            log_path TEXT
        )
    """,
}
INDEXES = {
    "idx_forge_matches_match": "CREATE INDEX idx_forge_matches_match "
                               "ON forge_matches(match_id)",
    "idx_forge_matches_deck_a": "CREATE INDEX idx_forge_matches_deck_a "
                                "ON forge_matches(deck_a_id)",
    "idx_forge_matches_deck_b": "CREATE INDEX idx_forge_matches_deck_b "
                                "ON forge_matches(deck_b_id)",
}


def _exists(cur, kind: str, name: str) -> bool:
    cur.execute("SELECT 1 FROM sqlite_master WHERE type = ? AND name = ?",
                (kind, name))
    return cur.fetchone() is not None


def main() -> None:
    if not DB_PATH.exists():
        print(f"ERR Database not found at {DB_PATH}.")
        return
    conn = sqlite3.connect(DB_PATH)
    try:
        cur = conn.cursor()
        changes = []
        for name, sql in TABLES.items():
            if not _exists(cur, "table", name):
                cur.execute(sql)
                changes.append(name)
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
