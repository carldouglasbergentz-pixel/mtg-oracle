"""Query functions over data/mtg.db.

All SQL is parameterized. Callers should not interpolate user input
into queries themselves — pass it as arguments here.

Unknown cards / rules / combos return None. Searches return [] on no
matches. No exceptions are raised for normal "not found" cases; the
caller decides how to present absence.
"""
from __future__ import annotations

import json
import re
import sqlite3
import unicodedata
from pathlib import Path
from typing import Optional

DB_PATH = Path(__file__).parent.parent / "data" / "mtg.db"

# Seconds to wait for a lock before giving up. `sync.py` writes in long
# transactions (104k combos in one go), and the TUI can run a sync in the
# background while the user keeps querying — so every connection waits
# rather than failing on the first contended read.
BUSY_TIMEOUT_S = 15.0


def like_literal(value: str) -> str:
    """`value` with LIKE's wildcards taken literally — `%` and `_` in a
    card name or search term must not match everything. Use with
    `LIKE ? ESCAPE '!'`; not backslash, which would need escaping again in
    every Python and SQL literal on the way."""
    return value.replace("!", "!!").replace("%", "!%").replace("_", "!_")


# --- Format legality ----------------------------------------------------

# The keys Scryfall's `legalities` object uses, i.e. the values that can
# appear in `card_legalities.format`. Hardcoded as a validation aid so a
# typo (`f:brawll`) is an error instead of an empty result set that reads
# as "nothing is legal". `SELECT DISTINCT format FROM card_legalities` is
# the source of truth if Scryfall ever adds one.
LEGALITY_FORMATS: frozenset[str] = frozenset({
    "alchemy", "brawl", "commander", "competitivebrawl", "duel", "future",
    "gladiator", "historic", "legacy", "modern", "oathbreaker", "oldschool",
    "pauper", "paupercommander", "penny", "pioneer", "predh", "premodern",
    "standard", "standardbrawl", "timeless", "tlr", "vintage",
})

# What people type -> Scryfall's key. Spaces, hyphens and underscores are
# folded away first, so 'Competitive Brawl' and 'competitive-brawl' both
# land on 'competitivebrawl'.
_FORMAT_ALIAS = {
    "edh": "commander",
    "duelcommander": "duel",          # Scryfall's `duel` IS Duel Commander
    "1v1commander": "duel",
    "historicbrawl": "brawl",         # Arena renamed Historic Brawl to Brawl
    "pdh": "paupercommander",
    "pennydreadful": "penny",
    "cbrawl": "competitivebrawl",
    "tinyleaders": "tlr",             # `tlr` is Tiny Leaders: Reborn
    "tinyleadersreborn": "tlr",
}

# `restricted` does not mean the same thing in every format:
#   vintage / oldschool  -> you may play ONE copy
#   duel / tlr           -> the card may be in the deck but NOT as commander
# Both are singleton-ish formats where "one copy" is already implied, which
# is why Scryfall reuses the same status word for two different rules.
RESTRICTED_MEANS_NO_COMMANDER: frozenset[str] = frozenset({"duel", "tlr"})

# Formats that enforce singleton (max 1 of each card except basic lands and
# cards whose oracle text explicitly opts out).
#
# Split in two because a deck's format is free text the user typed. Anything
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


def fold_format(raw: str) -> str:
    """Lowercase, strip spaces/hyphens/underscores, then resolve aliases."""
    folded = re.sub(r"[\s_-]+", "", raw.strip().lower())
    return _FORMAT_ALIAS.get(folded, folded)


def normalize_format(raw: str) -> str:
    """Fold user input to a `card_legalities.format` key. Raises ValueError.

    Strict — for the search language, where a bad format name should be a
    visible error. Custom formats resolve to the pool they inherit, so
    `f:canlander` compiles to Vintage's card pool.
    """
    key = fold_format(raw)
    if key in LEGALITY_FORMATS:
        return key
    custom = get_custom_formats().get(key)
    if custom and custom["derives_from"] in LEGALITY_FORMATS:
        return custom["derives_from"]
    valid = sorted(LEGALITY_FORMATS | set(get_custom_formats()))
    raise ValueError(f"unknown format: {raw!r}. Valid: {', '.join(valid)}")


# --- Custom (community) formats -----------------------------------------

# Cached because it's read on every `f:` term compile and every deck
# validation, and it changes only when `sync.py --only formats` runs.
# The TUI clears it after a sync; a fresh process starts empty anyway.
_CUSTOM_FORMATS_CACHE: Optional[dict[str, dict]] = None


def clear_format_cache() -> None:
    """Drop the cached custom-format table. Call after loading formats."""
    global _CUSTOM_FORMATS_CACHE
    _CUSTOM_FORMATS_CACHE = None


