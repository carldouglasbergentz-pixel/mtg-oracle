"""Deck management query layer.

All mutating operations open a single read-write connection; all reads
use the same read-only connection pattern as `queries.py`. Card names
are resolved against the cards table via `resolve_card_name`, which is
tolerant of apostrophe casing, alternative `//` separators, and
front-face-only DFC names.
"""
from __future__ import annotations

import datetime as dt
import sqlite3
from pathlib import Path
from typing import Optional

DB_PATH = Path(__file__).parent.parent / "data" / "mtg.db"


class DeckError(ValueError):
    """Raised for deck-layer validation errors — surfaces to UI."""


# --- Connection helpers ----------------------------------------------

def _ro() -> sqlite3.Connection:
    if not DB_PATH.exists():
        raise FileNotFoundError(f"Database not found at {DB_PATH}.")
    conn = sqlite3.connect(f"file:{DB_PATH}?mode=ro", uri=True)
    conn.row_factory = sqlite3.Row
    return conn


def _rw() -> sqlite3.Connection:
    if not DB_PATH.exists():
        raise FileNotFoundError(f"Database not found at {DB_PATH}.")
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    return conn


def _now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


# --- Card-name resolution --------------------------------------------

def resolve_card_name(raw: str) -> Optional[str]:
    """Find the canonical card name in the DB.

    Tolerant of:
    - case differences ("sol ring" -> "Sol Ring")
    - alternative `//` separators on DFC/split cards
      ("Fire/Ice", "fire // ice", "fire//ice" -> "Fire // Ice")
    - front-face-only DFC names ("Delver of Secrets" -> "Delver of
      Secrets // Insectile Aberration", "Jace, Vryn's Prodigy" -> the
      full melded name).

    Returns the canonical name, or None if no match. Ambiguous prefix
    matches return the shortest match (usually the intended card).
    """
    if not raw:
        return None
    name = raw.strip()
    if not name:
        return None

    conn = _ro()
    try:
        cur = conn.cursor()
        # 1) Exact match (case-insensitive)
        cur.execute("SELECT name FROM cards WHERE name = ? COLLATE NOCASE", (name,))
        row = cur.fetchone()
        if row:
            return row[0]

        # 2) Normalize separators — users often type "Fire/Ice" or
        # "Fire // Ice" without the spaces Scryfall uses. Try a few variants.
        normalized_variants = []
        for sep in [" // ", "//", "/"]:
            if sep in name:
                parts = [p.strip() for p in name.split(sep)]
                normalized_variants.append(" // ".join(parts))
                break
        for v in normalized_variants:
            cur.execute("SELECT name FROM cards WHERE name = ? COLLATE NOCASE", (v,))
            row = cur.fetchone()
            if row:
                return row[0]

        # 3) Front-face-only prefix for DFC/split/flip. We only accept
        # matches where `name // something` is exactly the canonical form,
        # not arbitrary substrings (which would be too fuzzy).
        cur.execute(
            "SELECT name FROM cards WHERE name LIKE ? COLLATE NOCASE "
            "ORDER BY LENGTH(name) LIMIT 1",
            (f"{name} // %",),
        )
        row = cur.fetchone()
        if row:
            return row[0]

        return None
    finally:
        conn.close()


# --- Folders ---------------------------------------------------------

def create_folder(name: str) -> int:
    name = name.strip()
    if not name:
        raise DeckError("folder name required")
    conn = _rw()
    try:
        cur = conn.cursor()
        try:
            cur.execute(
                "INSERT INTO deck_folders (name, created_at) VALUES (?, ?)",
                (name, _now()),
            )
        except sqlite3.IntegrityError:
            raise DeckError(f"folder already exists: {name!r}")
        conn.commit()
        return cur.lastrowid
    finally:
        conn.close()


def list_folders() -> list[dict]:
    """All folders + deck count per folder, plus a synthetic 'Unsorted' row
    counting decks that have NULL folder_id."""
    conn = _ro()
    try:
        cur = conn.cursor()
        cur.execute(
            """
            SELECT f.id, f.name, f.created_at,
                   (SELECT COUNT(*) FROM decks d WHERE d.folder_id = f.id) AS deck_count
            FROM deck_folders f
            ORDER BY f.name COLLATE NOCASE
            """
        )
        rows = [dict(r) for r in cur.fetchall()]
        cur.execute("SELECT COUNT(*) FROM decks WHERE folder_id IS NULL")
        unsorted = cur.fetchone()[0]
        if unsorted:
            rows.append({
                "id": None, "name": "(unsorted)", "created_at": None, "deck_count": unsorted,
            })
        return rows
    finally:
        conn.close()


