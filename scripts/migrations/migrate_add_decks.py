"""Idempotent migration: add deck_folders, decks, and deck_cards tables.

Phase 3 Decks Lite schema. Folders are flat (no nesting) — users organize
by format / theme and move decks between folders. Nested trees can be
added later if there's demand.

Safe to run multiple times.
"""
import sqlite3
from pathlib import Path

DB_PATH = Path(__file__).parent.parent.parent / "data" / "mtg.db"


def table_exists(cur, name):
    cur.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (name,))
    return cur.fetchone() is not None


def main():
    if not DB_PATH.exists():
        print(f"ERR Database not found at {DB_PATH}.")
        return
    conn = sqlite3.connect(DB_PATH)
    cur = conn.cursor()
    changes = []

    if not table_exists(cur, "deck_folders"):
        cur.execute(
            """
            CREATE TABLE deck_folders (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE COLLATE NOCASE,
                created_at TEXT NOT NULL
            )
            """
        )
        changes.append("deck_folders")

    if not table_exists(cur, "decks"):
        cur.execute(
            """
            CREATE TABLE decks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                folder_id INTEGER,
                name TEXT NOT NULL,
                format TEXT,
                description TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                FOREIGN KEY (folder_id) REFERENCES deck_folders(id),
                UNIQUE (folder_id, name) ON CONFLICT ABORT
            )
            """
        )
        changes.append("decks")

    if not table_exists(cur, "deck_cards"):
        cur.execute(
            """
            CREATE TABLE deck_cards (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                deck_id INTEGER NOT NULL,
                card_name TEXT NOT NULL,
                quantity INTEGER NOT NULL DEFAULT 1 CHECK(quantity > 0),
                category TEXT,
                is_commander INTEGER NOT NULL DEFAULT 0,
                is_sideboard INTEGER NOT NULL DEFAULT 0,
                added_at TEXT NOT NULL,
                FOREIGN KEY (deck_id) REFERENCES decks(id) ON DELETE CASCADE,
                FOREIGN KEY (card_name) REFERENCES cards(name)
            )
            """
        )
        changes.append("deck_cards")

    cur.execute("CREATE INDEX IF NOT EXISTS idx_deck_cards_deck ON deck_cards(deck_id)")
    cur.execute("CREATE INDEX IF NOT EXISTS idx_deck_cards_card ON deck_cards(card_name)")
    cur.execute("CREATE INDEX IF NOT EXISTS idx_decks_folder ON decks(folder_id)")

    conn.commit()
    conn.close()
    if changes:
        print("OK Migration applied:")
        for c in changes:
            print(f"   - {c} table")
    else:
        print("OK Deck schema already up to date.")


if __name__ == "__main__":
    main()
