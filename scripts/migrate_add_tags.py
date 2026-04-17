"""Idempotent migration: add card_tags and card_abilities tables.

Safe to run multiple times. Checks each table before creating.
No data is dropped.
"""
import sqlite3
from pathlib import Path

DB_PATH = Path(__file__).parent.parent / "data" / "mtg.db"


def table_exists(cur: sqlite3.Cursor, table: str) -> bool:
    cur.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (table,))
    return cur.fetchone() is not None


def main() -> None:
    if not DB_PATH.exists():
        print(f"ERR Database not found at {DB_PATH}. Run init_db.py first.")
        return

    conn = sqlite3.connect(DB_PATH)
    cur = conn.cursor()
    changes = []

    if not table_exists(cur, "card_tags"):
        cur.execute(
            """
            CREATE TABLE card_tags (
                card_name TEXT NOT NULL,
                tag TEXT NOT NULL,
                category TEXT NOT NULL,
                source TEXT NOT NULL,
                PRIMARY KEY (card_name, tag),
                FOREIGN KEY (card_name) REFERENCES cards(name)
            )
            """
        )
        changes.append("card_tags table created")

    cur.execute("CREATE INDEX IF NOT EXISTS idx_card_tags_tag ON card_tags(tag)")
    cur.execute("CREATE INDEX IF NOT EXISTS idx_card_tags_category ON card_tags(category)")

    if not table_exists(cur, "card_abilities"):
        cur.execute(
            """
            CREATE TABLE card_abilities (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                card_name TEXT NOT NULL,
                ability_index INTEGER NOT NULL,
                ability_type TEXT NOT NULL,
                cost TEXT,
                effect TEXT,
                has_target INTEGER NOT NULL DEFAULT 0,
                produces_mana INTEGER NOT NULL DEFAULT 0,
                is_mana_ability INTEGER NOT NULL DEFAULT 0,
                raw_text TEXT NOT NULL,
                FOREIGN KEY (card_name) REFERENCES cards(name)
            )
            """
        )
        changes.append("card_abilities table created")

    cur.execute("CREATE INDEX IF NOT EXISTS idx_card_abilities_card ON card_abilities(card_name)")
    cur.execute("CREATE INDEX IF NOT EXISTS idx_card_abilities_type ON card_abilities(ability_type)")
    cur.execute("CREATE INDEX IF NOT EXISTS idx_card_abilities_mana ON card_abilities(is_mana_ability)")

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
