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
#
# The collector part is far messier than a number in the wild, and every shape
# below came out of a real export that silently lost the card:
#   (PLST) DDN-64      The List reprints carry their original set in the number
#   (PLST) A25-50      ...and the original set can be one letter plus digits
#   (MED) WS3          alphanumeric collector numbers
#   (PUMA) U5          a single letter prefix
#   (PWCS) 2022-5      promo numbering by year
#   (7ED) 76*          a star marks a promo / alternate printing
# `\d*[a-z]?` matched none of them, so `Mana Leak (PLST) DDN-64` stayed a name
# and resolved to nothing — 17 of one list's 100 cards vanished that way.
#
# The collector token is permissive on purpose. It cannot eat a card name:
# the match is anchored to end-of-line AND requires the parenthesised set
# code first, so everything it consumes is by construction after the name.
# Verified against all 35k names in the database — none is altered.
# The set code itself must be all-upper or all-lower, never Title Case. Real
# codes are `CLB`, `7ED`, `PLST`, `40K` (and some exporters lowercase them);
# what Title Case catches is a parenthesised *part of a name* — there are
# cards called `Unearth (Theme)`, `Hazmat Suit (Used)` and `Imaginary Friends
# (Plane)`, and the old pattern quietly turned the first into `Unearth`, which
# resolves to a different card entirely.
_SET_TAIL_RE = re.compile(
    r"\s*\((?:[A-Z0-9]{2,6}|[a-z0-9]{2,6})\)"
    r"(?:\s*[A-Za-z0-9][A-Za-z0-9-]*)?"
    r"\s*[★*]?\s*$")
# Foil / etched / showcase markers some exports add. `*E*` is etched, which the
# named-only list missed; a single letter covers the family without guessing.
_FOIL_TAIL_RE = re.compile(
    r"\s*\*(?:[A-Za-z]|foil|etched|showcase|borderless)\*\s*$", re.IGNORECASE)

# mtgtop8 groups the main deck by card type, and writes the group size into
# the heading: `40 LANDS (42)`, `28 INSTANTS and SORC.`, `7 OTHER SPELLS`.
# `_is_section_header` rejects any line containing a digit — deliberately, so
# `1 Island` can never be a header — which left these to be parsed as cards.
# Four such lines added 99 phantom cards to a 100-card deck.
#
# They are groupings *within* the main deck, not sections, so recognising one
# leaves the current section alone.
#
# Two guards, because a closed vocabulary alone was not enough: there really
# are cards named `Lands`, `Spells` and `Artifacts` (Jumpstart theme dividers),
# so the label must ALSO be upper-case, which is how mtgtop8 writes it. That
# makes `40 LANDS` a heading and `40 Lands` a card, with no judgement call
# about which is more likely.
_TYPE_GROUP_WORDS = frozenset((
    "lands", "creatures", "instants", "sorceries", "instants and sorc.",
    "instants and sorceries", "other spells", "spells", "artifacts",
    "enchantments", "planeswalkers", "battles",
))
# Connectives stay lower-case even in a shouted heading.
_GROUP_CONNECTIVES = frozenset(("and", "or"))
_TYPE_GROUP_RE = re.compile(
    r"^\d+\s+(?P<label>[A-Za-z .&]+?)\s*(?:\(\d+\))?\s*:?\s*$")

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


def _is_type_group(line: str) -> bool:
    """True for an mtgtop8 type-group heading like `40 LANDS (42)`.

    Not a section — the cards under it are still main deck — so the caller
    skips the line and keeps whatever section it was already in.
    """
    m = _TYPE_GROUP_RE.match(line.strip())
    if not m:
        return False
    label = m.group("label").strip()
    if label.lower() not in _TYPE_GROUP_WORDS:
        return False
    # Shouted, ignoring connectives: `40 LANDS` is a heading, `40 Lands` is
    # the Jumpstart card of that name.
    words = [w for w in label.split() if w.lower() not in _GROUP_CONNECTIVES]
    return bool(words) and all(w == w.upper() for w in words)


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
        # A type-group heading is not a section, so it is skipped — but it
        # does end a commander block. mtgtop8 writes `COMMANDER / 1 Elminster
        # / 40 LANDS (42) / ...`, with nothing but the heading to say the
        # commander is over; preserving the section there filed all 100 cards
        # as commanders. A heading inside a sideboard keeps its section.
        if _is_type_group(line):
            if section == "commander":
                section = "main"
            continue
        parsed = _parse_line(line)
        if parsed is None:
            # Silently skip unparseable lines; callers decide how to surface.
            continue
        qty, name = parsed
        rows.append({"name": name, "quantity": qty, "section": section})
    return rows
