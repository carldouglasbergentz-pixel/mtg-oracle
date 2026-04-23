"""Sync cards and rulings from Scryfall's bulk data API.

Fetches the `oracle_cards` and `rulings` bulk files, upserts them into the
local SQLite database, and records the upstream `updated_at` per source in
the `sync_state` table so subsequent runs can skip when nothing has changed.

Run:
    python scripts/sync_cards.py            # skip if upstream unchanged
    python scripts/sync_cards.py --force    # re-ingest regardless
"""
import argparse
import datetime as dt
import json
import sqlite3
import sys
import urllib.request
from pathlib import Path

DB_PATH = Path(__file__).parent.parent / "data" / "mtg.db"
RAW_DIR = Path(__file__).parent.parent / "data" / "raw"
BULK_INDEX_URL = "https://api.scryfall.com/bulk-data"
USER_AGENT = "mtg-oracle/0.1 (+https://github.com/dbergentz/mtg-oracle)"


def _http_get(url: str) -> bytes:
    req = urllib.request.Request(
        url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"}
    )
    with urllib.request.urlopen(req, timeout=300) as r:
        return r.read()


def fetch_bulk_index() -> dict[str, dict]:
    """Return bulk-data metadata keyed by type (e.g. 'oracle_cards', 'rulings')."""
    print(f"-> GET {BULK_INDEX_URL}")
    payload = json.loads(_http_get(BULK_INDEX_URL))
    return {entry["type"]: entry for entry in payload["data"]}


def get_sync_state(cur: sqlite3.Cursor, source: str) -> str | None:
    cur.execute("SELECT updated_at FROM sync_state WHERE source = ?", (source,))
    row = cur.fetchone()
    return row[0] if row else None


