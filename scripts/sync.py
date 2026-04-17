"""One-shot orchestrator: sync cards, rules, and combos from their upstream sources.

Each individual sync script records its own `sync_state` entry and is
idempotent — so this runner is safe to schedule as a daily/weekly cron
job. Use `--force` to re-ingest all three regardless of upstream change.

Run:
    python scripts/sync.py              # fast: skip unchanged sources
    python scripts/sync.py --force      # re-ingest everything
    python scripts/sync.py --only cards rules   # subset
"""
import argparse
import sqlite3
import sys
import time
from pathlib import Path

# Local imports — each module exposes a `sync(force: bool) -> None` entrypoint.
sys.path.insert(0, str(Path(__file__).parent))
import sync_cards
import sync_combos
import sync_rules
import tag_cards

DB_PATH = Path(__file__).parent.parent / "data" / "mtg.db"

SOURCES = {
    "cards": ("Scryfall cards + rulings", sync_cards.sync),
    "rules": ("Wizards Comprehensive Rules", sync_rules.sync),
    "combos": ("Commander Spellbook", sync_combos.sync),
    "tags": ("Local tagging (keywords, types, abilities)", tag_cards.sync),
}


def _print_summary() -> None:
    if not DB_PATH.exists():
        return
    conn = sqlite3.connect(DB_PATH)
    cur = conn.cursor()
    print()
    print("=== sync_state ===")
    cur.execute("SELECT source, updated_at, last_sync, row_count FROM sync_state ORDER BY source")
    for row in cur.fetchall():
        print(f"  {row[0]:<28} updated_at={row[1]}  rows={row[3]}")
    conn.close()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--force", action="store_true", help="Re-ingest even if upstream is unchanged")
    parser.add_argument(
        "--only",
        nargs="+",
        choices=list(SOURCES.keys()),
        metavar="SOURCE",
        help=f"Run only the named sources (default: all). Choices: {', '.join(SOURCES)}.",
    )
    args = parser.parse_args()

    selected = args.only or list(SOURCES.keys())

    failures = []
    for key in selected:
        label, sync_fn = SOURCES[key]
        print(f"\n### {key}: {label} ###")
        started = time.time()
        try:
            sync_fn(force=args.force)
        except Exception as e:
            print(f"ERR {key} failed: {e}")
            failures.append(key)
            continue
        print(f"   ({time.time() - started:.1f}s)")

    _print_summary()

    if failures:
        print(f"\nFAIL one or more sources failed: {', '.join(failures)}")
        sys.exit(1)
    print("\nDone.")


if __name__ == "__main__":
    main()