def get_custom_formats() -> dict[str, dict]:
    """Community formats keyed by format key *and* by every alias.

    Returns {} when the table doesn't exist yet or the DB is missing — a
    custom format is an enhancement, never a prerequisite for querying.
    """
    global _CUSTOM_FORMATS_CACHE
    if _CUSTOM_FORMATS_CACHE is not None:
        return _CUSTOM_FORMATS_CACHE
    formats: dict[str, dict] = {}
    if DB_PATH.exists():
        conn = sqlite3.connect(
            f"file:{DB_PATH}?mode=ro", uri=True, timeout=BUSY_TIMEOUT_S,
        )
        conn.row_factory = sqlite3.Row
        try:
            rows = conn.execute(
                "SELECT format, name, aliases, derives_from, points_budget, "
                "singleton, source_url, list_current_as_of "
                "FROM custom_formats"
            ).fetchall()
        except sqlite3.OperationalError:
            rows = []  # table not created yet
        finally:
            conn.close()
        for row in rows:
            spec = dict(row)
            try:
                aliases = json.loads(spec.get("aliases") or "[]")
            except json.JSONDecodeError:
                aliases = []
            spec["aliases"] = aliases
            formats[spec["format"]] = spec
            for alias in aliases:
                formats.setdefault(fold_format(alias), spec)
    _CUSTOM_FORMATS_CACHE = formats
    return formats


def resolve_format(raw: Optional[str]) -> Optional[dict]:
    """Everything a caller needs to know about a format name.

    Returns None for an unrecognised name, otherwise:
        key           canonical key ('commander', 'canadianhighlander')
        label         human name ('commander', 'Canadian Highlander')
        legality_key  `card_legalities.format` to check against, or None
        points_budget per-deck points cap, or None
        singleton     True when the format allows one copy of each card
        custom        True for a `custom_formats` row
    """
    if not raw:
        return None
    key = fold_format(raw)
    if key in LEGALITY_FORMATS:
        return {
            "key": key, "label": key, "legality_key": key,
            "points_budget": None,
            "singleton": key in SINGLETON_LEGALITY_FORMATS,
            "custom": False,
        }
    spec = get_custom_formats().get(key)
    if not spec:
        return None
    return {
        "key": spec["format"],
        "label": spec["name"],
        "legality_key": spec["derives_from"],
        "points_budget": spec["points_budget"],
        "singleton": bool(spec["singleton"]),
        "custom": True,
    }


def is_singleton_format(raw: Optional[str]) -> bool:
    """True when a format name carries a one-copy rule.

    Resolves through the same alias table the search language uses, so a
    format spelled any of the ways people spell it lands on one answer. A
    defined format carries the answer itself (`resolve_format`); the
    community set is the fallback for formats with no definition file.
    """
    if not raw:
        return False
    info = resolve_format(raw)
    if info is not None:
        return bool(info["singleton"])
    return fold_format(raw) in SINGLETON_COMMUNITY_FORMATS


def get_card_points(format_key: str) -> dict[str, int]:
    """card_name -> points for one custom format ({} if it has no list)."""
    if not format_key or not DB_PATH.exists():
        return {}
    conn = sqlite3.connect(
        f"file:{DB_PATH}?mode=ro", uri=True, timeout=BUSY_TIMEOUT_S,
    )
    try:
        rows = conn.execute(
            "SELECT card_name, points FROM custom_format_points WHERE format = ?",
            (format_key,),
        ).fetchall()
    except sqlite3.OperationalError:
        return {}
    finally:
        conn.close()
    return {name: pts for name, pts in rows}


# Spellbook combo step text often references a card slot that
# `combo_cards` doesn't enumerate ("the affinity permanent", "your
# commander", "any X creature/permanent/spell"). Those combos look
# smaller than they actually are. We detect the template variable so
# the renderer can suffix the card count with `+` and stop pretending
# the listed cards are the full set. Detection is a vetted whitelist
# rather than a broad "any \w+" regex — flavor text and ordinary
# English would produce too many false positives otherwise.
_TEMPLATE_VAR_RE = re.compile(
    r"\bthe affinity\b"
    r"|\byour commander\b"
    r"|\bany \w+\s+(creature|permanent|spell)\b"
    r"|\bnoncreature spell\b"
    r"|\ba \w+ (creature|permanent|spell) "
    r"(you control|in your hand|in your graveyard|on the battlefield)\b",
    re.IGNORECASE,
)


