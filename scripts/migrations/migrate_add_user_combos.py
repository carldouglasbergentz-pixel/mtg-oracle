"""Idempotent migration: add user_combos + user_combo_cards tables.

User-curated combos that supplement Spellbook. They live in their own
tables so they're preserved across `sync_combos.py` wipe-and-rebuild.

Schema mirrors the Spellbook shape closely so query-layer UNION is
straightforward. IDs use a `user-` prefix to guarantee no collision
with Spellbook's numeric-pair IDs.

After this runs, `mtg_oracle.queries` and `mtg_oracle.decks` will pick
up user_combos automatically — no further migration needed.
"""
import sqlite3
from pathlib import Path

DB_PATH = Path(__file__).parent.parent.parent / "data" / "mtg.db"


def table_exists(cur, name: str) -> bool:
    cur.execute(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (name,),
    )
    return cur.fetchone() is not None


def main():
    if not DB_PATH.exists():
        print(f"ERR Database not found at {DB_PATH}.")
        return
    conn = sqlite3.connect(DB_PATH)
    cur = conn.cursor()
    changes: list[str] = []

    if not table_exists(cur, "user_combos"):
        cur.execute(
            """
            CREATE TABLE user_combos (
                id TEXT PRIMARY KEY,
                name TEXT,
                color_identity TEXT,
                description TEXT,
                added_at TEXT NOT NULL,
                added_by TEXT
            )
            """
        )
        changes.append("user_combos")

    if not table_exists(cur, "user_combo_cards"):
        cur.execute(
            """
            CREATE TABLE user_combo_cards (
                combo_id TEXT NOT NULL,
                card_name TEXT NOT NULL,
                quantity INTEGER DEFAULT 1,
                PRIMARY KEY (combo_id, card_name),
                FOREIGN KEY (combo_id) REFERENCES user_combos(id) ON DELETE CASCADE
            )
            """
        )
        cur.execute(
            "CREATE INDEX IF NOT EXISTS idx_user_combo_cards_card "
            "ON user_combo_cards(card_name)"
        )
        changes.append("user_combo_cards")

    conn.commit()
    conn.close()

    if changes:
        print("OK Migration applied:")
        for c in changes:
            print(f"   - {c}")
    else:
        print("OK Schema already up to date.")


if __name__ == "__main__":
    main()
