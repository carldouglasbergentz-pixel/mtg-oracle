"""One-shot orchestrator: sync cards, rules, and combos from their upstream sources.

Each individual sync script records its own `sync_state` entry and is
idempotent — so this runner is safe to schedule as a daily/weekly cron
job. Use `--force` to re-ingest every source regardless of upstream change.

After every run a `=== changelog ===` section summarizes what actually
changed in the database: added / removed / modified rows per source.

Run:
    python scripts/sync.py              # fast: skip unchanged sources
    python scripts/sync.py --force      # re-ingest everything
    python scripts/sync.py --only cards rules   # subset
"""
import argparse
import contextlib
import io
import sqlite3
import sys
import time
from pathlib import Path
from typing import Optional

sys.path.insert(0, str(Path(__file__).parent))
import load_custom_formats
import migrate_add_corrections
import migrate_add_custom_formats
import migrate_add_decks
import migrate_add_folder_format
import migrate_add_legalities
import migrate_add_mana_cost
import migrate_add_nocase_indexes
import migrate_add_oracle_id
import migrate_add_oracle_tags
import migrate_add_scryfall_fields
import migrate_add_tags
import migrate_add_user_combos
import migrate_fix_card_tags_pk
import migrate_unique_deck_names
import sync_cards
import sync_combos
import sync_oracle_tags
import sync_rules
import tag_cards

# Every migration, in dependency order, so any database back to the very
# first schema reaches the current one: tests/test_scripts_regressions.py
# replays the initial commit's schema through this list. All are idempotent
# (ALTER / CREATE only when missing) and silent when there's nothing to do,
# so they run on every invocation.
SELF_HEAL_MIGRATIONS = (
    # sync_state and the columns every card ingest writes.
    migrate_add_oracle_id,
    migrate_add_mana_cost,
    migrate_add_scryfall_fields,
    migrate_add_legalities,
    migrate_add_custom_formats,
    # decks before the two that widen it.
    migrate_add_decks,
    migrate_add_folder_format,
    migrate_unique_deck_names,
    # card_tags must exist before its key can be fixed; the fix re-tags
    # from cards, so it also needs the card columns above.
    migrate_add_tags,
    migrate_fix_card_tags_pk,
    migrate_add_corrections,
    migrate_add_user_combos,
    migrate_add_oracle_tags,
    # Last: it indexes columns the migrations above may have just created.
    migrate_add_nocase_indexes,
)

DB_PATH = Path(__file__).parent.parent / "data" / "mtg.db"

SOURCES = {
    "cards": ("Scryfall cards + rulings", sync_cards.sync),
    "rules": ("Wizards Comprehensive Rules", sync_rules.sync),
    "combos": ("Commander Spellbook", sync_combos.sync),
    "tags": ("Local tagging (keywords, types, abilities)", tag_cards.sync),
    # Scryfall Tagger's community "what does this card do" labels. Runs
    # after cards because it resolves oracle_id -> name against them.
    "oracletags": ("Scryfall Tagger oracle tags", sync_oracle_tags.sync),
    # No upstream fetch — curated JSON in data/formats/. Runs last because
    # it resolves card names against the cards table.
    "formats": ("Community formats (points lists)", load_custom_formats.sync),
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
        "points_count": _count_or_zero(cur, "custom_format_points"),
        "oracle_tags_count": _count_or_zero(cur, "card_oracle_tags"),
    }


def _count_or_zero(cur: sqlite3.Cursor, table: str) -> int:
    """Row count, or 0 when the table doesn't exist.

    Defensive: the snapshot is taken after the self-heal migrations, so the
    table should always be there — but a diff is a report, and a report
    should not be the thing that crashes a successful sync.
    """
    try:
        return cur.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
    except sqlite3.OperationalError:
        return 0


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
        "points": {
            "net": post["points_count"] - pre["points_count"],
            "total": post["points_count"],
        },
        "oracletags": {
            "net": post["oracle_tags_count"] - pre["oracle_tags_count"],
            "total": post["oracle_tags_count"],
        },
    }


