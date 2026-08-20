"""Export Simic-CI cards that find/fetch nonbasic-capable lands.

Scope:
  - Library tutors that can fetch any land (or by subtype: Forest/Locus/Gate/...
    so dual lands and nonbasics are reachable). "Search for a *basic* land
    card" is excluded.
  - Top-X-card reveal effects with a land payoff (Elvish Rejuvenator).
  - Play-from-graveyard (Crucible of Worlds).
  - Play-from-top (Oracle of Mul Daya).
  - Put-land-from-hand onto battlefield (Cultivator Colossus, Sakura-Tribe
    Scout).
  - Return-land-to-battlefield (Splendid Reclamation).

NOT in scope: "play an additional land each turn" — those enable extra
land drops but don't FIND lands.

Color identity ⊆ {G, U} (i.e. '', 'G', 'U', 'G,U'). Excludes tokens, basic
lands, Alchemy A-prefix variants, and non-tournament layouts (planes etc.).
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
OUT_PATH = OUT_DIR / "simic_nonbasic_land_fetchers.xlsx"

SIMIC_CIS = ("", "G", "U", "G,U")
EXCLUDED_LAYOUTS = {"planar", "scheme", "vanguard", "host", "augment", "art_series"}

# A "fetch verb" puts a land into a useful zone. We require one of these
# AND a non-basic-qualified land reference in the same clause.
# Note: a standalone "look at the top X cards" without a put/play action is
# NOT a fetch — Sinuous Benthisaur just counts Cave cards, it doesn't fetch
# one. Real top-X-land cards (Elvish Rejuvenator etc.) have the put-onto-
# battlefield in a separate sentence which still matches.
LAND_VERB = re.compile(
    r"\bsearch\s+your\s+library\s+for\b"
    r"|\bplay\s+(?:lands?|the\s+top\s+card|land\s+cards?)\s+from\s+"
    r"(?:your\s+(?:graveyard|hand|library)|the\s+top|among\s+them)\b"
    r"|\b(?:put|return)\s+[^.]{0,80}?(?:onto|to)\s+the\s+battlefield\b",
    re.IGNORECASE | re.DOTALL,
)

# Land references that cover utility nonbasics. We deliberately exclude
# Forest/Island/Swamp/Mountain/Plains tutors (Wood Elves, Nature's Lore,
# Skyshroud Claim, fetchlands themselves) — those find duals/triomes which
# are already reachable via fetchlands; the goal here is utility lands
# (Cavern of Souls, Reliquary Tower, Strip Mine, Maze's End targets).
NONBASIC_LAND_REF = re.compile(
    r"\bnon[\s-]?basic\s+lands?\b"
    r"|(?<!basic\s)\bland\s+cards?\b"
    r"|\b(?:locus|gate|cave|desert|mine|tower|lair)\s+(?:land\s+)?cards?\b"
    r"|\bplay\s+lands?\s+from\b",
    re.IGNORECASE,
)

# Phrases in the verb match that indicate the land source is the player's
# hand — these cards require you to already have the land in hand.
HAND_SOURCE_MARKERS = ("from your hand", " in your hand ")
# Clause-level disqualifier: "reveal your hand and put all land cards from
# it onto the battlefield" (Manabond) — anaphoric "it" references the hand.
HAND_CLAUSE_MARKER = "reveal your hand"


def is_basic_land(type_line: str) -> bool:
    return "Basic" in type_line and "Land" in type_line


def is_token(type_line: str, layout: str | None) -> bool:
    if layout and layout.lower() == "token":
        return True
    return "Token" in type_line


def classify_card(oracle_text: str) -> list[str]:
    """Return list of (clause-level) match labels. Empty -> exclude card."""
    if not oracle_text:
        return []
    # Split on sentence-ish boundaries — period followed by whitespace, or newline.
    clauses = re.split(r"(?<=\.)\s+|\n", oracle_text)
    labels: list[str] = []
    for clause in clauses:
        if not clause.strip():
            continue
        verb_m = LAND_VERB.search(clause)
        if not verb_m:
            continue
        verb_text = verb_m.group(0).lower()
        if any(marker in verb_text for marker in HAND_SOURCE_MARKERS):
            continue
        if HAND_CLAUSE_MARKER in clause.lower():
            continue
        ref_m = NONBASIC_LAND_REF.search(clause)
        if not ref_m:
            continue
        # Build a compact label: <verb>:<ref-noun>
        verb = verb_text
        if "search your library" in verb:
            v = "search"
        elif "play" in verb and "graveyard" in verb:
            v = "play_from_graveyard"
        elif "play" in verb and ("top" in verb or "library" in verb):
            v = "play_from_top"
        elif verb.startswith("return"):
            v = "return_to_bf"
        elif verb.startswith("put"):
            v = "put_onto_bf"
        else:
            v = "other"

        ref = ref_m.group(0).lower().strip()
        if "nonbasic" in ref or "non-basic" in ref or "non basic" in ref:
            r = "nonbasic-land"
        elif "land card" in ref:
            r = "land-card"
        elif "play lands from" in ref:
            r = "lands"
        else:
            # special land subtype: locus/gate/cave/desert/mine/tower/lair
            r = ref.replace(" cards", "").replace(" card", "").replace(" land", "")

        labels.append(f"{v}:{r}")
    # de-dupe while preserving order
    seen = set()
    out = []
    for l in labels:
        if l not in seen:
            seen.add(l)
            out.append(l)
    return out


def main() -> None:
    if not DB_PATH.exists():
        print(f"ERROR: db not found at {DB_PATH}", file=sys.stderr)
        sys.exit(1)
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row

    placeholders = ",".join("?" * len(SIMIC_CIS))
    q = f"""
        SELECT name, mana_cost, mana_value, color_identity, type_line, layout,
               power, toughness, rarity, oracle_text
        FROM cards
        WHERE color_identity IN ({placeholders})
        ORDER BY mana_value, name COLLATE NOCASE
    """
    rows: list[dict] = []
    total = 0
    for row in conn.execute(q, SIMIC_CIS):
        total += 1
        type_line = row["type_line"] or ""
        layout = (row["layout"] or "").lower()
        if is_basic_land(type_line) or is_token(type_line, row["layout"]):
            continue
        if layout in EXCLUDED_LAYOUTS:
            continue
        if row["name"].startswith("A-"):
            continue

        labels = classify_card(row["oracle_text"] or "")
        if not labels:
            continue

        rows.append({
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
            "oracle_text": row["oracle_text"] or "",
            "match_reason": "; ".join(labels),
        })

    print(f"scanned {total} Simic-CI/colorless cards")
    print(f"  nonbasic-capable land fetchers: {len(rows)}")

    write_workbook(rows)
    print(f"wrote {OUT_PATH}")


def write_workbook(rows: list[dict]) -> None:
    wb = Workbook()
    ws = wb.active
    ws.title = "Nonbasic Land Fetchers"

    headers = [
        ("name", "Card Name", 30),
        ("mana_cost", "Mana Cost", 14),
        ("mv", "MV", 5),
        ("type_line", "Type Line", 32),
        ("pt", "P/T", 7),
        ("ci", "CI", 8),
        ("rarity", "Rarity", 10),
        ("oracle_text", "Oracle Text", 80),
        ("match_reason", "Match Reason", 40),
    ]
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
