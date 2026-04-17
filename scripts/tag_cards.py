"""Parse each card's oracle_text and type_line into structured tags + per-ability records.

Phase 1: deterministic regex parsing only (no LLM). Populates:
  - card_tags       — flat key-value tags with category (keyword/supertype/type/subtype)
  - card_abilities  — one row per parsed ability, with has_target / produces_mana / is_mana_ability flags

Re-tagging all ~34k cards takes a few seconds, so the script wipes both
tables and rebuilds from scratch on every run. Records progress via the
`sync_state` table so runners can see when it last ran.

Mana-ability detection follows CR 605.1a/b:
  An ability is a mana ability iff it could add mana when it resolves
  AND it does not have a target AND it is not a loyalty ability.

Run:
    python scripts/tag_cards.py
"""
import argparse
import datetime as dt
import json
import re
import sqlite3
import sys
from pathlib import Path
from typing import Optional

DB_PATH = Path(__file__).parent.parent / "data" / "mtg.db"

# CR 702 keyword abilities + a handful of common mechanics. Long phrases
# are listed first so they are matched before their single-word substrings.
KEYWORDS = sorted({
    "first strike", "double strike", "split second", "living weapon",
    "totem armor", "umbra armor", "cumulative upkeep", "level up",
    "jump-start", "battle cry",
    "deathtouch", "defender", "flash", "flying", "haste", "hexproof",
    "indestructible", "lifelink", "menace", "reach", "shroud", "trample",
    "vigilance", "ward", "fear", "intimidate", "horsemanship", "prowess",
    "cascade", "dredge", "convoke", "delve", "flashback", "unearth",
    "exalted", "infect", "evoke", "persist", "undying", "annihilator",
    "affinity", "storm", "cycling", "echo", "madness", "buyback",
    "kicker", "retrace", "rebound", "morph", "megamorph", "suspend",
    "overload", "emerge", "embalm", "eternalize", "escape", "foretell",
    "mutate", "companion", "adventure", "disturb", "daybound",
    "nightbound", "cleave", "channel", "boast", "devoid", "fabricate",
    "crew", "partner", "prowl", "bushido", "changeling", "decayed",
    "graft", "hideaway", "improvise", "ingest", "melee", "mentor",
    "metalcraft", "miracle", "myriad", "ninjutsu", "offering", "outlast",
    "populate", "prototype", "reconfigure", "renown", "riot", "scavenge",
    "spectacle", "squad", "training", "vanishing", "venture", "discover",
    "bargain", "saga", "impending", "offspring", "plot", "compleated",
    "encore", "casualty", "craft", "toxic", "exploit", "extort",
    "explore", "evolve", "dash", "bestow", "entwine", "epic", "fading",
    "conspire", "splice", "transmute", "wither", "sunburst", "replicate",
    "phasing", "modular", "ripple", "haunt", "shadow", "banding",
    "landfall", "provoke", "regenerate", "amplify", "gravestorm",
    "forecast", "flanking", "cipher", "dethrone", "devour", "exert",
    "fuse", "connive", "afflict", "aftermath", "enlist", "blitz",
    "plot", "protection", "landwalk", "forestwalk", "islandwalk",
    "mountainwalk", "plainswalk", "swampwalk",
}, key=len, reverse=True)

SUPERTYPES = {"Legendary", "Basic", "Snow", "World", "Ongoing", "Host", "Elite", "Token"}

# --- Compiled patterns ---
KEYWORD_PATTERN = re.compile(
    r"(?<![A-Za-z])(" + "|".join(re.escape(k) for k in KEYWORDS) + r")(?![A-Za-z])",
    re.IGNORECASE,
)
REMINDER_TEXT = re.compile(r"\s*\([^)]*\)\s*")
FACE_SEPARATOR = re.compile(r"\s*//\s*")
TRIGGER_PREFIX = re.compile(r"^(?:at\s|when(?:ever)?\s)", re.IGNORECASE)
# Loyalty ability cost:  +1:, -3:, 0:, −X:  (handles unicode minus U+2212)
LOYALTY_PREFIX = re.compile(r"^[+\-\u2212]?\d+\s*:")
# "Add {G}", "Add two mana of any color", "Add one mana"
PRODUCES_MANA = re.compile(
    r"\bAdd\b[^.\n]*?(?:\bmana\b|\{[WUBRGCXSPYHAE0-9/]+\})",
    re.IGNORECASE,
)
HAS_TARGET = re.compile(r"\btarget\b", re.IGNORECASE)


