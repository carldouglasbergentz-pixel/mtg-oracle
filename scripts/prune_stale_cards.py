"""Delete stale `cards` rows that no Scryfall export writes any more.

`sync_cards.py` upserts keyed on `cards.name` and never prunes, so any row
whose name stopped appearing upstream lingers forever with whatever columns
existed when it was first written. Two groups accumulated:

  1. 84 malformed double-name rows from an early sync bug
     ('Birds of Paradise // Birds of Paradise', 'Command Tower // Command
     Tower', ...) — `oracle_id`, `layout` and every Scryfall-derived
     column are NULL.
  2. A handful of retired Alchemy rebalances that kept an `oracle_id` from
     an older export but never got `color_identity` / `mana_value`.

They are not harmless. A NULL `color_identity` is treated as colorless,
so `search ci<=w` inside a mono-white commander deck happily returns
'Blood Crypt // Blood Crypt', and `card birds of paradise` has a phantom
twin in the result list.

Rows referenced by `deck_cards` are never touched, even if stale — a deck
the user built wins over data hygiene.

Run:
    python scripts/prune_stale_cards.py           # dry run, lists rows
    python scripts/prune_stale_cards.py --yes     # actually delete
"""
import argparse
import sqlite3
from pathlib import Path

DB_PATH = Path(__file__).parent.parent / "data" / "mtg.db"

# A row is stale when the current export has never written its
# Scryfall-derived columns. Each of these getters in sync_cards.py returns
# '' rather than NULL for the legitimately-empty case (colorless card, no
# games list), so a NULL means "this row predates the column and no export
# has touched it since".
#
# Each clause is only trustworthy once that column has been backfilled at
# all. A migration that ADDS a column sets it NULL on every row, so using
# the clause before the next ingest would mark the entire table stale — see
# `_usable_clauses`. Without that guard this script was one `--yes` away
# from deleting all 35k cards.
STALE_CLAUSES = [
    ("oracle_id", "oracle_id IS NULL"),
    ("color_identity", "color_identity IS NULL"),
    ("games", "games IS NULL"),
]

# Refuse to act if the "stale" set is implausibly large. Stale rows are
# upstream's leftovers — a few dozen. Anything near the whole table means
# the premise is wrong, not that the database is full of junk.
MAX_STALE_FRACTION = 0.05
MAX_STALE_FLOOR = 250


def _usable_clauses(cur: sqlite3.Cursor) -> tuple[str, list[str]]:
    """Build the WHERE from clauses whose column has real data behind it.

    Returns (where_sql, skipped_column_names).
    """
    usable, skipped = [], []
    for column, clause in STALE_CLAUSES:
        cur.execute(f"SELECT EXISTS (SELECT 1 FROM cards WHERE {column} IS NOT NULL)")
        if cur.fetchone()[0]:
            usable.append(clause)
        else:
            skipped.append(column)
    if not usable:
        return "0", [c for c, _ in STALE_CLAUSES]
    return "(" + " OR ".join(usable) + ")", skipped


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--yes", action="store_true",
        help="Delete the rows. Without this flag the script only reports.",
    )
    args = parser.parse_args()

    if not DB_PATH.exists():
        print(f"ERR Database not found at {DB_PATH}.")
        return

    conn = sqlite3.connect(DB_PATH)
    cur = conn.cursor()

    total_cards = cur.execute("SELECT COUNT(*) FROM cards").fetchone()[0]
    where, skipped = _usable_clauses(cur)
    if skipped:
        print(f"NOTE ignoring the {', '.join(skipped)} check — that column has no "
              f"data yet, so it would flag every row.\n"
              f"     Run `python scripts/sync.py --only cards` first to populate it.")
    if where == "0":
        print("ERR no usable staleness signal — nothing evaluated.")
        conn.close()
        return

    cur.execute(
        f"""
        SELECT name, oracle_id, layout,
               EXISTS (SELECT 1 FROM deck_cards dc
                       WHERE dc.card_name = cards.name COLLATE NOCASE) AS in_deck
        FROM cards WHERE {where}
        ORDER BY name
        """
    )
    rows = cur.fetchall()
    if not rows:
        print("OK No stale card rows found.")
        conn.close()
        return

    prunable = [r for r in rows if not r[3]]
    protected = [r for r in rows if r[3]]

    print(f"{len(rows)} stale card row(s) of {total_cards:,}:")
    for name, oracle_id, layout, in_deck in rows[:60]:
        flag = "  KEEP (used in a deck)" if in_deck else ""
        print(f"   - {name}{flag}")
    if len(rows) > 60:
        print(f"   ... and {len(rows) - 60} more")
    if protected:
        print(f"\n{len(protected)} row(s) kept because a deck references them.")

    # Safety valve. Stale rows are upstream's leftovers; a count anywhere
    # near the table size means the premise is broken, not the data.
    ceiling = max(MAX_STALE_FLOOR, int(total_cards * MAX_STALE_FRACTION))
    if len(rows) > ceiling:
        print(
            f"\nABORT {len(rows):,} of {total_cards:,} rows look stale, which is "
            f"more than the {ceiling:,}-row sanity limit.\n"
            f"      That is not a database full of junk — it means a column this "
            f"script trusts hasn't been\n"
            f"      populated yet. Run `python scripts/sync.py --only cards` and "
            f"try again. Nothing was deleted."
        )
        conn.close()
        return

    if not args.yes:
        print(
            f"\nDry run — nothing deleted. Re-run with --yes to remove "
            f"{len(prunable)} row(s)."
        )
        conn.close()
        return

    names = [r[0] for r in prunable]
    # Delete derived rows first so no orphans are left behind. rulings /
    # card_tags / card_abilities all key on card_name.
    deleted = {}
    for table in ("rulings", "card_tags", "card_abilities", "card_legalities"):
        cur.executemany(
            f"DELETE FROM {table} WHERE card_name = ?", [(n,) for n in names]
        )
        deleted[table] = cur.rowcount
    cur.executemany("DELETE FROM cards WHERE name = ?", [(n,) for n in names])
    deleted["cards"] = cur.rowcount
    conn.commit()
    conn.close()

    print(f"\nOK Deleted {len(names)} card row(s):")
    for table, n in deleted.items():
        print(f"   - {table}: {n} row(s)")


if __name__ == "__main__":
    main()
