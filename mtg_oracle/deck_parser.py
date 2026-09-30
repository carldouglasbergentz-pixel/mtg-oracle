"""Plain-text deck parser.

Accepts the common formats produced by Moxfield, Archidekt, Arena,
MTGO, manual deckstrings, and card-shop exports. Output is a flat list
of {name, quantity, section} dicts where section is one of:
    'main', 'sideboard', 'commander', 'maybeboard'
A line that names a printing also carries `set_code` (Scryfall's
lower-case code, e.g. `c18`) and `collector_number` (e.g. `263`, `76★`,
`DDN-64`, or None when only the set is given). Lines without one have
neither key.

Recognized input shapes:

    4 Lightning Bolt                      # qty + name
    4x Lightning Bolt
    Lightning Bolt                        # implicit qty=1
    Lightning Bolt x4                     # trailing qty
    1 Jace, Vryn's Prodigy                # comma in name
    1 Fire/Ice                            # alt separator (resolve_card_name handles it)
    4 Lightning Bolt (CLB) 146            # set + collector number (kept as the printing)
    4 Lightning Bolt *F*                  # foil marker (stripped)
    # comment line                        # ignored
    // also a comment
    (blank line)                          # ignored

Section headers (case-insensitive):

    Deck / Maindeck / Main                -> 'main'
    Sideboard / SB: / SB                  -> 'sideboard'
    Commander / Commanders                -> 'commander'
    Companion                             -> 'companion' (treated as sideboard)
    Maybeboard / Maybe / Considering      -> 'maybeboard' (the deck's considering list)
    Tokens                                -> ignored (its rows are dropped)

A trailing count on a header (`Sideboard (15)`) is allowed, and so is an
inline sideboard prefix (`SB: 2 Duress`) on a single card line.

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
    # Moxfield's newer name for it, and ours: the deck's considering list.
    "considering": "maybeboard",
    # Token lists are not deck contents. `Treasure` resolves to a real
    # (front_card) row, so reading them as cards put tokens in the main deck.
    "tokens": "tokens",
    "token": "tokens",
}

# Moxfield and Archidekt write the section size after the header:
# `Commander (1)`, `Sideboard (15)`.
_HEADER_COUNT_RE = re.compile(r"\s*\(\d+\)\s*$")
# MTGO / Forge inline sideboard marker.
_INLINE_SB_RE = re.compile(r"^SB:\s*(.+)$", re.IGNORECASE)
# Archidekt appends categories and colour tags after the set block:
# `1x Sol Ring (c21) 263 [Ramp] ^Have,#37d67a^`. No card name contains
# `[` or `^` (checked against the database), so the strip cannot eat a name.
_CATEGORY_TAIL_RE = re.compile(r"\s*\[[^\]]*\](?:\s*\^[^^]*\^)?\s*$")

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
#
# The tail is kept, not just stripped: it is the printing the user chose, and
# the art follows it (`set_code` / `collector_number` on the row).
_SET_TAIL_RE = re.compile(
    r"\s*\((?P<set>[A-Z0-9]{2,6}|[a-z0-9]{2,6})\)"
    r"(?:\s*(?P<number>[A-Za-z0-9][A-Za-z0-9-]*))?"
    r"\s*(?P<star>[★*])?\s*$")
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
    s = _HEADER_COUNT_RE.sub("", line.strip().lower()).rstrip(":").strip()
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


def _printing(tail: re.Match) -> dict:
    """{set_code, collector_number} from a matched set tail, as Scryfall
    spells them: the set code lower-case, a star as `★` (`76*` is how some
    exporters type Scryfall's `76★`).

    Stored as the paste says, unvalidated: the database holds oracle cards
    only, so there is no list of real printings to check against. A printing
    nothing recognises costs only its art — Forge export and the app fall
    back to the default printing.
    """
    set_code = tail.group("set").lower()
    if set_code.isdigit():
        # `(15)` is a count some exporters append, never a set code.
        return {}
    number = tail.group("number")
    if number and tail.group("star"):
        number += "★"
    return {"set_code": set_code, "collector_number": number}


def _parse_line(line: str) -> tuple[int, str, dict] | None:
    """Parse a card line into (quantity, name, printing). `printing` is
    {set_code, collector_number} when the line names one, else {}. Returns
    None if the line can't be interpreted as a card entry."""
    line = line.strip()
    if not line:
        return None
    # Strip trailing category / foil markers, then keep the printing.
    line = _CATEGORY_TAIL_RE.sub("", line)
    line = _FOIL_TAIL_RE.sub("", line)
    printing: dict = {}
    tail = _SET_TAIL_RE.search(line)
    if tail:
        printing = _printing(tail)
        line = line[:tail.start()]
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
            return qty, name, printing
    # Trailing "Card x4".
    m = _TRAIL_COUNT_RE.match(line)
    if m:
        try:
            qty = int(m.group(2))
        except ValueError:
            return None
        return qty, m.group(1).strip(), printing
    # Implicit quantity 1.
    return 1, line, printing


def parse_deckstring(text: str) -> list[dict]:
    """Top-level entry point. Returns a list of {name, quantity, section}
    dicts, plus {set_code, collector_number} on lines that name a printing.
    Order in the output follows the input order.
    """
    rows: list[dict] = []
    section = "main"
    # A UTF-8 BOM (Windows PowerShell's Out-File writes one) survives
    # str.strip(), and turned the first line into an unresolvable name.
    text = text.lstrip("\ufeff")
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
        if section == "tokens":
            continue
        row_section = section
        inline_sb = _INLINE_SB_RE.match(line)
        if inline_sb:
            line, row_section = inline_sb.group(1), "sideboard"
        parsed = _parse_line(line)
        if parsed is None:
            # Silently skip unparseable lines; callers decide how to surface.
            continue
        qty, name, printing = parsed
        rows.append({"name": name, "quantity": qty, "section": row_section,
                     **printing})
    return rows
