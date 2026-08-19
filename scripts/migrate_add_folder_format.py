"""Idempotent migration: `deck_folders.format` — a folder's default format.

Every format rule (legality, singleton, points) hangs off `decks.format`, and
a new deck is created with none — so a deck dropped into a folder called
"Canadian Highlander" silently got no rules at all. Giving the folder a
format lets the organisation the user already has carry the meaning: decks
created there inherit it.

The column is a *default*, not an override. A deck's own `format` always
wins; the folder only fills it in at creation time.
"""
import sqlite3
from pathlib import Path

DB_PATH = Path(__file__).parent.parent / "data" / "mtg.db"


def _col_exists(cur, table: str, column: str) -> bool:
    cur.execute(f"PRAGMA table_info({table})")
    return any(r[1] == column for r in cur.fetchall())


def main() -> None:
    if not DB_PATH.exists():
        print(f"ERR Database not found at {DB_PATH}.")
        return
    conn = sqlite3.connect(DB_PATH)
    cur = conn.cursor()
    changes = []
    if not _col_exists(cur, "deck_folders", "format"):
        cur.execute("ALTER TABLE deck_folders ADD COLUMN format TEXT")
        changes.append("deck_folders.format")
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
