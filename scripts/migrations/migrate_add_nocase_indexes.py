"""Idempotent migration: case-insensitive indexes on every card-name column.

The project's convention is `COLLATE NOCASE` on name comparisons, but every
index was on the default BINARY collation — and SQLite cannot use a BINARY
index to satisfy a NOCASE comparison. So the convention silently forced full
table scans on the hottest queries in the app:

    get_deck's `LEFT JOIN cards ON c.name = dc.card_name COLLATE NOCASE`
        scanned all 35k cards once per deck row: 747 ms for a 100-card deck,
        paid on every command, because the TUI refreshes the deck pane after
        each one.
    `SELECT ... WHERE card_name = ? COLLATE NOCASE` on card_legalities
        14.7 ms per card, paid on every `add`.
    combos_in_deck's `card_name COLLATE NOCASE IN (...)`
        scanned all 368k combo_cards rows.

Measured effect of these five indexes on the real database:

    get_deck join      747 ms  ->  0.8 ms   (884x)
    name lookup       0.89 ms  ->  0.02 ms
    legality lookup   14.7 ms  ->  0.05 ms
    combos_in_deck      47 ms  ->  2.9 ms

Cost: ~23 MB on a 270 MB database, ~0.5 s to build.

Run:
    python scripts/migrations/migrate_add_nocase_indexes.py
"""
import sqlite3
import time
from pathlib import Path

DB_PATH = Path(__file__).parent.parent.parent / "data" / "mtg.db"

# (index name, table, indexed expression). One per column that any query
# compares with COLLATE NOCASE.
INDEXES = [
    ("idx_cards_name_nocase", "cards", "name COLLATE NOCASE"),
    ("idx_combo_cards_card_nocase", "combo_cards", "card_name COLLATE NOCASE"),
    ("idx_user_combo_cards_card_nocase", "user_combo_cards",
     "card_name COLLATE NOCASE"),
    ("idx_deck_cards_card_nocase", "deck_cards", "card_name COLLATE NOCASE"),
    ("idx_card_legalities_card_nocase", "card_legalities",
     "card_name COLLATE NOCASE"),
    ("idx_card_tags_card_nocase", "card_tags", "card_name COLLATE NOCASE"),
    ("idx_rulings_card_nocase", "rulings", "card_name COLLATE NOCASE"),
    ("idx_custom_points_card_nocase", "custom_format_points",
     "card_name COLLATE NOCASE"),
]


def _table_exists(cur, name: str) -> bool:
    cur.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (name,))
    return cur.fetchone() is not None


def _index_exists(cur, name: str) -> bool:
    cur.execute("SELECT 1 FROM sqlite_master WHERE type='index' AND name=?", (name,))
    return cur.fetchone() is not None


def main() -> None:
    if not DB_PATH.exists():
        print(f"ERR Database not found at {DB_PATH}.")
        return
    conn = sqlite3.connect(DB_PATH)
    cur = conn.cursor()
    built = []
    for name, table, expression in INDEXES:
        if _index_exists(cur, name) or not _table_exists(cur, table):
            continue
        started = time.perf_counter()
        cur.execute(f"CREATE INDEX {name} ON {table}({expression})")
        built.append((name, (time.perf_counter() - started) * 1000))
    conn.commit()
    conn.close()

    if built:
        print("OK Migration applied:")
        for name, ms in built:
            print(f"   - {name} ({ms:.0f} ms)")
    else:
        print("OK Schema already up to date.")


if __name__ == "__main__":
    main()
