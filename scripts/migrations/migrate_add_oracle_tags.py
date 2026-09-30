"""Idempotent migration: add the card_oracle_tags table.

Scryfall Tagger's community `oracle` tags — "what does this card do" — as
distinct from `card_tags`, which this project derives locally from keywords
and subtypes. Both exist because they answer different questions:
`card_tags` knows Bolt is an Instant, Tagger knows it is removal.

Keyed on card_name rather than the upstream oracle_id, because every query
in the project asks by name and joining through cards on every lookup would
buy nothing. `sync_oracle_tags.py` does the oracle_id -> name resolution once.
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

    if not table_exists(cur, "card_oracle_tags"):
        cur.execute(
            """
            CREATE TABLE card_oracle_tags (
                card_name TEXT NOT NULL,
                tag TEXT NOT NULL,
                weight TEXT,
                PRIMARY KEY (card_name, tag)
            )
            """
        )
        cur.execute(
            "CREATE INDEX IF NOT EXISTS idx_card_oracle_tags_tag "
            "ON card_oracle_tags(tag)"
        )
        cur.execute(
            "CREATE INDEX IF NOT EXISTS idx_card_oracle_tags_card_nocase "
            "ON card_oracle_tags(card_name COLLATE NOCASE)"
        )
        changes.append("card_oracle_tags")

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
