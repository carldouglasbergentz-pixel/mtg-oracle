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
STALE_WHERE = (
    "(oracle_id IS NULL OR color_identity IS NULL OR games IS NULL)"
)


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

    cur.execute(
        f"""
        SELECT name, oracle_id, layout,
               EXISTS (SELECT 1 FROM deck_cards dc
                       WHERE dc.card_name = cards.name COLLATE NOCASE) AS in_deck
        FROM cards WHERE {STALE_WHERE}
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

    print(f"{len(rows)} stale card row(s):")
    for name, oracle_id, layout, in_deck in rows:
        flag = "  KEEP (used in a deck)" if in_deck else ""
        print(f"   - {name}{flag}")
    if protected:
        print(f"\n{len(protected)} row(s) kept because a deck references them.")

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
