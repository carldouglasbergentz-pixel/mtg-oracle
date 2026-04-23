"""Idempotent migration: add `cards.mana_cost` column.

After this runs, re-populate the column by running
`python scripts/sync.py --only cards --force` (the cached Scryfall
bulk file is used — no extra network round trip).
"""
import sqlite3
from pathlib import Path

DB_PATH = Path(__file__).parent.parent / "data" / "mtg.db"


def column_exists(cur: sqlite3.Cursor, table: str, column: str) -> bool:
    cur.execute(f"PRAGMA table_info({table})")
    return any(row[1] == column for row in cur.fetchall())


def main() -> None:
    if not DB_PATH.exists():
        print(f"ERR Database not found at {DB_PATH}.")
        return
    conn = sqlite3.connect(DB_PATH)
    cur = conn.cursor()
    changes = []
    if not column_exists(cur, "cards", "mana_cost"):
        cur.execute("ALTER TABLE cards ADD COLUMN mana_cost TEXT")
        changes.append("cards.mana_cost added")
    conn.commit()
    conn.close()
    if changes:
        print("OK Migration applied:")
        for c in changes:
            print(f"   - {c}")
        print("\nNext: python scripts/sync.py --only cards --force")
    else:
        print("OK Schema already up to date.")


if __name__ == "__main__":
    main()
