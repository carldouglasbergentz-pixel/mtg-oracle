"""Export Simic-CI typal-matters cards to xlsx (for Omo, Queen of Vesuva).

Heuristic: scan oracle_text for tribal-context patterns built from the canonical
list of creature subtypes in the DB. Two output sheets:
  - "Typal Matters" — cards that reference a specific creature type in a
    payoff context (anthems, triggers, scaling, tutoring, choose-a-type).
  - "Enablers" — cards that make creatures count as every (or any) creature
    type. Highly relevant under Omo even though they aren't "matters" cards.

Color identity filter: subset of {G, U} (i.e. '', 'G', 'U', 'G,U').
Excludes tokens and basic lands.
"""
import re
import sqlite3
import sys
from pathlib import Path

from openpyxl import Workbook
from openpyxl.styles import Alignment, Font, PatternFill
from openpyxl.utils import get_column_letter

DB_PATH = Path(__file__).parent.parent / "data" / "mtg.db"
OUT_DIR = Path(__file__).parent.parent / "data" / "exports"
OUT_PATH = OUT_DIR / "omo_typal_simic.xlsx"

# Color identities that fit in a Simic (G/U) commander like Omo.
SIMIC_CIS = ("", "G", "U", "G,U")

# Layouts that aren't legal in Commander / regular play — drop them.
EXCLUDED_LAYOUTS = {"planar", "scheme", "vanguard", "host", "augment", "art_series"}

# Subtypes returned by the DB query that are NOT real creature tribes — they
# come from un-set / acorn cards or tagging quirks. Filter out to reduce false
# positives. Conservative — keep anything that could plausibly be a tribe.
TRIBE_DENYLIST = {
    "and/or", "art", "etiquette", "gamma", "head", "of", "or", "the", "proper",
    "hatificer", "human?", "elemental?", "armored", "elves",  # 'elves' is plural of 'elf' which is also tagged
}

# Irregular singular -> plural that simple "+s" doesn't handle.
IRREGULAR_PLURALS = {
    "elf": "elves",
    "dwarf": "dwarves",
    "wolf": "wolves",
    "leaf": "leaves",
    "fungus": "fungi",
    "octopus": "octopuses",
    "sphinx": "sphinxes",
    "phoenix": "phoenixes",
    "lich": "liches",
    "leech": "leeches",
    "fox": "foxes",
    "ox": "oxen",
    "goose": "geese",
    "mongoose": "mongooses",
    "mouse": "mice",
    "louse": "lice",
    "fish": "fish",
    "sheep": "sheep",
    "deer": "deer",
    "moose": "moose",
    "merfolk": "merfolk",
    "townsfolk": "townsfolk",
    "kavu": "kavu",
    "naga": "naga",
    "tyranid": "tyranids",
    "c'tan": "c'tan",
    "astartes": "astartes",
}


def plural_of(t: str) -> str:
    t = t.lower()
    if t in IRREGULAR_PLURALS:
        return IRREGULAR_PLURALS[t]
    if t.endswith(("s", "x", "z")) or t.endswith(("sh", "ch")):
        return t + "es"
    if t.endswith("y") and len(t) > 1 and t[-2] not in "aeiou":
        return t[:-1] + "ies"
    return t + "s"


def fetch_tribes(conn: sqlite3.Connection) -> list[str]:
    """Subtypes that appear only on creature/kindred/tribal cards."""
    q = """
    SELECT ct.tag,
        SUM(CASE WHEN c.type_line LIKE '%Creature%'
                 OR c.type_line LIKE '%Kindred%'
                 OR c.type_line LIKE '%Tribal%'
                 THEN 1 ELSE 0 END) AS on_tribe,
        SUM(CASE WHEN c.type_line NOT LIKE '%Creature%'
                 AND c.type_line NOT LIKE '%Kindred%'
                 AND c.type_line NOT LIKE '%Tribal%'
                 THEN 1 ELSE 0 END) AS on_other
    FROM card_tags ct
    JOIN cards c ON c.name = ct.card_name
    WHERE ct.category = 'subtype'
    GROUP BY ct.tag
    HAVING on_tribe > 0 AND on_other = 0
    """
    out = []
    for tag, _, _ in conn.execute(q):
        if tag in TRIBE_DENYLIST:
            continue
        if "?" in tag or len(tag) < 2:
            continue
        out.append(tag)
    return sorted(out)