def _fmt_signed(n: int) -> str:
    return f"{n:+,}" if n else "   0"


def _print_changelog(diff: dict, failures: list[str],
                     notes: dict[str, list[str]]) -> None:
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
    for key in ("rulings", "tags", "abilities", "points", "oracletags"):
        d = diff[key]
        net_label = f"(net {_fmt_signed(d['net']).strip()})"
        print(f"  {key:10} {net_label:>27}  {d['total']:>10,}")

    touched = any(
        diff[k]["added"] or diff[k]["removed"] or (diff[k].get("modified") or 0)
        for k in ("cards", "rules", "combos")
    ) or any(diff[k]["net"]
             for k in ("rulings", "tags", "abilities", "points", "oracletags"))
    if not touched and failures:
        # An empty diff next to a failed source means "nothing landed", not
        # "upstream had nothing new" — saying the latter hides the failure.
        print(f"\n  (no changes recorded - {len(failures)} source(s) failed: "
              f"{', '.join(failures)})")
    elif not touched:
        print("\n  (no changes - all sources already up to date)")
    for key, lines in notes.items():
        for line in lines:
            print(f"  {key}: {line}")
    print()
    print("  note: `cards.total` counts cards with a Scryfall oracle_id;")
    print("        rulings/tags/abilities/points/oracletags use wipe-and-rebuild,")
    print("        so only net delta is tracked.")


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

    # SOURCES order, not command-line order: oracletags, tags and formats
    # resolve names against the cards table, so `--only tags cards` must
    # still run cards first. The set also drops a source named twice.
    wanted = set(args.only or SOURCES)
    selected = [key for key in SOURCES if key in wanted]

    # Self-heal schema before any sync runs, so a database created by an
    # older init_db.py still has every table the pipeline writes to.
    # Each migration announces itself only when it actually changed
    # something — a dozen "already up to date" lines every run is noise.
    failures = []
    if DB_PATH.exists():
        for migration in SELF_HEAL_MIGRATIONS:
            buf = io.StringIO()
            try:
                with contextlib.redirect_stdout(buf):
                    migration.main()
            except (Exception, SystemExit) as e:
                # One migration refusing (migrate_unique_deck_names exits on
                # duplicate deck names, and leaves the user to rename them)
                # must not block the card sync. The run still ends in FAIL.
                print(buf.getvalue().rstrip())
                print(f"ERR migration {migration.__name__} failed: "
                      f"{e if isinstance(e, Exception) else f'exit status {e.code}'}")
                failures.append(migration.__name__)
                continue
            if "Migration applied" in buf.getvalue():
                print(buf.getvalue().rstrip())

    pre: Optional[dict] = None
    if DB_PATH.exists():
        conn = sqlite3.connect(DB_PATH)
        pre = _snapshot_state(conn)
        conn.close()

    # A source may return a list of changelog lines for what only its ingest
    # can see (sync_cards: name collisions). The others return None.
    notes: dict[str, list[str]] = {}
    for key in selected:
        label, sync_fn = SOURCES[key]
        print(f"\n### {key}: {label} ###")
        started = time.time()
        try:
            source_notes = sync_fn(force=args.force)
            if source_notes:
                notes[key] = list(source_notes)
        except SystemExit as e:
            # The sync_*.py scripts also run standalone, where `sys.exit(1)`
            # after an ERR line is the right exit. Here it would skip every
            # later source, the changelog and the FAIL summary.
            print(f"ERR {key} failed (exit status {e.code})")
            failures.append(key)
            continue
        except Exception as e:
            print(f"ERR {key} failed: {e}")
            failures.append(key)
            continue
        print(f"   ({time.time() - started:.1f}s)")

    if pre is not None and DB_PATH.exists():
        conn = sqlite3.connect(DB_PATH)
        post = _snapshot_state(conn)
        conn.close()
        _print_changelog(_diff_state(pre, post), failures, notes)

    _print_sync_state()

    if failures:
        print(f"\nFAIL one or more sources failed: {', '.join(failures)}")
        sys.exit(1)
    print("\nDone.")


if __name__ == "__main__":
    main()