def flag_template_vars(rows: list[dict], cur: sqlite3.Cursor) -> list[dict]:
    """Set `has_template_vars` on each row by scanning its combo steps.

    Public helper — used by `decks.combos_in_deck` and the local combo
    finders. Only meaningful for Spellbook-sourced combos; user combos
    store free text in `description` and don't have a steps sub-table,
    so they always get `has_template_vars = False`.

    Modifies rows in place; returns the same list for fluency.
    """
    spellbook_ids = [
        r["id"] for r in rows
        if r.get("source") in (None, "spellbook")
    ]
    flagged: set = set()
    if spellbook_ids:
        placeholders = ",".join("?" * len(spellbook_ids))
        cur.execute(
            f"SELECT combo_id, text FROM combo_steps "
            f"WHERE combo_id IN ({placeholders})",
            spellbook_ids,
        )
        for combo_id, text in cur.fetchall():
            if combo_id in flagged:
                continue
            if _TEMPLATE_VAR_RE.search(text or ""):
                flagged.add(combo_id)
    for r in rows:
        r["has_template_vars"] = r["id"] in flagged
    return rows


_LIGATURE_MAP = str.maketrans({
    # Ligatures that NFKD doesn't decompose.
    "Æ": "AE", "æ": "ae",
    "Œ": "OE", "œ": "oe",
    "ß": "ss",
    "Þ": "Th", "þ": "th",
    "Ð": "D", "ð": "d",
    "Ø": "O", "ø": "o",
})


# Apostrophes and quotes — straight, curly, backtick — get stripped so users
# don't have to type them ("lim-duls vault" matches "Lim-Dûl's Vault",
# "kongming, sleeping dragon" matches 'Kongming, "Sleeping Dragon"').
_APOSTROPHE_DROP = str.maketrans({
    "'": None, "’": None, "‘": None, "`": None,
    '"': None, "“": None, "”": None,
})


def _ascii_fold(s: str) -> str:
    """Strip diacritics + map common ligatures + drop apostrophes so loosely
    typed input matches the canonical card name.

    Examples:
        "Lórien Revealed"  -> "lorien revealed"
        "Æther Vial"       -> "aether vial"
        "Lim-Dûl's Vault"  -> "lim-duls vault"
        'Kongming, "Sleeping Dragon"' -> "kongming, sleeping dragon"
    """
    if not s:
        return ""
    folded = s.translate(_LIGATURE_MAP).translate(_APOSTROPHE_DROP)
    decomposed = unicodedata.normalize("NFKD", folded)
    return "".join(ch for ch in decomposed if not unicodedata.combining(ch)).lower()


def resolve_card_name(raw: str) -> Optional[str]:
    """Find the canonical card name in the DB. Tolerant of:

    - case differences ("sol ring" -> "Sol Ring")
    - alternative `//` separators on DFC/split cards
      ("Fire/Ice", "fire // ice", "fire//ice" -> "Fire // Ice")
    - front-face-only DFC names ("Delver of Secrets" -> "Delver of
      Secrets // Insectile Aberration")
    - ASCII fold-down: a name typed without diacritics matches a card
      that has them ("lorien revealed" -> "Lórien Revealed",
      "aether vial" -> "Aether Vial").

    Returns the canonical name, or None if no match.
    """
    if not raw:
        return None
    name = raw.strip()
    if not name:
        return None

    if not DB_PATH.exists():
        return None
    conn = sqlite3.connect(
        f"file:{DB_PATH}?mode=ro", uri=True, timeout=BUSY_TIMEOUT_S,
    )
    try:
        cur = conn.cursor()
        # 1) Exact match (case-insensitive). COLLATE NOCASE is ASCII-only,
        # which is fine for the common case.
        cur.execute("SELECT name FROM cards WHERE name = ? COLLATE NOCASE", (name,))
        row = cur.fetchone()
        if row:
            return row[0]

        # 2) Normalize alternate `//` separators that humans use.
        for sep in (" // ", "//", "/"):
            if sep in name:
                parts = [p.strip() for p in name.split(sep)]
                normalized = " // ".join(parts)
                cur.execute(
                    "SELECT name FROM cards WHERE name = ? COLLATE NOCASE",
                    (normalized,),
                )
                row = cur.fetchone()
                if row:
                    return row[0]
                break

        # 3) Front-face-only DFC: try `<name> // %` prefix.
        cur.execute(
            "SELECT name FROM cards WHERE name LIKE ? ESCAPE '!' COLLATE NOCASE "
            "ORDER BY LENGTH(name) LIMIT 1",
            (f"{like_literal(name)} // %",),
        )
        row = cur.fetchone()
        if row:
            return row[0]

        # 4) ASCII fold-down fallback: linear scan over every name. No SQL
        # first-letter prefilter — SQLite's LOWER() is ASCII-only, so one
        # never matched 'Éomer' for input 'eomer'.
        target = _ascii_fold(name)
        cur.execute("SELECT name FROM cards")
        for (cn,) in cur.fetchall():
            if _ascii_fold(cn) == target:
                return cn
            # Front-face DFC: also fold the part before " // ".
            if " // " in cn:
                front = cn.split(" // ", 1)[0]
                if _ascii_fold(front) == target:
                    return cn

        return None
    finally:
        conn.close()


