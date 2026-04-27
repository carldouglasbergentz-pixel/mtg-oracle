"""Plain-text deck parser.

Accepts the common formats produced by Moxfield, Archidekt, Arena,
MTGO, manual deckstrings, and card-shop exports. Output is a flat list
of {name, quantity, section} dicts where section is one of:
    'main', 'sideboard', 'commander', 'maybeboard'

Recognized input shapes:

    4 Lightning Bolt                      # qty + name
    4x Lightning Bolt
    Lightning Bolt                        # implicit qty=1
    Lightning Bolt x4                     # trailing qty
    1 Jace, Vryn's Prodigy                # comma in name
    1 Fire/Ice                            # alt separator (resolve_card_name handles it)
    4 Lightning Bolt (CLB) 146            # set + collector number (stripped)
    4 Lightning Bolt *F*                  # foil marker (stripped)
    # comment line                        # ignored
    // also a comment
    (blank line)                          # ignored

Section headers (case-insensitive):

    Deck / Maindeck / Main                -> 'main'
    Sideboard / SB: / SB                  -> 'sideboard'
    Commander / Commanders                -> 'commander'
    Companion                             -> 'companion' (treated as sideboard)
    Maybeboard / Maybe                    -> 'maybeboard'
    Tokens                                -> ignored

Default section is 'main'. A line "Sideboard" on its own switches the
rest of the file to sideboard until another header appears.
"""
from __future__ import annotations

import re
from typing import Iterator

_SECTION_ALIASES = {
    "deck": "main",
    "maindeck": "main",
    "main deck": "main",
    "main": "main",
    "mainboard": "main",
    "sideboard": "sideboard",
    "sb": "sideboard",
    "sb:": "sideboard",
    "side board": "sideboard",
    "companion": "sideboard",
    "commander": "commander",
    "commanders": "commander",
    "maybeboard": "maybeboard",
    "maybe": "maybeboard",
    "maybe board": "maybeboard",
}

# Set-code + collector number: " (XYZ)", " (XYZ) 123", " (XYZ) 123a".
# Keep the set code block short (2-6 chars) so we don't eat parts of names
# that happen to contain parentheses.
_SET_TAIL_RE = re.compile(r"\s*\([A-Za-z0-9]{2,6}\)\s*\d*[a-z]?\s*$")
# Foil / etched markers some exports add.
_FOIL_TAIL_RE = re.compile(r"\s*\*(?:F|foil|etched)\*\s*$", re.IGNORECASE)

# Leading count: "4 " or "4x " or "4X "
_LEAD_COUNT_RE = re.compile(r"^(\d+)[xX]?\s+(.+)$")
# Trailing count: "Card x4" or "Card X4"
_TRAIL_COUNT_RE = re.compile(r"^(.+?)\s+[xX](\d+)\s*$")


def _is_section_header(line: str) -> str | None:
    """If `line` is a section header (e.g. 'Deck', 'Sideboard'), return the
    canonical section key. Otherwise None.

    A section header is a line that, after lowercasing and stripping,
    matches one of the known aliases — with no digits / quantity words.
    """
    s = line.strip().lower().rstrip(":").strip()
    if not s:
        return None
    if any(ch.isdigit() for ch in s):
        return None
    return _SECTION_ALIASES.get(s)


def _parse_line(line: str) -> tuple[int, str] | None:
    """Parse a card line into (quantity, name). Returns None if the line
    can't be interpreted as a card entry."""
    line = line.strip()
    if not line:
        return None
    # Strip trailing foil / set-code / collector metadata.
    line = _FOIL_TAIL_RE.sub("", line)
    line = _SET_TAIL_RE.sub("", line)
    line = line.strip()
    if not line:
        return None
    # Leading count is the most common form.
    m = _LEAD_COUNT_RE.match(line)
    if m:
        try:
            qty = int(m.group(1))
        except ValueError:
            return None
        name = m.group(2).strip()
        if name:
            return qty, name
    # Trailing "Card x4".
    m = _TRAIL_COUNT_RE.match(line)
    if m:
        try:
            qty = int(m.group(2))
        except ValueError:
            return None
        return qty, m.group(1).strip()
    # Implicit quantity 1.
    return 1, line


def parse_deckstring(text: str) -> list[dict]:
    """Top-level entry point. Returns a list of {name, quantity, section}
    dicts. Order in the output follows the input order.
    """
    rows: list[dict] = []
    section = "main"
    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            continue
        if line.startswith("#") or line.startswith("//"):
            continue
        header = _is_section_header(line)
        if header is not None:
            section = header
            continue
        parsed = _parse_line(line)
        if parsed is None:
            # Silently skip unparseable lines; callers decide how to surface.
            continue
        qty, name = parsed
        rows.append({"name": name, "quantity": qty, "section": section})
    return rows