def build_tribe_patterns(tribes: list[str]) -> list[tuple[str, str, re.Pattern]]:
    """For each tribe, compile patterns that only fire on Omo-relevant context.

    Omo's "everything counter" is applied AFTER a creature enters / a spell
    resolves, so cast-time and ETB-time effects don't see the type. Only
    static abilities, activated abilities, and combat/death triggers on
    permanents already in play benefit. So we drop:
      - {Tribe} spells / {Tribe} cards (cast-time, hand/library)
      - search-for-{Tribe} (library)
      - create {Tribe} token (tokens enter as their own type)
      - another {Tribe} (almost always paired with an ETB trigger)
      - broad "whenever a {Tribe}" (can't tell ETB from combat without parsing)
    and keep only patterns that target permanents-in-play.
    """
    out: list[tuple[str, str, re.Pattern]] = []
    for t in tribes:
        sing = re.escape(t)
        plur = re.escape(plural_of(t))
        T = f"(?:{sing}|{plur})"
        patterns = [
            ("anthem",   rf"\b{T}\s+(?:creatures?\s+)?you\s+control\b"),
            ("other",    rf"\bother\s+{T}\b"),
            ("each",     rf"\beach\s+(?:other\s+)?{T}\b"),
            ("for_each", rf"\bfor\s+each\s+{T}\b"),
            ("target",   rf"\btarget\s+{sing}\b"),
        ]
        for label, pat in patterns:
            out.append((t, label, re.compile(pat, re.IGNORECASE)))
    return out


# Generic typal patterns not tied to a specific tribe.
# `chosen_type` is intentionally narrow — it only fires when "the chosen type"
# is referenced in an in-play context (creatures-you-control, get/have/gain
# anthem, or permanent-counting). Cast-time references like Cavern of Souls'
# "cast a creature spell of the chosen type" don't match.
GENERIC_PATTERNS = [
    ("chosen_type", re.compile(
        r"\b(?:creatures?|permanents?)\s+you\s+control\s+of\s+(?:the\s+chosen|that)\s+type\b"
        r"|\bof\s+(?:the\s+chosen|that)\s+type\s+(?:gets?|have|gains?|with)\b"
        r"|\bcreatures?\s+of\s+the\s+chosen\s+type\s+(?:gets?|have|gains?)\b",
        re.IGNORECASE)),
    ("share_a_type", re.compile(
        r"\bshares?(?:\s+at\s+least\s+one)?\s+(?:a\s+)?creature\s+type\b", re.IGNORECASE)),
    ("named_type", re.compile(
        r"\bcreatures?\s+of\s+the\s+named\s+type\b", re.IGNORECASE)),
]

# "Enabler" patterns — cards that grant every-creature-type status, or that
# make all your creatures share a chosen type (Conspiracy / Xenograft).
ENABLER_PATTERNS = [
    ("every_creature_type", re.compile(r"\b(?:is|are|becomes?)\s+every\s+creature\s+type\b", re.IGNORECASE)),
    ("all_creature_types",  re.compile(r"\ball\s+creature\s+types\b", re.IGNORECASE)),
    ("changeling",          re.compile(r"\bchangeling\b", re.IGNORECASE)),
    ("are_chosen_type",     re.compile(
        r"\b(?:creatures?\s+you\s+control\s+(?:are|is)|each\s+creature\s+you\s+control\s+is)\s+the\s+chosen\s+type\b",
        re.IGNORECASE)),
]


def is_basic_land(type_line: str) -> bool:
    return "Basic" in type_line and "Land" in type_line


def is_token(type_line: str, layout: str | None) -> bool:
    if layout and layout.lower() == "token":
        return True
    return "Token" in type_line


def classify_card(oracle_text: str,
                  tribe_patterns: list[tuple[str, str, re.Pattern]]
                  ) -> tuple[set[str], set[str], bool]:
    """Return (matched_tribes, match_reasons, is_enabler)."""
    matched_tribes: set[str] = set()
    reasons: set[str] = set()
    is_enabler = False

    if not oracle_text:
        return matched_tribes, reasons, is_enabler

    for tribe, label, pat in tribe_patterns:
        if pat.search(oracle_text):
            matched_tribes.add(tribe)
            reasons.add(f"{tribe}:{label}")

    for label, pat in GENERIC_PATTERNS:
        if pat.search(oracle_text):
            reasons.add(f"generic:{label}")

    for label, pat in ENABLER_PATTERNS:
        if pat.search(oracle_text):
            is_enabler = True
            reasons.add(f"enabler:{label}")

    return matched_tribes, reasons, is_enabler