# --- Connection --------------------------------------------------------

def _clamp_limit(limit: int, maximum: int) -> int:
    """Clamp a caller's page size. Resetting an oversized one to a small
    default instead made rows past it unreachable by paging."""
    return max(1, min(limit, maximum))


def _connect() -> sqlite3.Connection:
    """Open a read-only connection to the knowledge-base DB."""
    if not DB_PATH.exists():
        raise FileNotFoundError(
            f"MTG Oracle database not found at {DB_PATH}. "
            "Run `python scripts/init_db.py` then `python scripts/sync.py`."
        )
    # SQLite URI mode lets us request read-only; keeps accidental writes impossible.
    uri = f"file:{DB_PATH}?mode=ro"
    # A sync holds a write transaction for tens of seconds. Without a busy
    # timeout, any read during that window fails instantly with
    # "database is locked" instead of just waiting its turn.
    conn = sqlite3.connect(uri, uri=True, timeout=BUSY_TIMEOUT_S)
    conn.row_factory = sqlite3.Row
    return conn


def _rows_to_dicts(rows) -> list[dict]:
    return [dict(r) for r in rows]


# --- Cards --------------------------------------------------------------

# What `mtg_oracle.roles` needs to classify a card and price it. Kept lean on
# purpose: `get_card` also fetches tags, abilities, rulings and combos, which
# is four extra queries per card — fine for one card on screen, wrong for a
# hundred-card deck.
CARD_FACT_COLUMNS = (
    "name", "mana_cost", "mana_value", "type_line", "oracle_text",
    "color_identity", "colors", "layout", "card_faces", "rarity",
    "edhrec_rank", "power", "toughness",
)


def get_card_facts(names) -> dict[str, dict]:
    """Bulk-fetch the columns needed to classify cards, keyed by INPUT name.

    Names are resolved tolerantly (`resolve_card_name`), so the returned dict
    is keyed by what the caller asked for while the row holds the canonical
    name. Names that don't resolve are simply absent — the caller decides
    whether a miss is fatal.
    """
    wanted = list(dict.fromkeys(names))
    if not wanted:
        return {}
    resolved = {n: resolve_card_name(n) for n in wanted}
    canonical = sorted({c for c in resolved.values() if c})
    if not canonical:
        return {}

    cols = ", ".join(CARD_FACT_COLUMNS)
    rows: dict[str, dict] = {}
    conn = _connect()
    try:
        cur = conn.cursor()
        # Chunked so a very large deck list can't blow SQLite's variable limit.
        for i in range(0, len(canonical), 400):
            chunk = canonical[i:i + 400]
            marks = ", ".join("?" * len(chunk))
            cur.execute(
                f"SELECT {cols} FROM cards "
                f"WHERE name COLLATE NOCASE IN ({marks})", chunk)
            for row in cur.fetchall():
                rows[row["name"].lower()] = dict(row)
    finally:
        conn.close()

    out = {}
    for asked, canon in resolved.items():
        if canon and canon.lower() in rows:
            out[asked] = rows[canon.lower()]
    return out


def get_oracle_tags(names) -> dict[str, frozenset[str]]:
    """Bulk-fetch Scryfall Tagger oracle tags, keyed by INPUT name.

    Mirrors `get_card_facts`: tolerant name resolution, chunked, absent for
    names that don't resolve or carry no tags. `mtg_oracle.roles` imports
    nothing from this package, so the tags have to be fetched here and passed
    in to `roles.classify(card, tags=...)`.
    """
    wanted = list(dict.fromkeys(names))
    if not wanted:
        return {}
    resolved = {n: resolve_card_name(n) for n in wanted}
    canonical = sorted({c for c in resolved.values() if c})
    if not canonical:
        return {}

    tags: dict[str, set[str]] = {}
    conn = _connect()
    try:
        cur = conn.cursor()
        for i in range(0, len(canonical), 400):
            chunk = canonical[i:i + 400]
            marks = ", ".join("?" * len(chunk))
            cur.execute(
                f"SELECT card_name, tag FROM card_oracle_tags "
                f"WHERE card_name COLLATE NOCASE IN ({marks})", chunk)
            for row in cur.fetchall():
                tags.setdefault(row["card_name"].lower(), set()).add(row["tag"])
    except sqlite3.OperationalError:
        # No card_oracle_tags table yet: an un-synced database still
        # classifies, it just falls back to the text rules everywhere.
        return {}
    finally:
        conn.close()

    out = {}
    for asked, canon in resolved.items():
        found = tags.get((canon or "").lower())
        if found:
            out[asked] = frozenset(found)
    return out


