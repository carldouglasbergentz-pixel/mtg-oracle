"""Idempotent migration: a deck's "considering" list (Moxfield's maybeboard).

    deck_considering   cards being considered for a deck: not in it, not
                       counted, exported or played, and not held to its
                       rules until they move into it. A table of its own so
                       nothing that counts or plays a deck (every
                       `is_sideboard = 0` query) can pick them up by mistake.
    deck_changes       rebuilt so `section` may also be 'considering': a
                       move to or from the list is a change like any other,
                       in the history and undoable. SQLite can't widen a
                       CHECK in place, so the table is copied row for row
                       into a new one with the wider check; nothing else of
                       it changes.

Needs deck_changes' printing columns first (migrate_add_printings_and_games).
Cascades from `decks`; `decks._rw` turns foreign keys on.

Run:
    python scripts/migrate_add_considering.py
"""
import sqlite3
from pathlib import Path

DB_PATH = Path(__file__).parent.parent / "data" / "mtg.db"

# Mirrored in scripts/init_db.py — keep the two in step.
CONSIDERING_SQL = """
    CREATE TABLE deck_considering (
        id INTEGER PRIMARY KEY,
        deck_id INTEGER NOT NULL
            REFERENCES decks(id) ON DELETE CASCADE,
        card_name TEXT NOT NULL REFERENCES cards(name),
        quantity INTEGER NOT NULL DEFAULT 1 CHECK (quantity > 0),
        added_at TEXT NOT NULL
    )
"""
CONSIDERING_INDEXES = {
    "idx_deck_considering_deck": "CREATE INDEX idx_deck_considering_deck ON deck_considering(deck_id)",
    "idx_deck_considering_card_nocase": "CREATE INDEX idx_deck_considering_card_nocase "
                                        "ON deck_considering(card_name COLLATE NOCASE)",
}
CHANGES_SQL = """
    CREATE TABLE deck_changes (
        id INTEGER PRIMARY KEY,
        revision_id INTEGER NOT NULL
            REFERENCES deck_revisions(id) ON DELETE CASCADE,
        card_name TEXT NOT NULL,
        section TEXT NOT NULL
            CHECK (section IN ('main', 'sideboard', 'commander', 'considering')),
        qty_before INTEGER NOT NULL,
        qty_after INTEGER NOT NULL,
        set_code_before TEXT,
        collector_number_before TEXT,
        set_code_after TEXT,
        collector_number_after TEXT
    )
"""
CHANGES_INDEX = "CREATE INDEX idx_deck_changes_revision ON deck_changes(revision_id)"


def _sql(cur, kind: str, name: str):
    cur.execute("SELECT sql FROM sqlite_master WHERE type = ? AND name = ?", (kind, name))
    row = cur.fetchone()
    return row[0] if row else None


def main() -> None:
    if not DB_PATH.exists():
        print(f"ERR Database not found at {DB_PATH}.")
        return
    # One explicit transaction for all of it: Python's sqlite3 would otherwise run
    # the RENAME and CREATE outside one, and a failure midway would leave the
    # table renamed. SQLite's DDL is transactional.
    conn = sqlite3.connect(DB_PATH, isolation_level=None)
    try:
        cur = conn.cursor()
        cur.execute("BEGIN")
        changes = []
        if _sql(cur, "table", "deck_considering") is None:
            cur.execute(CONSIDERING_SQL)
            changes.append("deck_considering")
        for name, sql in CONSIDERING_INDEXES.items():
            if _sql(cur, "index", name) is None:
                cur.execute(sql)
                changes.append(name)
        current = _sql(cur, "table", "deck_changes")
        if current is not None and "'considering'" not in current:
            columns = [r[1] for r in cur.execute("PRAGMA table_info(deck_changes)")]
            missing = {"set_code_before", "collector_number_before", "set_code_after", "collector_number_after"} - set(columns)
            if missing:
                raise SystemExit(f"deck_changes lacks {sorted(missing)}: run migrate_add_printings_and_games first")
            listed = ", ".join(columns)
            cur.execute("ALTER TABLE deck_changes RENAME TO deck_changes_before_considering")
            cur.execute(CHANGES_SQL)
            cur.execute(f"INSERT INTO deck_changes ({listed}) SELECT {listed} FROM deck_changes_before_considering")
            copied = cur.execute("SELECT COUNT(*) FROM deck_changes").fetchone()[0]
            kept = cur.execute("SELECT COUNT(*) FROM deck_changes_before_considering").fetchone()[0]
            if copied != kept:
                raise SystemExit(f"deck_changes copy lost rows ({copied} of {kept}); nothing changed")
            cur.execute("DROP TABLE deck_changes_before_considering")
            if _sql(cur, "index", "idx_deck_changes_revision") is None:
                cur.execute(CHANGES_INDEX)
            changes.append(f"deck_changes (section may be 'considering'; {copied} rows kept)")
        cur.execute("COMMIT")
    except BaseException:
        if conn.in_transaction:
            cur.execute("ROLLBACK")
        raise
    finally:
        conn.close()
    if changes:
        print("OK Migration applied:")
        for c in changes:
            print(f"   - {c}")
    else:
        print("OK Schema already up to date.")


if __name__ == "__main__":
    main()