def set_sync_state(
    cur: sqlite3.Cursor, source: str, updated_at: str, row_count: int
) -> None:
    cur.execute(
        """
        INSERT INTO sync_state (source, updated_at, last_sync, row_count)
        VALUES (?, ?, ?, ?)
        ON CONFLICT(source) DO UPDATE SET
            updated_at = excluded.updated_at,
            last_sync = excluded.last_sync,
            row_count = excluded.row_count
        """,
        (source, updated_at, dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"), row_count),
    )


def download_bulk(entry: dict, label: str) -> Path:
    RAW_DIR.mkdir(parents=True, exist_ok=True)
    target = RAW_DIR / f"scryfall_{label}.json"
    url = entry["download_uri"]
    size_mb = entry.get("size", 0) / 1_000_000
    print(f"-> Downloading {label} ({size_mb:.1f} MB) from {url}")
    data = _http_get(url)
    target.write_bytes(data)
    print(f"OK Saved {target} ({len(data) / 1_000_000:.1f} MB)")
    return target


def _face_text(card: dict) -> str:
    """Build a combined oracle text, preserving both faces for DFC/split/flip cards."""
    top = card.get("oracle_text") or ""
    faces = card.get("card_faces") or []
    if not faces:
        return top

    parts = []
    for face in faces:
        name = face.get("name", "")
        text = face.get("oracle_text", "") or ""
        if name or text:
            parts.append(f"{name}\n{text}".strip())
    combined = "\n\n// \n\n".join(parts)
    return combined if combined else top


def _face_type_line(card: dict) -> str:
    top = card.get("type_line") or ""
    if top:
        return top
    faces = card.get("card_faces") or []
    return " // ".join(f.get("type_line", "") for f in faces if f.get("type_line"))


def _face_mana_cost(card: dict) -> str:
    """Return the card's mana cost, joining per-face costs with ' // '.

    Scryfall populates top-level mana_cost for single-faced cards, and
    leaves it empty for DFC/modal cards where each face in card_faces
    carries its own mana_cost. Lands have an empty mana_cost — we keep
    that as empty string, not NULL, so the column is consistently a
    string.
    """
    top = card.get("mana_cost") or ""
    faces = card.get("card_faces") or []
    if top or not faces:
        return top
    parts = [f.get("mana_cost", "") or "" for f in faces]
    # Preserve layout even when one face is costless (e.g. back side of a
    # transform card): `"{U} // "` is more informative than `"{U}"`.
    return " // ".join(parts)


def ingest_cards(conn: sqlite3.Connection, path: Path) -> int:
    print(f"-> Parsing {path.name}")
    with open(path, encoding="utf-8") as f:
        cards = json.load(f)

    cur = conn.cursor()
    count = 0
    for card in cards:
        # Scryfall oracle-cards includes tokens, emblems, art series, etc.
        # Skip non-playable layouts that add noise without rulings value.
        layout = card.get("layout", "")
        if layout in {"art_series", "emblem", "token", "double_faced_token"}:
            continue

        name = card.get("name")
        if not name:
            continue

        faces = card.get("card_faces")
        faces_json = json.dumps(faces, ensure_ascii=False) if faces else None

        cur.execute(
            """
            INSERT INTO cards (name, oracle_id, oracle_text, mana_cost, type_line, layout, card_faces)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(name) DO UPDATE SET
                oracle_id = excluded.oracle_id,
                oracle_text = excluded.oracle_text,
                mana_cost = excluded.mana_cost,
                type_line = excluded.type_line,
                layout = excluded.layout,
                card_faces = excluded.card_faces
            """,
            (
                name,
                card.get("oracle_id"),
                _face_text(card),
                _face_mana_cost(card),
                _face_type_line(card),
                layout,
                faces_json,
            ),
        )
        count += 1

    conn.commit()
    print(f"OK Upserted {count:,} cards")
    return count


def ingest_rulings(conn: sqlite3.Connection, path: Path) -> int:
    print(f"-> Parsing {path.name}")
    with open(path, encoding="utf-8") as f:
        rulings = json.load(f)

    cur = conn.cursor()

    # Build oracle_id -> name map so we can keep card_name populated for
    # existing queries that join on rulings.card_name.
    cur.execute("SELECT oracle_id, name FROM cards WHERE oracle_id IS NOT NULL")
    name_by_oracle = dict(cur.fetchall())

    # Rulings come as a full snapshot — wipe and re-insert.
    cur.execute("DELETE FROM rulings")

    count = 0
    orphaned = 0
    for r in rulings:
        oracle_id = r.get("oracle_id")
        name = name_by_oracle.get(oracle_id)
        if not name:
            orphaned += 1
            continue
        cur.execute(
            "INSERT INTO rulings (card_name, oracle_id, date, text) VALUES (?, ?, ?, ?)",
            (name, oracle_id, r.get("published_at", ""), r.get("comment", "")),
        )
        count += 1

    conn.commit()
    print(f"OK Inserted {count:,} rulings")
    if orphaned:
        print(f"   ({orphaned:,} rulings skipped - oracle_id not in cards table)")
    return count


def sync(force: bool = False) -> None:
    if not DB_PATH.exists():
        print(f"ERR Database not found at {DB_PATH}. Run init_db.py first.")
        sys.exit(1)

    bulks = fetch_bulk_index()

    conn = sqlite3.connect(DB_PATH)
    cur = conn.cursor()

    for source_type, label in [("oracle_cards", "oracle_cards"), ("rulings", "rulings")]:
        entry = bulks.get(source_type)
        if not entry:
            print(f"WARN Scryfall returned no bulk entry for {source_type} — skipping")
            continue

        upstream_updated = entry["updated_at"]
        local_updated = get_sync_state(cur, f"scryfall_{label}")

        if local_updated == upstream_updated and not force:
            print(f"-- {label}: up to date ({upstream_updated}), skipping")
            continue

        path = download_bulk(entry, label)
        if source_type == "oracle_cards":
            count = ingest_cards(conn, path)
        else:
            count = ingest_rulings(conn, path)

        set_sync_state(cur, f"scryfall_{label}", upstream_updated, count)
        conn.commit()

    conn.close()
    print("Done.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--force", action="store_true", help="Re-ingest even if upstream is unchanged")
    args = parser.parse_args()
    sync(force=args.force)
