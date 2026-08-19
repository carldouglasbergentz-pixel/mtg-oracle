"""Sync cards and rulings from Scryfall's bulk data API.

Fetches the `oracle_cards` and `rulings` bulk files, upserts them into the
local SQLite database, and records the upstream `updated_at` per source in
the `sync_state` table so subsequent runs can skip when nothing has changed.

Scryfall serves bulk data as gzipped JSON Lines (`jsonl_download_uri`) —
one JSON object per line, not a single JSON array. We keep the payload
gzipped on disk and stream it line by line, so a 400 MB export never has
to fit in memory.

Run:
    python scripts/sync_cards.py            # skip if upstream unchanged
    python scripts/sync_cards.py --force    # re-ingest regardless
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
from typing import Iterator

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
    """Stream the gzipped JSONL export to data/raw/ and return its path."""
    RAW_DIR.mkdir(parents=True, exist_ok=True)
    target = RAW_DIR / f"scryfall_{label}.jsonl.gz"
    url = entry.get("jsonl_download_uri")
    if not url:
        # Fail loud with the actual payload shape — Scryfall dropped the old
        # uncompressed `download_uri` field in 2026, and a bare KeyError here
        # told us nothing about what changed.
        raise RuntimeError(
            f"Scryfall bulk entry {label!r} has no 'jsonl_download_uri'. "
            f"Fields present: {sorted(entry)}. The bulk-data API shape "
            f"changed — update scripts/sync_cards.py."
        )
    size_mb = entry.get("compressed_size", 0) / 1_000_000
    print(f"-> Downloading {label} ({size_mb:.1f} MB gzipped) from {url}")
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=300) as resp, open(target, "wb") as f:
        shutil.copyfileobj(resp, f)
    print(f"OK Saved {target} ({target.stat().st_size / 1_000_000:.1f} MB)")
    return target


def _iter_jsonl(path: Path) -> Iterator[dict]:
    """Yield one object per line from a gzipped JSON Lines file.

    Streaming keeps peak memory flat — `json.load` on the uncompressed
    export used to hold ~150 MB of Python objects at once.
    """
    with gzip.open(path, "rt", encoding="utf-8") as f:
        for line in f:
            line = line.strip().rstrip(",")
            # Tolerate a stray array wrapper if Scryfall ever ships one.
            if not line or line in ("[", "]"):
                continue
            yield json.loads(line)


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


def _colors(card: dict) -> str:
    """Comma-separated sorted color letters from Scryfall's `colors` array.

    For multi-face cards Scryfall puts colors on each face; we union.
    Returns '' for colorless.
    """
    colors: set[str] = set()
    top = card.get("colors")
    if isinstance(top, list):
        colors.update(top)
    for f in (card.get("card_faces") or []):
        fc = f.get("colors")
        if isinstance(fc, list):
            colors.update(fc)
    return ",".join(sorted(colors))


def _color_identity(card: dict) -> str:
    """Comma-separated sorted color letters from Scryfall's `color_identity`.

    Color identity is always a top-level field on Scryfall (already merged
    across faces), and it includes colors from mana costs, color indicators
    and rules-text mana symbols — i.e. exactly the field commander-format
    legality is checked against.
    """
    ci = card.get("color_identity")
    if not isinstance(ci, list):
        return ""
    return ",".join(sorted(ci))


def _games(card: dict) -> str:
    """CSV of the games this card exists in: paper / mtgo / arena / ...

    This is the field that separates real cards from Arena-only Alchemy
    rebalances (the 220 `A-` prefixed rows), which otherwise sort to the
    top of every alphabetical search result.
    """
    games = card.get("games")
    if not isinstance(games, list):
        return ""
    return ",".join(sorted(str(g) for g in games))


def _legality_rows(name: str, card: dict) -> list[tuple[str, str, str]]:
    """(name, format, status) rows for every format the card is playable-ish in.

    `not_legal` is dropped: Scryfall reports all 23 formats for all 35k
    cards, and ~55% of those are `not_legal`. Absence of a row means
    "not legal", which every query already has to handle anyway.
    """
    legalities = card.get("legalities")
    if not isinstance(legalities, dict):
        return []
    return [
        (name, fmt, status)
        for fmt, status in legalities.items()
        if status and status != "not_legal"
    ]


def _mana_value(card: dict) -> int:
    """Scryfall's `cmc` is typically float (0.5 for Who/What/When/Where/Why).
    Round down to int; None -> 0."""
    v = card.get("cmc")
    try:
        return int(v or 0)
    except (TypeError, ValueError):
        return 0


def _power(card: dict):
    """Pull power from top level or first face with one."""
    v = card.get("power")
    if v is not None:
        return v
    for f in (card.get("card_faces") or []):
        if f.get("power") is not None:
            return f.get("power")
    return None


def _toughness(card: dict):
    v = card.get("toughness")
    if v is not None:
        return v
    for f in (card.get("card_faces") or []):
        if f.get("toughness") is not None:
            return f.get("toughness")
    return None


def ingest_cards(conn: sqlite3.Connection, path: Path) -> int:
    print(f"-> Parsing {path.name}")
    cur = conn.cursor()
    count = 0
    # Legalities are a full snapshot per sync — ban lists change, so a
    # stale `banned` row is worse than no row. Wipe and rebuild.
    cur.execute("DELETE FROM card_legalities")
    legality_rows: list[tuple[str, str, str]] = []
    for card in _iter_jsonl(path):
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
            INSERT INTO cards (
                name, oracle_id, oracle_text, mana_cost, mana_value,
                colors, color_identity, power, toughness, rarity,
                type_line, layout, card_faces,
                games, reserved, edhrec_rank
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(name) DO UPDATE SET
                oracle_id      = excluded.oracle_id,
                oracle_text    = excluded.oracle_text,
                mana_cost      = excluded.mana_cost,
                mana_value     = excluded.mana_value,
                colors         = excluded.colors,
                color_identity = excluded.color_identity,
                power          = excluded.power,
                toughness      = excluded.toughness,
                rarity         = excluded.rarity,
                type_line      = excluded.type_line,
                layout         = excluded.layout,
                card_faces     = excluded.card_faces,
                games          = excluded.games,
                reserved       = excluded.reserved,
                edhrec_rank    = excluded.edhrec_rank
            """,
            (
                name,
                card.get("oracle_id"),
                _face_text(card),
                _face_mana_cost(card),
                _mana_value(card),
                _colors(card),
                _color_identity(card),
                _power(card),
                _toughness(card),
                card.get("rarity"),
                _face_type_line(card),
                layout,
                faces_json,
                _games(card),
                int(bool(card.get("reserved"))),
                card.get("edhrec_rank"),
            ),
        )
        legality_rows.extend(_legality_rows(name, card))
        count += 1

    cur.executemany(
        "INSERT OR REPLACE INTO card_legalities (card_name, format, status) "
        "VALUES (?, ?, ?)",
        legality_rows,
    )
    conn.commit()
    formats = cur.execute(
        "SELECT COUNT(DISTINCT format) FROM card_legalities"
    ).fetchone()[0]
    print(f"OK Upserted {count:,} cards")
    print(f"OK Ingested {len(legality_rows):,} legality rows across {formats} formats")
    return count


def ingest_rulings(conn: sqlite3.Connection, path: Path) -> int:
    print(f"-> Parsing {path.name}")
    cur = conn.cursor()

    # Build oracle_id -> name map so we can keep card_name populated for
    # existing queries that join on rulings.card_name.
    cur.execute("SELECT oracle_id, name FROM cards WHERE oracle_id IS NOT NULL")
    name_by_oracle = dict(cur.fetchall())

    # Rulings come as a full snapshot — wipe and re-insert.
    cur.execute("DELETE FROM rulings")

    count = 0
    orphaned = 0
    for r in _iter_jsonl(path):
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
