"""Sync Scryfall Tagger oracle tags.

Tagger is Scryfall's community tagging project: 4,500-odd functional labels
(`counterspell-soft`, `mana rock`, `burn player-each`, `reanimate-creature`)
applied by hand to 35,000 cards. Scryfall publishes it as official bulk data,
rebuilt daily. `mtg_oracle.roles` uses it as the primary answer to "what does
this card do", falling back to its own text rules where a card is untagged.

The bulk file is JSON Lines: one *tag* per line, each carrying the list of
cards it applies to — the inverse of the per-card shape we want, so the whole
file is inverted in memory (~230k taggings, cheap) before writing.

Only DIRECT taggings are stored. Tags also form a parent graph, but expanding
it was measured to hurt: it made Seasoned Dungeoneer a tutor (via the
initiative dungeon's basic-land room) to buy one correct verdict elsewhere.

Run:
    python scripts/sync_oracle_tags.py            # skip if unchanged
    python scripts/sync_oracle_tags.py --force    # re-ingest regardless
"""
import argparse
import datetime as dt
import gzip
import json
import shutil
import sqlite3
import sys
import urllib.request
from pathlib import Path
from typing import Optional

DB_PATH = Path(__file__).parent.parent / "data" / "mtg.db"
BULK_INDEX = "https://api.scryfall.com/bulk-data"
BULK_TYPE = "oracle_tags"
CACHE_PATH = Path(__file__).parent.parent / "data" / "raw" / "oracle_tags.jsonl.gz"
USER_AGENT = "mtg-oracle/0.1"
SOURCE = "scryfall_oracle_tags"


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


def bulk_meta() -> dict:
    """The oracle_tags descriptor: `updated_at` marker plus the download URI.

    Read out of the bulk-data index rather than requested by name — the
    by-type path wants the underscored `oracle_tags`, and `/oracle-tags`
    answers 400, which is a footgun not worth leaving in place.
    """
    req = urllib.request.Request(
        BULK_INDEX, headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as r:
        index = json.loads(r.read().decode("utf-8"))
    for entry in index.get("data") or ():
        if entry.get("type") == BULK_TYPE:
            return entry
    raise LookupError(f"no {BULK_TYPE!r} entry in the Scryfall bulk-data index")


def fetch(uri: str) -> Path:
    """Stream the gzipped JSON Lines export to the raw cache."""
    CACHE_PATH.parent.mkdir(parents=True, exist_ok=True)
    print(f"-> Downloading {uri}")
    req = urllib.request.Request(uri, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=300) as r, open(CACHE_PATH, "wb") as f:
        shutil.copyfileobj(r, f)
    print(f"OK Cached at {CACHE_PATH} ({CACHE_PATH.stat().st_size / 1_000_000:.1f} MB)")
    return CACHE_PATH


def invert(path: Path) -> dict[str, list[tuple[str, str]]]:
    """oracle_id -> [(tag label, weight), ...] from the tag-per-line export."""
    by_card: dict[str, list[tuple[str, str]]] = {}
    tags = 0
    opener = gzip.open if path.suffix == ".gz" else open
    with opener(path, "rt", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            tag = json.loads(line)
            label = tag.get("label")
            if not label:
                continue
            tags += 1
            for tagging in tag.get("taggings") or ():
                oid = tagging.get("oracle_id")
                if oid:
                    by_card.setdefault(oid, []).append(
                        (label, tagging.get("weight") or "")
                    )
    print(f"OK Read {tags:,} tags covering {len(by_card):,} oracle ids")
    return by_card


def sync(force: bool = False) -> None:
    if not DB_PATH.exists():
        print(f"ERR Database not found at {DB_PATH}. Run init_db.py first.")
        sys.exit(1)

    try:
        meta = bulk_meta()
    except Exception as e:
        print(f"ERR Could not read {BULK_INDEX}: {e}")
        sys.exit(1)

    # Empty when the descriptor has no `updated_at`: then we cannot tell
    # whether upstream moved, so the skip check below must not fire.
    upstream_marker = meta.get("updated_at") or ""
    # `jsonl_download_uri`, not `download_uri` — same as the cards export.
    # Scryfall retired the uncompressed field.
    uri = meta.get("jsonl_download_uri")
    if not uri:
        print("ERR Bulk descriptor has no jsonl_download_uri.")
        sys.exit(1)

    conn = sqlite3.connect(DB_PATH)
    cur = conn.cursor()

    local_marker = _get_sync_state(cur, SOURCE)
    if upstream_marker and local_marker == upstream_marker and not force:
        print(f"-- oracle tags: up to date ({upstream_marker}), skipping")
        conn.close()
        return

    # Parsed in full before anything is deleted, so a truncated download
    # fails while the old rows are still there.
    by_card = invert(fetch(uri))
    if not by_card:
        print("ERR Upstream export parsed to zero taggings; keeping existing rows.")
        conn.close()
        sys.exit(1)

    # oracle_id -> name, so tags land on the key the query layer asks with.
    # A tagged card the cards table has never heard of is simply dropped;
    # Tagger covers cards from every game, this database is what it is.
    names = {
        oid: name
        for oid, name in cur.execute(
            "SELECT oracle_id, name FROM cards WHERE oracle_id IS NOT NULL"
        )
    }

    cur.execute("DELETE FROM card_oracle_tags")
    rows, unmatched = 0, 0
    for oid, taggings in by_card.items():
        name = names.get(oid)
        if not name:
            unmatched += 1
            continue
        cur.executemany(
            "INSERT OR IGNORE INTO card_oracle_tags (card_name, tag, weight) "
            "VALUES (?, ?, ?)",
            [(name, label, weight) for label, weight in taggings],
        )
        rows += len(taggings)

    # No marker: keep the previous one instead of a placeholder that the
    # next marker-less run would match and skip on (see sync_combos.py).
    _set_sync_state(cur, SOURCE, upstream_marker or local_marker or "", rows)
    conn.commit()
    matched = len(by_card) - unmatched
    conn.close()

    print(f"OK Ingested {rows:,} taggings for {matched:,} cards "
          f"({unmatched:,} tagged oracle ids not in this database)")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--force", action="store_true",
                        help="Re-ingest even if upstream is unchanged")
    args = parser.parse_args()
    sync(force=args.force)
