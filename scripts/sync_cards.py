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


def needs_backfill(cur: sqlite3.Cursor, source_type: str) -> str | None:
    """A reason to ingest even though upstream hasn't moved, or None.

    The `updated_at` marker only says "the upstream file is the same". It
    says nothing about whether *this* build has ever written the columns and
    tables it now depends on. When a self-heal migration adds
    `card_legalities` or `cards.games`, a plain `sync.py` used to add the
    schema and then skip the ingest that fills it — leaving an empty
    legality table, which makes every deck `add` fail as "not legal" and
    every `f:` search return zero. Detect that and ingest anyway.
    """
    checks = {
        "oracle_cards": [
            ("SELECT EXISTS (SELECT 1 FROM card_legalities)",
             "card_legalities is empty"),
            ("SELECT EXISTS (SELECT 1 FROM cards WHERE games IS NOT NULL)",
             "cards.games has never been populated"),
        ],
        "rulings": [
            ("SELECT EXISTS (SELECT 1 FROM rulings)", "rulings is empty"),
        ],
    }
    for sql, reason in checks.get(source_type, []):
        try:
            if not cur.execute(sql).fetchone()[0]:
                return reason
        except sqlite3.OperationalError:
            # Table missing entirely — the migration will create it, and
            # ingesting is exactly what fills it.
            return reason
    return None


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

    # The pre-2026 uncompressed cache is superseded and never read again.
    # data/raw/ is ours to manage and nothing else cleans it, so leaving
    # ~200 MB of dead payload behind would be our litter.
    legacy = RAW_DIR / f"scryfall_{label}.json"
    if legacy.exists():
        size = legacy.stat().st_size / 1_000_000
        legacy.unlink()
        print(f"   (removed superseded {legacy.name}, {size:.0f} MB)")
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


# Layouts that are not game cards. Tokens and emblems add noise without
# rulings value; `front_card` is the display face of a memorabilia product
# (all 291 in the 2026-08 export are legal nowhere), and it shares names
# with real cards — "Blink", "Heroes for Hire" — which it used to overwrite.
# Planes, schemes and vanguards stay: they are real casual-variant cards, and
# their one real collision (No Way Out) is settled by `_entry_rank`.
SKIPPED_LAYOUTS = frozenset({
    "art_series", "emblem", "token", "double_faced_token", "front_card",
})

# Set types whose entries lose a name collision against any other printing.
# Not skipped outright: `funny` holds 174 cards legal somewhere (Unfinity's
# black-bordered ones), and `memorabilia` holds one-off real cards such as
# Shichifukujin Dragon that exist under no other name.
NOVELTY_SET_TYPES = frozenset({"funny", "memorabilia"})


def _entry_rank(card: dict) -> tuple[bool, bool]:
    """Which of two export entries sharing a name is the real card.

    Legal somewhere beats legal nowhere; a regular set beats a novelty one.
    `cards` is keyed on name, so only one entry can hold the row, and the
    upsert used to hand it to whichever came last in the file: Unquenchable
    Fury became a memorabilia Sorcery and No Way Out a Plane.
    """
    legal = any(s != "not_legal" for s in (card.get("legalities") or {}).values())
    return legal, card.get("set_type") not in NOVELTY_SET_TYPES


def ingest_cards(conn: sqlite3.Connection, path: Path) -> tuple[int, int]:
    """Upsert every game card in the export. Returns (cards, name collisions)."""
    print(f"-> Parsing {path.name}")
    cur = conn.cursor()
    count = 0
    # name -> rank of the entry currently holding that row in this ingest.
    written: dict[str, tuple[bool, bool]] = {}
    collisions = 0
    skipped_layouts = 0
    # Legalities are a full snapshot per sync — ban lists change, so a
    # stale `banned` row is worse than no row. Wipe and rebuild.
    cur.execute("DELETE FROM card_legalities")
    legality_rows: list[tuple[str, str, str]] = []

    def flush_legalities() -> None:
        """Write and clear the buffer, so it never grows with the export.

        ~10 legality rows per card over 35k cards is 367k tuples; holding
        them all would reintroduce exactly the memory growth the switch to
        streaming JSONL removed.
        """
        if not legality_rows:
            return
        cur.executemany(
            "INSERT OR REPLACE INTO card_legalities (card_name, format, status) "
            "VALUES (?, ?, ?)",
            legality_rows,
        )
        legality_rows.clear()
    for card in _iter_jsonl(path):
        layout = card.get("layout", "")
        if layout in SKIPPED_LAYOUTS:
            skipped_layouts += 1
            continue

        name = card.get("name")
        if not name:
            continue

        rank = _entry_rank(card)
        if name in written:
            collisions += 1
            if rank <= written[name]:
                continue
            # The better entry arrived second: it takes the row, and the
            # loser's legalities go too, so nothing of it survives as a union.
            flush_legalities()
            cur.execute("DELETE FROM card_legalities WHERE card_name = ?", (name,))
        else:
            count += 1
        written[name] = rank

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
        if len(legality_rows) >= 20_000:
            flush_legalities()

    flush_legalities()
    conn.commit()
    formats, legality_total = cur.execute(
        "SELECT COUNT(DISTINCT format), COUNT(*) FROM card_legalities"
    ).fetchone()
    print(f"OK Upserted {count:,} cards "
          f"({skipped_layouts:,} non-card entries skipped)")
    if collisions:
        print(f"   {collisions:,} export entries shared a name with another; "
              f"kept the printing that is legal somewhere")
    print(f"OK Ingested {legality_total:,} legality rows across {formats} formats")
    return count, collisions


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


def sync(force: bool = False) -> list[str]:
    """Ingest whichever of cards / rulings moved upstream.

    Returns changelog notes for sync.py: facts only the ingest can see, such
    as name collisions, which a before/after table diff cannot show.
    """
    notes: list[str] = []
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
            backfill = needs_backfill(cur, source_type)
            if backfill is None:
                print(f"-- {label}: up to date ({upstream_updated}), skipping")
                continue
            print(f"-- {label}: upstream unchanged but {backfill} "
                  f"— re-ingesting to backfill")

        path = download_bulk(entry, label)
        if source_type == "oracle_cards":
            count, collisions = ingest_cards(conn, path)
            if collisions:
                notes.append(f"{collisions:,} duplicate-name export entries "
                             f"resolved to the printing legal somewhere")
        else:
            count = ingest_rulings(conn, path)

        set_sync_state(cur, f"scryfall_{label}", upstream_updated, count)
        conn.commit()

    conn.close()
    print("Done.")
    return notes


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--force", action="store_true", help="Re-ingest even if upstream is unchanged")
    args = parser.parse_args()
    sync(force=args.force)
