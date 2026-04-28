"""Per-deck analytics: mana curve, color-pip count, mana sources.

All inputs come from a deck dict produced by `decks.get_deck()`. No DB
access required — pure functions over the cards list. Sideboard cards
are excluded; commanders are included (you cast them from the command
zone, so their colored pips contribute to the mana base requirements).
"""
from __future__ import annotations

import re

# WUBRG canonical, plus C for colorless. Mana costs only ever use WUBRG;
# C only appears in mana production (lands like Wastes, Cabal Coffers).
_PIP_RE = re.compile(r"\{([WUBRG])\}")
_LAND_PIP_RE = re.compile(r"\{([WUBRGC])\}")

_BASIC_LAND_SUBTYPE_TO_COLOR = {
    "Plains": "W",
    "Island": "U",
    "Swamp": "B",
    "Mountain": "R",
    "Forest": "G",
}


def _is_land(type_line: str | None) -> bool:
    return bool(type_line) and "Land" in type_line


def _basic_land_color(type_line: str | None) -> str | None:
    """Return the produced color letter for a Basic Land, else None.

    Handles 'Basic Land — Forest', 'Basic Snow Land — Mountain', and the
    no-subtype 'Basic Land' (Wastes → colorless).
    """
    if not type_line or "Basic" not in type_line or "Land" not in type_line:
        return None
    parts = type_line.split("—", 1)
    subtype = parts[1].strip() if len(parts) > 1 else ""
    if not subtype:
        return "C"  # Wastes — basic with no land subtype
    for token in subtype.split():
        if token in _BASIC_LAND_SUBTYPE_TO_COLOR:
            return _BASIC_LAND_SUBTYPE_TO_COLOR[token]
    return "C"


def _land_sources(card: dict) -> set[str]:
    """Set of mana-color letters the land produces.

    Basic lands resolve from the type-line subtype. Non-basic lands scan
    `oracle_text` for `{W|U|B|R|G|C}` symbols — covers duals, shocks, fetches
    (the searchable subtype implies fetched colors but to keep it simple we
    just count what's printed on the card itself), pain lands, utility lands,
    etc. If oracle_text yields nothing, we fall back to the card's
    color_identity, then default to {C}.
    """
    type_line = card.get("type_line") or ""
    if not _is_land(type_line):
        return set()
    basic = _basic_land_color(type_line)
    if basic is not None:
        return {basic}
    text = card.get("oracle_text") or ""
    found = set(_LAND_PIP_RE.findall(text))
    if found:
        return found
    ci = (card.get("color_identity") or "")
    found = {ch for ch in ci.split(",") if ch}
    return found or {"C"}


def compute_deck_analytics(deck: dict) -> dict:
    """Compute analytics from a deck dict (`decks.get_deck()` output).

    Returns a dict with:
      mana_curve     — {0..6: count} where 6 means '6+'
      mv_avg         — mean mana value across non-land main-deck cards
      color_pips     — {W, U, B, R, G: count} of colored pips on non-lands
      pip_total      — sum of color_pips
      mana_sources   — {W, U, B, R, G, C: count} of land-produced sources
      nonland_count  — quantity of non-land main-deck cards
      land_count     — quantity of land cards (any kind)
    """
    curve = {i: 0 for i in range(7)}
    mv_total = 0
    nonland_count = 0
    land_count = 0
    pips = {c: 0 for c in "WUBRG"}
    sources = {c: 0 for c in "WUBRGC"}

    for card in deck.get("cards", []):
        if card.get("is_sideboard"):
            continue
        qty = card.get("quantity", 1) or 1
        type_line = card.get("type_line") or ""

        if _is_land(type_line):
            land_count += qty
            for c in _land_sources(card):
                sources[c] += qty
            continue

        nonland_count += qty
        mv = card.get("mana_value") or 0
        mv_total += mv * qty
        curve[min(6, mv)] += qty

        # DFC / split: mana_cost is "{1}{R} // {1}{U}". Only count the front
        # face — you can only cast one face at a time, so the back-face pips
        # don't add to your color requirements.
        mana_cost = card.get("mana_cost") or ""
        front_cost = mana_cost.split("//", 1)[0]
        for ch in _PIP_RE.findall(front_cost):
            pips[ch] += qty

    mv_avg = (mv_total / nonland_count) if nonland_count else 0.0
    return {
        "mana_curve": curve,
        "mv_avg": mv_avg,
        "color_pips": pips,
        "pip_total": sum(pips.values()),
        "mana_sources": sources,
        "nonland_count": nonland_count,
        "land_count": land_count,
    }