def strip_reminders(text: str) -> str:
    return REMINDER_TEXT.sub(" ", text or "").strip()


def split_ability_lines(oracle_text: str) -> list[str]:
    """Split oracle_text into candidate ability lines.

    Handles face separators ('//') for DFC/split/flip cards, drops blank
    lines and stray reminder fragments. Each returned string is the raw
    ability with reminder text stripped.
    """
    cleaned = FACE_SEPARATOR.sub("\n", oracle_text or "")
    lines = []
    for raw in cleaned.split("\n"):
        line = strip_reminders(raw)
        if line:
            lines.append(line)
    return lines


def classify_ability(line: str) -> tuple[str, Optional[str], str]:
    """Return (ability_type, cost_or_None, effect) for one ability line."""
    if LOYALTY_PREFIX.match(line):
        cost, _, effect = line.partition(":")
        return ("loyalty", cost.strip(), effect.strip())

    # Activated: first colon (outside parens which are already stripped)
    if ":" in line:
        cost, _, effect = line.partition(":")
        return ("activated", cost.strip(), effect.strip())

    if TRIGGER_PREFIX.match(line):
        return ("triggered", None, line)

    # A line composed solely of known keywords (optionally comma-separated
    # with modifiers like "Ward {2}" or "Protection from red") is a keyword
    # ability. Otherwise it's a static ability.
    if _is_keyword_only(line):
        return ("keyword", None, line)

    return ("static", None, line)


def _is_keyword_only(line: str) -> bool:
    """Heuristic: line is made up of known keywords + short modifiers.

    Accept lines like "Flying", "Flying, haste", "Ward {2}",
    "Protection from red", "Landwalk — Plainswalk". Reject lines with
    sentence structure (periods, multi-clause text, target references).
    """
    if "." in line or HAS_TARGET.search(line):
        return False
    # A line ending with a period strongly suggests a sentence, not a
    # pure keyword list. Trailing periods were already caught above.
    if not KEYWORD_PATTERN.search(line):
        return False
    # Remove recognized keywords + modifier tokens; if what remains is
    # tiny, we treat the whole line as a keyword ability.
    stripped = KEYWORD_PATTERN.sub("", line)
    # Strip common modifiers: mana symbols, numbers, "from X", "— X"
    stripped = re.sub(r"\{[^}]+\}", "", stripped)
    stripped = re.sub(r"\bfrom\s+\w+(?:\s+\w+)?\b", "", stripped, flags=re.IGNORECASE)
    stripped = re.sub(r"[—\-]\s*\w+(?:walk)?", "", stripped)
    stripped = re.sub(r"[\s,;:\-—]+", "", stripped)
    return len(stripped) <= 3  # residue of punctuation is fine


def detect_flags(ability_type: str, cost: Optional[str], effect: str, raw: str) -> tuple[bool, bool, bool]:
    """Return (has_target, produces_mana, is_mana_ability)."""
    full = raw  # has_target / produces_mana are scoped to the full ability text
    has_target = bool(HAS_TARGET.search(full))
    produces_mana = bool(PRODUCES_MANA.search(full))

    # CR 605.1a (activated) / 605.1b (triggered). Loyalty never counts.
    is_mana = (
        produces_mana
        and not has_target
        and ability_type in ("activated", "triggered")
    )
    return has_target, produces_mana, is_mana


def type_line_tags(type_line: str) -> list[tuple[str, str]]:
    """Return list of (tag, category) derived from type_line."""
    if not type_line:
        return []
    if " — " in type_line:
        left, right = type_line.split(" — ", 1)
    else:
        left, right = type_line, ""
    tags: list[tuple[str, str]] = []
    for token in left.split():
        cat = "supertype" if token in SUPERTYPES else "type"
        tags.append((token.lower(), cat))
    for token in right.split():
        tags.append((token.lower(), "subtype"))
    return tags


