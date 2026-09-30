"""Idempotent migration: add oracle_id, layout, card_faces columns and sync_state table.

Safe to run multiple times. Checks each column/table before altering.
No data is dropped — existing rows retain their values (new columns are NULL).
"""
import sqlite3
from pathlib import Path

DB_PATH = Path(__file__).parent.parent.parent / "data" / "mtg.db"


def column_exists(cur: sqlite3.Cursor, table: str, column: str) -> bool:
    cur.execute(f"PRAGMA table_info({table})")
    return any(row[1] == column for row in cur.fetchall())


def table_exists(cur: sqlite3.Cursor, table: str) -> bool:
    cur.execute(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (table,)
    )
    return cur.fetchone() is not None


def main() -> None:
    if not DB_PATH.exists():
        print(f"ERR Database not found at {DB_PATH}. Run init_db.py first.")
        return

    conn = sqlite3.connect(DB_PATH)
    cur = conn.cursor()
    changes = []

    # cards: new columns
    for col, ddl in [
        ("oracle_id", "ALTER TABLE cards ADD COLUMN oracle_id TEXT"),
        ("layout", "ALTER TABLE cards ADD COLUMN layout TEXT"),
        ("card_faces", "ALTER TABLE cards ADD COLUMN card_faces TEXT"),
    ]:
        if not column_exists(cur, "cards", col):
            cur.execute(ddl)
            changes.append(f"cards.{col} added")

    cur.execute("CREATE INDEX IF NOT EXISTS idx_cards_oracle_id ON cards(oracle_id)")

    # rulings: new column + index
    if not column_exists(cur, "rulings", "oracle_id"):
        cur.execute("ALTER TABLE rulings ADD COLUMN oracle_id TEXT")
        changes.append("rulings.oracle_id added")

    cur.execute(
        "CREATE INDEX IF NOT EXISTS idx_rulings_oracle_id ON rulings(oracle_id)"
    )

    # sync_state table
    if not table_exists(cur, "sync_state"):
        cur.execute(
            """
            CREATE TABLE sync_state (
                source TEXT PRIMARY KEY,
                updated_at TEXT NOT NULL,
                last_sync TEXT NOT NULL,
                row_count INTEGER
            )
            """
        )
        changes.append("sync_state table created")

    conn.commit()
    conn.close()

    if changes:
        print("OK Migration applied:")
        for c in changes:
            print(f"   - {c}")
    else:
        print("OK Schema already up to date — nothing to do.")


if __name__ == "__main__":
    main()
