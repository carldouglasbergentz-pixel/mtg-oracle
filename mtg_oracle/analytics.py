"""Per-deck analytics: mana curve, color-pip count, mana sources.

All inputs come from a deck dict produced by `decks.get_deck()`. No DB
access required — pure functions over the cards list. Sideboard cards
are excluded; commanders are included (you cast them from the command
zone, so their colored pips contribute to the mana base requirements).

Two-faced cards are classified by their FRONT face, matching the deck
renderer's type grouping. `"Land" in type_line` over the combined type
line counted `Emeria's Call // Emeria, Shattered Skyclave` as a pure
land, which both inflated the land count and dropped the front face's
mana value and pips out of the curve entirely.

A modal DFC whose back is a land is a spell you *may* play as a land, so
it counts in the curve and the pips AND as a mana source, and is
reported separately (`39 lands, 42 with MDFCs`). A transform card whose
back is a land is not: you reach that face by transforming, not by
playing it, so it is only ever a spell.
"""
from __future__ import annotations

import json
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


# Layouts where the player chooses which face to put onto the stack or the
# battlefield. Only these give a back-face land you can actually play; a
# `transform` back is reached by transforming, not by playing it.
_CHOOSE_A_FACE_LAYOUTS = frozenset({"modal_dfc"})


def _is_land(type_line: str | None) -> bool:
    """True when `Land` appears as a card type, not as a substring.

    `"Land" in type_line` also matched `Land — Town`'s neighbours like
    `Woodland Cemetery` — harmless there, but the same sloppiness is what
    made `Sorcery // Land` read as a land.
    """
    if not type_line:
        return False
    return any(word == "Land" for word in type_line.replace("—", " ").split())


def _faces(card: dict) -> list[dict]:
    """Per-face data from `cards.card_faces`, or [] for a single-faced card."""
    raw = card.get("card_faces")
    if not raw:
        return []
    if isinstance(raw, list):
        return raw
    try:
        parsed = json.loads(raw)
    except (json.JSONDecodeError, TypeError):
        return []
    return parsed if isinstance(parsed, list) else []


def _front_type_line(card: dict) -> str:
    """The front face's type line — what the card is when you look at it."""
    faces = _faces(card)
    if faces:
        return faces[0].get("type_line") or ""
    combined = card.get("type_line") or ""
    return combined.split(" // ", 1)[0]


def _playable_land_back(card: dict) -> dict | None:
    """The back face when it's a land the player may choose to play.

    None unless the layout lets you pick a face and the front isn't already
    a land (a Pathway is Land // Land — a full land, not a half one).
    """
    if (card.get("layout") or "") not in _CHOOSE_A_FACE_LAYOUTS:
        return None
    faces = _faces(card)
    if len(faces) < 2 or _is_land(faces[0].get("type_line")):
        return None
    for face in faces[1:]:
        if _is_land(face.get("type_line")):
            return face
    return None


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


def _mana_from_land(type_line: str, oracle_text: str, fallback_ci: str) -> set[str]:
    """Mana-color letters a land face produces.

    Basic lands resolve from the type-line subtype. Non-basic lands scan
    oracle text for `{W|U|B|R|G|C}` symbols — covers duals, shocks, fetches
    (the searchable subtype implies fetched colors but to keep it simple we
    just count what's printed on the card itself), pain lands, utility lands,
    etc. If the text yields nothing, fall back to color identity, then {C}.
    """
    basic = _basic_land_color(type_line)
    if basic is not None:
        return {basic}
    found = set(_LAND_PIP_RE.findall(oracle_text or ""))
    if found:
        return found
    found = {ch for ch in (fallback_ci or "").split(",") if ch}
    return found or {"C"}


def _land_sources(card: dict) -> set[str]:
    """Colors produced by a card whose FRONT face is a land.

    A Pathway is `Land // Land` and you choose a side, so it is a source of
    either colour and both are counted — that's how a deckbuilder reads a
    mana base. A transform card with a land front (`Balamb Garden, SeeD
    Academy`) only contributes its front: the other face isn't a land.
    """
    front = _front_type_line(card)
    if not _is_land(front):
        return set()
    faces = _faces(card)
    ci = card.get("color_identity") or ""
    if not faces:
        return _mana_from_land(front, card.get("oracle_text") or "", ci)

    land_faces = [f for f in faces if _is_land(f.get("type_line"))]
    if (card.get("layout") or "") not in _CHOOSE_A_FACE_LAYOUTS:
        land_faces = land_faces[:1]  # only the front is playable
    produced: set[str] = set()
    for face in land_faces:
        produced |= _mana_from_land(
            face.get("type_line") or "", face.get("oracle_text") or "", ci,
        )
    return produced


def compute_deck_analytics(deck: dict) -> dict:
    """Compute analytics from a deck dict (`decks.get_deck()` output).

    Returns a dict with:
      mana_curve      — {0..6: count} where 6 means '6+'
      mv_avg          — mean mana value across non-land main-deck cards
      color_pips      — {W, U, B, R, G: count} of colored pips on non-lands
      pip_total       — sum of color_pips
      mana_sources    — {W, U, B, R, G, C: count}, including MDFC land backs
      nonland_count   — quantity of non-land main-deck cards
      land_count      — quantity of cards whose front face is a land
      mdfc_land_count — quantity of spells with a playable land back face
      land_total      — land_count + mdfc_land_count, i.e. how many cards can
                        produce a land drop; this is what `mana_sources` counts
    """
    curve = {i: 0 for i in range(7)}
    mv_total = 0
    nonland_count = 0
    land_count = 0
    mdfc_land_count = 0
    pips = {c: 0 for c in "WUBRG"}
    sources = {c: 0 for c in "WUBRGC"}

    for card in deck.get("cards", []):
        if card.get("is_sideboard"):
            continue
        qty = card.get("quantity", 1) or 1

        if _is_land(_front_type_line(card)):
            land_count += qty
            for c in _land_sources(card):
                sources[c] += qty
            continue

        # Everything else is a spell — including a modal DFC whose back is a
        # land, because the front is what you cast and what costs mana.
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

        # ...and it still counts as a mana source, because you may play that
        # face as a land instead of casting the front.
        back = _playable_land_back(card)
        if back is not None:
            mdfc_land_count += qty
            for c in _mana_from_land(
                back.get("type_line") or "",
                back.get("oracle_text") or "",
                card.get("color_identity") or "",
            ):
                sources[c] += qty

    mv_avg = (mv_total / nonland_count) if nonland_count else 0.0
    return {
        "mana_curve": curve,
        "mv_avg": mv_avg,
        "color_pips": pips,
        "pip_total": sum(pips.values()),
        "mana_sources": sources,
        "nonland_count": nonland_count,
        "land_count": land_count,
        "mdfc_land_count": mdfc_land_count,
        "land_total": land_count + mdfc_land_count,
    }
