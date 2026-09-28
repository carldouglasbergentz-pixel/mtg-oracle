"""Deck management query layer.

All mutating operations open a single read-write connection; all reads
use the same read-only connection pattern as `queries.py`. Card names
are resolved against the cards table via `resolve_card_name`, which is
tolerant of apostrophe casing, alternative `//` separators, and
front-face-only DFC names.
"""
from __future__ import annotations

import datetime as dt
import re
import sqlite3
from pathlib import Path
from typing import Optional

from mtg_oracle.queries import BUSY_TIMEOUT_S, RESTRICTED_MEANS_NO_COMMANDER
from mtg_oracle.queries import resolve_card_name
from mtg_oracle.queries import flag_template_vars as _flag_template_vars
from mtg_oracle.queries import get_card_points as _get_card_points
from mtg_oracle.queries import is_singleton_format as _is_singleton_format
from mtg_oracle.queries import resolve_format as _resolve_format

DB_PATH = Path(__file__).parent.parent / "data" / "mtg.db"


# Phrase Wizards uses on cards that override singleton (Relentless Rats,
# Shadowborn Apostle, Dragon's Approach, Persistent Petitioners, Rat Colony,
# Slime Against Humanity, Hare Apparent, Templar Knight, ...).
_UNLIMITED_PHRASE = "a deck can have any number of cards named"
# The capped variant: Nazgûl ("up to nine"), Seven Dwarves ("up to seven").
# Spelled out as a word on every printed card, hence the word table.
_UP_TO_RE = re.compile(r"a deck can have up to (\w+) cards named")
_NUMBER_WORDS = {
    "two": 2, "three": 3, "four": 4, "five": 5, "six": 6, "seven": 7,
    "eight": 8, "nine": 9, "ten": 10, "eleven": 11, "twelve": 12,
}


class DeckError(ValueError):
    """Raised for deck-layer validation errors — surfaces to UI."""


class CardNotFoundError(DeckError):
    """A card name that resolves to nothing in the cards table."""


class AmbiguousDeckError(DeckError):
    """A deck name that matches decks in more than one folder.

    `folders` lists where, each value usable as a `folder=` argument
    (`UNSORTED` for a deck outside any folder).
    """

    def __init__(self, name: str, folders: list[str]):
        self.folders = folders
        super().__init__(
            f"ambiguous deck name {name!r} — it exists in "
            f"{', '.join(folders)}; pass a folder "
            f"({UNSORTED!r} for a deck outside any folder)"
        )


# How to address "decks outside any folder". Every public function taking
# `folder` reads it three ways:
#     None / ""      any folder — the name alone must be unique
#     UNSORTED       only decks with no folder
#     "<name>"       only that folder
# It is the same string every surface already displays for those decks, and
# create_folder refuses it as a folder name so it can never mean both.
UNSORTED = "(unsorted)"


def is_unsorted(folder: Optional[str]) -> bool:
    """True when `folder` names the decks outside any folder."""
    return bool(folder) and folder.strip().lower() == UNSORTED


def _assert_valid_name(name: str, kind: str) -> None:
    """'/' separates folder and deck in every path surface (`cd F/D`,
    `show F/D`), so a name containing one could never be addressed."""
    if "/" in name:
        raise DeckError(
            f"{kind} name {name!r} contains '/', which separates folder and "
            f"deck in a path (<folder>/<deck>) — pick another name"
        )


# Upper bound on one row's quantity. Well past any real deck (Relentless Rats
# lists run ~40), and far below SQLite's INTEGER limit, which an unchecked
# `99999999999999999999 Mountain` paste overflowed with a raw OverflowError.
MAX_QUANTITY = 999


class QuantityError(DeckError):
    """A row quantity outside 1..MAX_QUANTITY. Not waived by `force`."""


def _check_quantity(quantity: int) -> None:
    if not 1 <= quantity <= MAX_QUANTITY:
        raise QuantityError(f"quantity must be between 1 and {MAX_QUANTITY}")


# --- Connection helpers ----------------------------------------------

def _ro() -> sqlite3.Connection:
    if not DB_PATH.exists():
        raise FileNotFoundError(f"Database not found at {DB_PATH}.")
    conn = sqlite3.connect(
        f"file:{DB_PATH}?mode=ro", uri=True, timeout=BUSY_TIMEOUT_S,
    )
    conn.row_factory = sqlite3.Row
    return conn


def _rw() -> sqlite3.Connection:
    if not DB_PATH.exists():
        raise FileNotFoundError(f"Database not found at {DB_PATH}.")
    conn = sqlite3.connect(DB_PATH, timeout=BUSY_TIMEOUT_S)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    return conn


def _now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


# --- Folders ---------------------------------------------------------

