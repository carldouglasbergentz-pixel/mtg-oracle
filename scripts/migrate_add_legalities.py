"""Idempotent migration: per-format legality + printing metadata.

Adds:
  - card_legalities (card_name, format, status)  — one row per format the
    card is NOT plainly illegal in. Scryfall reports all 23 formats for
    every card, but ~55% of those rows are `not_legal`; storing only
    legal / restricted / banned keeps the table at ~367k rows instead of
    ~812k, and "no row" reads as "not legal" everywhere.
  - cards.games        TEXT     — CSV of paper / mtgo / arena / astral / sega.
                                  This is what separates real cards from
                                  Arena-only Alchemy rebalances.
  - cards.reserved     INTEGER  — Reserved List flag (571 cards).
  - cards.edhrec_rank  INTEGER  — EDHREC popularity rank, 1 = most played.
                                  NULL for cards EDHREC doesn't rank.

All three columns and the table come straight out of the `oracle_cards`
bulk export we already download — no new upstream source.

After this runs:
    python scripts/sync.py --only cards --force
"""
import sqlite3
from pathlib import Path

DB_PATH = Path(__file__).parent.parent / "data" / "mtg.db"

TABLE = """
CREATE TABLE card_legalities (
    card_name TEXT NOT NULL,
    format TEXT NOT NULL,
    status TEXT NOT NULL,
    PRIMARY KEY (card_name, format),
    FOREIGN KEY (card_name) REFERENCES cards(name)
)
"""


def _table_exists(cur, name: str) -> bool:
    cur.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (name,))
    return cur.fetchone() is not None


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

    if not _table_exists(cur, "card_legalities"):
        cur.execute(TABLE)
        changes.append("card_legalities table")
    # Format-first index: every query is "which cards are legal in X".
    cur.execute(
        "CREATE INDEX IF NOT EXISTS idx_card_legalities_format "
        "ON card_legalities(format, status)"
    )

    for col, ddl in [
        ("games",       "ALTER TABLE cards ADD COLUMN games TEXT"),
        ("reserved",    "ALTER TABLE cards ADD COLUMN reserved INTEGER"),
        ("edhrec_rank", "ALTER TABLE cards ADD COLUMN edhrec_rank INTEGER"),
    ]:
        if not _col_exists(cur, "cards", col):
            cur.execute(ddl)
            changes.append(f"cards.{col}")
    cur.execute("CREATE INDEX IF NOT EXISTS idx_cards_edhrec ON cards(edhrec_rank)")

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
