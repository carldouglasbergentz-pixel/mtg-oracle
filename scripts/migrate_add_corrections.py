"""Idempotent migration: add the `corrections` table.

Stores persistent feedback-loop entries so mistakes caught by the user
(or self-caught by the assistant mid-answer) don't have to be discovered
again next session. Queried before answering card-interaction questions.

Safe to run multiple times.
"""
import sqlite3
from pathlib import Path

DB_PATH = Path(__file__).parent.parent / "data" / "mtg.db"


def table_exists(cur: sqlite3.Cursor, table: str) -> bool:
    cur.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (table,))
    return cur.fetchone() is not None


def main() -> None:
    if not DB_PATH.exists():
        print(f"ERR Database not found at {DB_PATH}.")
        return

    conn = sqlite3.connect(DB_PATH)
    cur = conn.cursor()
    changes = []

    if not table_exists(cur, "corrections"):
        cur.execute(
            """
            CREATE TABLE corrections (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                topic TEXT NOT NULL,
                category TEXT NOT NULL,
                incorrect_claim TEXT NOT NULL,
                correct_claim TEXT NOT NULL,
                explanation TEXT,
                relates_to TEXT,
                source TEXT NOT NULL,
                added_at TEXT NOT NULL,
                added_by TEXT NOT NULL
            )
            """
        )
        changes.append("corrections table created")

    cur.execute("CREATE INDEX IF NOT EXISTS idx_corrections_topic ON corrections(topic)")
    cur.execute("CREATE INDEX IF NOT EXISTS idx_corrections_category ON corrections(category)")
    cur.execute("CREATE INDEX IF NOT EXISTS idx_corrections_added_at ON corrections(added_at)")

    conn.commit()
    conn.close()

    if changes:
        print("OK Migration applied:")
        for c in changes:
            print(f"   - {c}")
    else:
        print("OK corrections schema already up to date.")


if __name__ == "__main__":
    main()