def get_card(
    name: str,
    restrict_to_ci: Optional[list[str]] = None,
) -> Optional[dict]:
    """Return the full profile of a single card by exact name, or None.

    Includes: base fields, tags grouped by category, parsed abilities,
    rulings (chronological), and the top combos the card appears in.

    `restrict_to_ci`: when set, the embedded combos list is pre-filtered
    so each combo's color identity is a subset of the given letter list
    (i.e. legal in a deck with that commander color identity). `None`
    means no filter; `[]` means colorless only.
    """
    if not name:
        return None
    # resolve_card_name handles case differences, `//` variants, front-face-only
    # DFC names, and ASCII fold-down ("lorien revealed" -> "Lórien Revealed").
    canonical_input = resolve_card_name(name) or name
    conn = _connect()
    try:
        cur = conn.cursor()
        cur.execute(
            "SELECT name, oracle_id, oracle_text, mana_cost, type_line, layout, "
            "card_faces, games, reserved, edhrec_rank "
            "FROM cards WHERE name = ?",
            (canonical_input,),
        )
        row = cur.fetchone()
        if not row:
            return None
        card: dict = dict(row)
        canonical = card["name"]
        if card.get("card_faces"):
            try:
                card["card_faces"] = json.loads(card["card_faces"])
            except json.JSONDecodeError:
                pass  # leave as raw string if malformed

        cur.execute(
            "SELECT tag, category, source FROM card_tags WHERE card_name = ? "
            "ORDER BY category, tag",
            (canonical,),
        )
        tags_by_cat: dict[str, list[str]] = {}
        for tag_row in cur.fetchall():
            tags_by_cat.setdefault(tag_row["category"], []).append(tag_row["tag"])
        card["tags"] = tags_by_cat

        cur.execute(
            "SELECT ability_index, ability_type, cost, effect, "
            "has_target, produces_mana, is_mana_ability, raw_text "
            "FROM card_abilities WHERE card_name = ? ORDER BY ability_index",
            (canonical,),
        )
        card["abilities"] = _rows_to_dicts(cur.fetchall())

        cur.execute(
            "SELECT date, text FROM rulings WHERE card_name = ? ORDER BY date",
            (canonical,),
        )
        card["rulings"] = _rows_to_dicts(cur.fetchall())

        # Legality: absence of a row means not legal, so only the formats
        # worth naming come back. Split by status because "banned in
        # Legacy" and "not in the Standard pool" are different facts —
        # and `restricted` is split again, because in Duel Commander and
        # Tiny Leaders it means "not as your commander", not "one copy".
        cur.execute(
            "SELECT format, status FROM card_legalities WHERE card_name = ? "
            "ORDER BY format",
            (canonical,),
        )
        legal_by_status: dict[str, list[str]] = {}
        for row in cur.fetchall():
            status = row["status"]
            if (status == "restricted"
                    and row["format"] in RESTRICTED_MEANS_NO_COMMANDER):
                status = "no_commander"
            legal_by_status.setdefault(status, []).append(row["format"])
        card["legalities"] = legal_by_status

        # Build an optional CI subset filter — combos.color_identity is
        # stored as a contiguous letter string ('WBG', 'GU', '' for colorless),
        # so the subset check excludes any combo containing a letter that
        # isn't in restrict_to_ci.
        ci_where, ci_params = "", []
        if restrict_to_ci is not None:
            allowed = set(restrict_to_ci)
            excluded = [ch for ch in "WUBRG" if ch not in allowed]
            if excluded:
                ci_where = " AND " + " AND ".join(
                    "(c.color_identity IS NULL OR c.color_identity NOT LIKE ?)"
                    for _ in excluded
                )
                ci_params = [f"%{ch}%" for ch in excluded]
        cur.execute(
            "SELECT c.id, c.color_identity, c.name AS combo_name, "
            "(SELECT COUNT(*) FROM combo_cards WHERE combo_id=c.id) AS card_count, "
            "(SELECT GROUP_CONCAT(card_name, ' + ') "
            " FROM combo_cards WHERE combo_id=c.id) AS cards, "
            "'spellbook' AS source "
            "FROM combos c JOIN combo_cards cc ON cc.combo_id = c.id "
            "WHERE cc.card_name = ?" + ci_where + " "
            "UNION ALL "
            "SELECT c.id, c.color_identity, c.name AS combo_name, "
            "(SELECT COUNT(*) FROM user_combo_cards WHERE combo_id=c.id) AS card_count, "
            "(SELECT GROUP_CONCAT(card_name, ' + ') "
            " FROM user_combo_cards WHERE combo_id=c.id) AS cards, "
            "'user' AS source "
            "FROM user_combos c JOIN user_combo_cards cc ON cc.combo_id = c.id "
            "WHERE cc.card_name = ? COLLATE NOCASE" + ci_where + " "
            "ORDER BY card_count ASC, id "
            "LIMIT 10",
            tuple([canonical] + ci_params + [canonical] + ci_params),
        )
        card["combos"] = flag_template_vars(_rows_to_dicts(cur.fetchall()), cur)
        card["combos_filtered_by_ci"] = (
            "".join(restrict_to_ci) if restrict_to_ci else "C"
        ) if restrict_to_ci is not None else None

        # A correction names a card the way people say it — often one face
        # ('Emeritus of Ideation' for 'Emeritus of Ideation // Ancestral
        # Recall') — so the full name and each face all count as a match.
        names = [canonical] + (canonical.split(" // ") if " // " in canonical else [])
        cur.execute(
            "SELECT id, topic, correct_claim, source FROM corrections "
            "WHERE " + " OR ".join([_RELATES_TO_NAMES] * len(names)) + " "
            "ORDER BY added_at DESC",
            [_relates_to_pattern(n) for n in names],
        )
        card["corrections"] = _rows_to_dicts(cur.fetchall())

        return card
    finally:
        conn.close()


