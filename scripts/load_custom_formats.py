"""Load community-format definitions from data/formats/*.json into the DB.

This is the one sync source with no upstream fetch: the files are curated by
hand. That's deliberate. The Canadian Highlander points list is 43 cards on
an HTML page with no JSON, CSV or API, and it changes roughly quarterly — a
scraper would be a parser waiting to break for a file you could retype in
five minutes. The JSON is the source of truth, versioned in git, with the
source URL and a `verified_at` date so staleness is visible.

Every card name is resolved through `resolve_card_name` and a name that
doesn't resolve is a hard failure, not a warning: a typo in a points list
silently under-counting a deck's points is exactly the kind of quiet wrong
answer this project exists to avoid.

Run:
    python scripts/sync.py --only formats
    python scripts/load_custom_formats.py
"""
import argparse
import datetime as dt
import json
import sqlite3
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))
from mtg_oracle.queries import LEGALITY_FORMATS, resolve_card_name

DB_PATH = Path(__file__).parent.parent / "data" / "mtg.db"
FORMATS_DIR = Path(__file__).parent.parent / "data" / "formats"

REQUIRED_KEYS = {"format", "name", "points"}


class FormatDefinitionError(ValueError):
    """A format JSON file is malformed or names a card we can't resolve."""


def _now() -> str:
    return (dt.datetime.now(dt.timezone.utc)
            .isoformat(timespec="seconds").replace("+00:00", "Z"))


def load_definition(path: Path) -> dict:
    """Parse and validate one format file. Raises FormatDefinitionError."""
    try:
        with open(path, encoding="utf-8") as f:
            spec = json.load(f)
    except json.JSONDecodeError as e:
        raise FormatDefinitionError(f"{path.name}: invalid JSON: {e}")

    missing = REQUIRED_KEYS - set(spec)
    if missing:
        raise FormatDefinitionError(
            f"{path.name}: missing required key(s): {', '.join(sorted(missing))}"
        )
    if not isinstance(spec["points"], dict):
        raise FormatDefinitionError(f"{path.name}: 'points' must be an object")

    # An unvalidated `derives_from` becomes a legality key with no rows,
    # which rejects every card in the format with a confident-sounding
    # "not in the format's card pool". Catch the typo here instead.
    derives = spec.get("derives_from")
    if derives is not None and derives not in LEGALITY_FORMATS:
        raise FormatDefinitionError(
            f"{path.name}: derives_from={derives!r} is not a Scryfall legality "
            f"format. Valid: {', '.join(sorted(LEGALITY_FORMATS))}"
        )

    resolved: dict[str, int] = {}
    unresolved: list[str] = []
    for raw_name, value in spec["points"].items():
        if not isinstance(value, int) or value < 1:
            raise FormatDefinitionError(
                f"{path.name}: {raw_name!r} has non-positive-integer points {value!r}"
            )
        canonical = resolve_card_name(raw_name)
        if not canonical:
            unresolved.append(raw_name)
            continue
        resolved[canonical] = value
    if unresolved:
        raise FormatDefinitionError(
            f"{path.name}: {len(unresolved)} card name(s) don't resolve against "
            f"the cards table: {', '.join(repr(n) for n in unresolved)}. "
            f"Fix the spelling or run `sync.py --only cards` first."
        )
    spec["_resolved_points"] = resolved
    return spec


def _upsert(cur: sqlite3.Cursor, spec: dict) -> int:
    fmt = spec["format"]
    cur.execute(
        """
        INSERT INTO custom_formats
            (format, name, aliases, derives_from, points_budget, singleton,
             source_url, list_current_as_of, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(format) DO UPDATE SET
            name               = excluded.name,
            aliases            = excluded.aliases,
            derives_from       = excluded.derives_from,
            points_budget      = excluded.points_budget,
            singleton          = excluded.singleton,
            source_url         = excluded.source_url,
            list_current_as_of = excluded.list_current_as_of,
            updated_at         = excluded.updated_at
        """,
        (
            fmt,
            spec["name"],
            json.dumps(spec.get("aliases") or [], ensure_ascii=False),
            spec.get("derives_from"),
            spec.get("points_budget"),
            int(bool(spec.get("singleton"))),
            spec.get("source_url"),
            spec.get("list_current_as_of"),
            _now(),
        ),
    )
    # Points are a full snapshot per format — a card dropped from the list
    # must lose its row, or a deck keeps paying for it forever.
    cur.execute("DELETE FROM custom_format_points WHERE format = ?", (fmt,))
    cur.executemany(
        "INSERT INTO custom_format_points (format, card_name, points) VALUES (?, ?, ?)",
        [(fmt, name, pts) for name, pts in spec["_resolved_points"].items()],
    )
    return len(spec["_resolved_points"])


def sync(force: bool = False) -> None:
    """Load every data/formats/*.json. `force` is accepted for interface
    parity with the other sync sources; the load is cheap and always runs."""
    if not DB_PATH.exists():
        print(f"ERR Database not found at {DB_PATH}. Run init_db.py first.")
        sys.exit(1)
    if not FORMATS_DIR.exists():
        print(f"-- formats: no {FORMATS_DIR.name}/ directory, nothing to load")
        return

    paths = sorted(FORMATS_DIR.glob("*.json"))
    if not paths:
        print(f"-- formats: no .json files in {FORMATS_DIR.name}/, nothing to load")
        return

    specs = [load_definition(p) for p in paths]  # validate all before writing

    conn = sqlite3.connect(DB_PATH)
    cur = conn.cursor()
    total = 0
    for spec, path in zip(specs, paths):
        n = _upsert(cur, spec)
        total += n
        budget = spec.get("points_budget")
        derives = spec.get("derives_from") or "no inherited pool"
        print(f"OK {spec['name']}: {n} pointed card(s), "
              f"budget {budget}, pool from {derives} ({path.name})")
        stale = spec.get("list_current_as_of")
        if stale:
            print(f"   list current as of {stale} — re-check {spec.get('source_url')}")

    cur.execute(
        """
        INSERT INTO sync_state (source, updated_at, last_sync, row_count)
        VALUES (?, ?, ?, ?)
        ON CONFLICT(source) DO UPDATE SET
            updated_at = excluded.updated_at,
            last_sync  = excluded.last_sync,
            row_count  = excluded.row_count
        """,
        ("custom_formats", _now(), _now(), total),
    )
    conn.commit()
    conn.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--force", action="store_true",
                        help="(no-op; retained for interface parity with sync_*.py)")
    args = parser.parse_args()
    try:
        sync(force=args.force)
    except FormatDefinitionError as e:
        print(f"ERR {e}")
        raise SystemExit(1)
