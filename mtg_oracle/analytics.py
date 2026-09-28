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

A split card is the exception to "front face": Scryfall's mana value for
it is the SUM of both halves, so it is placed by its cheapest half you can
cast from hand — the same face `roles.effective_mana` prices it at — and
its pips come from that half. An aftermath half is cast only from the
graveyard and is never that face.
"""
from __future__ import annotations

import json
import re

COLORS = "WUBRG"
_SYMBOL_RE = re.compile(r"\{([^}]+)\}")

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


# --- what a spell costs ---------------------------------------------------

def _symbol_mana_value(symbol: str) -> int:
    """CR 202.3 mana value of one cost symbol: `{2/W}` is 2, `{X}` is 0."""
    head = symbol.split("/", 1)[0]
    if head.isdigit():
        return int(head)
    if symbol in ("X", "Y", "Z"):
        return 0
    return 1


def _cost_mana_value(cost: str) -> int:
    return sum(_symbol_mana_value(s) for s in _SYMBOL_RE.findall(cost.upper()))


def _is_aftermath(face: dict) -> bool:
    """CR 702.127a: an aftermath half is cast only from the graveyard."""
    return (face.get("oracle_text") or "").lstrip().lower().startswith("aftermath")


def _cast_cost(card: dict) -> tuple[int, str]:
    """`(mana value, mana cost)` of the face this card is cast as.

    The front face, except for a split card: there the printed mana value is
    the sum of both halves, so the cheapest half castable from hand is used.
    """
    if (card.get("layout") or "") == "split":
        castable = [f for f in _faces(card) if not _is_aftermath(f)]
        if castable:
            cost = min((f.get("mana_cost") or "" for f in castable),
                       key=_cost_mana_value)
            return _cost_mana_value(cost), cost
    mana_cost = card.get("mana_cost") or ""
    return card.get("mana_value") or 0, mana_cost.split("//", 1)[0]


def cost_pips(cost: str) -> dict[str, int]:
    """Colour requirements of one mana cost, as `{W, U, B, R, G, C: count}`.

    A hybrid symbol `{G/W}` counts one pip toward EACH of its colours: it is
    a requirement either colour can satisfy, and a mana base short of both
    cannot cast it — so each colour has to know it is being asked. A
    consequence is that the sum of the buckets is a count of requirements,
    not of symbols. Twobrid `{2/W}` and Phyrexian `{B/P}` count their colour
    (you pay it, or pay more / pay life instead). `{C}` is its own bucket:
    Eldrazi need a colourless source, which is not the same as generic mana.
    `{X}`, generic and snow `{S}` ask for no colour.
    """
    pips = {c: 0 for c in COLORS + "C"}
    for symbol in _SYMBOL_RE.findall(cost.upper()):
        if symbol == "C":
            pips["C"] += 1
            continue
        for colour in {part for part in symbol.split("/") if part in COLORS}:
            pips[colour] += 1
    return pips


# --- what a land produces -------------------------------------------------

# `Add ...` up to the end of the sentence — the only clause that says what a
# land makes. Scanning the whole text counted activation costs as colours:
# Kor Haven's `{1}{W}, {T}: Prevent ...` made it a white source.
_ADD_CLAUSE = re.compile(r"\badd\b([^.]*)", re.IGNORECASE)
_ANY_COLOR = re.compile(
    r"\bany (?:one )?colou?r\b|\bany combination of colou?rs\b|\bany type\b",
    re.IGNORECASE)
# Command Tower, Arcane Signet's lands, Path of Ancestry. Also Thriving lands'
# "the chosen color", which the player picks from the deck's colours.
_DECK_COLORS = re.compile(r"\bcommander's colou?r identity\b|\bthe chosen colou?r\b",
                          re.IGNORECASE)
# Fetchlands: "Search your library for an Island or Swamp card".
_FETCH = re.compile(r"\bsearch (?:your|their) library for ([^.]*?)\bcards?\b",
                    re.IGNORECASE)


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


def _land_type_colors(text: str) -> set[str]:
    return {c for name, c in _BASIC_LAND_SUBTYPE_TO_COLOR.items()
            if re.search(rf"\b{name}\b", text)}


def _mana_from_land(type_line: str, oracle_text: str, fallback_ci: str,
                    deck_colors: frozenset[str]) -> set[str]:
    """Mana-color letters a land face produces.

    Basic lands resolve from the type-line subtype. Otherwise the `Add ...`
    clauses decide: printed symbols, "any color" (all five), and "your
    commander's color identity" (the deck's colours). A fetchland produces
    what it can fetch — the land types it names, or the deck's colours for
    "a basic land card". A land that says neither (Maze of Ith) falls back
    to its own colour identity, then to {C}.

    "Any color" and fetched colours are clipped to `deck_colors` when those
    are known: City of Brass in a Rakdos deck is a black and a red source,
    and reporting it as green too describes mana the deck never asks for.
    Printed symbols are never clipped — Tundra makes {W} whatever the deck.
    """
    basic = _basic_land_color(type_line)
    if basic is not None:
        return {basic}
    text = oracle_text or ""
    found: set[str] = set()
    flexible: set[str] = set()   # colours the player picks: clipped below
    for clause in _ADD_CLAUSE.findall(text):
        found |= {s for s in _SYMBOL_RE.findall(clause.upper()) if s in COLORS + "C"}
        if _DECK_COLORS.search(clause):
            found |= deck_colors
        elif _ANY_COLOR.search(clause):
            flexible |= set(COLORS)
    for target in _FETCH.findall(text):
        named = _land_type_colors(target)
        if named:
            flexible |= named
        elif re.search(r"\bland\b", target, re.IGNORECASE):
            found |= deck_colors
    found |= (flexible & deck_colors) if deck_colors else flexible
    if flexible and not found:
        # An Arid Mesa in a mono-blue deck fetches nothing it can use. That is
        # no source at all — not a colourless one, which the fallback says.
        return set()
    if found:
        return found
    found = {ch for ch in (fallback_ci or "").split(",") if ch}
    return found or {"C"}


def _land_sources(card: dict, deck_colors: frozenset[str]) -> set[str]:
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
        return _mana_from_land(front, card.get("oracle_text") or "", ci, deck_colors)

    land_faces = [f for f in faces if _is_land(f.get("type_line"))]
    if (card.get("layout") or "") not in _CHOOSE_A_FACE_LAYOUTS:
        land_faces = land_faces[:1]  # only the front is playable
    produced: set[str] = set()
    for face in land_faces:
        produced |= _mana_from_land(
            face.get("type_line") or "", face.get("oracle_text") or "", ci,
            deck_colors,
        )
    return produced


def _identity(card: dict) -> set[str]:
    return {c for c in (card.get("color_identity") or "").split(",") if c}


def deck_colors(cards: list[dict]) -> frozenset[str]:
    """The colours this deck's mana is for — what flexible sources are clipped to.

    The commanders' colour identity when the deck has commanders — nothing
    outside it can be cast. Otherwise the union of EVERY main-deck card's
    colour identity, lands included. That is deliberate: a Canadian
    Highlander deck runs off-colour duals and surveil lands to power
    Prismatic Ending's converge, so a UW list with a B/G surveil land really
    does want black and green mana, and City of Brass counts toward them.
    Only colours nothing in the deck touches are clipped. Empty when unknown
    (an empty or all-colourless deck). Read from the deck dict itself, so the
    module stays free of database calls.
    """
    main = [c for c in cards if not c.get("is_sideboard")]
    commanders = [c for c in main if c.get("is_commander")]
    colours: set[str] = set()
    for card in commanders or main:
        colours |= _identity(card)
    return frozenset(colours & set(COLORS))


def compute_deck_analytics(deck: dict) -> dict:
    """Compute analytics from a deck dict (`decks.get_deck()` output).

    Returns a dict with:
      mana_curve      — {0..6: count} where 6 means '6+'
      mv_avg          — mean mana value across non-land main-deck cards
      color_pips      — {W, U, B, R, G, C: count} of colour requirements on
                        non-lands; see `cost_pips` for how hybrid counts
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
    pips = {c: 0 for c in COLORS + "C"}
    sources = {c: 0 for c in COLORS + "C"}
    cards = deck.get("cards", [])
    colours = deck_colors(cards)

    for card in cards:
        if card.get("is_sideboard"):
            continue
        qty = card.get("quantity", 1) or 1

        if _is_land(_front_type_line(card)):
            land_count += qty
            for c in _land_sources(card, colours):
                sources[c] += qty
            continue

        # Everything else is a spell — including a modal DFC whose back is a
        # land, because the front is what you cast and what costs mana. You
        # cast one face at a time, so the other face's pips add nothing.
        nonland_count += qty
        mv, cost = _cast_cost(card)
        mv_total += mv * qty
        curve[min(6, mv)] += qty
        for c, n in cost_pips(cost).items():
            pips[c] += n * qty

        # ...and it still counts as a mana source, because you may play that
        # face as a land instead of casting the front.
        back = _playable_land_back(card)
        if back is not None:
            mdfc_land_count += qty
            for c in _mana_from_land(
                back.get("type_line") or "",
                back.get("oracle_text") or "",
                card.get("color_identity") or "",
                colours,
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