# `relates_to` is a JSON array of names. Matching the quoted element keeps a
# short name from matching inside a longer one ('Strangle' in
# 'Strangleroot Geist'), which a bare substring match did.
_RELATES_TO_NAMES = "relates_to LIKE ? ESCAPE '!' COLLATE NOCASE"


def _relates_to_pattern(name: str) -> str:
    return f"%{like_literal(json.dumps(name, ensure_ascii=False))}%"


def get_rulings(card_name: str) -> list[dict]:
    """All rulings for a card, chronologically. Case-insensitive name match."""
    if not card_name:
        return []
    conn = _connect()
    try:
        cur = conn.cursor()
        cur.execute(
            "SELECT date, text FROM rulings WHERE card_name = ? COLLATE NOCASE "
            "ORDER BY date",
            (resolve_card_name(card_name) or card_name,),
        )
        return _rows_to_dicts(cur.fetchall())
    finally:
        conn.close()


# --- Combos -------------------------------------------------------------

def find_combos_with_card(card_name: str, limit: int = 25) -> list[dict]:
    """Combos that include card_name. Smallest (fewest cards) first.
    Card-name match is case-insensitive. Includes user-curated combos."""
    if not card_name:
        return []
    card_name = resolve_card_name(card_name) or card_name
    limit = _clamp_limit(limit, 500)
    conn = _connect()
    try:
        cur = conn.cursor()
        cur.execute(
            "SELECT c.id, c.color_identity, c.name, "
            "(SELECT COUNT(*) FROM combo_cards WHERE combo_id=c.id) AS card_count, "
            "(SELECT GROUP_CONCAT(card_name, ' + ') "
            " FROM combo_cards WHERE combo_id=c.id) AS cards, "
            "'spellbook' AS source "
            "FROM combos c JOIN combo_cards cc ON cc.combo_id = c.id "
            "WHERE cc.card_name = ? COLLATE NOCASE "
            "UNION ALL "
            "SELECT c.id, c.color_identity, c.name, "
            "(SELECT COUNT(*) FROM user_combo_cards WHERE combo_id=c.id) AS card_count, "
            "(SELECT GROUP_CONCAT(card_name, ' + ') "
            " FROM user_combo_cards WHERE combo_id=c.id) AS cards, "
            "'user' AS source "
            "FROM user_combos c JOIN user_combo_cards cc ON cc.combo_id = c.id "
            "WHERE cc.card_name = ? COLLATE NOCASE "
            "ORDER BY card_count ASC, id LIMIT ?",
            (card_name, card_name, limit),
        )
        rows = _rows_to_dicts(cur.fetchall())
        return flag_template_vars(rows, cur)
    finally:
        conn.close()


