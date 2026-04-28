"""Insert a user-curated combo into `user_combos` + `user_combo_cards`.

Designed as the backend for an eventual in-app questionnaire UX
("which cards / what color identity / what does it do / what is the
sequence"). Today it accepts a Python dict directly — call from another
script or a REPL.

Card names are validated against the `cards` table via
`mtg_oracle.queries.resolve_card_name` (case + diacritics tolerant), so
typos surface immediately. The combo's color_identity is auto-derived
from the union of its cards' color identities if not given explicitly.

Schema reminder (see `scripts/migrate_add_user_combos.py`):
    user_combos (id, name, color_identity, description, added_at, added_by)
    user_combo_cards (combo_id, card_name, quantity)

ID convention: `user-NNN` zero-padded sequence so the IDs stay
distinguishable from Spellbook's numeric-pair IDs at a glance.
"""
from __future__ import annotations

import datetime as dt
import sqlite3
import sys
from pathlib import Path
from typing import Optional

sys.path.insert(0, str(Path(__file__).parent.parent))

from mtg_oracle.queries import resolve_card_name

DB_PATH = Path(__file__).parent.parent / "data" / "mtg.db"


def _now() -> str:
    return (
        dt.datetime.now(dt.timezone.utc)
        .isoformat(timespec="seconds")
        .replace("+00:00", "Z")
    )


def _next_user_id(cur: sqlite3.Cursor) -> str:
    """Return the next `user-NNN` ID, scanning existing rows."""
    cur.execute("SELECT id FROM user_combos WHERE id LIKE 'user-%'")
    used = set()
    for (rid,) in cur.fetchall():
        try:
            used.add(int(rid.split("-", 1)[1]))
        except (IndexError, ValueError):
            continue
    n = 1
    while n in used:
        n += 1
    return f"user-{n:03d}"


def _derive_ci(cur: sqlite3.Cursor, card_names: list[str]) -> str:
    """Union of `cards.color_identity` across given card names. Returns
    a contiguous letter string in WUBRG order (Spellbook-compatible)."""
    placeholders = ",".join("?" * len(card_names))
    cur.execute(
        f"SELECT DISTINCT color_identity FROM cards "
        f"WHERE name COLLATE NOCASE IN ({placeholders})",
        card_names,
    )
    letters: set[str] = set()
    for (ci,) in cur.fetchall():
        if not ci:
            continue
        for ch in ci.split(","):
            ch = ch.strip()
            if ch:
                letters.add(ch)
    order = "WUBRG"
    return "".join(c for c in order if c in letters)


def add_user_combo(
    cards: list[str],
    description: str,
    name: Optional[str] = None,
    color_identity: Optional[str] = None,
    added_by: str = "user",
    combo_id: Optional[str] = None,
) -> str:
    """Insert a user combo. Returns the assigned ID.

    Card names are resolved against the `cards` table — unresolvable
    names raise ValueError before any write.
    """
    if not cards:
        raise ValueError("cards list is required")
    if not description:
        raise ValueError("description is required")
    if not DB_PATH.exists():
        raise FileNotFoundError(f"Database not found at {DB_PATH}")

    canonical = []
    unresolved = []
    for c in cards:
        r = resolve_card_name(c)
        if r:
            canonical.append(r)
        else:
            unresolved.append(c)
    if unresolved:
        raise ValueError(f"unresolved card name(s): {unresolved}")

    conn = sqlite3.connect(DB_PATH)
    cur = conn.cursor()
    try:
        if combo_id is None:
            combo_id = _next_user_id(cur)
        if color_identity is None:
            color_identity = _derive_ci(cur, canonical)
        cur.execute(
            "INSERT INTO user_combos "
            "(id, name, color_identity, description, added_at, added_by) "
            "VALUES (?, ?, ?, ?, ?, ?)",
            (combo_id, name, color_identity, description, _now(), added_by),
        )
        for cn in canonical:
            cur.execute(
                "INSERT INTO user_combo_cards (combo_id, card_name, quantity) "
                "VALUES (?, ?, 1)",
                (combo_id, cn),
            )
        conn.commit()
        return combo_id
    finally:
        conn.close()


if __name__ == "__main__":
    print("This script is a library. Import add_user_combo() from another script.")
