"""Idempotent migration: deck names unique per folder, ignoring case.

`decks` declared `UNIQUE (folder_id, name)`, which guarded less than it
looked like it did:

    folder_id NULL   SQLite treats NULLs as distinct, so any number of
                     unsorted decks could share one name.
    name BINARY      `Dup` and `dup` were different names to the constraint
                     but the same deck to every lookup (`COLLATE NOCASE`).

Either way the result was a deck no command could reach: every lookup by
name came back "ambiguous", including the delete that would have fixed it.

The unique index on `(COALESCE(folder_id, 0), name COLLATE NOCASE)` closes
both holes. Folder ids start at 1, so 0 stands for "unsorted".

Existing duplicates are never renamed silently — the user's deck names are
theirs. If any exist the migration lists them and exits non-zero without
changing anything; rename or move one of each pair and run it again.

Run:
    python scripts/migrations/migrate_unique_deck_names.py
"""
import sqlite3
import sys
from pathlib import Path

DB_PATH = Path(__file__).parent.parent.parent / "data" / "mtg.db"

INDEX_NAME = "idx_decks_folder_name_nocase_unique"
INDEX_SQL = (
    f"CREATE UNIQUE INDEX {INDEX_NAME} "
    "ON decks(COALESCE(folder_id, 0), name COLLATE NOCASE)"
)


def _table_exists(cur, name: str) -> bool:
    cur.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (name,))
    return cur.fetchone() is not None


def _index_exists(cur, name: str) -> bool:
    cur.execute("SELECT 1 FROM sqlite_master WHERE type='index' AND name=?", (name,))
    return cur.fetchone() is not None


def find_duplicates(cur) -> list[tuple[str, str, str]]:
    """(folder, name, deck ids) for every name used twice in one folder."""
    cur.execute(
        """
        SELECT COALESCE(f.name, '(unsorted)'), MIN(d.name),
               GROUP_CONCAT(d.id, ', ')
        FROM decks d LEFT JOIN deck_folders f ON f.id = d.folder_id
        GROUP BY COALESCE(d.folder_id, 0), d.name COLLATE NOCASE
        HAVING COUNT(*) > 1
        ORDER BY 1, 2
        """
    )
    return cur.fetchall()


def main() -> None:
    if not DB_PATH.exists():
        print(f"ERR Database not found at {DB_PATH}.")
        return
    conn = sqlite3.connect(DB_PATH)
    try:
        cur = conn.cursor()
        if not _table_exists(cur, "decks") or _index_exists(cur, INDEX_NAME):
            print("OK Schema already up to date.")
            return
        duplicates = find_duplicates(cur)
        if duplicates:
            # stderr, because sync.py captures stdout of self-heal migrations
            # and only replays it when something was applied.
            print("ERR cannot make deck names unique — these names are used "
                  "more than once in the same folder:", file=sys.stderr)
            for folder, name, ids in duplicates:
                print(f"   - {folder}/{name}  (deck ids {ids})", file=sys.stderr)
            print("   Rename or move one of each, then run this migration "
                  "again.", file=sys.stderr)
            sys.exit(1)
        cur.execute(INDEX_SQL)
        conn.commit()
    finally:
        conn.close()
    print("OK Migration applied:")
    print(f"   - {INDEX_NAME}")


if __name__ == "__main__":
    main()