def find_combos_with_all(card_names: list[str], limit: int = 25) -> list[dict]:
    """Combos that include EVERY card in card_names.

    Card-name matches are case-insensitive. Combos are returned ordered
    by card count (smallest first).
    """
    # Resolve, then dedupe case-insensitively: HAVING compares against the
    # number of names, so a repeated name made every combo unreachable.
    unique: dict[str, str] = {}
    for raw in card_names:
        name = resolve_card_name(raw) or raw
        unique.setdefault(name.casefold(), name)
    card_names = list(unique.values())
    if not card_names:
        return []
    limit = _clamp_limit(limit, 500)
    placeholders = ",".join("?" * len(card_names))
    conn = _connect()
    try:
        cur = conn.cursor()
        # COLLATE NOCASE on the column makes the IN (...) comparison
        # case-insensitive. COUNT(DISTINCT card_name) still counts unique
        # canonical rows from combo_cards, so the HAVING check is sound.
        sql = (
            f"SELECT c.id, c.color_identity, c.name, "
            f"(SELECT COUNT(*) FROM combo_cards WHERE combo_id=c.id) AS card_count, "
            f"(SELECT GROUP_CONCAT(card_name, ' + ') "
            f" FROM combo_cards WHERE combo_id=c.id) AS cards, "
            f"'spellbook' AS source "
            f"FROM combos c "
            f"WHERE c.id IN ( "
            f"  SELECT combo_id FROM combo_cards "
            f"  WHERE card_name COLLATE NOCASE IN ({placeholders}) "
            f"  GROUP BY combo_id "
            f"  HAVING COUNT(DISTINCT card_name) = ?) "
            f"UNION ALL "
            f"SELECT c.id, c.color_identity, c.name, "
            f"(SELECT COUNT(*) FROM user_combo_cards WHERE combo_id=c.id) AS card_count, "
            f"(SELECT GROUP_CONCAT(card_name, ' + ') "
            f" FROM user_combo_cards WHERE combo_id=c.id) AS cards, "
            f"'user' AS source "
            f"FROM user_combos c "
            f"WHERE c.id IN ( "
            f"  SELECT combo_id FROM user_combo_cards "
            f"  WHERE card_name COLLATE NOCASE IN ({placeholders}) "
            f"  GROUP BY combo_id "
            f"  HAVING COUNT(DISTINCT card_name) = ?) "
            f"ORDER BY card_count ASC, id LIMIT ?"
        )
        cur.execute(
            sql,
            (*card_names, len(card_names), *card_names, len(card_names), limit),
        )
        rows = _rows_to_dicts(cur.fetchall())
        return flag_template_vars(rows, cur)
    finally:
        conn.close()


def get_combo(combo_id: str) -> Optional[dict]:
    """Full combo details: cards, results, prerequisites, steps.

    Looks up Spellbook combos first; falls back to `user_combos` for
    IDs with the `user-` prefix or any ID not present in Spellbook.
    User combos only carry cards + description (no prerequisites /
    steps / results sub-tables today)."""
    if not combo_id:
        return None
    conn = _connect()
    try:
        cur = conn.cursor()
        cur.execute(
            "SELECT id, name, color_identity, description FROM combos WHERE id = ?",
            (combo_id,),
        )
        row = cur.fetchone()
        if row:
            combo: dict = dict(row)
            combo["source"] = "spellbook"
            cur.execute(
                "SELECT card_name, quantity FROM combo_cards WHERE combo_id = ?",
                (combo_id,),
            )
            combo["cards"] = _rows_to_dicts(cur.fetchall())
            cur.execute(
                "SELECT text FROM combo_prerequisites WHERE combo_id = ? ORDER BY id",
                (combo_id,),
            )
            combo["prerequisites"] = [r["text"] for r in cur.fetchall()]
            cur.execute(
                "SELECT step_order, text FROM combo_steps WHERE combo_id = ? "
                "ORDER BY step_order",
                (combo_id,),
            )
            combo["steps"] = [r["text"] for r in cur.fetchall()]
            cur.execute(
                "SELECT text FROM combo_results WHERE combo_id = ? ORDER BY id",
                (combo_id,),
            )
            combo["results"] = [r["text"] for r in cur.fetchall()]
            return combo

        # Not in Spellbook — try user_combos.
        cur.execute(
            "SELECT id, name, color_identity, description, added_at, added_by "
            "FROM user_combos WHERE id = ?",
            (combo_id,),
        )
        row = cur.fetchone()
        if not row:
            return None
        combo = dict(row)
        combo["source"] = "user"
        cur.execute(
            "SELECT card_name, quantity FROM user_combo_cards WHERE combo_id = ?",
            (combo_id,),
        )
        combo["cards"] = _rows_to_dicts(cur.fetchall())
        # User combos don't have prerequisites/steps/results sub-tables —
        # everything narrative lives in `description`. Empty lists keep the
        # renderer's shape contract intact.
        combo["prerequisites"] = []
        combo["steps"] = []
        combo["results"] = []
        return combo
    finally:
        conn.close()


