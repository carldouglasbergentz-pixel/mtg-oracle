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

from mtg_oracle.queries import BUSY_TIMEOUT_S, RESTRICTED_MEANS_NO_COMMANDER
from mtg_oracle.queries import resolve_card_name as _resolve_canonical
from mtg_oracle.queries import flag_template_vars as _flag_template_vars
from mtg_oracle.queries import fold_format as _fold_format
from mtg_oracle.queries import get_card_points as _get_card_points
from mtg_oracle.queries import resolve_format as _resolve_format

DB_PATH = Path(__file__).parent.parent / "data" / "mtg.db"

# Formats that enforce singleton (max 1 of each card except basic lands and
# cards whose oracle text explicitly opts out).
#
# Split in two because `decks.format` is free text the user typed. Anything
# that folds to a Scryfall legality key is matched on the key, so every
# spelling of it works at once ('EDH', 'edh', 'Duel Commander', '1v1
# commander' all land on a key). The rest are community formats Scryfall
# doesn't track, matched on the folded string.
SINGLETON_LEGALITY_FORMATS: frozenset[str] = frozenset({
    "commander", "duel", "oathbreaker",
    "brawl", "standardbrawl", "competitivebrawl",
    "gladiator", "paupercommander", "predh",
    "tlr",  # Tiny Leaders: Reborn — 50-card singleton, MV <= 3
})

# Folded (no spaces / hyphens), because that's what `fold_format` produces.
# 'canlander' is the abbreviation people actually type and was missing
# before, so Canadian Highlander decks got no singleton enforcement at all.
SINGLETON_COMMUNITY_FORMATS: frozenset[str] = frozenset({
    "highlander", "canadianhighlander", "canlander",
    "australianhighlander", "ozziehighlander", "ozhighlander",
    "leviathan",
})


# Phrase Wizards uses on cards that override singleton (Relentless Rats,
# Shadowborn Apostle, Dragon's Approach, Persistent Petitioners, Rat Colony,
# Slime Against Humanity, Hare Apparent, Templar Knight, Nazgûl, ...).
_UNLIMITED_PHRASE = "a deck can have any number of cards named"


class DeckError(ValueError):
    """Raised for deck-layer validation errors — surfaces to UI."""


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


# --- Card-name resolution --------------------------------------------

# Re-exported from queries.resolve_card_name so deck operations and the
# card-lookup path share the exact same matching rules.
resolve_card_name = _resolve_canonical


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
    """List decks; optional folder filter. Returns deck + folder + card count
    + commander_ci (sorted list of letters, [] for colorless commander, None
    when no commander is set on the deck)."""
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
        rows = [dict(r) for r in cur.fetchall()]
        for row in rows:
            row["commander_ci"] = _deck_color_identity_inner(cur, row["id"])
        return rows
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
                   c.color_identity, c.power, c.toughness, c.oracle_text
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


# --- Format-aware deck metadata --------------------------------------

def _is_singleton_format(fmt: Optional[str]) -> bool:
    """True when `decks.format` names a format with a one-copy rule.

    Resolves through the same alias table the search language uses, so a
    format spelled any of the ways people spell it lands on one answer.
    A custom format carries its own `singleton` flag (from its JSON); the
    community set is the fallback for formats with no definition file.
    """
    if not fmt:
        return False
    info = _resolve_format(fmt)
    if info is not None:
        if info["custom"]:
            return bool(info["singleton"])
        return info["key"] in SINGLETON_LEGALITY_FORMATS
    return _fold_format(fmt) in SINGLETON_COMMUNITY_FORMATS


def _is_basic_land(type_line: Optional[str]) -> bool:
    if not type_line:
        return False
    return "Basic" in type_line and "Land" in type_line


def _allows_unlimited_copies(oracle_text: Optional[str]) -> bool:
    if not oracle_text:
        return False
    return _UNLIMITED_PHRASE in oracle_text.lower()


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
    is_sideboard: bool,
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
            # Copy restriction (Vintage, Old School): one only. In a
            # singleton format the singleton rule already caps it and gives
            # the clearer message, so don't pre-empt it.
            cur.execute(
                "SELECT COALESCE(SUM(quantity), 0) FROM deck_cards "
                "WHERE deck_id = ? AND card_name = ? COLLATE NOCASE "
                "  AND is_sideboard = ?",
                (deck_id, canonical, int(is_sideboard)),
            )
            if cur.fetchone()[0] + quantity > 1:
                raise DeckError(
                    f"{canonical!r} is restricted in {label} (limit 1 copy). "
                    f"Pass force=True to override."
                )


