"""Idempotent migration: per-deck change history, for review and undo.

    deck_revisions   one row per user action that changed a deck's contents
                     (add, remove, promote, demote, import, load, replace,
                     undo) — one action is one revision, however many cards
                     it touched.
    deck_changes     the quantity diff of one revision, one row per
                     (card_name, section) whose quantity changed.

Rename, move and format changes are not content and are not logged.
Both tables cascade from `decks`; `decks._rw` turns foreign keys on.

Run:
    python scripts/migrate_add_deck_history.py
"""
import sqlite3
from pathlib import Path

DB_PATH = Path(__file__).parent.parent / "data" / "mtg.db"

# Mirrored in scripts/init_db.py — keep the two in step.
TABLES = {
    "deck_revisions": """
        CREATE TABLE deck_revisions (
            id INTEGER PRIMARY KEY,
            deck_id INTEGER NOT NULL
                REFERENCES decks(id) ON DELETE CASCADE,
            at TEXT NOT NULL,
            action TEXT NOT NULL,
            note TEXT
        )
    """,
    "deck_changes": """
        CREATE TABLE deck_changes (
            id INTEGER PRIMARY KEY,
            revision_id INTEGER NOT NULL
                REFERENCES deck_revisions(id) ON DELETE CASCADE,
            card_name TEXT NOT NULL,
            section TEXT NOT NULL
                CHECK (section IN ('main', 'sideboard', 'commander')),
            qty_before INTEGER NOT NULL,
            qty_after INTEGER NOT NULL
        )
    """,
}
INDEXES = {
    "idx_deck_revisions_deck": "CREATE INDEX idx_deck_revisions_deck "
                               "ON deck_revisions(deck_id)",
    "idx_deck_changes_revision": "CREATE INDEX idx_deck_changes_revision "
                                 "ON deck_changes(revision_id)",
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