# --- Rules --------------------------------------------------------------

def rule_sort_key(rule_number: str) -> tuple:
    """Natural order for CR numbers: '702.2' < '702.10' < '702.10a'."""
    return tuple(
        (0, int(part), "") if part.isdigit() else (1, 0, part.lower())
        for part in re.findall(r"[0-9]+|[A-Za-z]+", rule_number or "")
    )


def get_rule(rule_number: str) -> Optional[dict]:
    """Return a rule by rule_number (case-insensitive on the letter suffix),
    plus its immediate child rules."""
    if not rule_number:
        return None
    conn = _connect()
    try:
        cur = conn.cursor()
        cur.execute(
            "SELECT rule_number, parent_rule, section_title, text "
            "FROM rules WHERE rule_number = ? COLLATE NOCASE",
            (rule_number,),
        )
        row = cur.fetchone()
        if not row:
            return None
        rule: dict = dict(row)
        canonical = rule["rule_number"]

        cur.execute(
            "SELECT rule_number, text FROM rules WHERE parent_rule = ?",
            (canonical,),
        )
        rule["children"] = sorted(
            _rows_to_dicts(cur.fetchall()),
            key=lambda r: rule_sort_key(r["rule_number"]),
        )

        return rule
    finally:
        conn.close()


def search_rules(pattern: str, limit: int = 25) -> list[dict]:
    """Search rules text for a substring (case-insensitive)."""
    if not pattern:
        return []
    limit = _clamp_limit(limit, 200)
    conn = _connect()
    try:
        cur = conn.cursor()
        # Sorted in Python before the limit: rule numbers need a natural
        # sort, and SQL's text order puts 702.10 before 702.2.
        cur.execute(
            "SELECT rule_number, section_title, text FROM rules "
            "WHERE text LIKE ? ESCAPE '!' COLLATE NOCASE",
            (f"%{like_literal(pattern)}%",),
        )
        rows = sorted(
            _rows_to_dicts(cur.fetchall()),
            key=lambda r: rule_sort_key(r["rule_number"]),
        )
        return rows[:limit]
    finally:
        conn.close()


# --- Corrections --------------------------------------------------------

def get_corrections(
    card: Optional[str] = None,
    topic: Optional[str] = None,
    text: Optional[str] = None,
    limit: int = 25,
) -> list[dict]:
    """List corrections, optionally filtered.

    `card` and `topic` are precise filters and are ANDed together — use
    them when the caller knows which field it means (the CLI's
    `--card` / `--topic` flags).

    `text` is the loose single-box filter: it matches if the term appears
    in `relates_to` OR `topic` OR `incorrect_claim`. That's what a user
    typing `correction yawgmoth` means — a correction whose topic slug
    doesn't happen to repeat the card name must still be found.

    When all three are None, returns the most recent corrections overall.
    """
    limit = _clamp_limit(limit, 200)

    clauses: list[str] = []
    params: list = []
    if card:
        clauses.append(_RELATES_TO_NAMES)
        params.append(_relates_to_pattern(card))
    # Escaped like every other user-typed LIKE: `correction 100%` matched all.
    if topic:
        clauses.append(
            "(topic LIKE ? ESCAPE '!' OR incorrect_claim LIKE ? ESCAPE '!')"
        )
        params.extend([f"%{like_literal(topic)}%"] * 2)
    if text:
        clauses.append(
            "(relates_to LIKE ? ESCAPE '!' OR topic LIKE ? ESCAPE '!' "
            "OR incorrect_claim LIKE ? ESCAPE '!')"
        )
        params.extend([f"%{like_literal(text)}%"] * 3)

    where = (" WHERE " + " AND ".join(clauses)) if clauses else ""
    sql = (
        "SELECT id, topic, category, incorrect_claim, correct_claim, "
        "explanation, relates_to, source, added_at, added_by "
        "FROM corrections" + where + " ORDER BY added_at DESC LIMIT ?"
    )
    params.append(limit)

    conn = _connect()
    try:
        cur = conn.cursor()
        cur.execute(sql, params)
        rows = _rows_to_dicts(cur.fetchall())
        for r in rows:
            if r.get("relates_to"):
                try:
                    r["relates_to"] = json.loads(r["relates_to"])
                except json.JSONDecodeError:
                    # Legacy or hand-edited rows may carry a free-text
                    # relates_to instead of a JSON array. Leave the raw
                    # string in place — the renderer copes either way,
                    # and silently dropping it would lose information.
                    pass
        return rows
    finally:
        conn.close()