def main() -> None:
    if not DB_PATH.exists():
        print(f"ERROR: db not found at {DB_PATH}", file=sys.stderr)
        sys.exit(1)
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row

    tribes = fetch_tribes(conn)
    print(f"loaded {len(tribes)} candidate tribes")
    tribe_patterns = build_tribe_patterns(tribes)
    print(f"compiled {len(tribe_patterns)} tribal regexes")

    placeholders = ",".join("?" * len(SIMIC_CIS))
    q = f"""
        SELECT name, mana_cost, mana_value, color_identity, type_line, layout,
               power, toughness, rarity, oracle_text
        FROM cards
        WHERE color_identity IN ({placeholders})
        ORDER BY mana_value, name COLLATE NOCASE
    """
    matters_rows = []
    enabler_rows = []
    seen_oracle: dict[str, str] = {}  # de-dupe reprints by oracle_id-equivalent

    total = 0
    for row in conn.execute(q, SIMIC_CIS):
        total += 1
        type_line = row["type_line"] or ""
        layout = (row["layout"] or "").lower()
        if is_basic_land(type_line) or is_token(type_line, row["layout"]):
            continue
        if layout in EXCLUDED_LAYOUTS:
            continue
        # Alchemy/Arena rebalances duplicate paper cards — skip the A- variant.
        if row["name"].startswith("A-"):
            continue
        oracle = row["oracle_text"] or ""
        tribes_hit, reasons, is_enabler = classify_card(oracle, tribe_patterns)

        is_matters = bool(reasons) and any(
            not r.startswith("enabler:") for r in reasons
        )

        if not (is_matters or is_enabler):
            continue

        # de-dupe by name (DB shouldn't have dupes but be safe)
        if row["name"] in seen_oracle:
            continue
        seen_oracle[row["name"]] = oracle

        record = {
            "name": row["name"],
            "mana_cost": row["mana_cost"] or "",
            "mv": row["mana_value"] if row["mana_value"] is not None else "",
            "type_line": type_line,
            "pt": (
                f"{row['power']}/{row['toughness']}"
                if row["power"] is not None and row["toughness"] is not None
                else ""
            ),
            "ci": row["color_identity"] or "(colorless)",
            "rarity": row["rarity"] or "",
            "oracle_text": oracle,
            "matched_tribes": ", ".join(sorted(tribes_hit)) if tribes_hit else "",
            "match_reason": "; ".join(sorted(reasons)),
        }
        if is_enabler:
            enabler_rows.append(record)
        if is_matters:
            matters_rows.append(record)

    print(f"scanned {total} Simic-CI/colorless cards")
    print(f"  typal-matters: {len(matters_rows)}")
    print(f"  enablers:      {len(enabler_rows)}")

    write_workbook(matters_rows, enabler_rows)
    print(f"wrote {OUT_PATH}")


def write_workbook(matters_rows: list[dict], enabler_rows: list[dict]) -> None:
    wb = Workbook()
    matters_ws = wb.active
    matters_ws.title = "Typal Matters"
    enablers_ws = wb.create_sheet("Enablers")

    headers = [
        ("name", "Card Name", 30),
        ("mana_cost", "Mana Cost", 14),
        ("mv", "MV", 5),
        ("type_line", "Type Line", 32),
        ("pt", "P/T", 7),
        ("ci", "CI", 8),
        ("rarity", "Rarity", 10),
        ("oracle_text", "Oracle Text", 70),
        ("matched_tribes", "Matched Tribes", 28),
        ("match_reason", "Match Reason", 40),
    ]

    for ws, rows in ((matters_ws, matters_rows), (enablers_ws, enabler_rows)):
        bold = Font(bold=True)
        header_fill = PatternFill("solid", fgColor="DCE6F1")
        for col_idx, (_, label, width) in enumerate(headers, start=1):
            cell = ws.cell(row=1, column=col_idx, value=label)
            cell.font = bold
            cell.fill = header_fill
            ws.column_dimensions[get_column_letter(col_idx)].width = width

        wrap = Alignment(wrap_text=True, vertical="top")
        for r_idx, rec in enumerate(rows, start=2):
            for c_idx, (key, _, _) in enumerate(headers, start=1):
                cell = ws.cell(row=r_idx, column=c_idx, value=rec[key])
                cell.alignment = wrap

        ws.freeze_panes = "A2"
        ws.auto_filter.ref = f"A1:{get_column_letter(len(headers))}{max(2, len(rows) + 1)}"

    wb.save(OUT_PATH)


if __name__ == "__main__":
    main()