def _assert_points_fit(
    deck_name: str,
    folder: Optional[str],
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
    """
    if is_sideboard:
        return
    if not fmt_info or fmt_info.get("points_budget") is None:
        return
    card_pts = _get_card_points(fmt_info["key"]).get(canonical)
    if not card_pts:
        return
    spent = deck_points(deck_name, folder)
    already = spent["total"] if spent else 0
    budget = fmt_info["points_budget"]
    cost = card_pts * quantity
    if already + cost > budget:
        raise DeckError(
            f"{canonical!r} costs {card_pts} point(s) in {fmt_info['label']}; "
            f"the deck is at {already}/{budget} and would go to "
            f"{already + cost}. Pass force=True to override."
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
    resolve: bool = True,
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
      named ...". Sideboard rows do not interact with singleton checks
      against main-deck rows.
    """
    if quantity < 1:
        raise DeckError("quantity must be >= 1")
    canonical = resolve_card_name(card_name) if resolve else card_name
    if not canonical:
        raise DeckError(f"card not found: {card_name!r}")
    conn = _rw()
    try:
        cur = conn.cursor()
        did = _deck_id(cur, deck_name, folder)

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
                is_commander=is_commander, is_sideboard=is_sideboard,
                quantity=quantity, singleton=singleton,
            )

        # Singleton validation. Sideboard cards are validated against
        # other sideboard rows; main vs. sideboard are independent.
        # Runs BEFORE the points check: a duplicate pointed card deserves
        # "you already have one" rather than a budget arithmetic error.
        if (not force and not is_commander and singleton
                and not _is_basic_land(card_type)
                and not _allows_unlimited_copies(card_oracle)):
            cur.execute(
                """
                SELECT COALESCE(SUM(quantity), 0) FROM deck_cards
                WHERE deck_id = ? AND card_name = ? COLLATE NOCASE
                  AND is_sideboard = ?
                """,
                (did, canonical, int(is_sideboard)),
            )
            current = cur.fetchone()[0]
            if current + quantity > 1:
                section = "sideboard" if is_sideboard else "main deck"
                raise DeckError(
                    f"singleton format ({fmt!r}): {canonical!r} would have "
                    f"{current + quantity} copies in the {section} "
                    f"(limit is 1; basics and 'any number' cards are exempt). "
                    f"Pass force=True to override."
                )

        if not force:
            _assert_points_fit(
                deck_name, folder, canonical, fmt_info,
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


def set_commander(
    deck_name: str,
    card_name: str,
    folder: Optional[str] = None,
    unset: bool = False,
    force: bool = False,
) -> tuple[str, str, Optional[str]]:
    """Promote a card to commander, or demote one with `unset=True`.

    Returns (canonical_name, action, format_set) where `action` is one of:
        'promoted'   — existing main/sideboard row flipped to commander
        'added'      — card wasn't in deck; inserted as a fresh commander row
        'unchanged'  — card already a commander row with quantity 1
        'demoted'    — commander row flipped back to main (unset path)
    `format_set` is the new deck format if it was auto-set as part of the
    call (e.g. 'commander'), or None when no format change happened.

    Promotion rules:
    - Forces quantity to 1 and is_sideboard to 0 on the chosen row.
    - When multiple rows exist for the same card (rare, e.g. main + sideboard),
      the main-deck row is preferred.
    - Multiple commanders are allowed (Partner / Background / Friends Forever)
      — promoting a 2nd card simply adds another is_commander=1 row.
    - On promote/add, if `decks.format` is currently NULL, it's auto-set to
      'commander' so format-aware behavior (CI filter on search, singleton
      on add) starts working immediately. An already-set format is left
      alone — the user knows what they're doing.
    - Format legality is checked on promote and add, with `force=True` to
      override. A card can be perfectly legal in the 99 and still banned as
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
            conn.commit()
            # Demotion never touches `format` — preserves the user's intent
            # (the deck might still be a commander deck, just with a
            # different commander incoming).
            return canonical, "demoted", None

        # Promotion: pick the best existing row to flip, preferring a clean
        # main-deck row (is_sideboard=0). is_commander=1 rows sort first
        # within is_sideboard=0 so we detect the "already commander" case.
        cur.execute(
            "SELECT id, quantity, is_commander, is_sideboard FROM deck_cards "
            "WHERE deck_id = ? AND card_name = ? COLLATE NOCASE "
            "ORDER BY is_sideboard ASC, is_commander DESC",
            (did, canonical),
        )
        rows = cur.fetchall()

        # Same legality gate `add` uses. Promoting a row in place used to
        # skip it entirely, which made `commander <card>` a way to put a
        # banned card in the command zone.
        if not force:
            cur.execute("SELECT format FROM decks WHERE id = ?", (did,))
            fmt = cur.fetchone()["format"]
            fmt_info = _resolve_format(fmt)
            # quantity is the *delta*: promoting flips a row in place and adds
            # no copy, so a restricted card already in a Vintage deck must not
            # trip the one-copy check against itself.
            _assert_legal_in_format(
                cur, did, canonical, fmt_info,
                is_commander=True, is_sideboard=False,
                quantity=0 if rows else 1,
                singleton=_is_singleton_format(fmt),
            )
            if not rows:
                # Only a fresh insert changes the points total; promoting a
                # card already in the deck is already paid for.
                _assert_points_fit(
                    deck_name, folder, canonical, fmt_info,
                    quantity=1, is_sideboard=False,
                )

        if not rows:
            cur.execute(
                "INSERT INTO deck_cards "
                "(deck_id, card_name, quantity, is_commander, is_sideboard, added_at) "
                "VALUES (?, ?, 1, 1, 0, ?)",
                (did, canonical, _now()),
            )
            format_set = _auto_set_commander_format(cur, did)
            cur.execute("UPDATE decks SET updated_at = ? WHERE id = ?", (_now(), did))
            conn.commit()
            return canonical, "added", format_set

        primary = rows[0]
        if primary["is_commander"] and not primary["is_sideboard"] and primary["quantity"] == 1:
            # Already a clean commander row — but format may still be NULL
            # if the user manually inserted the row before this verb existed.
            format_set = _auto_set_commander_format(cur, did)
            if format_set:
                conn.commit()
            return canonical, "unchanged", format_set
        cur.execute(
            "UPDATE deck_cards "
            "SET is_commander = 1, is_sideboard = 0, quantity = 1 "
            "WHERE id = ?",
            (primary["id"],),
        )
        format_set = _auto_set_commander_format(cur, did)
        cur.execute("UPDATE decks SET updated_at = ? WHERE id = ?", (_now(), did))
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
    if quantity is not None and quantity < 1:
        raise DeckError("quantity must be >= 1")
    card_name = resolve_card_name(card_name) or card_name
    conn = _rw()
    try:
        cur = conn.cursor()
        did = _deck_id(cur, deck_name, folder)

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
    if limit < 1 or limit > 500:
        limit = 50
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
    """Load parsed deckstring rows into an EXISTING deck.

    `parsed` is a list of {name, quantity, section} dicts, where `section`
    is one of 'main', 'sideboard', 'commander', 'maybeboard'. Maybeboard
    rows are skipped (no table for them yet).

    `force=True` (the default) is what both import paths want: a pasted
    list is loaded verbatim, because singleton / CI checks would reject
    legitimate decks whose commander line happens to come after the
    non-commander cards.

    Returns a summary: rows added, total copies added, names that didn't
    resolve to a card, and rows the deck layer rejected. Nothing is ever
    dropped silently.
    """
    added = 0
    copies = 0
    unresolved: list[str] = []
    rejected: list[tuple[str, str]] = []
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
                force=force,
            )
            added += 1
            copies += row["quantity"]
        except DeckError as e:
            if "card not found" in str(e):
                unresolved.append(row["name"])
            elif force:
                # With force=True every validation rule is skipped, so a
                # DeckError here is structural — the deck vanished, the name
                # is ambiguous. Collecting it would turn one real failure
                # into N identical "rejected" lines and a success exit.
                raise
            else:
                rejected.append((row["name"], str(e)))
    return {
        "added": added,
        "copies": copies,
        "unresolved": unresolved,
        "rejected": rejected,
        "total_input": len(parsed),
    }


def import_deck(
    name: str,
    parsed: list[dict],
    folder: Optional[str] = None,
    format: Optional[str] = None,
) -> dict:
    """Create a new deck and load it from parsed rows."""
    create_deck(name, folder=folder, format=format)
    return load_parsed_into_deck(name, parsed, folder=folder)
