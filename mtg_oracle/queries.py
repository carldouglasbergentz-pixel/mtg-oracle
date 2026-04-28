"""Query functions over data/mtg.db.

All SQL is parameterized. Callers should not interpolate user input
into queries themselves — pass it as arguments here.

Unknown cards / rules / combos return None. Searches return [] on no
matches. No exceptions are raised for normal "not found" cases; the
caller decides how to present absence.
"""
from __future__ import annotations

import json
import sqlite3
import unicodedata
from pathlib import Path
from typing import Optional

DB_PATH = Path(__file__).parent.parent / "data" / "mtg.db"


_LIGATURE_MAP = str.maketrans({
    # Ligatures that NFKD doesn't decompose.
    "Æ": "AE", "æ": "ae",
    "Œ": "OE", "œ": "oe",
    "ß": "ss",
    "Þ": "Th", "þ": "th",
    "Ð": "D", "ð": "d",
    "Ø": "O", "ø": "o",
})


# Apostrophes — straight, curly, backtick — get stripped so users don't have
# to type them ("lim-duls vault" matches "Lim-Dûl's Vault").
_APOSTROPHE_DROP = str.maketrans({"'": None, "’": None, "‘": None, "`": None})


def _ascii_fold(s: str) -> str:
    """Strip diacritics + map common ligatures + drop apostrophes so loosely
    typed input matches the canonical card name.

    Examples:
        "Lórien Revealed"  -> "lorien revealed"
        "Æther Vial"       -> "aether vial"
        "Lim-Dûl's Vault"  -> "lim-duls vault"
        "Kongming, Sleeping Dragon" matches "Kongming, 'Sleeping Dragon'"
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
    conn = sqlite3.connect(f"file:{DB_PATH}?mode=ro", uri=True)
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
            "SELECT name FROM cards WHERE name LIKE ? COLLATE NOCASE "
            "ORDER BY LENGTH(name) LIMIT 1",
            (f"{name} // %",),
        )
        row = cur.fetchone()
        if row:
            return row[0]

        # 4) ASCII fold-down fallback: linear scan, ~30-50 ms over 34k names.
        # Scoped only to cards whose first ASCII letter matches the input,
        # which keeps it fast in practice.
        target = _ascii_fold(name)
        first = target[:1]
        if first.isalpha():
            cur.execute(
                "SELECT name FROM cards WHERE LOWER(SUBSTR(name, 1, 1)) IN (?, ?)",
                (first, first.upper()),
            )
        else:
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

def _connect() -> sqlite3.Connection:
    """Open a read-only connection to the knowledge-base DB."""
    if not DB_PATH.exists():
        raise FileNotFoundError(
            f"MTG Oracle database not found at {DB_PATH}. "
            "Run `python scripts/init_db.py` then `python scripts/sync.py`."
        )
    # SQLite URI mode lets us request read-only; keeps accidental writes impossible.
    uri = f"file:{DB_PATH}?mode=ro"
    conn = sqlite3.connect(uri, uri=True)
    conn.row_factory = sqlite3.Row
    return conn


def _rows_to_dicts(rows) -> list[dict]:
    return [dict(r) for r in rows]


# --- Cards --------------------------------------------------------------

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
            "SELECT name, oracle_id, oracle_text, mana_cost, type_line, layout, card_faces "
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
            " FROM combo_cards WHERE combo_id=c.id) AS cards "
            "FROM combos c JOIN combo_cards cc ON cc.combo_id = c.id "
            "WHERE cc.card_name = ?" + ci_where + " "
            "ORDER BY card_count ASC, c.id "
            "LIMIT 10",
            tuple([canonical] + ci_params),
        )
        card["combos"] = _rows_to_dicts(cur.fetchall())
        card["combos_filtered_by_ci"] = (
            "".join(restrict_to_ci) if restrict_to_ci else "C"
        ) if restrict_to_ci is not None else None

        cur.execute(
            "SELECT id, topic, correct_claim, source FROM corrections "
            "WHERE relates_to LIKE ? COLLATE NOCASE ORDER BY added_at DESC",
            (f"%{canonical}%",),
        )
        card["corrections"] = _rows_to_dicts(cur.fetchall())

        return card
    finally:
        conn.close()


def search_cards(
    name_like: Optional[str] = None,
    tag: Optional[str] = None,
    card_type: Optional[str] = None,
    color_identity: Optional[str] = None,
    is_mana_ability: Optional[bool] = None,
    limit: int = 50,
) -> list[dict]:
    """Filter the cards table. All filters are ANDed together.

    - name_like: substring match (case-insensitive)
    - tag: exact tag match in card_tags (any category)
    - card_type: exact tag match with category in ('type', 'subtype', 'supertype')
    - color_identity: substring match on type_line (placeholder; better schema TBD)
    - is_mana_ability: if True, only cards with >=1 parsed mana ability
    """
    if limit < 1 or limit > 1000:
        limit = 50

    clauses: list[str] = []
    params: list = []

    if name_like:
        clauses.append("c.name LIKE ?")
        params.append(f"%{name_like}%")
    if tag:
        clauses.append(
            "EXISTS (SELECT 1 FROM card_tags t "
            "WHERE t.card_name = c.name AND t.tag = ?)"
        )
        params.append(tag.lower())
    if card_type:
        clauses.append(
            "EXISTS (SELECT 1 FROM card_tags t "
            "WHERE t.card_name = c.name AND t.tag = ? "
            "AND t.category IN ('type','subtype','supertype'))"
        )
        params.append(card_type.lower())
    if color_identity:
        clauses.append("c.type_line LIKE ?")
        params.append(f"%{color_identity}%")
    if is_mana_ability is True:
        clauses.append(
            "EXISTS (SELECT 1 FROM card_abilities a "
            "WHERE a.card_name = c.name AND a.is_mana_ability = 1)"
        )

    where = (" WHERE " + " AND ".join(clauses)) if clauses else ""
    sql = (
        "SELECT c.name, c.type_line FROM cards c"
        + where
        + " ORDER BY c.name COLLATE NOCASE LIMIT ?"
    )
    params.append(limit)

    conn = _connect()
    try:
        cur = conn.cursor()
        cur.execute(sql, params)
        return _rows_to_dicts(cur.fetchall())
    finally:
        conn.close()


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
            (card_name,),
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
    if limit < 1 or limit > 500:
        limit = 25
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
        return _rows_to_dicts(cur.fetchall())
    finally:
        conn.close()


def find_combos_with_all(card_names: list[str], limit: int = 25) -> list[dict]:
    """Combos that include EVERY card in card_names.

    Card-name matches are case-insensitive. Combos are returned ordered
    by card count (smallest first).
    """
    if not card_names:
        return []
    if limit < 1 or limit > 500:
        limit = 25
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
        return _rows_to_dicts(cur.fetchall())
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
            "SELECT rule_number, text FROM rules WHERE parent_rule = ? "
            "ORDER BY rule_number",
            (canonical,),
        )
        rule["children"] = _rows_to_dicts(cur.fetchall())

        return rule
    finally:
        conn.close()


def search_rules(pattern: str, limit: int = 25) -> list[dict]:
    """Search rules text for a substring (case-insensitive)."""
    if not pattern:
        return []
    if limit < 1 or limit > 200:
        limit = 25
    conn = _connect()
    try:
        cur = conn.cursor()
        cur.execute(
            "SELECT rule_number, section_title, text FROM rules "
            "WHERE text LIKE ? COLLATE NOCASE "
            "ORDER BY rule_number LIMIT ?",
            (f"%{pattern}%", limit),
        )
        return _rows_to_dicts(cur.fetchall())
    finally:
        conn.close()


# --- Corrections --------------------------------------------------------

def get_corrections(
    card: Optional[str] = None,
    topic: Optional[str] = None,
    limit: int = 25,
) -> list[dict]:
    """List corrections, optionally filtered by card or topic keyword.

    When both are None, returns the most recent corrections overall.
    """
    if limit < 1 or limit > 200:
        limit = 25

    clauses: list[str] = []
    params: list = []
    if card:
        clauses.append("relates_to LIKE ?")
        params.append(f"%{card}%")
    if topic:
        clauses.append("(topic LIKE ? OR incorrect_claim LIKE ?)")
        params.append(f"%{topic}%")
        params.append(f"%{topic}%")

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
