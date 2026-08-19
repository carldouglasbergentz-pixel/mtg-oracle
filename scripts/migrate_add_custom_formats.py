"""Idempotent migration: community formats Scryfall doesn't track.

Scryfall covers 23 formats, including several people assume are "community"
— `duel` *is* Duel Commander, `tlr` is Tiny Leaders: Reborn, and
Oathbreaker / Pauper Commander / PreDH / Old School are all in there. What
it can't express is a **points list**: Canadian Highlander doesn't ban its
strongest cards, it prices them and caps a deck at 10 points total.

Two tables:
  - custom_formats        one row per format, with `derives_from` naming the
                          Scryfall format whose card pool it inherits
                          (Canadian Highlander shares Vintage's ban list) and
                          `points_budget` for the per-deck cap.
  - custom_format_points  card -> points, per format.

Legality itself is *not* duplicated here. A format either inherits a
Scryfall pool via `derives_from` or has no pool restriction; a separate
bans table waits until some format actually needs bans that aren't
derivable, per the project's YAGNI rule.

Source data lives in `data/formats/*.json` and is loaded by
`scripts/load_custom_formats.py` (run as the `formats` source of sync.py).
"""
import sqlite3
from pathlib import Path

DB_PATH = Path(__file__).parent.parent / "data" / "mtg.db"

TABLES = [
    ("custom_formats", """
        CREATE TABLE custom_formats (
            format TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            aliases TEXT,
            derives_from TEXT,
            points_budget INTEGER,
            singleton INTEGER NOT NULL DEFAULT 0,
            source_url TEXT,
            list_current_as_of TEXT,
            updated_at TEXT NOT NULL
        )
    """),
    ("custom_format_points", """
        CREATE TABLE custom_format_points (
            format TEXT NOT NULL,
            card_name TEXT NOT NULL,
            points INTEGER NOT NULL CHECK(points > 0),
            PRIMARY KEY (format, card_name),
            FOREIGN KEY (format) REFERENCES custom_formats(format) ON DELETE CASCADE,
            FOREIGN KEY (card_name) REFERENCES cards(name)
        )
    """),
]


def _table_exists(cur, name: str) -> bool:
    cur.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (name,))
    return cur.fetchone() is not None


def main() -> None:
    if not DB_PATH.exists():
        print(f"ERR Database not found at {DB_PATH}.")
        return
    conn = sqlite3.connect(DB_PATH)
    cur = conn.cursor()
    changes = []
    for name, ddl in TABLES:
        if not _table_exists(cur, name):
            cur.execute(ddl)
            changes.append(name)
    cur.execute(
        "CREATE INDEX IF NOT EXISTS idx_custom_points_card "
        "ON custom_format_points(card_name)"
    )
    conn.commit()
    conn.close()

    if changes:
        print("OK Migration applied:")
        for c in changes:
            print(f"   - {c} table")
        print("\nNext: python scripts/sync.py --only formats")
    else:
        print("OK Schema already up to date.")


if __name__ == "__main__":
    main()
