"""Idempotent migration: add Scryfall-derived searchable columns to `cards`.

- colors       TEXT — comma-separated sorted color letters, e.g., "B,G" (empty for colorless)
- mana_value   INTEGER — Scryfall's `cmc` cast to int (0 for lands; 0.5-style split cards floor)
- power        TEXT — creature power as string ("3", "*", "1+*", NULL for non-creatures)
- toughness    TEXT — creature toughness, same shape
- rarity       TEXT — "common" / "uncommon" / "rare" / "mythic" / "bonus" / "special"

After this runs:
    python scripts/sync.py --only cards --force

to repopulate — uses the cached Scryfall bulk file.
"""
import sqlite3
from pathlib import Path

DB_PATH = Path(__file__).parent.parent / "data" / "mtg.db"


def col_exists(cur, table, column):
    cur.execute(f"PRAGMA table_info({table})")
    return any(r[1] == column for r in cur.fetchall())


def main():
    if not DB_PATH.exists():
        print(f"ERR Database not found at {DB_PATH}.")
        return
    conn = sqlite3.connect(DB_PATH)
    cur = conn.cursor()
    changes = []
    for col, ddl in [
        ("colors",     "ALTER TABLE cards ADD COLUMN colors TEXT"),
        ("mana_value", "ALTER TABLE cards ADD COLUMN mana_value INTEGER"),
        ("power",      "ALTER TABLE cards ADD COLUMN power TEXT"),
        ("toughness",  "ALTER TABLE cards ADD COLUMN toughness TEXT"),
        ("rarity",     "ALTER TABLE cards ADD COLUMN rarity TEXT"),
    ]:
        if not col_exists(cur, "cards", col):
            cur.execute(ddl)
            changes.append(f"cards.{col}")
    cur.execute("CREATE INDEX IF NOT EXISTS idx_cards_mana_value ON cards(mana_value)")
    cur.execute("CREATE INDEX IF NOT EXISTS idx_cards_rarity ON cards(rarity)")
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
