"""Fetch combos from Commander Spellbook and ingest them.

Uses the public bulk JSON endpoint, which is updated weekly and contains
every combo in the Spellbook database.
"""
import json
import sqlite3
import sys
import urllib.request
from pathlib import Path

DB_PATH = Path(__file__).parent.parent / "data" / "mtg.db"
SPELLBOOK_URL = "https://json.commanderspellbook.com/variants.json"
CACHE_PATH = Path(__file__).parent.parent / "data" / "raw" / "spellbook_variants.json"


def fetch() -> dict:
    CACHE_PATH.parent.mkdir(parents=True, exist_ok=True)
    print(f"-> Fetching {SPELLBOOK_URL}")
    req = urllib.request.Request(SPELLBOOK_URL, headers={"User-Agent": "mtg-oracle/1.0"})
    with urllib.request.urlopen(req, timeout=180) as r:
        data = r.read()
    CACHE_PATH.write_bytes(data)
    print(f"OK Cached at {CACHE_PATH} ({len(data) / 1_000_000:.1f} MB)")
    return json.loads(data)


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


def main() -> None:
    payload = fetch()

    conn = sqlite3.connect(DB_PATH)
    cur = conn.cursor()

    # Wipe combo data — combos can be removed/restructured upstream
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

    conn.commit()
    conn.close()

    print(f"OK Ingested {count:,} combos")


if __name__ == "__main__":
    main()