def delete_folder(name: str, force: bool = False) -> None:
    """Delete a folder. If it contains decks, either moves them to Unsorted
    (force=True) or raises (force=False)."""
    conn = _rw()
    try:
        cur = conn.cursor()
        cur.execute("SELECT id FROM deck_folders WHERE name = ? COLLATE NOCASE", (name,))
        row = cur.fetchone()
        if not row:
            raise DeckError(f"folder not found: {name!r}")
        fid = row[0]
        cur.execute("SELECT COUNT(*) FROM decks WHERE folder_id = ?", (fid,))
        n = cur.fetchone()[0]
        if n and not force:
            raise DeckError(
                f"folder {name!r} contains {n} deck(s); pass force=True or move them first"
            )
        if n and force:
            cur.execute("UPDATE decks SET folder_id = NULL WHERE folder_id = ?", (fid,))
        cur.execute("DELETE FROM deck_folders WHERE id = ?", (fid,))
        conn.commit()
    finally:
        conn.close()


def _folder_id(cur, name: Optional[str]) -> Optional[int]:
    if not name:
        return None
    cur.execute("SELECT id FROM deck_folders WHERE name = ? COLLATE NOCASE", (name,))
    row = cur.fetchone()
    if not row:
        raise DeckError(f"folder not found: {name!r}")
    return row[0]


# --- Decks -----------------------------------------------------------

