"""Sync combos from Commander Spellbook.

Uses the public bulk JSON endpoint, which is updated weekly. The HTTP
`ETag` header is recorded in `sync_state` so subsequent runs skip work
when the upstream file is unchanged.

Run:
    python scripts/sync_combos.py            # skip if unchanged
    python scripts/sync_combos.py --force    # re-ingest regardless
"""
import argparse
import datetime as dt
import json
import shutil
import sqlite3
import sys
import urllib.request
from pathlib import Path
from typing import Optional

DB_PATH = Path(__file__).parent.parent / "data" / "mtg.db"
SPELLBOOK_URL = "https://json.commanderspellbook.com/variants.json"
CACHE_PATH = Path(__file__).parent.parent / "data" / "raw" / "spellbook_variants.json"
USER_AGENT = "mtg-oracle/0.1"


def _get_sync_state(cur: sqlite3.Cursor, source: str) -> Optional[str]:
    cur.execute("SELECT updated_at FROM sync_state WHERE source = ?", (source,))
    row = cur.fetchone()
    return row[0] if row else None


def _set_sync_state(cur: sqlite3.Cursor, source: str, updated_at: str, row_count: int) -> None:
    now = dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
    cur.execute(
        """
        INSERT INTO sync_state (source, updated_at, last_sync, row_count)
        VALUES (?, ?, ?, ?)
        ON CONFLICT(source) DO UPDATE SET
            updated_at = excluded.updated_at,
            last_sync = excluded.last_sync,
            row_count = excluded.row_count
        """,
        (source, updated_at, now, row_count),
    )


def _head_etag() -> tuple[str, str]:
    """Return (etag, last_modified) from a HEAD request."""
    req = urllib.request.Request(SPELLBOOK_URL, method="HEAD", headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.headers.get("ETag", ""), r.headers.get("Last-Modified", "")


def fetch() -> dict:
    """Download variants.json to the raw cache, then parse it from disk.

    The payload is ~600 MB. Streaming it to disk first and letting
    `json.load` read from the file keeps one fewer full copy in memory
    than `json.loads(response.read())` did.
    """
    CACHE_PATH.parent.mkdir(parents=True, exist_ok=True)
    print(f"-> Downloading {SPELLBOOK_URL}")
    req = urllib.request.Request(SPELLBOOK_URL, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=300) as r, open(CACHE_PATH, "wb") as f:
        shutil.copyfileobj(r, f)
    print(f"OK Cached at {CACHE_PATH} ({CACHE_PATH.stat().st_size / 1_000_000:.1f} MB)")
    with open(CACHE_PATH, encoding="utf-8") as f:
        return json.load(f)


def normalize(payload):
    """Spellbook's bulk file may be a list or a {'variants': [...]} envelope.
    Yields normalized combo dicts. Defensive against schema drift."""
    if isinstance(payload, dict) and "variants" in payload:
        variants = payload["variants"]
    elif isinstance(payload, list):
        variants = payload
    else:
        raise ValueError(f"Unexpected payload shape: {type(payload).__name__}")

    for v in variants:
        cards = []
        for entry in v.get("uses", v.get("cards", [])) or []:
            # 'uses' (newer schema): {"card": {"name": ...}, "quantity": N}
            # 'cards' (older):       {"name": ...}
            card_obj = entry.get("card") if isinstance(entry, dict) else None
            name = (card_obj or {}).get("name") or entry.get("name") if isinstance(entry, dict) else None
            qty = entry.get("quantity", 1) if isinstance(entry, dict) else 1
            if name:
                cards.append((name, qty))

        results = []
        for f in v.get("produces", v.get("results", [])) or []:
            feature = f.get("feature") if isinstance(f, dict) else None
            name = (feature or {}).get("name") or (f.get("name") if isinstance(f, dict) else None)
            if name:
                results.append(name)

        prereq_text = v.get("other_prerequisites") or v.get("prerequisites", "") or ""
        steps_text = v.get("description", "") or ""

        yield {
            "id": str(v.get("id", "")),
            "name": v.get("name", "") or "",
            "color_identity": v.get("identity") or v.get("color_identity") or "",
            "description": v.get("notes", "") or "",
            "cards": cards,
            "results": results,
            "prerequisites": _split_lines(prereq_text),
            "steps": _split_lines(steps_text),
        }


def _split_lines(text):
    if not text:
        return []
    return [line.strip() for line in str(text).split("\n") if line.strip()]


def sync(force: bool = False) -> None:
    if not DB_PATH.exists():
        print(f"ERR Database not found at {DB_PATH}. Run init_db.py first.")
        sys.exit(1)

    try:
        etag, last_mod = _head_etag()
    except Exception as e:
        print(f"WARN HEAD request failed ({e}); proceeding with full download.")
        etag, last_mod = "", ""

    # ETag strings include surrounding quotes; keep as-is — we just compare.
    upstream_marker = etag or last_mod or "unknown"

    conn = sqlite3.connect(DB_PATH)
    cur = conn.cursor()

    local_marker = _get_sync_state(cur, "spellbook_variants")
    if local_marker == upstream_marker and not force:
        print(f"-- combos: up to date ({upstream_marker}), skipping")
        conn.close()
        return

    payload = fetch()

    for tbl in ("combo_cards", "combo_results", "combo_prerequisites", "combo_steps", "combos"):
        cur.execute(f"DELETE FROM {tbl}")

    count = 0
    for combo in normalize(payload):
        if not combo["id"]:
            continue

        cur.execute(
            "INSERT OR REPLACE INTO combos (id, name, color_identity, description) VALUES (?, ?, ?, ?)",
            (combo["id"], combo["name"], combo["color_identity"], combo["description"]),
        )

        for card_name, qty in combo["cards"]:
            cur.execute(
                "INSERT OR IGNORE INTO combo_cards (combo_id, card_name, quantity) VALUES (?, ?, ?)",
                (combo["id"], card_name, qty),
            )

        for result in combo["results"]:
            cur.execute(
                "INSERT INTO combo_results (combo_id, text) VALUES (?, ?)",
                (combo["id"], result),
            )

        for prereq in combo["prerequisites"]:
            cur.execute(
                "INSERT INTO combo_prerequisites (combo_id, text) VALUES (?, ?)",
                (combo["id"], prereq),
            )

        for i, step in enumerate(combo["steps"]):
            cur.execute(
                "INSERT INTO combo_steps (combo_id, step_order, text) VALUES (?, ?, ?)",
                (combo["id"], i, step),
            )

        count += 1
        if count % 1000 == 0:
            print(f"   ...{count:,} combos processed")

    _set_sync_state(cur, "spellbook_variants", upstream_marker, count)
    conn.commit()
    conn.close()

    print(f"OK Ingested {count:,} combos")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--force", action="store_true", help="Re-ingest even if upstream is unchanged")
    args = parser.parse_args()
    sync(force=args.force)
