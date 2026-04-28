"""One-shot orchestrator: sync cards, rules, and combos from their upstream sources.

Each individual sync script records its own `sync_state` entry and is
idempotent — so this runner is safe to schedule as a daily/weekly cron
job. Use `--force` to re-ingest all four regardless of upstream change.

After every run a `=== changelog ===` section summarizes what actually
changed in the database: added / removed / modified rows per source.

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
from typing import Optional

sys.path.insert(0, str(Path(__file__).parent))
import migrate_add_scryfall_fields
import migrate_add_user_combos
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


def _snapshot_state(conn: sqlite3.Connection) -> dict:
    """Capture keyed state needed to compute a meaningful diff post-sync.

    For tables with stable PKs (cards.oracle_id, rules.rule_number,
    combos.id) we snapshot the set of PKs plus a hash map of body text
    so we can report added / removed / modified separately.

    For wipe-and-rebuild tables without a stable business key (rulings,
    card_tags, card_abilities) we fall back to simple row counts; the
    diff can only report net change.
    """
    cur = conn.cursor()
    def hashmap(rows):
        return {k: hash(v or "") for k, v in rows}
    return {
        "card_ids": {oid for (oid,) in cur.execute(
            "SELECT oracle_id FROM cards WHERE oracle_id IS NOT NULL"
        )},
        "card_text": hashmap(cur.execute(
            "SELECT oracle_id, oracle_text FROM cards WHERE oracle_id IS NOT NULL"
        )),
        "rulings_count": cur.execute("SELECT COUNT(*) FROM rulings").fetchone()[0],
        "rule_numbers": {rn for (rn,) in cur.execute("SELECT rule_number FROM rules")},
        "rule_text": hashmap(cur.execute("SELECT rule_number, text FROM rules")),
        "combo_ids": {cid for (cid,) in cur.execute("SELECT id FROM combos")},
        "card_tags_count": cur.execute("SELECT COUNT(*) FROM card_tags").fetchone()[0],
        "card_abilities_count": cur.execute("SELECT COUNT(*) FROM card_abilities").fetchone()[0],
    }


def _diff_state(pre: dict, post: dict) -> dict:
    """Produce per-source added / removed / modified counts plus net totals."""
    card_common = pre["card_ids"] & post["card_ids"]
    card_text_changed = sum(
        1 for oid in card_common if pre["card_text"].get(oid) != post["card_text"].get(oid)
    )
    rule_common = pre["rule_numbers"] & post["rule_numbers"]
    rule_text_changed = sum(
        1 for rn in rule_common if pre["rule_text"].get(rn) != post["rule_text"].get(rn)
    )
    return {
        "cards": {
            "added": len(post["card_ids"] - pre["card_ids"]),
            "removed": len(pre["card_ids"] - post["card_ids"]),
            "modified": card_text_changed,
            "total": len(post["card_ids"]),
        },
        "rulings": {
            "net": post["rulings_count"] - pre["rulings_count"],
            "total": post["rulings_count"],
        },
        "rules": {
            "added": len(post["rule_numbers"] - pre["rule_numbers"]),
            "removed": len(pre["rule_numbers"] - post["rule_numbers"]),
            "modified": rule_text_changed,
            "total": len(post["rule_numbers"]),
        },
        "combos": {
            "added": len(post["combo_ids"] - pre["combo_ids"]),
            "removed": len(pre["combo_ids"] - post["combo_ids"]),
            "modified": None,  # Spellbook wipes and rebuilds; same-id-different-body not tracked
            "total": len(post["combo_ids"]),
        },
        "tags": {
            "net": post["card_tags_count"] - pre["card_tags_count"],
            "total": post["card_tags_count"],
        },
        "abilities": {
            "net": post["card_abilities_count"] - pre["card_abilities_count"],
            "total": post["card_abilities_count"],
        },
    }


def _fmt_signed(n: int) -> str:
    return f"{n:+,}" if n else "   0"


def _print_changelog(diff: dict) -> None:
    """Render the diff in a fixed-width terminal-style table."""
    def mod_str(v):
        return _fmt_signed(v) if v is not None else "   -"

    print()
    print("=== changelog ===")
    print(f"  {'table':10} {'added':>8} {'removed':>8} {'modified':>9} {'total':>10}")
    for key in ("cards", "rules", "combos"):
        d = diff[key]
        print(
            f"  {key:10} "
            f"{_fmt_signed(d['added']):>8} "
            f"{_fmt_signed(-d['removed']):>8} "
            f"{mod_str(d['modified']):>9} "
            f"{d['total']:>10,}"
        )
    for key in ("rulings", "tags", "abilities"):
        d = diff[key]
        net_label = f"(net {_fmt_signed(d['net']).strip()})"
        print(f"  {key:10} {net_label:>27}  {d['total']:>10,}")

    touched = any(
        diff[k]["added"] or diff[k]["removed"] or (diff[k].get("modified") or 0)
        for k in ("cards", "rules", "combos")
    ) or any(diff[k]["net"] for k in ("rulings", "tags", "abilities"))
    if not touched:
        print("\n  (no changes - all sources already up to date)")
    print()
    print("  note: `cards.total` counts cards with a Scryfall oracle_id;")
    print("        rulings/tags/abilities use wipe-and-rebuild so only net delta is tracked.")


def _print_sync_state() -> None:
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

    # Self-heal schema before any sync runs. Both migrations are
    # idempotent (ALTER / CREATE only when missing) and silent when
    # there's nothing to do, so they're safe on every run.
    if DB_PATH.exists():
        migrate_add_scryfall_fields.main()
        migrate_add_user_combos.main()

    pre: Optional[dict] = None
    if DB_PATH.exists():
        conn = sqlite3.connect(DB_PATH)
        pre = _snapshot_state(conn)
        conn.close()

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

    if pre is not None and DB_PATH.exists():
        conn = sqlite3.connect(DB_PATH)
        post = _snapshot_state(conn)
        conn.close()
        _print_changelog(_diff_state(pre, post))

    _print_sync_state()

    if failures:
        print(f"\nFAIL one or more sources failed: {', '.join(failures)}")
        sys.exit(1)
    print("\nDone.")


if __name__ == "__main__":
    main()
