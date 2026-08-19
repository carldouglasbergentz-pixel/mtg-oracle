"""Idempotent migration: widen card_tags' primary key to include `category`.

The original PK was (card_name, tag), which cannot hold a token that is
both a subtype and a keyword on the same card. Nine tokens collide today —
saga, adventure, dragon, elemental, goblin, hero, licid, wolf, dungeon.

The insert is `INSERT OR IGNORE`, and `tag_cards.py` builds the rows from a
Python `set`, so *which* category survived depended on set iteration order:
`search kw:saga` could match a Saga on one sync and miss it on the next.
The fix is (card_name, tag, category).

`card_tags` is 100% derived from the `cards` table and is wiped and rebuilt
on every `sync.py --only tags`, so this rebuilds the table and immediately
re-runs the tagger — no data can be lost.

Safe to run multiple times: it checks the existing PK first and exits early
when the schema is already correct.

Run:
    python scripts/migrate_fix_card_tags_pk.py
"""
import sqlite3
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import tag_cards

DB_PATH = Path(__file__).parent.parent / "data" / "mtg.db"

NEW_TABLE = """
CREATE TABLE card_tags (
    card_name TEXT NOT NULL,
    tag TEXT NOT NULL,
    category TEXT NOT NULL,
    source TEXT NOT NULL,
    PRIMARY KEY (card_name, tag, category),
    FOREIGN KEY (card_name) REFERENCES cards(name)
)
"""


def _pk_columns(cur: sqlite3.Cursor) -> list[str]:
    """Primary-key column names of card_tags, in key order."""
    cols = [r for r in cur.execute("PRAGMA table_info(card_tags)") if r[5]]
    return [c[1] for c in sorted(cols, key=lambda c: c[5])]


def main() -> None:
    if not DB_PATH.exists():
        print(f"ERR Database not found at {DB_PATH}.")
        return

    conn = sqlite3.connect(DB_PATH)
    cur = conn.cursor()
    cur.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='card_tags'")
    if not cur.fetchone():
        print("OK card_tags does not exist yet — init_db.py will create it correctly.")
        conn.close()
        return

    pk = _pk_columns(cur)
    if pk == ["card_name", "tag", "category"]:
        print("OK Schema already up to date.")
        conn.close()
        return

    before = cur.execute("SELECT COUNT(*) FROM card_tags").fetchone()[0]
    print(f"-> Rebuilding card_tags (PK {tuple(pk)} -> "
          f"('card_name', 'tag', 'category')); {before:,} rows will be regenerated")
    cur.execute("DROP TABLE card_tags")
    cur.execute(NEW_TABLE)
    cur.execute("CREATE INDEX IF NOT EXISTS idx_card_tags_tag ON card_tags(tag)")
    cur.execute("CREATE INDEX IF NOT EXISTS idx_card_tags_category ON card_tags(category)")
    conn.commit()
    conn.close()

    # Repopulate straight away — the table is derived data, and leaving it
    # empty would silently break every `kw:` / tag query until the next sync.
    tag_cards.sync()

    conn = sqlite3.connect(DB_PATH)
    after = conn.execute("SELECT COUNT(*) FROM card_tags").fetchone()[0]
    conn.close()
    print(f"OK Migration applied: {before:,} -> {after:,} tag rows "
          f"({after - before:+,} recovered)")


if __name__ == "__main__":
    main()