def create_folder(name: str) -> int:
    name = name.strip()
    if not name:
        raise DeckError("folder name required")
    _assert_valid_name(name, "folder")
    if is_unsorted(name):
        raise DeckError(f"{UNSORTED!r} is reserved for decks outside any folder")
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
    """All folders + deck count + default format per folder, plus a synthetic
    'Unsorted' row counting decks that have NULL folder_id."""
    conn = _ro()
    try:
        cur = conn.cursor()
        cur.execute(
            """
            SELECT f.id, f.name, f.created_at, f.format,
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
                "id": None, "name": UNSORTED, "created_at": None,
                "format": None, "deck_count": unsorted,
            })
        return rows
    finally:
        conn.close()


def get_folder_format(name: str) -> Optional[str]:
    """A folder's default format, or None."""
    conn = _ro()
    try:
        cur = conn.cursor()
        cur.execute(
            "SELECT format FROM deck_folders WHERE name = ? COLLATE NOCASE", (name,)
        )
        row = cur.fetchone()
        return row["format"] if row else None
    finally:
        conn.close()


def set_folder_format(
    name: str, fmt: Optional[str], apply_to_decks: bool = False,
) -> tuple[Optional[str], Optional[dict], int]:
    """Set (or clear) a folder's default format.

    Returns (stored_value, resolved_info, decks_updated). The default only
    fills in a *new* deck's format — a deck that already has one keeps it.
    With `apply_to_decks=True` the format is also stamped onto existing decks
    in the folder that have none; decks with a format are never overwritten,
    because the folder is a default and not an authority.
    """
    fmt = (fmt or "").strip() or None
    if is_unsorted(name) or not (name or "").strip():
        # _folder_id reads both as "no folder", and the UPDATE below would
        # then match nothing and report success.
        raise DeckError(
            "decks outside a folder have no folder default; set each "
            "deck's own format instead"
        )
    conn = _rw()
    try:
        cur = conn.cursor()
        fid = _folder_id(cur, name)
        cur.execute("UPDATE deck_folders SET format = ? WHERE id = ?", (fmt, fid))
        updated = 0
        if apply_to_decks and fmt:
            cur.execute(
                "UPDATE decks SET format = ?, updated_at = ? "
                "WHERE folder_id = ? AND (format IS NULL OR format = '')",
                (fmt, _now(), fid),
            )
            updated = cur.rowcount
        conn.commit()
    finally:
        conn.close()
    return fmt, _resolve_format(fmt), updated


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
            cur.execute(
                "SELECT d.name FROM decks d WHERE d.folder_id = ? AND EXISTS ("
                "  SELECT 1 FROM decks u WHERE u.folder_id IS NULL"
                "  AND u.name = d.name COLLATE NOCASE) "
                "ORDER BY d.name COLLATE NOCASE",
                (fid,),
            )
            clashes = [r[0] for r in cur.fetchall()]
            if clashes:
                raise DeckError(
                    f"cannot move the decks in {name!r} to {UNSORTED}: "
                    f"a deck there already has the name "
                    f"{', '.join(repr(c) for c in clashes)}. Rename first."
                )
            cur.execute("UPDATE decks SET folder_id = NULL WHERE folder_id = ?", (fid,))
        cur.execute("DELETE FROM deck_folders WHERE id = ?", (fid,))
        conn.commit()
    finally:
        conn.close()


def _folder_id(cur, name: Optional[str]) -> Optional[int]:
    """The folder's id, or None for "no folder" (None, "" or UNSORTED)."""
    if not name or is_unsorted(name):
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
    """Create an empty deck. `folder` None, "" or UNSORTED: outside any folder."""
    conn = _rw()
    try:
        did = _create_deck(conn.cursor(), name, folder, format, description)
        conn.commit()
        return did
    finally:
        conn.close()


def _create_deck(
    cur,
    name: str,
    folder: Optional[str],
    format: Optional[str],
    description: Optional[str],
) -> int:
    """`create_deck` on an open cursor; the caller commits."""
    name = name.strip()
    if not name:
        raise DeckError("deck name required")
    _assert_valid_name(name, "deck")
    # Same normalisation as set_deck_format: a blank format is no format,
    # never a stored '' that reads as "set" to some checks and not others.
    format = (format or "").strip() or None
    fid = _folder_id(cur, folder)
    _assert_deck_name_free(cur, name, fid)
    if format is None and fid is not None:
        # Inherit the folder's default. This is the whole point of the
        # folder format: a deck dropped into "Canadian Highlander" should
        # get Canlander's rules without being told twice.
        cur.execute("SELECT format FROM deck_folders WHERE id = ?", (fid,))
        row = cur.fetchone()
        format = (row["format"] if row else None) or None
    try:
        cur.execute(
            """
            INSERT INTO decks (folder_id, name, format, description, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            (fid, name, format, description, _now(), _now()),
        )
    except sqlite3.IntegrityError:
        # Only reachable in a race with another writer; the check above
        # gives the same message first.
        raise DeckError(_name_taken_message(cur, name, fid))
    return cur.lastrowid


def _assert_deck_name_free(
    cur, name: str, fid: Optional[int], except_id: Optional[int] = None,
) -> None:
    """Raise unless no other deck in folder `fid` has this name, any case.

    Checked here rather than left to the unique index, because the index
    only exists once `migrate_unique_deck_names.py` has run, and its error
    names an index rather than the deck.
    """
    cur.execute(
        "SELECT 1 FROM decks WHERE folder_id IS ? AND name = ? COLLATE NOCASE "
        "AND id IS NOT ?",
        (fid, name, except_id),
    )
    if cur.fetchone():
        raise DeckError(_name_taken_message(cur, name, fid))


def _name_taken_message(cur, name: str, fid: Optional[int]) -> str:
    where = UNSORTED
    if fid is not None:
        cur.execute("SELECT name FROM deck_folders WHERE id = ?", (fid,))
        row = cur.fetchone()
        where = f"folder {row[0]!r}" if row else where
    return f"a deck named {name!r} already exists in {where}"


def list_decks(folder: Optional[str] = None) -> list[dict]:
    """List decks; optional folder filter (None: every deck, UNSORTED: decks
    outside any folder). Returns deck + folder + card count + commander_ci
    (sorted list of letters, [] for colorless commander, None when no
    commander is set on the deck)."""
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
                FROM decks d WHERE d.folder_id IS ?
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
        rows = [dict(r) for r in cur.fetchall()]
        for row in rows:
            row["commander_ci"] = _deck_color_identity_inner(cur, row["id"])
        return rows
    finally:
        conn.close()


def _deck_id(cur, name: str, folder: Optional[str] = None) -> int:
    """Find a deck by name, in `folder` (see UNSORTED for the three meanings).

    Raises DeckError when there is no such deck or folder, and
    AmbiguousDeckError when `folder` is None and several folders have one.
    """
    if folder:
        fid = _folder_id(cur, folder)
        cur.execute(
            "SELECT d.id, f.name FROM decks d "
            "LEFT JOIN deck_folders f ON f.id = d.folder_id "
            "WHERE d.name = ? COLLATE NOCASE AND d.folder_id IS ?",
            (name, fid),
        )
    else:
        cur.execute(
            "SELECT d.id, f.name FROM decks d "
            "LEFT JOIN deck_folders f ON f.id = d.folder_id "
            "WHERE d.name = ? COLLATE NOCASE "
            "ORDER BY f.name IS NULL, f.name COLLATE NOCASE",
            (name,),
        )
    rows = cur.fetchall()
    if not rows:
        raise DeckError(f"deck not found: {name!r}")
    if len(rows) > 1:
        raise AmbiguousDeckError(name, [r[1] or UNSORTED for r in rows])
    return rows[0][0]


def get_deck(name: str, folder: Optional[str] = None) -> Optional[dict]:
    """Full deck with all cards + their type_line + mana_cost merged in.

    None when there is no such deck (or no such folder). A name that matches
    decks in several folders raises AmbiguousDeckError instead — "missing"
    and "say which one" need different answers from the caller.
    """
    conn = _ro()
    try:
        cur = conn.cursor()
        try:
            did = _deck_id(cur, name, folder)
        except AmbiguousDeckError:
            raise
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
                   c.color_identity, c.power, c.toughness, c.oracle_text,
                   -- layout + per-face data: analytics needs to tell an MDFC
                   -- whose back is a land (a spell you may play as a land)
                   -- from a transform card whose back is only reachable by
                   -- transforming.
                   c.layout, c.card_faces
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
        deck["commander_ci"] = _deck_color_identity_inner(cur, did)
        info = _resolve_format(deck.get("format"))
        deck["format_info"] = info
        deck["points"] = _deck_points_inner(cur, did, info)
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
    _assert_valid_name(new_name, "deck")
    conn = _rw()
    try:
        cur = conn.cursor()
        did = _deck_id(cur, old_name, folder)
        cur.execute("SELECT folder_id FROM decks WHERE id = ?", (did,))
        fid = cur.fetchone()[0]
        # except_id: renaming `foo` to `Foo` is a case change, not a clash.
        _assert_deck_name_free(cur, new_name, fid, except_id=did)
        try:
            cur.execute(
                "UPDATE decks SET name = ?, updated_at = ? WHERE id = ?",
                (new_name, _now(), did),
            )
        except sqlite3.IntegrityError:
            raise DeckError(_name_taken_message(cur, new_name, fid))
        conn.commit()
    finally:
        conn.close()


def move_deck(name: str, new_folder: Optional[str], folder: Optional[str] = None) -> None:
    """Move a deck to `new_folder`; None, "" or UNSORTED moves it out of
    every folder."""
    conn = _rw()
    try:
        cur = conn.cursor()
        did = _deck_id(cur, name, folder)
        new_fid = _folder_id(cur, new_folder)
        cur.execute("SELECT name FROM decks WHERE id = ?", (did,))
        stored_name = cur.fetchone()[0]
        _assert_deck_name_free(cur, stored_name, new_fid, except_id=did)
        try:
            cur.execute(
                "UPDATE decks SET folder_id = ?, updated_at = ? WHERE id = ?",
                (new_fid, _now(), did),
            )
        except sqlite3.IntegrityError:
            raise DeckError(_name_taken_message(cur, stored_name, new_fid))
        conn.commit()
    finally:
        conn.close()


# --- Format-aware deck metadata --------------------------------------

def _is_basic_land(type_line: Optional[str]) -> bool:
    if not type_line:
        return False
    return "Basic" in type_line and "Land" in type_line


def _singleton_copy_limit(
    type_line: Optional[str], oracle_text: Optional[str],
) -> Optional[int]:
    """Copies of this card a singleton format allows; None means no limit."""
    if _is_basic_land(type_line):
        return None
    text = (oracle_text or "").lower()
    if _UNLIMITED_PHRASE in text:
        return None
    m = _UP_TO_RE.search(text)
    if m:
        # An unknown number word falls back to singleton: rejecting a legal
        # copy is loud and forceable, allowing an illegal one is silent.
        return _NUMBER_WORDS.get(m.group(1), 1)
    return 1


def _deck_color_identity_inner(cur, did: int) -> Optional[list[str]]:
    """Sorted union of color_identity letters for all is_commander=1 rows.

    Returns None when the deck has no commanders (so callers can skip the
    filter entirely). Returns [] for a deck whose only commander is colorless.
    """
    cur.execute(
        """
        SELECT c.color_identity
        FROM deck_cards dc
        LEFT JOIN cards c ON c.name = dc.card_name COLLATE NOCASE
        WHERE dc.deck_id = ? AND dc.is_commander = 1
        """,
        (did,),
    )
    rows = cur.fetchall()
    if not rows:
        return None
    letters: set[str] = set()
    for (ci,) in rows:
        if not ci:
            continue
        for ch in ci.split(","):
            ch = ch.strip()
            if ch:
                letters.add(ch)
    return sorted(letters)


def get_deck_color_identity(
    deck_name: str, folder: Optional[str] = None,
) -> Optional[list[str]]:
    """Public helper: returns the deck's effective CI as a sorted letter list.

    None means "no commanders set" — callers (search filter, add-validation)
    should treat that as "no CI constraint applies".
    """
    conn = _ro()
    try:
        cur = conn.cursor()
        did = _deck_id(cur, deck_name, folder)
        return _deck_color_identity_inner(cur, did)
    finally:
        conn.close()


def get_deck_format_info(
    deck_name: str, folder: Optional[str] = None,
) -> Optional[dict]:
    """`queries.resolve_format()` for this deck's format, or None.

    Carries the human label, the inherited legality key, and the points
    budget — everything the UI needs to explain what it's enforcing.
    """
    conn = _ro()
    try:
        cur = conn.cursor()
        did = _deck_id(cur, deck_name, folder)
        cur.execute("SELECT format FROM decks WHERE id = ?", (did,))
        row = cur.fetchone()
        return _resolve_format(row["format"] if row else None)
    finally:
        conn.close()


def set_deck_format(
    deck_name: str,
    fmt: Optional[str],
    folder: Optional[str] = None,
) -> tuple[Optional[str], Optional[dict]]:
    """Set (or clear, with `fmt=None`) a deck's format.

    Returns (stored_value, resolved_info) where `resolved_info` is
    `queries.resolve_format()`'s answer — None when the name means nothing to
    the rules engine. The value is stored verbatim either way: `decks.format`
    has always been free text, and refusing an unrecognised name would stop
    people labelling decks for formats we don't model yet. The caller is
    expected to say which rules actually became active.
    """
    fmt = (fmt or "").strip() or None
    conn = _rw()
    try:
        cur = conn.cursor()
        did = _deck_id(cur, deck_name, folder)
        cur.execute(
            "UPDATE decks SET format = ?, updated_at = ? WHERE id = ?",
            (fmt, _now(), did),
        )
        conn.commit()
    finally:
        conn.close()
    return fmt, _resolve_format(fmt)


def deck_points(deck_name: str, folder: Optional[str] = None) -> Optional[dict]:
    """Points a deck spends under its format's points list.

    Returns None when the format has no points list (every Scryfall format,
    and community formats without a definition file). Otherwise:
        budget   points allowed per deck
        total    points spent
        cards    [(card_name, points, quantity, subtotal)] highest first
        over     True when total > budget

    Points count per copy, which matters only in theory — every pointed
    format is singleton — but counting quantity keeps a forced 2-of honest.
    """
    conn = _ro()
    try:
        cur = conn.cursor()
        did = _deck_id(cur, deck_name, folder)
        cur.execute("SELECT format FROM decks WHERE id = ?", (did,))
        row = cur.fetchone()
        info = _resolve_format(row["format"] if row else None)
        return _deck_points_inner(cur, did, info)
    finally:
        conn.close()


def _deck_points_inner(cur, did: int, info: Optional[dict]) -> Optional[dict]:
    """Points calculation on an open cursor, for callers that have one.

    `get_deck` is on the TUI's hot path — `_refresh_nav` calls it after
    every command — and it already knows the deck id and resolved format,
    so it must not pay for three more connections to rediscover them.
    """
    if not info or info.get("points_budget") is None:
        return None
    table = _get_card_points(info["key"])
    if not table:
        return None
    cur.execute(
        "SELECT card_name, quantity FROM deck_cards "
        "WHERE deck_id = ? AND is_sideboard = 0",
        (did,),
    )
    rows = cur.fetchall()

    # Points tables are keyed on canonical names; deck rows are too (add
    # resolves), but match case-insensitively so a hand-inserted row counts.
    lowered = {name.lower(): (name, pts) for name, pts in table.items()}
    cards: list[tuple[str, int, int, int]] = []
    total = 0
    for row in rows:
        hit = lowered.get((row["card_name"] or "").lower())
        if not hit:
            continue
        canonical, pts = hit
        qty = row["quantity"]
        cards.append((canonical, pts, qty, pts * qty))
        total += pts * qty
    cards.sort(key=lambda c: (-c[3], c[0].lower()))
    return {
        "format": info["key"],
        "label": info["label"],
        "budget": info["points_budget"],
        "total": total,
        "cards": cards,
        "over": total > info["points_budget"],
    }


def _assert_legal_in_format(
    cur,
    deck_id: int,
    canonical: str,
    fmt_info: Optional[dict],
    *,
    is_commander: bool,
    quantity: int,
    singleton: bool,
) -> None:
    """Raise DeckError if the card can't go in this slot of this deck.

    Shared by `add_card_to_deck` and `set_commander` — the latter promotes
    an existing row in place, and when this lived inline in `add` the TUI's
    `commander <card>` verb bypassed every legality rule.
    """
    if not fmt_info or not fmt_info["legality_key"]:
        return
    legality_fmt = fmt_info["legality_key"]
    label = fmt_info["label"]
    # For a custom format, say whose pool it is — "not legal in Canadian
    # Highlander" is confusing without "(Vintage pool)".
    pool = (f"{label} (inherits {legality_fmt}'s card pool)"
            if fmt_info["custom"] else label)
    status = _card_legality_status(cur, canonical, legality_fmt)
    if status == "banned":
        raise DeckError(
            f"{canonical!r} is banned in {pool}. Pass force=True to override."
        )
    if status is None:
        raise DeckError(
            f"{canonical!r} is not legal in {pool} "
            f"(not in the format's card pool). Pass force=True to override."
        )
    # `restricted` means two different things depending on the format —
    # see queries.RESTRICTED_MEANS_NO_COMMANDER.
    if status == "restricted":
        if legality_fmt in RESTRICTED_MEANS_NO_COMMANDER:
            if is_commander:
                raise DeckError(
                    f"{canonical!r} is banned as a commander in {label} "
                    f"(it may still be in the deck). "
                    f"Pass force=True to override."
                )
        elif not singleton:
            # Copy restriction (Vintage, Old School): one copy across main
            # deck AND sideboard combined — unlike the singleton rule, which
            # this project applies per section. In a singleton format the
            # singleton rule already caps it and gives the clearer message,
            # so don't pre-empt it.
            cur.execute(
                "SELECT COALESCE(SUM(quantity), 0) FROM deck_cards "
                "WHERE deck_id = ? AND card_name = ? COLLATE NOCASE",
                (deck_id, canonical),
            )
            if cur.fetchone()[0] + quantity > 1:
                raise DeckError(
                    f"{canonical!r} is restricted in {label} (limit 1 copy "
                    f"across main deck and sideboard). "
                    f"Pass force=True to override."
                )


def _assert_points_fit(
    cur,
    did: int,
    canonical: str,
    fmt_info: Optional[dict],
    *,
    quantity: int,
    is_sideboard: bool,
) -> None:
    """Raise DeckError if adding this card would blow the points budget.

    A pointed card can be perfectly legal and still not fit. Only called
    when the card is entering the deck; promoting a card already in the
    deck to commander costs nothing extra.

    Sideboard rows are not charged, because `deck_points` measures the main
    deck — which is what a points cap applies to. Charging the sideboard
    against a main-deck total would let two 8-point sideboard cards both
    pass while the badge still read 2/10.

    Runs on the caller's cursor: inside an import transaction, a fresh
    connection would not see the rows loaded so far.
    """
    if is_sideboard:
        return
    if not fmt_info or fmt_info.get("points_budget") is None:
        return
    card_pts = _get_card_points(fmt_info["key"]).get(canonical)
    if not card_pts:
        return
    spent = _deck_points_inner(cur, did, fmt_info)
    already = spent["total"] if spent else 0
    budget = fmt_info["points_budget"]
    cost = card_pts * quantity
    if already + cost > budget:
        raise DeckError(
            f"{canonical!r} costs {card_pts} point(s) in {fmt_info['label']}; "
            f"the deck is at {already}/{budget} and would go to "
            f"{already + cost}. Pass force=True to override."
        )


def _assert_can_be_added_as_commander(
    cur, deck_id: int, canonical: str, quantity: int,
) -> None:
    """Raise DeckError unless this is a fresh, single commander copy."""
    if quantity != 1:
        raise DeckError(
            f"a commander is a single card; cannot add {quantity}x "
            f"{canonical!r} as commander. Pass force=True to override."
        )
    cur.execute(
        "SELECT MAX(is_commander) FROM deck_cards "
        "WHERE deck_id = ? AND card_name = ? COLLATE NOCASE "
        "  AND is_sideboard = 0",
        (deck_id, canonical),
    )
    already = cur.fetchone()[0]
    if already == 1:
        raise DeckError(
            f"{canonical!r} is already a commander of this deck. "
            f"Pass force=True to override."
        )
    if already == 0:
        raise DeckError(
            f"{canonical!r} is already in the main deck; promote that copy "
            f"to commander instead. Pass force=True to override."
        )


def _card_legality_status(cur, card_name: str, fmt: str) -> Optional[str]:
    """'legal' / 'restricted' / 'banned', or None when there's no row.

    No row means not legal — `sync_cards.py` drops `not_legal` rows because
    they're 55% of the payload and carry no information.
    """
    cur.execute(
        "SELECT status FROM card_legalities "
        "WHERE card_name = ? COLLATE NOCASE AND format = ?",
        (card_name, fmt),
    )
    row = cur.fetchone()
    return row["status"] if row else None


# --- Deck cards ------------------------------------------------------

def add_card_to_deck(
    deck_name: str,
    card_name: str,
    quantity: int = 1,
    category: Optional[str] = None,
    is_commander: bool = False,
    is_sideboard: bool = False,
    folder: Optional[str] = None,
    force: bool = False,
) -> str:
    """Returns the canonical card name that was added (helpful for echoing
    back when the input was a loose form like 'Fire/Ice').

    Format-aware validation runs by default — disable with `force=True`:
    - Color identity: in a deck with at least one is_commander=1 row, the
      added card's color_identity must be a subset of the deck's CI.
      Skipped for the commander, which *defines* the CI.
    - Legality: when `decks.format` resolves to a legality key (directly or
      via a custom format's `derives_from`), the card must be `legal` or
      `restricted` in it. Banned and out-of-pool cards are rejected with
      different messages. This one *does* apply to commanders — an illegal
      commander is still illegal, and `set_commander` runs the same check.
    - Points: in a format with a points budget, a card whose points don't
      fit is rejected even though it's legal.
    - Singleton: in a singleton format (commander, canadian highlander, ...),
      a card already in the deck cannot be added again unless it's a basic
      land or its oracle text says "a deck can have any number of cards
      named ..."; "a deck can have up to N cards named ..." caps it at N.
      Sideboard rows do not interact with singleton checks against
      main-deck rows.
    - Commander: `is_commander=True` adds exactly one copy, and is refused
      when the card is already a commander or already in the main deck
      (promote that copy with `set_commander` instead).
    """
    conn = _rw()
    try:
        cur = conn.cursor()
        did = _deck_id(cur, deck_name, folder)
        before = _snapshot(cur, did)
        canonical = _add_card(
            cur, did, card_name, quantity=quantity, category=category,
            is_commander=is_commander, is_sideboard=is_sideboard, force=force,
        )
        _record_revision(cur, did, "add", before, note=canonical)
        conn.commit()
        return canonical
    finally:
        conn.close()


def _add_card(
    cur,
    did: int,
    card_name: str,
    *,
    quantity: int,
    category: Optional[str],
    is_commander: bool,
    is_sideboard: bool,
    force: bool,
) -> str:
    """`add_card_to_deck` on an open cursor; the caller commits.

    Every check runs before the first write, so a DeckError leaves the
    transaction exactly as it found it — which is what lets an import skip
    a rejected row and carry on.
    """
    _check_quantity(quantity)
    canonical = resolve_card_name(card_name)
    if not canonical:
        raise CardNotFoundError(f"card not found: {card_name!r}")
    # Look up the card's metadata once for validation.
    cur.execute(
        "SELECT color_identity, type_line, oracle_text FROM cards "
        "WHERE name = ? COLLATE NOCASE",
        (canonical,),
    )
    meta = cur.fetchone()
    card_ci = (meta["color_identity"] if meta else None) or ""
    card_type = (meta["type_line"] if meta else None)
    card_oracle = (meta["oracle_text"] if meta else None)

    # CI validation (skipped for the commander itself — adding a
    # commander defines the CI, it isn't checked against it).
    if not force and not is_commander:
        deck_ci = _deck_color_identity_inner(cur, did)
        if deck_ci is not None:
            card_letters = {ch for ch in card_ci.split(",") if ch}
            allowed = set(deck_ci)
            outside = sorted(card_letters - allowed)
            if outside:
                deck_label = "{" + ",".join(deck_ci) + "}" if deck_ci else "{colorless}"
                raise DeckError(
                    f"{canonical!r} has color identity "
                    f"{{{','.join(sorted(card_letters))}}} which is outside "
                    f"the deck's CI {deck_label} (offending: {','.join(outside)}). "
                    f"Pass force=True to override."
                )

    cur.execute("SELECT format FROM decks WHERE id = ?", (did,))
    fmt = cur.fetchone()["format"]
    fmt_info = _resolve_format(fmt)
    singleton = _is_singleton_format(fmt)

    if not force:
        _assert_legal_in_format(
            cur, did, canonical, fmt_info,
            is_commander=is_commander,
            quantity=quantity, singleton=singleton,
        )

    # A commander is one card, and not also a card in the 99 — in any
    # format, since the flag itself is what makes this a commander deck.
    # Checked before singleton so the message names the actual problem.
    if not force and is_commander and not is_sideboard:
        _assert_can_be_added_as_commander(cur, did, canonical, quantity)

    # Singleton validation. Sideboard cards are validated against
    # other sideboard rows; main vs. sideboard are independent.
    # Runs BEFORE the points check: a duplicate pointed card deserves
    # "you already have one" rather than a budget arithmetic error.
    limit = _singleton_copy_limit(card_type, card_oracle)
    if not force and singleton and limit is not None:
        cur.execute(
            """
            SELECT COALESCE(SUM(quantity), 0) FROM deck_cards
            WHERE deck_id = ? AND card_name = ? COLLATE NOCASE
              AND is_sideboard = ?
            """,
            (did, canonical, int(is_sideboard)),
        )
        current = cur.fetchone()[0]
        if current + quantity > limit:
            section = "sideboard" if is_sideboard else "main deck"
            raise DeckError(
                f"singleton format ({fmt!r}): {canonical!r} would have "
                f"{current + quantity} copies in the {section} "
                f"(limit is {limit}; basics and 'any number' cards are "
                f"exempt). Pass force=True to override."
            )

    if not force:
        _assert_points_fit(
            cur, did, canonical, fmt_info,
            quantity=quantity, is_sideboard=is_sideboard,
        )

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
        _check_quantity(existing["quantity"] + quantity)
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
    return canonical


def set_commander(
    deck_name: str,
    card_name: str,
    folder: Optional[str] = None,
    unset: bool = False,
    force: bool = False,
) -> tuple[str, str, Optional[str]]:
    """Promote a card to commander, or demote one with `unset=True`.

    Returns (canonical_name, action, format_set) where `action` is one of:
        'promoted'   — one copy of an existing main/sideboard row became
                       the commander (any other copies stay in that row)
        'added'      — card wasn't in deck; inserted as a fresh commander row
        'unchanged'  — card is already a commander
        'demoted'    — commander row flipped back to main (unset path)
    `format_set` is the new deck format if it was auto-set as part of the
    call (e.g. 'commander'), or None when no format change happened.

    Promotion rules:
    - Exactly one copy becomes the commander; a row holding more copies
      keeps the rest (in the main deck or sideboard, where they were).
    - When multiple rows exist for the same card (rare, e.g. main + sideboard),
      the main-deck row is preferred.
    - Multiple commanders are allowed (Partner / Background / Friends Forever)
      — promoting a 2nd card simply adds another is_commander=1 row.
    - On promote/add, if `decks.format` is currently NULL, it's auto-set to
      'commander' so format-aware behavior (CI filter on search, singleton
      on add) starts working immediately. An already-set format is left
      alone — the user knows what they're doing.
    - Format legality is checked on promote and add, with `force=True` to
      override, against the format the deck ends up with — so an unset
      format is checked as 'commander'. A copy that enters the main deck
      (fresh, or from the sideboard) is also charged against a points
      budget. A card can be perfectly legal in the 99 and still banned as
      a commander (Duel Commander, Tiny Leaders), and promoting in place
      must not be a way around that. Demotion is never checked — removing a
      commander can't make a deck less legal.
    """
    canonical = resolve_card_name(card_name)
    if not canonical:
        raise DeckError(f"card not found: {card_name!r}")
    conn = _rw()
    try:
        cur = conn.cursor()
        did = _deck_id(cur, deck_name, folder)
        before = _snapshot(cur, did)

        if unset:
            cur.execute(
                "SELECT id FROM deck_cards "
                "WHERE deck_id = ? AND card_name = ? COLLATE NOCASE "
                "  AND is_commander = 1",
                (did, canonical),
            )
            row = cur.fetchone()
            if not row:
                raise DeckError(
                    f"{canonical!r} is not currently a commander in this deck"
                )
            cur.execute(
                "UPDATE deck_cards SET is_commander = 0 WHERE id = ?",
                (row["id"],),
            )
            cur.execute("UPDATE decks SET updated_at = ? WHERE id = ?", (_now(), did))
            _record_revision(cur, did, "demote", before, note=canonical)
            conn.commit()
            # Demotion never touches `format` — preserves the user's intent
            # (the deck might still be a commander deck, just with a
            # different commander incoming).
            return canonical, "demoted", None

        # Promotion: pick the best existing row to take the commander from,
        # preferring a main-deck row (is_sideboard=0). is_commander=1 rows
        # sort first within is_sideboard=0 so we detect "already commander".
        cur.execute(
            "SELECT id, quantity, is_commander, is_sideboard FROM deck_cards "
            "WHERE deck_id = ? AND card_name = ? COLLATE NOCASE "
            "ORDER BY is_sideboard ASC, is_commander DESC",
            (did, canonical),
        )
        rows = cur.fetchall()
        primary = rows[0] if rows else None

        # Check against the format the deck will have when this call is done.
        # An unset format becomes 'commander' below, and checking against the
        # old NULL let a card banned in Commander into the command zone.
        cur.execute("SELECT format FROM decks WHERE id = ?", (did,))
        fmt = cur.fetchone()["format"] or "commander"

        # Same legality gate `add` uses. Promoting a row in place used to
        # skip it entirely, which made `commander <card>` a way to put a
        # banned card in the command zone.
        if not force:
            fmt_info = _resolve_format(fmt)
            # quantity is the *delta*: promoting moves a copy and adds none,
            # so a restricted card already in a Vintage deck must not trip
            # the one-copy check against itself.
            _assert_legal_in_format(
                cur, did, canonical, fmt_info,
                is_commander=True,
                quantity=0 if rows else 1,
                singleton=_is_singleton_format(fmt),
            )
            # Only a copy entering the main deck changes the points total: a
            # fresh insert, or one taken from the sideboard, which
            # deck_points never charged.
            if primary is None or primary["is_sideboard"]:
                _assert_points_fit(
                    cur, did, canonical, fmt_info,
                    quantity=1, is_sideboard=False,
                )

        if primary is None:
            cur.execute(
                "INSERT INTO deck_cards "
                "(deck_id, card_name, quantity, is_commander, is_sideboard, added_at) "
                "VALUES (?, ?, 1, 1, 0, ?)",
                (did, canonical, _now()),
            )
            format_set = _auto_set_commander_format(cur, did)
            cur.execute("UPDATE decks SET updated_at = ? WHERE id = ?", (_now(), did))
            _record_revision(cur, did, "promote", before, note=canonical)
            conn.commit()
            return canonical, "added", format_set

        if primary["is_commander"] and not primary["is_sideboard"]:
            # Already a commander — but format may still be NULL if the row
            # was inserted before this verb existed.
            format_set = _auto_set_commander_format(cur, did)
            if format_set:
                conn.commit()
            return canonical, "unchanged", format_set
        if primary["quantity"] == 1:
            cur.execute(
                "UPDATE deck_cards SET is_commander = 1, is_sideboard = 0 "
                "WHERE id = ?",
                (primary["id"],),
            )
        else:
            # One copy becomes the commander; the rest stay where they were.
            # Flipping the whole row to quantity 1 silently deleted them.
            cur.execute(
                "UPDATE deck_cards SET quantity = quantity - 1 WHERE id = ?",
                (primary["id"],),
            )
            cur.execute(
                "INSERT INTO deck_cards "
                "(deck_id, card_name, quantity, is_commander, is_sideboard, added_at) "
                "VALUES (?, ?, 1, 1, 0, ?)",
                (did, canonical, _now()),
            )
        format_set = _auto_set_commander_format(cur, did)
        cur.execute("UPDATE decks SET updated_at = ? WHERE id = ?", (_now(), did))
        _record_revision(cur, did, "promote", before, note=canonical)
        conn.commit()
        return canonical, "promoted", format_set
    finally:
        conn.close()


def _auto_set_commander_format(cur, did: int) -> Optional[str]:
    """If the deck has no format set, set it to 'commander'. Returns the
    new format value or None if no change was made. Called from the
    promote/add paths in `set_commander` so marking a card as commander
    automatically activates format-aware behavior (CI filter, singleton)."""
    cur.execute("SELECT format FROM decks WHERE id = ?", (did,))
    row = cur.fetchone()
    if row is None or row["format"]:
        return None
    cur.execute("UPDATE decks SET format = 'commander' WHERE id = ?", (did,))
    return "commander"


def remove_card_from_deck(
    deck_name: str,
    card_name: str,
    quantity: Optional[int] = None,
    folder: Optional[str] = None,
) -> tuple[str, int, int]:
    """Remove copies of a card from the deck.

    `quantity=None` removes ALL copies (the original behavior — used when
    the user just wants the card gone). A positive integer decrements the
    matching rows by that amount, clamping at 0 so over-removal silently
    succeeds (`remove mountain 100` on a 9-Mountain deck takes all 9 and
    echoes the actual delta).

    Symmetric with `add_card_to_deck`: the name goes through
    `resolve_card_name` first, so `remove fire/ice` and `remove lorien
    revealed` work exactly like their `add` counterparts. A name that
    resolves to nothing is still tried verbatim — a deck row can name a
    card that isn't in the cards table (imported from a paste), and the
    user must be able to delete it.

    Returns (canonical_name, removed_count, remaining_count) so the TUI
    can echo a precise "OK removed Nx Card (M remaining)" message.
    """
    if quantity is not None:
        _check_quantity(quantity)
    card_name = resolve_card_name(card_name) or card_name
    conn = _rw()
    try:
        cur = conn.cursor()
        did = _deck_id(cur, deck_name, folder)
        before = _snapshot(cur, did)

        # Sum current copies across all rows for this card (main + sideboard
        # + commander; rare to have multiple but possible). Decrement is
        # applied across rows in arbitrary order with main-deck rows first
        # — the caller wanting finer control should specify exact row.
        cur.execute(
            "SELECT id, quantity FROM deck_cards "
            "WHERE deck_id = ? AND card_name = ? COLLATE NOCASE "
            "ORDER BY is_sideboard ASC, is_commander ASC",
            (did, card_name),
        )
        rows = cur.fetchall()
        if not rows:
            raise DeckError(f"card not in deck: {card_name!r}")

        current_total = sum(r["quantity"] for r in rows)
        if quantity is None:
            # Original "remove all" path.
            cur.execute(
                "DELETE FROM deck_cards "
                "WHERE deck_id = ? AND card_name = ? COLLATE NOCASE",
                (did, card_name),
            )
            removed = current_total
            remaining = 0
        else:
            to_remove = min(quantity, current_total)
            removed = to_remove
            remaining = current_total - to_remove
            for row in rows:
                if to_remove <= 0:
                    break
                if to_remove >= row["quantity"]:
                    cur.execute("DELETE FROM deck_cards WHERE id = ?", (row["id"],))
                    to_remove -= row["quantity"]
                else:
                    cur.execute(
                        "UPDATE deck_cards SET quantity = ? WHERE id = ?",
                        (row["quantity"] - to_remove, row["id"]),
                    )
                    to_remove = 0
        cur.execute("UPDATE decks SET updated_at = ? WHERE id = ?", (_now(), did))
        _record_revision(cur, did, "remove", before, note=card_name)
        conn.commit()
        return card_name, removed, remaining
    finally:
        conn.close()


def combos_in_deck(
    deck_name: str,
    folder: Optional[str] = None,
    limit: int = 50,
) -> list[dict]:
    """Combos whose every card is in the given deck.

    Returns combos sorted smallest (fewest cards) first, then by id.
    Includes both Spellbook combos and user-curated combos from the
    `user_combos` table — the latter render the same way thanks to the
    matched column shape (id is text in both, just with a `user-` prefix
    for user combos).
    """
    limit = max(1, min(limit, 500))
    conn = _ro()
    try:
        cur = conn.cursor()
        did = _deck_id(cur, deck_name, folder)
        # A combo is "in the deck" when every card it requires has a
        # corresponding row in deck_cards (case-insensitive name match).
        # The same logic runs against Spellbook (`combos` / `combo_cards`)
        # and user-curated (`user_combos` / `user_combo_cards`) tables;
        # the two halves are UNION ALL'd so callers see one stream.
        cur.execute(
            """
            SELECT c.id, c.color_identity, c.name,
                   (SELECT COUNT(*) FROM combo_cards WHERE combo_id=c.id) AS card_count,
                   (SELECT GROUP_CONCAT(card_name, ' + ')
                    FROM combo_cards WHERE combo_id=c.id) AS cards,
                   'spellbook' AS source
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
            UNION ALL
            SELECT c.id, c.color_identity, c.name,
                   (SELECT COUNT(*) FROM user_combo_cards WHERE combo_id=c.id) AS card_count,
                   (SELECT GROUP_CONCAT(card_name, ' + ')
                    FROM user_combo_cards WHERE combo_id=c.id) AS cards,
                   'user' AS source
            FROM user_combos c
            WHERE c.id IN (
                SELECT cc.combo_id
                FROM user_combo_cards cc
                WHERE cc.card_name COLLATE NOCASE IN (
                    SELECT card_name FROM deck_cards WHERE deck_id = ?
                )
                GROUP BY cc.combo_id
                HAVING COUNT(DISTINCT cc.card_name) =
                       (SELECT COUNT(*) FROM user_combo_cards WHERE combo_id = cc.combo_id)
            )
            ORDER BY card_count ASC, id
            LIMIT ?
            """,
            (did, did, limit),
        )
        rows = [dict(r) for r in cur.fetchall()]
        return _flag_template_vars(rows, cur)
    finally:
        conn.close()


def load_parsed_into_deck(
    name: str,
    parsed: list[dict],
    folder: Optional[str] = None,
    force: bool = True,
) -> dict:
    """Load parsed deckstring rows into an EXISTING deck, all or nothing.

    `parsed` is a list of {name, quantity, section} dicts, where `section`
    is one of 'main', 'sideboard', 'commander', 'maybeboard'. Maybeboard
    rows are skipped (no table for them yet).

    `force=True` (the default) is what both import paths want: a pasted
    list is loaded verbatim, because singleton / CI checks would reject
    legitimate decks whose commander line happens to come after the
    non-commander cards.

    Returns the summary `_load_rows` builds. Nothing is ever dropped
    silently, and a structural failure rolls back every row.
    """
    conn = _rw()
    try:
        cur = conn.cursor()
        did = _deck_id(cur, name, folder)
        result = _load_rows(cur, did, parsed, force=force, action="load")
        conn.commit()
        return result
    except BaseException:
        conn.rollback()
        raise
    finally:
        conn.close()


def import_deck(
    name: str,
    parsed: list[dict],
    folder: Optional[str] = None,
    format: Optional[str] = None,
) -> dict:
    """Create a new deck and load it from parsed rows, all or nothing.

    The create and every row share one transaction: a failure part-way
    used to leave a half-loaded deck behind, and the retry then failed on
    "already exists".
    """
    conn = _rw()
    try:
        cur = conn.cursor()
        did = _create_deck(cur, name, folder, format, None)
        result = _load_rows(cur, did, parsed, force=True, action="import")
        conn.commit()
        return result
    except BaseException:
        conn.rollback()
        raise
    finally:
        conn.close()


def _load_rows(
    cur, did: int, parsed: list[dict], *, force: bool, action: str,
) -> dict:
    """Add parsed rows to deck `did` on an open cursor; the caller commits.

    Returns: rows added, copies added, names that didn't resolve, rows the
    deck layer rejected, maybeboard rows skipped, input row count,
    `format_set` — 'commander' when the list had a commander and the deck
    had no format, as `set_commander` does, else None — and `revision_id`,
    the one history revision the whole list became (None if nothing
    changed).
    """
    before = _snapshot(cur, did)
    added = 0
    copies = 0
    maybeboard = 0
    commander_added = False
    unresolved: list[str] = []
    rejected: list[tuple[str, str]] = []
    for row in parsed:
        if row["section"] == "maybeboard":
            maybeboard += 1
            continue
        is_commander = row["section"] == "commander"
        try:
            _add_card(
                cur, did, row["name"],
                quantity=row["quantity"], category=None,
                is_commander=is_commander,
                is_sideboard=(row["section"] == "sideboard"),
                force=force,
            )
        except CardNotFoundError:
            unresolved.append(row["name"])
            continue
        except QuantityError as e:
            # The row's fault, not a structural failure — `force` does not
            # waive it — so it is reported with the row.
            rejected.append((row["name"], str(e)))
            continue
        except DeckError as e:
            if force:
                # With force=True every validation rule is skipped, so a
                # DeckError here is structural. Collecting it would turn one
                # real failure into N identical "rejected" lines.
                raise
            rejected.append((row["name"], str(e)))
            continue
        added += 1
        copies += row["quantity"]
        commander_added = commander_added or is_commander
    # Same rule as set_commander: naming a commander is what makes a deck a
    # Commander deck, and without a format none of its rules apply.
    format_set = _auto_set_commander_format(cur, did) if commander_added else None
    revision_id, _ = _record_revision(cur, did, action, before)
    return {
        "revision_id": revision_id,
        "added": added,
        "copies": copies,
        "unresolved": unresolved,
        "rejected": rejected,
        "maybeboard": maybeboard,
        "format_set": format_set,
        "total_input": len(parsed),
    }


# --- Change history, replace and undo --------------------------------
#
# One user action that changes a deck's contents is one `deck_revisions`
# row, and its `deck_changes` rows are the quantity diff per (card_name,
# section). Every content write snapshots the deck first and records the
# diff in the same transaction, so history can never disagree with the deck.
# Rename, move and format changes are not content and are not logged.

SECTIONS = ("commander", "main", "sideboard")


class UnresolvedCardsError(DeckError):
    """A replace list naming cards that resolve to nothing. `names` lists
    them; nothing was changed."""

    def __init__(self, names: list[str]):
        self.names = names
        super().__init__(
            f"{len(names)} card name(s) not found, so nothing was replaced: "
            f"{', '.join(names)}. Fix them, or re-run with --force to "
            f"replace without them."
        )


def _section_of(is_commander, is_sideboard) -> str:
    # Sideboard wins, as in the export: a sideboard row flagged commander is
    # not in the command zone.
    if is_sideboard:
        return "sideboard"
    return "commander" if is_commander else "main"


def _snapshot(cur, did: int) -> dict[tuple[str, str], int]:
    """{(card_name, section): quantity} for every row of the deck."""
    cur.execute(
        "SELECT card_name, is_commander, is_sideboard, quantity "
        "FROM deck_cards WHERE deck_id = ?",
        (did,),
    )
    state: dict[tuple[str, str], int] = {}
    for row in cur.fetchall():
        key = (row["card_name"],
               _section_of(row["is_commander"], row["is_sideboard"]))
        state[key] = state.get(key, 0) + row["quantity"]
    return state


def _change_order(key: tuple[str, str]) -> tuple[int, str]:
    return SECTIONS.index(key[1]), key[0].lower()


def _record_revision(
    cur, did: int, action: str, before: dict, note: Optional[str] = None,
) -> tuple[Optional[int], list[dict]]:
    """Diff the deck against `before` and store it as one revision.

    Returns (revision_id, changes). A write that changed nothing records
    nothing and returns (None, []) — history holds no empty revisions.
    """
    after = _snapshot(cur, did)
    changes = [
        {"card": key[0], "section": key[1],
         "before": before.get(key, 0), "after": after.get(key, 0)}
        for key in sorted(set(before) | set(after), key=_change_order)
        if before.get(key, 0) != after.get(key, 0)
    ]
    if not changes:
        return None, []
    cur.execute(
        "INSERT INTO deck_revisions (deck_id, at, action, note) "
        "VALUES (?, ?, ?, ?)",
        (did, _now(), action, note),
    )
    revision_id = cur.lastrowid
    cur.executemany(
        "INSERT INTO deck_changes "
        "(revision_id, card_name, section, qty_before, qty_after) "
        "VALUES (?, ?, ?, ?, ?)",
        [(revision_id, c["card"], c["section"], c["before"], c["after"])
         for c in changes],
    )
    return revision_id, changes


_SECTION_WHERE = {
    "sideboard": "is_sideboard = 1",
    "commander": "is_commander = 1 AND is_sideboard = 0",
    "main": "is_commander = 0 AND is_sideboard = 0",
}


def _set_section_quantity(
    cur, did: int, card_name: str, section: str, quantity: int,
) -> None:
    """Make (card, section) hold exactly `quantity` copies.

    An existing row keeps its id, category and added_at; duplicate rows for
    the same key collapse into the first. Quantity 0 deletes them all.
    """
    cur.execute(
        f"SELECT id FROM deck_cards WHERE deck_id = ? "
        f"AND card_name = ? COLLATE NOCASE AND {_SECTION_WHERE[section]} "
        f"ORDER BY id",
        (did, card_name),
    )
    ids = [r["id"] for r in cur.fetchall()]
    keep = ids[:1] if quantity else []
    for row_id in ids:
        if row_id not in keep:
            cur.execute("DELETE FROM deck_cards WHERE id = ?", (row_id,))
    if keep:
        cur.execute("UPDATE deck_cards SET quantity = ? WHERE id = ?",
                    (quantity, keep[0]))
    elif quantity:
        cur.execute(
            "INSERT INTO deck_cards (deck_id, card_name, quantity, "
            "is_commander, is_sideboard, added_at) VALUES (?, ?, ?, ?, ?, ?)",
            (did, card_name, quantity, int(section == "commander"),
             int(section == "sideboard"), _now()),
        )


def _split_changes(changes: list[dict]) -> dict:
    return {
        "added": [c for c in changes if c["before"] == 0],
        "removed": [c for c in changes if c["after"] == 0],
        "changed": [c for c in changes if c["before"] and c["after"]],
    }


def _deck_name(cur, did: int) -> str:
    cur.execute("SELECT name FROM decks WHERE id = ?", (did,))
    return cur.fetchone()["name"]


def replace_deck_contents(
    name: str,
    parsed: list[dict],
    folder: Optional[str] = None,
    force: bool = False,
) -> dict:
    """Make an existing deck hold exactly the parsed list, as one revision.

    `parsed` is `deck_parser.parse_deckstring` output. The list is applied
    verbatim — no legality, CI, singleton or points checks, as for import.
    Maybeboard rows are skipped and counted. A row with a bad quantity is
    reported in `rejected` and its card keeps its current quantity. Rows
    whose quantity is unchanged are not touched, so they keep their
    category and added_at. If the list names a commander and the deck has
    no format, the format becomes 'commander', as on import.

    A name that resolves to no card aborts the whole replace with
    UnresolvedCardsError, and nothing changes; `force=True` replaces anyway
    without those names.

    Returns {deck, action: 'replace', revision_id (None when the deck
    already matched), added, removed, changed — lists of {card, section,
    before, after} — unresolved, rejected, maybeboard, format_set}.
    """
    target: dict[tuple[str, str], int] = {}
    keep_current: set[tuple[str, str]] = set()
    unresolved: list[str] = []
    rejected: list[tuple[str, str]] = []
    maybeboard = 0
    for row in parsed:
        if row["section"] == "maybeboard":
            maybeboard += 1
            continue
        section = row["section"] if row["section"] in SECTIONS else "main"
        canonical = resolve_card_name(row["name"])
        if not canonical:
            unresolved.append(row["name"])
            continue
        key = (canonical, section)
        try:
            _check_quantity(row["quantity"])
            _check_quantity(target.get(key, 0) + row["quantity"])
        except QuantityError as e:
            rejected.append((row["name"], str(e)))
            keep_current.add(key)
            continue
        target[key] = target.get(key, 0) + row["quantity"]
    if unresolved and not force:
        raise UnresolvedCardsError(unresolved)

    conn = _rw()
    try:
        cur = conn.cursor()
        did = _deck_id(cur, name, folder)
        before = _snapshot(cur, did)
        # Deck rows spell names as stored; match the list to them without
        # regard to case so a stored spelling never reads as a swap.
        stored = {card.lower(): card for card, _ in before}
        target = {(stored.get(card.lower(), card), section): qty
                  for (card, section), qty in target.items()}
        keep_current = {(stored.get(card.lower(), card), section)
                        for card, section in keep_current}
        for key in set(before) | set(target):
            if key in keep_current:
                continue
            if before.get(key, 0) != target.get(key, 0):
                _set_section_quantity(cur, did, key[0], key[1],
                                      target.get(key, 0))
        names_commander = any(section == "commander" for _, section in target)
        format_set = (_auto_set_commander_format(cur, did)
                      if names_commander else None)
        revision_id, changes = _record_revision(cur, did, "replace", before)
        if changes:
            cur.execute("UPDATE decks SET updated_at = ? WHERE id = ?",
                        (_now(), did))
        result = {
            "deck": _deck_name(cur, did),
            "action": "replace",
            "revision_id": revision_id,
            **_split_changes(changes),
            "unresolved": unresolved,
            "rejected": rejected,
            "maybeboard": maybeboard,
            "format_set": format_set,
        }
        conn.commit()
        return result
    except BaseException:
        conn.rollback()
        raise
    finally:
        conn.close()


def undo_last_change(name: str, folder: Optional[str] = None) -> dict:
    """Revert the deck's most recent revision, recording that as a revision.

    The undo is itself a revision (action 'undo'), so undoing an undo
    re-applies the change — it works as redo. Only contents are restored: a
    format that an import or `set_commander` auto-set stays set, because
    format changes are not part of the history.

    Raises DeckError when the deck has no history, or when its contents no
    longer match what the latest revision recorded (a change made outside
    the history, e.g. by an older build) — undoing then would overwrite it.

    Returns {deck, action: 'undo', revision_id, undone: {id, action, at,
    note}, added, removed, changed}, the lists shaped as in
    `replace_deck_contents`.
    """
    conn = _rw()
    try:
        cur = conn.cursor()
        did = _deck_id(cur, name, folder)
        cur.execute(
            "SELECT id, action, at, note FROM deck_revisions "
            "WHERE deck_id = ? ORDER BY id DESC LIMIT 1",
            (did,),
        )
        latest = cur.fetchone()
        if latest is None:
            raise DeckError(
                f"nothing to undo: deck {_deck_name(cur, did)!r} has no "
                f"recorded changes"
            )
        cur.execute(
            "SELECT card_name, section, qty_before, qty_after "
            "FROM deck_changes WHERE revision_id = ?",
            (latest["id"],),
        )
        changes = cur.fetchall()
        before = _snapshot(cur, did)
        drifted = [c["card_name"] for c in changes
                   if before.get((c["card_name"], c["section"]), 0)
                   != c["qty_after"]]
        if drifted:
            raise DeckError(
                f"cannot undo revision #{latest['id']}: the deck no longer "
                f"matches it ({', '.join(drifted)} changed since)"
            )
        for c in changes:
            _set_section_quantity(cur, did, c["card_name"], c["section"],
                                  c["qty_before"])
        revision_id, inverse = _record_revision(
            cur, did, "undo", before,
            note=f"undo of #{latest['id']} ({latest['action']})")
        cur.execute("UPDATE decks SET updated_at = ? WHERE id = ?",
                    (_now(), did))
        result = {
            "deck": _deck_name(cur, did),
            "action": "undo",
            "revision_id": revision_id,
            "undone": dict(latest),
            **_split_changes(inverse),
        }
        conn.commit()
        return result
    except BaseException:
        conn.rollback()
        raise
    finally:
        conn.close()


def deck_history(
    name: str, folder: Optional[str] = None, limit: int = 20,
) -> list[dict]:
    """The deck's revisions, newest first: [{id, at, action, note,
    changes: [{card, section, before, after}]}]."""
    limit = max(1, min(limit, 500))
    conn = _ro()
    try:
        cur = conn.cursor()
        did = _deck_id(cur, name, folder)
        cur.execute(
            "SELECT id, at, action, note FROM deck_revisions "
            "WHERE deck_id = ? ORDER BY id DESC LIMIT ?",
            (did, limit),
        )
        revisions = [dict(r) for r in cur.fetchall()]
        for rev in revisions:
            cur.execute(
                "SELECT card_name, section, qty_before, qty_after "
                "FROM deck_changes WHERE revision_id = ?",
                (rev["id"],),
            )
            rows = [{"card": c["card_name"], "section": c["section"],
                     "before": c["qty_before"], "after": c["qty_after"]}
                    for c in cur.fetchall()]
            rev["changes"] = sorted(
                rows, key=lambda c: _change_order((c["card"], c["section"])))
        return revisions
    finally:
        conn.close()