def keyword_tags(oracle_text: str) -> list[str]:
    """All distinct CR 702 keywords found in (reminder-stripped) oracle_text."""
    cleaned = strip_reminders(oracle_text or "")
    found = {m.group(1).lower() for m in KEYWORD_PATTERN.finditer(cleaned)}
    return sorted(found)


def tag_card(
    name: str, oracle_text: str, type_line: str, card_faces_json: Optional[str]
) -> tuple[list[tuple], list[tuple]]:
    """Compute (tags, abilities) row tuples for a single card.

    For multi-face cards (transform, modal_dfc, split, flip) we prefer
    the per-face JSON stored in cards.card_faces, so face names don't
    leak into parsed abilities and type_line tokens from each face are
    categorized correctly.
    """
    faces: list[tuple[str, str]] = []
    if card_faces_json:
        try:
            parsed = json.loads(card_faces_json)
            for f in parsed:
                faces.append((f.get("oracle_text") or "", f.get("type_line") or ""))
        except json.JSONDecodeError:
            faces = []
    if not faces:
        faces = [(oracle_text or "", type_line or "")]

    tag_set: set[tuple[str, str, str]] = set()
    ability_rows: list[tuple] = []
    ability_index = 0

    for face_text, face_type in faces:
        for tag, cat in type_line_tags(face_type):
            tag_set.add((tag, cat, "type_line"))
        for kw in keyword_tags(face_text):
            tag_set.add((kw, "keyword", "regex"))

        for line in split_ability_lines(face_text):
            ability_type, cost, effect = classify_ability(line)
            has_target, produces_mana, is_mana = detect_flags(ability_type, cost, effect, line)
            ability_rows.append((
                name,
                ability_index,
                ability_type,
                cost,
                effect,
                int(has_target),
                int(produces_mana),
                int(is_mana),
                line,
            ))
            ability_index += 1

    tag_rows = [(name, tag, cat, src) for (tag, cat, src) in tag_set]
    return tag_rows, ability_rows


def _set_sync_state(cur: sqlite3.Cursor, tag_rows: int, ability_rows: int) -> None:
    now = dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
    cur.execute(
        """
        INSERT INTO sync_state (source, updated_at, last_sync, row_count)
        VALUES (?, ?, ?, ?)
        ON CONFLICT(source) DO UPDATE SET
            updated_at = excluded.updated_at,
            last_sync = excluded.last_sync,
            row_count = excluded.row_count
        """,
        ("local_tags", now, now, tag_rows + ability_rows),
    )


def sync(force: bool = False) -> None:
    """Regenerate card_tags + card_abilities from the current cards table."""
    if not DB_PATH.exists():
        print(f"ERR Database not found at {DB_PATH}.")
        sys.exit(1)

    conn = sqlite3.connect(DB_PATH)
    cur = conn.cursor()

    print("-> Wiping existing card_tags + card_abilities")
    cur.execute("DELETE FROM card_tags")
    cur.execute("DELETE FROM card_abilities")

    cur.execute("SELECT name, oracle_text, type_line, card_faces FROM cards")
    cards = cur.fetchall()

    tag_buffer: list[tuple] = []
    ability_buffer: list[tuple] = []

    for name, oracle_text, type_line, card_faces in cards:
        t, a = tag_card(name, oracle_text or "", type_line or "", card_faces)
        tag_buffer.extend(t)
        ability_buffer.extend(a)

    cur.executemany(
        "INSERT OR IGNORE INTO card_tags (card_name, tag, category, source) VALUES (?, ?, ?, ?)",
        tag_buffer,
    )
    cur.executemany(
        """
        INSERT INTO card_abilities
          (card_name, ability_index, ability_type, cost, effect,
           has_target, produces_mana, is_mana_ability, raw_text)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        ability_buffer,
    )

    _set_sync_state(cur, len(tag_buffer), len(ability_buffer))
    conn.commit()
    conn.close()

    print(f"OK Tagged {len(cards):,} cards "
          f"-> {len(tag_buffer):,} tag rows, {len(ability_buffer):,} ability rows")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--force", action="store_true", help="(no-op; retained for interface parity with sync_*.py)")
    args = parser.parse_args()
    sync(force=args.force)