def create_deck(
    name: str,
    folder: Optional[str] = None,
    format: Optional[str] = None,
    description: Optional[str] = None,
) -> int:
    name = name.strip()
    if not name:
        raise DeckError("deck name required")
    conn = _rw()
    try:
        cur = conn.cursor()
        fid = _folder_id(cur, folder)
        try:
            cur.execute(
                """
                INSERT INTO decks (folder_id, name, format, description, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                (fid, name, format, description, _now(), _now()),
            )
        except sqlite3.IntegrityError:
            raise DeckError(f"deck {name!r} already exists in folder {folder or '(unsorted)'}")
        conn.commit()
        return cur.lastrowid
    finally:
        conn.close()


def list_decks(folder: Optional[str] = None) -> list[dict]:
    """List decks; optional folder filter. Returns deck + folder + card count."""
    conn = _ro()
    try:
        cur = conn.cursor()
        if folder:
            fid = _folder_id(cur, folder)
            cur.execute(
                """
                SELECT d.id, d.name, d.format, d.description,
                       d.created_at, d.updated_at,
                       (SELECT name FROM deck_folders WHERE id = d.folder_id) AS folder,
                       (SELECT COALESCE(SUM(quantity), 0) FROM deck_cards dc WHERE dc.deck_id = d.id) AS card_count
                FROM decks d WHERE d.folder_id = ?
                ORDER BY d.name COLLATE NOCASE
                """,
                (fid,),
            )
        else:
            cur.execute(
                """
                SELECT d.id, d.name, d.format, d.description,
                       d.created_at, d.updated_at,
                       (SELECT name FROM deck_folders WHERE id = d.folder_id) AS folder,
                       (SELECT COALESCE(SUM(quantity), 0) FROM deck_cards dc WHERE dc.deck_id = d.id) AS card_count
                FROM decks d
                ORDER BY folder COLLATE NOCASE, d.name COLLATE NOCASE
                """
            )
        return [dict(r) for r in cur.fetchall()]
    finally:
        conn.close()


def _deck_id(cur, name: str, folder: Optional[str] = None) -> int:
    """Find a deck by name. If multiple decks share a name across folders,
    raise unless `folder` disambiguates."""
    if folder:
        fid = _folder_id(cur, folder)
        cur.execute(
            "SELECT id FROM decks WHERE name = ? COLLATE NOCASE AND folder_id IS ?",
            (name, fid),
        )
    else:
        cur.execute(
            "SELECT id, folder_id FROM decks WHERE name = ? COLLATE NOCASE",
            (name,),
        )
    rows = cur.fetchall()
    if not rows:
        raise DeckError(f"deck not found: {name!r}")
    if len(rows) > 1:
        raise DeckError(
            f"ambiguous deck name {name!r} — exists in multiple folders; "
            "disambiguate with a folder argument"
        )
    return rows[0][0]


def get_deck(name: str, folder: Optional[str] = None) -> Optional[dict]:
    """Full deck with all cards + their type_line + mana_cost merged in."""
    conn = _ro()
    try:
        cur = conn.cursor()
        try:
            did = _deck_id(cur, name, folder)
        except DeckError:
            return None
        cur.execute(
            """
            SELECT d.id, d.name, d.format, d.description,
                   d.created_at, d.updated_at,
                   (SELECT name FROM deck_folders WHERE id = d.folder_id) AS folder
            FROM decks d WHERE d.id = ?
            """,
            (did,),
        )
        deck = dict(cur.fetchone())
        cur.execute(
            """
            SELECT dc.card_name, dc.quantity, dc.category,
                   dc.is_commander, dc.is_sideboard,
                   c.type_line, c.mana_cost, c.mana_value, c.colors,
                   c.power, c.toughness
            FROM deck_cards dc
            LEFT JOIN cards c ON c.name = dc.card_name COLLATE NOCASE
            WHERE dc.deck_id = ?
            ORDER BY dc.is_sideboard, dc.is_commander DESC,
                     c.type_line COLLATE NOCASE, dc.card_name COLLATE NOCASE
            """,
            (did,),
        )
        deck["cards"] = [dict(r) for r in cur.fetchall()]
        deck["total_main"] = sum(
            c["quantity"] for c in deck["cards"]
            if not c["is_sideboard"]
        )
        deck["total_side"] = sum(
            c["quantity"] for c in deck["cards"] if c["is_sideboard"]
        )
        return deck
    finally:
        conn.close()


def delete_deck(name: str, folder: Optional[str] = None) -> None:
    conn = _rw()
    try:
        cur = conn.cursor()
        did = _deck_id(cur, name, folder)
        cur.execute("DELETE FROM deck_cards WHERE deck_id = ?", (did,))
        cur.execute("DELETE FROM decks WHERE id = ?", (did,))
        conn.commit()
    finally:
        conn.close()


def rename_deck(old_name: str, new_name: str, folder: Optional[str] = None) -> None:
    new_name = new_name.strip()
    if not new_name:
        raise DeckError("new deck name required")
    conn = _rw()
    try:
        cur = conn.cursor()
        did = _deck_id(cur, old_name, folder)
        try:
            cur.execute(
                "UPDATE decks SET name = ?, updated_at = ? WHERE id = ?",
                (new_name, _now(), did),
            )
        except sqlite3.IntegrityError:
            raise DeckError(f"deck {new_name!r} already exists in the same folder")
        conn.commit()
    finally:
        conn.close()


def move_deck(name: str, new_folder: Optional[str], folder: Optional[str] = None) -> None:
    conn = _rw()
    try:
        cur = conn.cursor()
        did = _deck_id(cur, name, folder)
        new_fid = _folder_id(cur, new_folder) if new_folder else None
        try:
            cur.execute(
                "UPDATE decks SET folder_id = ?, updated_at = ? WHERE id = ?",
                (new_fid, _now(), did),
            )
        except sqlite3.IntegrityError:
            raise DeckError(
                f"deck {name!r} already exists in folder {new_folder or '(unsorted)'}"
            )
        conn.commit()
    finally:
        conn.close()


# --- Deck cards ------------------------------------------------------

def add_card_to_deck(
    deck_name: str,
    card_name: str,
    quantity: int = 1,
    category: Optional[str] = None,
    is_commander: bool = False,
    is_sideboard: bool = False,
    folder: Optional[str] = None,
    resolve: bool = True,
) -> str:
    """Returns the canonical card name that was added (helpful for echoing
    back when the input was a loose form like 'Fire/Ice')."""
    if quantity < 1:
        raise DeckError("quantity must be >= 1")
    canonical = resolve_card_name(card_name) if resolve else card_name
    if not canonical:
        raise DeckError(f"card not found: {card_name!r}")
    conn = _rw()
    try:
        cur = conn.cursor()
        did = _deck_id(cur, deck_name, folder)
        # If the same (card, commander/sideboard) row already exists, merge qty.
        cur.execute(
            """
            SELECT id, quantity FROM deck_cards
            WHERE deck_id = ? AND card_name = ? COLLATE NOCASE
              AND is_commander = ? AND is_sideboard = ?
            """,
            (did, canonical, int(is_commander), int(is_sideboard)),
        )
        existing = cur.fetchone()
        if existing:
            cur.execute(
                "UPDATE deck_cards SET quantity = ? WHERE id = ?",
                (existing["quantity"] + quantity, existing["id"]),
            )
        else:
            cur.execute(
                """
                INSERT INTO deck_cards
                  (deck_id, card_name, quantity, category, is_commander, is_sideboard, added_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                (did, canonical, quantity, category,
                 int(is_commander), int(is_sideboard), _now()),
            )
        cur.execute("UPDATE decks SET updated_at = ? WHERE id = ?", (_now(), did))
        conn.commit()
        return canonical
    finally:
        conn.close()


def remove_card_from_deck(
    deck_name: str, card_name: str, folder: Optional[str] = None,
) -> None:
    conn = _rw()
    try:
        cur = conn.cursor()
        did = _deck_id(cur, deck_name, folder)
        cur.execute(
            "DELETE FROM deck_cards WHERE deck_id = ? AND card_name = ? COLLATE NOCASE",
            (did, card_name),
        )
        if cur.rowcount == 0:
            raise DeckError(f"card not in deck: {card_name!r}")
        cur.execute("UPDATE decks SET updated_at = ? WHERE id = ?", (_now(), did))
        conn.commit()
    finally:
        conn.close()


def combos_in_deck(
    deck_name: str,
    folder: Optional[str] = None,
    limit: int = 50,
) -> list[dict]:
    """Combos whose every card is in the given deck.

    Returns combos sorted smallest (fewest cards) first, then by id.
    Each row matches the shape of `find_combos_with_card` so it renders
    with the existing `render_combo_list`.
    """
    if limit < 1 or limit > 500:
        limit = 50
    conn = _ro()
    try:
        cur = conn.cursor()
        did = _deck_id(cur, deck_name, folder)
        # A combo is "in the deck" when every card it requires has a
        # corresponding row in deck_cards (case-insensitive name match).
        # We match via the total cards per combo vs cards intersecting
        # the deck; if they are equal, the combo is fully satisfied.
        cur.execute(
            """
            SELECT c.id, c.color_identity, c.name,
                   (SELECT COUNT(*) FROM combo_cards WHERE combo_id=c.id) AS card_count,
                   (SELECT GROUP_CONCAT(card_name, ' + ')
                    FROM combo_cards WHERE combo_id=c.id) AS cards
            FROM combos c
            WHERE c.id IN (
                SELECT cc.combo_id
                FROM combo_cards cc
                WHERE cc.card_name COLLATE NOCASE IN (
                    SELECT card_name FROM deck_cards WHERE deck_id = ?
                )
                GROUP BY cc.combo_id
                HAVING COUNT(DISTINCT cc.card_name) =
                       (SELECT COUNT(*) FROM combo_cards WHERE combo_id = cc.combo_id)
            )
            ORDER BY card_count ASC, c.id
            LIMIT ?
            """,
            (did, limit),
        )
        return [dict(r) for r in cur.fetchall()]
    finally:
        conn.close()


def import_deck(
    name: str,
    parsed: list[dict],
    folder: Optional[str] = None,
    format: Optional[str] = None,
) -> dict:
    """Create a new deck and load it from parsed rows.

    `parsed` is a list of {name, quantity, section} dicts, where `section`
    is one of 'main', 'sideboard', 'commander', 'maybeboard'. Maybeboard
    rows are skipped (no table for them yet). Returns a summary with
    added/unresolved counts.
    """
    create_deck(name, folder=folder, format=format)
    added = 0
    unresolved: list[str] = []
    for row in parsed:
        if row["section"] == "maybeboard":
            continue
        try:
            add_card_to_deck(
                name,
                row["name"],
                quantity=row["quantity"],
                is_commander=(row["section"] == "commander"),
                is_sideboard=(row["section"] == "sideboard"),
                folder=folder,
            )
            added += 1
        except DeckError as e:
            if "card not found" in str(e):
                unresolved.append(row["name"])
            else:
                # Propagate other errors — they indicate bugs.
                raise
    return {
        "added": added,
        "unresolved": unresolved,
        "total_input": len(parsed),
    }
