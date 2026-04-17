"""Ingest cards and rulings from mtg_judge_db.json.

Expected structure (abbreviated keys to save space):
    [
      {
        "n":  "Card Name",
        "t":  "Oracle text",
        "ty": "Type line",
        "r":  [{"date": "YYYY-MM-DD", "text": "..."}, ...]
      },
      ...
    ]
"""
import json
import sqlite3
import sys
from pathlib import Path

DB_PATH = Path(__file__).parent.parent / "data" / "mtg.db"


def main(json_path: str) -> None:
    path = Path(json_path)
    if not path.exists():
        print(f"ERR File not found: {path}")
        sys.exit(1)

    with open(path, encoding="utf-8") as f:
        data = json.load(f)

    if not isinstance(data, list):
        print(f"ERR Expected a JSON array, got {type(data).__name__}")
        sys.exit(1)

    conn = sqlite3.connect(DB_PATH)
    cur = conn.cursor()

    card_count = 0
    ruling_count = 0
    skipped = 0

    for card in data:
        name = card.get("n")
        if not name:
            skipped += 1
            continue

        cur.execute(
            "INSERT OR REPLACE INTO cards (name, oracle_text, type_line) VALUES (?, ?, ?)",
            (name, card.get("t", ""), card.get("ty", "")),
        )
        card_count += 1

        # Wipe existing rulings for this card to avoid duplicates on re-ingest
        cur.execute("DELETE FROM rulings WHERE card_name = ?", (name,))

        for ruling in card.get("r", []) or []:
            cur.execute(
                "INSERT INTO rulings (card_name, date, text) VALUES (?, ?, ?)",
                (name, ruling.get("date", ""), ruling.get("text", "")),
            )
            ruling_count += 1

    conn.commit()
    conn.close()

    print(f"OK Ingested {card_count:,} cards and {ruling_count:,} rulings")
    if skipped:
        print(f"   ({skipped} entries skipped — missing name field)")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: python ingest_cards.py <path_to_mtg_judge_db.json>")
        sys.exit(1)
    main(sys.argv[1])
