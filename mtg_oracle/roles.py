"""What a card *does*, and what it *really* costs.

Two questions this answers for any card, not just ones someone has curated:

  roles()          the functional roles a card can fill — counterspell,
                   sweeper, spot removal, cantrip, card advantage, threat,
                   tutor, mana, utility. Derived from oracle text and type
                   line, the same deterministic approach `tag_cards.py` uses.
  effective_mana() the least mana that actually gets you the card's effect,
                   which is frequently not the printed mana value.

Derivation covers any card in the database. `OVERRIDES` carries the
judgement calls that text alone cannot settle, each with its reason — so
what is a rule and what is an opinion stays visible.

WHY EFFECTIVE MANA IS THE LOAD-BEARING PART
-------------------------------------------
Force of Will is not a five-drop and Dig Through Time is not an eight-drop.
Any density or on-curve analysis run on printed mana value is measuring the
wrong deck. The conventions below are applied uniformly rather than per card,
so the result can be argued with as a whole instead of card by card.
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass
from typing import Optional

# --- the taxonomy -------------------------------------------------------

ROLES = (
    "land",      # a land by its front face
    "mana",      # non-land mana source (Mox, Sol Ring)
    "counter",   # counterspells, hard and soft
    "sweeper",   # mass removal
    "spot",      # single-target removal, bounce, tuck
    "tutor",     # searches the library for a specific card
    "threat",    # creature or planeswalker — something that wins
    "draw",      # net card advantage
    "cantrip",   # cheap selection, roughly card-neutral
    "utility",   # locks, hate pieces, engines — the fallback
)

LABELS = {
    "land": "Lands", "mana": "Fast mana", "counter": "Counterspells",
    "sweeper": "Sweepers / wraths", "spot": "Single-target removal",
    "tutor": "Tutors", "threat": "Threats / win conditions",
    "draw": "Card advantage", "cantrip": "Cantrips / selection",
    "utility": "Utility / lock / hate",
}

# Precedence for picking ONE primary role, so per-deck densities sum to the
# deck size and lists can be compared. Ordered by what the card is *for*:
# a Solitude is removal that happens to leave a body, so `spot` beats
# `threat`; a Brainstorm is none of the earlier things, so it lands on
# `cantrip`.
#
# `planeswalker` sits high on purpose. Almost every planeswalker can remove
# or draw something, and almost none of them are played as removal or as a
# cantrip — Jace, the Mind Sculptor's `-12` exiles a library, which read as
# a sweeper until this rule existed. Ones that really are lock pieces
# (Teferi, Time Raveler; Narset) are named in OVERRIDES.
PRIMARY_ORDER = ("land", "mana", "planeswalker", "sweeper", "counter",
                 "spot", "tutor", "threat", "draw", "cantrip", "utility")

# --- text patterns ------------------------------------------------------

_REMINDER = re.compile(r"\s*\([^)]*\)")
_FACE_SPLIT = re.compile(r"\s*//\s*")


def _clean(text: Optional[str]) -> str:
    """Oracle text with reminder text stripped and whitespace flattened."""
    return " ".join(_REMINDER.sub(" ", text or "").split()).lower()


_COUNTER = re.compile(r"\bcounter target\b|\bcounter it\b|\bcounter that spell\b")
# A returned *spell* is a soft counter (Reprieve); a returned permanent is not.
_SOFT_COUNTER = re.compile(r"return target spell to its owner's hand")

_SWEEPER = re.compile(
    # A sweeper clears the BATTLEFIELD. "Exile all cards from target player's
    # library" is a mill effect, and matching it made Jace, the Mind
    # Sculptor's -12 read as mass removal.
    r"\b(?:destroy|exile)\s+all\b(?!\s+(?:cards\s+from|graveyards))"
    r"|\bput all creatures\b"
    r"|\breturn all\b"
    r"|\bdestroy each\b"
    r"|\bdeals \d+ damage to each creature\b"
    r"|\beach creature (?:gets|gains) -\d+/-\d+\b"
    r"|\bsacrifices? all\b"
)
_SPOT = re.compile(
    r"\b(?:destroy|exile) (?:up to one )?target\b"
    r"|\bput target (?:attacking )?(?:creature|nonland permanent|artifact)\b"
    r"|\breturn target [^.]{0,50}?to its owner's hand\b"
    r"|\btarget creature .{0,40}(?:bottom of its owner's library|owner's hand)\b"
    r"|\bgain control of target\b"
    r"|\bexile each permanent with the most votes\b"
    # Damage and -N/-N are removal too. Absent at first because the UW lists
    # that seeded this module kill by exiling; the first red deck run through
    # it dropped Lightning Bolt, Flame Slash and eight others into `utility`.
    r"|\bdeals? \d+ damage to (?:any target|target|up to)\b"
    r"|\bdeals? damage equal to [^.]{0,40}to (?:any target|target)\b"
    r"|\btarget creature gets -\d+/-\d+\b"
    r"|\btarget creature gets -x/-x\b"
    # `divided as you choose among ... target creatures` (Pyrokinesis, Fire)
    # and the energy-payment phrasing (Galvanic Discharge) never say
    # "damage to target".
    r"|\bdamage divided as you choose among\b"
    # `.` rather than `[^.]` on purpose: Galvanic Discharge puts three
    # sentences between choosing the target and dealing the damage.
    r"|\bchoose target (?:creature|permanent).{0,180}?\bdeals that much damage\b"
)
_TUTOR = re.compile(r"\bsearch your library\b")

# Card advantage needs the *count*, not just the word "draw": Ancestral Recall
# and Opt both say "draw" and are not the same card. Two or more cards, or an
# unbounded amount, is advantage; exactly one replaces the card you spent.
_DRAW_MANY = re.compile(
    r"\bdraws? (?:two|three|four|five|six|seven|eight|nine|ten|x|half)\b"
    r"|\bdraw cards equal\b"
    r"|\bput (?:two|three) of (?:them|those cards) into your hand\b"
)
_DRAW_ONE = re.compile(r"\bdraws? a card\b")
# Plenty of blue selection never says "draw": Impulse, Stock Up and Fact or
# Fiction all put cards into your hand instead.
_TO_HAND = re.compile(r"\bput [^.]{0,24}?into your hand\b")
# Two or more into hand is advantage; one is selection.
_TO_HAND_MANY = re.compile(r"\bput (?:two|three|four) (?:of (?:them|those cards) )?"
                           r"into your hand\b")
# The Brainstorm template: the draw is partly given back, so the card is
# selection rather than advantage no matter how many it says it draws.
_PUT_BACK = re.compile(r"\bput (?:one|two|three) cards? from your hand on top\b"
                       r"|\bput the rest on the bottom\b")
_SELECT = re.compile(r"\bscry \d\b|\bsurveil \d\b|\blook at the top\b"
                     r"|\breveal the top\b|\bmill (?:a|one|two)\b")
_INVESTIGATE = re.compile(r"\binvestigate\b")
_WINCON = re.compile(r"\byou win the game\b")
_MAKES_CREATURES = re.compile(r"\bcreate\b[^.]{0,70}\bcreature tokens?\b")
_PRODUCES_MANA = re.compile(r":\s*add\b")


def _faces(card: dict) -> list[dict]:
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


def front_type_line(card: dict) -> str:
    """The type line of the face you look at — what the card *is*."""
    faces = _faces(card)
    if faces:
        return faces[0].get("type_line") or ""
    return _FACE_SPLIT.split(card.get("type_line") or "", 1)[0]


def _is_land_word(type_line: Optional[str]) -> bool:
    """`Land` as a card type in a type line, not as a substring.

    `"Land" in type_line` also matches `Woodland Cemetery` — harmless there,
    but the same looseness made `Sorcery // Land` read as a land.
    """
    return any(w == "Land" for w in (type_line or "").replace("—", " ").split())


def is_land(card: dict) -> bool:
    """True when the card is a land by the face you look at."""
    return _is_land_word(front_type_line(card))


# --- second faces: which ones you can actually cast from hand -----------

# A whitelist, not a blacklist, and every entry carries its rule. Getting
# this wrong is not a rounding error: `prepare` was assumed to work like
# `adventure`, which turned a five-mana creature into a one-mana Ancestral
# Recall in an entire analysis. See corrections table, topic
# `prepare_cards_cannot_be_cast_for_their_inset_frame`.
#
#   adventure  CR 715  — cast either half from hand
#   omen       CR 716  — cast either half from hand
#   modal_dfc  CR 712  — choose a face as you play it
#   split      CR 709  — choose a half as you cast it. Scryfall's mana value
#              for a split card is the SUM of both halves, so Fire // Ice
#              reads as a four-drop until the cheaper half is taken.
#
# Deliberately absent:
#   prepare    CR 722.3 — "Preparation cards can't be cast using the
#              alternative characteristics found within their inset frames."
#              The inset frame is only castable as a COPY created in exile
#              once the front face has resolved (722.3c). Always at least
#              the front face's cost.
#   transform  CR 712  — the back is reached by transforming, never by casting.
#   meld       CR 727  — likewise.
CASTABLE_SECOND_FACE = frozenset({"adventure", "omen", "modal_dfc", "split"})


# A draw that happens again next turn, rather than once on resolution.
# `-3: ... Draw a card` (loyalty), `{1}, {T}: Draw two cards` (activated), and
# `Whenever you scry, ...` / `At the beginning of your upkeep` (recurring
# triggers) all repeat. `When this creature enters, draw a card` does not —
# that is an ETB, and treating it as an engine would make Snapcaster Mage a
# card-advantage engine.
_LOYALTY_ABILITY = re.compile(r"^[+−-]?\d+\s*:")
# `{1}, {T}: Draw two cards` — a cost, then a colon. Bounded so a colon that
# turns up later in a sentence cannot make prose look like an ability.
_ACTIVATED = re.compile(r"^[^:]{0,40}:\s")
_RECURRING_TRIGGER = re.compile(r"\bwhenever\b|\bat the beginning of\b", re.IGNORECASE)
_ETB_ONLY = re.compile(r"\bwhen (?:this|[A-Z][^,]{0,40}) enters\b", re.IGNORECASE)


def _is_engine(card: dict, has_draw: bool) -> bool:
    """True when a permanent's card draw repeats rather than happening once."""
    if not has_draw:
        return False
    front = front_type_line(card)
    if any(t in front for t in ("Instant", "Sorcery")) or _is_land_word(front):
        return False
    if "Planeswalker" in front:
        return True   # loyalty abilities are per-turn by construction
    raw = card.get("oracle_text") or ""
    # Find the clause that actually draws, and ask whether it repeats.
    for line in raw.split(chr(10)):
        low = _clean(line)
        if not (_DRAW_MANY.search(low) or _DRAW_ONE.search(low)
                or _TO_HAND.search(low)):
            continue
        if _ETB_ONLY.search(line) and not _RECURRING_TRIGGER.search(line):
            continue
        if (_LOYALTY_ABILITY.search(line) or _ACTIVATED.search(line)
                or _RECURRING_TRIGGER.search(line)):
            return True
    return False


def _has_printed_body(card: dict) -> bool:
    """True when the card's own power/toughness survives without counters.

    A `{X}` creature printed as a 1/1 is castable at X=0; one printed as a
    0/0 that relies on X counters is not.
    """
    faces = _faces(card)
    src = faces[0] if faces else card
    power = str(src.get("power") or card.get("power") or "").strip()
    tough = str(src.get("toughness") or card.get("toughness") or "").strip()
    if not power or not tough:
        return False
    # `*` and other non-numeric values depend on the board; treat as no body.
    if not power.lstrip("+-").isdigit() or not tough.lstrip("+-").isdigit():
        return False
    return int(tough) > 0


def _face_mana_value(cost: str) -> Optional[int]:
    """Mana value of a single face's cost string, with {X} counted as 0."""
    if not cost:
        return None
    total = 0
    for sym in re.findall(r"\{([^}]+)\}", cost):
        if sym.isdigit():
            total += int(sym)
        elif sym.upper() == "X":
            continue
        else:
            total += 1  # coloured, hybrid, phyrexian, snow — all one
    return total


# --- effective mana -----------------------------------------------------

# {X} spells are costed at this X. The floor is the smallest X at which the
# card does the job it is being counted for.
#
#   X sizes an ANSWER          -> 2. The smallest X that kills a real card in
#     (Wrath of the Skies,        this format: a two-drop, or a Mox plus a
#      Prismatic Ending,          one-drop. X=0 would make Wrath of the Skies
#      March of Otherworldly      a two-mana sweeper, which it is not.
#      Light, Logic Knot)
#
#   X sizes a DRAW             -> 2. One card for four mana is not card
#     (Sphinx's Revelation,       advantage, it is a bad Divination, so the
#      Blue Sun's Zenith,         role's floor is two.
#      Pull from Tomorrow)
#
#   X sizes a BODY             -> 1. One 4/4 flying Angel for {1}{W}{W} is a
#     (Entreat the Angels,        threat, full stop. Pricing it at X=2 charges
#      Forth Eorlingas!)          for a second Angel the role does not need.
X_VALUE = 2
X_VALUE_TOKENS = 1

# `Create X ... creature tokens` / `X +1/+1 counters`: the X buys bodies, and
# one body already performs the `threat` role.
_X_MAKES_BODIES = re.compile(
    r"\bcreate x\b[^.]{0,60}\bcreature tokens?\b"
    r"|\bput x \+1/\+1 counters\b",
    re.IGNORECASE,
)

# The most a card can cost and still count as selection rather than card
# advantage. Impulse and Consult the Star Charts sit at two; Stock Up at
# three is buying cards.
CANTRIP_MAX_MANA = 2

# Cards assumed in the graveyard when a delve spell is cast. Delve pays
# generic mana only, so the floor is the coloured pips.
DELVE_YARD = 6

_FREE_ALT = re.compile(
    r"rather than pay this spell's mana cost"
    r"|rather than pay its mana cost"
)
_EVOKE_FREE = re.compile(r"evoke\s*[—-]\s*exile", re.IGNORECASE)
# A cost is a run of brace symbols, so capture them all: `warp {1}{U}` is two
# mana, and stopping at the first `}` prices it as one.
_COST_RUN = r"((?:\{[^}]+\})+)"
_EVOKE_COST = re.compile(r"evoke\s*" + _COST_RUN, re.IGNORECASE)
_DELVE = re.compile(r"\bdelve\b", re.IGNORECASE)
_MIRACLE = re.compile(r"\bmiracle\s*" + _COST_RUN, re.IGNORECASE)
_WARP = re.compile(r"\bwarp\s*" + _COST_RUN, re.IGNORECASE)
# Phyrexian mana can always be paid with life, so it costs no mana at all.
_PHYREXIAN_ONLY = re.compile(r"^(?:\{[WUBRGC]/P\})+$", re.IGNORECASE)


@dataclass(frozen=True)
class Cost:
    """What a card costs, printed and effective, and why they differ."""
    printed: int
    effective: int
    reason: str = ""
    alternative: Optional[int] = None   # miracle etc: an upside, not the norm
    alternative_reason: str = ""

    @property
    def adjusted(self) -> bool:
        return self.effective != self.printed


def effective_mana(card: dict, x_value: int = X_VALUE) -> Cost:
    """The least mana that gets you this card's main effect.

    Conventions, applied uniformly:
      free alternative cost (pitch, pay life, return a land)  -> 0
      evoke that exiles a card                                -> 0
      warp (CR 702.185a — castable from hand)                 -> the warp cost
      delve                                                   -> coloured pips
      {X} in the cost                                         -> X = x_value
      a cheaper second face that is castable from hand        -> that face
      miracle                                                 -> NOT applied;
        reported as `alternative` instead. A pitch cost is reliable — you
        decide to pay it. Miracle is conditional on draw order, and treating
        Terminus as a one-mana sweeper claims a UW deck can wrath on turn one.
    """
    printed = int(card.get("mana_value") or 0)
    cost_str = card.get("mana_cost") or ""
    text = _clean(card.get("oracle_text"))
    layout = card.get("layout") or "normal"

    def x_count(s: str) -> int:
        return len(re.findall(r"\{X\}", s, re.IGNORECASE))

    # One body already does the `threat` job, so X buys nothing extra for the
    # purposes of "when can I deploy this". See X_VALUE_TOKENS.
    x_each = X_VALUE_TOKENS if _X_MAKES_BODIES.search(text) else x_value

    alt, alt_reason = None, ""
    m = _MIRACLE.search(card.get("oracle_text") or "")
    if m:
        mv = _face_mana_value(m.group(1))
        if mv is not None:
            alt = mv + x_each * x_count(m.group(1))
            alt_reason = f"miracle {m.group(1)}"

    def out(eff, reason):
        return Cost(printed, eff, reason, alt, alt_reason)

    if _PHYREXIAN_ONLY.match(cost_str.strip()):
        return out(0, "Phyrexian mana — payable with life")
    if _FREE_ALT.search(text):
        return out(0, "free alternative cost")
    if _EVOKE_FREE.search(card.get("oracle_text") or ""):
        return out(0, "evoke — exile a card")

    w = _WARP.search(card.get("oracle_text") or "")
    if w:
        mv = _face_mana_value(w.group(1))
        if mv is not None and mv < printed:
            return out(mv, f"warp {w.group(1)}")

    e = _EVOKE_COST.search(card.get("oracle_text") or "")
    if e:
        mv = _face_mana_value(e.group(1))
        if mv is not None and mv < printed:
            return out(mv, f"evoke {e.group(1)}")

    # A second face you may cast from hand, if it is cheaper.
    if layout in CASTABLE_SECOND_FACE:
        faces = _faces(card)
        cheapest = None
        for face in faces[1:]:
            mv = _face_mana_value(face.get("mana_cost") or "")
            if mv is None:
                continue
            if cheapest is None or mv < cheapest[0]:
                cheapest = (mv, face.get("name") or "back face")
        if cheapest and cheapest[0] < printed:
            return out(cheapest[0], f"{cheapest[1]} costs {cheapest[0]}")

    if _DELVE.search(text):
        pips = sum(
            1 for sym in re.findall(r"\{([^}]+)\}", cost_str)
            if not sym.isdigit() and sym.upper() != "X"
        )
        generic = sum(int(s) for s in re.findall(r"\{(\d+)\}", cost_str))
        eff = pips + max(0, generic - DELVE_YARD)
        if eff < printed:
            return out(eff, f"delve with {DELVE_YARD} cards in the yard")

    n_x = x_count(cost_str)
    if n_x:
        # X=0 is a real mode for a creature that has a printed body: Wan Shi
        # Tong, Librarian is {X}{U}{U} on a 1/1, so for two mana you get a
        # flash flier with vigilance and X only buys counters on top. Costing
        # it at X=2 prices a card the deck never has to pay for. A base 0/0
        # (Walking Ballista, Hangarback Walker) dies at X=0 and is excluded.
        if "Creature" in front_type_line(card) and _has_printed_body(card):
            return out(printed, "{X} creature with a printed body — X=0 is castable")
        # Entreat the Angels is {X}{X}{W}{W}{W}: two X's, so X=2 costs seven.
        return out(printed + x_each * n_x,
                   f"{{X}} at X={x_each}" + (f" (×{n_x})" if n_x > 1 else ""))

    return out(printed, "")


# --- role derivation ----------------------------------------------------

def has_land_back(card: dict) -> bool:
    """True for a spell whose *back* face is a land you may choose to play.

    Only modal DFCs qualify: a `transform` back is reached by transforming,
    not by playing it. Such a card is a spell that also makes a land drop,
    so the analysis counts it as a mana source without counting its spell
    half as castable.
    """
    if (card.get("layout") or "") != "modal_dfc":
        return False
    faces = _faces(card)
    if len(faces) < 2 or is_land(card):
        return False
    return any(_is_land_word(f.get("type_line")) for f in faces[1:])


def _role_text(card: dict) -> str:
    """The oracle text that describes what you can actually do with the card.

    The front face always counts. A second face counts only when you may cast
    it from hand — otherwise a modal DFC's land back turns every such card
    into a mana source, and Ondu Inversion stops being a sweeper.
    """
    faces = _faces(card)
    if not faces:
        return _clean(card.get("oracle_text"))
    parts = [faces[0].get("oracle_text") or ""]
    if (card.get("layout") or "") in CASTABLE_SECOND_FACE:
        for f in faces[1:]:
            if not _is_land_word(f.get("type_line")):
                parts.append(f.get("oracle_text") or "")
    return _clean(" ".join(parts))


def derive_roles(card: dict) -> set[str]:
    """Every role the card's text and types say it can fill."""
    if is_land(card):
        return {"land"}

    front = front_type_line(card)
    text = _role_text(card)
    found: set[str] = set()

    if _COUNTER.search(text) or _SOFT_COUNTER.search(text):
        found.add("counter")
    if _SWEEPER.search(text):
        found.add("sweeper")
    # No `target` guard: Council's Judgment is removal precisely because it
    # doesn't target, and every other alternative above spells out "target".
    if _SPOT.search(text):
        found.add("spot")
    if _TUTOR.search(text):
        found.add("tutor")
    # Card advantage means NET cards. Brainstorm says "draw three" and gives
    # two back, so the count alone is not enough; a card that puts the rest
    # on the bottom, or its own hand back on top, is selection.
    net_positive = (
        (_DRAW_MANY.search(text) and not _PUT_BACK.search(text))
        or _TO_HAND_MANY.search(text)
        or (_DRAW_ONE.search(text) and _INVESTIGATE.search(text))
    )
    if net_positive:
        found.add("draw")
    if "Creature" in front or "Planeswalker" in front:
        found.add("threat")
    if _WINCON.search(text) or _MAKES_CREATURES.search(text):
        found.add("threat")
    # Only a card whose *whole* job is mana. Without the guard, every modal
    # DFC with a land back reads as a Mox.
    if _PRODUCES_MANA.search(text) and not found and "Creature" not in front:
        found.add("mana")
    # Selection — but only when the card is not already net card advantage,
    # or Dig Through Time reads as a cantrip because it also bottoms the
    # rest. `classify()` decides whether it is cheap enough to stay a
    # cantrip, because only it knows the effective mana value.
    if not net_positive and (
        _SELECT.search(text) or _DRAW_ONE.search(text)
        or _TO_HAND.search(text) or _PUT_BACK.search(text)
    ):
        found.add("cantrip")
    return found or {"utility"}


@dataclass
class Classification:
    """One card's roles, primary role and cost — plus how it was decided."""
    name: str
    primary: str
    roles: tuple[str, ...]
    cost: Cost
    source: str = "derived"          # derived | override
    reason: str = ""
    # True when nothing in the text matched and the card fell through to
    # `utility`. Surfaced by the CLI: on an unfamiliar deck it is the list of
    # cards whose classification deserves a human look.
    low_confidence: bool = False
    # True for a permanent that keeps drawing cards, turn after turn, rather
    # than once. Elminster and Teferi, Hero of Dominaria draw *every* turn;
    # Memory Deluge draws twice, once. Both are card advantage and the role
    # set says so — but they are not interchangeable when you are deciding how
    # many you need, because five permanents that draw is an engine count and
    # fourteen spells that draw is a resource count.
    engine: bool = False


# Curated judgement calls. Text alone cannot decide these, so they are
# listed with a reason rather than buried in a heuristic.
#   name: (primary, extra roles to add, mv override or None, reason)
OVERRIDES: dict[str, tuple[str, tuple[str, ...], Optional[int], str]] = {
    # Planeswalkers whose job is not the removal ability they happen to have.
    "Teferi, Time Raveler":      ("utility", ("spot", "cantrip"), None,
                                  "flash-lock is the reason it is played"),
    "Narset, Parter of Veils":   ("utility", ("cantrip",), None, "draw-hate"),
    # Creatures that lock rather than attack.
    "Harbinger of the Seas":     ("utility", ("threat",), None, "nonbasic lock"),
    "Hullbreacher":              ("utility", ("threat",), None, "draw-hate"),
    # Removal that leaves a body — the removal is the point.
    "Solitude":                  ("spot", ("threat",), None, ""),
    "Subtlety":                  ("counter", ("threat",), None,
                                  "soft-counters a creature or planeswalker spell"),
    "Fractured Identity":        ("spot", ("threat",), None,
                                  "exile, and in 1v1 you keep the copy"),
    "Brazen Borrower":           ("spot", ("threat",), None, "Petty Theft is the mode you want"),
    # Delve threats need to be large, not merely castable.
    "Murktide Regent":           ("threat", (), 4,
                                  "delve floor is 2 but a small Murktide is not a threat"),
    # Cycling that is the actual mode.
    "Shark Typhoon":             ("threat", ("draw",), 4,
                                  "cycling {X}{1}{U} at X=2 makes a 2/2 and draws"),
    # Spree / modal costs the parser cannot price.
    "Three Steps Ahead":         ("counter", ("draw", "threat"), 3,
                                  "spree: {U} plus {1}{U} for the counter mode"),
    # Tuck effects at X=0 are real removal.
    "Unexpectedly Absent":       ("spot", (), 2, "{X}{W}{W} at X=0 tucks on top"),
    # Three mana is three mana whatever converge reaches — but what it kills
    # depends on how many colours the deck can actually spend, which is a
    # property of the manabase and not of the card. A two-colour deck exiles
    # mana value 2; add one source of a third colour (a Triome) and the same
    # three mana exiles mana value 3.
    "Prismatic Ending":          ("spot", (), 3, "{X}{W} at X=2"),
    # A preparation card: see CASTABLE_SECOND_FACE. Listed explicitly so the
    # 5-mana body is never mistaken for the {U} inset frame again.
    "Emeritus of Ideation":      ("threat", ("draw",), None,
                                  "CR 722.3 — the inset frame is not castable from hand; "
                                  "the Recall copy costs a further {U} after the body resolves"),
    # Mana that is also interaction.
    "Mana Drain":                ("counter", ("mana",), None, ""),
    # Engines.
    "Search for Azcanta":        ("utility", ("draw",), None, "engine, transforms into a land"),
    "Shadow of the Second Sun":  ("utility", (), None, "extra upkeep, draw and untap each turn"),
    "Elixir of Immortality":     ("utility", (), None, "recursion / anti-mill"),
    "Ghost Vacuum":              ("utility", (), None, "graveyard hate"),
    "Dress Down":                ("utility", ("cantrip",), None, ""),
    # Conditional sweeper.
    "Settle the Wreckage":       ("sweeper", (), None, "conditional — attackers only"),
    # Priced by its Omen half, so it should be labelled by it too: a
    # four-mana draw-three that can instead be a six-mana 4/4 flier.
    "Marang River Regent":       ("draw", ("threat", "spot"), None,
                                  "the Omen half is the mode that gets cast"),
    # Delve pays generic mana, and {X} in a delve spell's cost is generic —
    # so the graveyard partly pays for X, which the cost parser cannot see.
    "Logic Knot":                ("counter", (), 3,
                                  "{X}{U}{U} at X=2, with delve paying part of X"),
    # Conditional upgrades: the text describes a two-card mode that needs a
    # kicker or a graveyard, while the cost we price is the base mode. Label
    # by the base mode so cost and role agree; the upgrade is upside.
    "Consult the Star Charts":   ("cantrip", ("draw",), None,
                                  "two cards only when kicked for {1}{U} more"),
    "Flow State":                ("cantrip", ("draw",), None,
                                  "two cards only with an instant and a sorcery in the yard"),
}


def _override_for(name: str, card: dict) -> Optional[tuple]:
    """Look up a curated override by any name the card is known by.

    Two-faced cards are stored under their full name (`Marang River Regent //
    Coil and Catch`) but everyone — decklists, players, this table — writes
    the front face. Keying only on the exact stored name silently dropped
    every override for a two-faced card.
    """
    if name in OVERRIDES:
        return OVERRIDES[name]
    front = _FACE_SPLIT.split(name, 1)[0].strip()
    if front in OVERRIDES:
        return OVERRIDES[front]
    faces = _faces(card)
    if faces:
        face_name = (faces[0].get("name") or "").strip()
        if face_name in OVERRIDES:
            return OVERRIDES[face_name]
    return None


def classify(card: dict, x_value: int = X_VALUE) -> Classification:
    """Roles, primary role and effective cost for one card."""
    name = card.get("name") or card.get("card_name") or "?"
    cost = effective_mana(card, x_value)
    derived = derive_roles(card)

    # Cheap selection is a cantrip; the same shape at more mana is card
    # advantage. Two mana is the line — Impulse is selection, Stock Up at
    # three is buying cards, and nobody calls Fact or Fiction a cantrip.
    #
    # This applies even when the card has other roles: a four-mana counter
    # that also draws is a two-for-one, and Cryptic Command is a third of
    # this archetype's card advantage precisely because of that mode.
    #
    # It does NOT apply to a permanent whose draw repeats. Faerie Mastermind
    # costs two and draws a card every time an opponent overdraws, which is
    # card advantage however cheap it is — the cost test only makes sense for
    # a spell that resolves once.
    repeats = _is_engine(card, "draw" in derived or "cantrip" in derived)
    if "cantrip" in derived and not repeats:
        if cost.effective > CANTRIP_MAX_MANA:
            derived.discard("cantrip")
            derived.add("draw")
        else:
            derived.discard("draw")
    elif repeats:
        derived.add("draw")
        derived.discard("cantrip")

    override = _override_for(name, card)
    if override:
        primary, extra, mv, reason = override
        roles = derived | set(extra) | {primary}
        roles.discard("planeswalker")
        if mv is not None:
            cost = Cost(cost.printed, mv, reason or f"see OVERRIDES[{name!r}]",
                        cost.alternative, cost.alternative_reason)
        return Classification(name, primary, tuple(sorted(roles)), cost,
                              "override", reason,
                              engine=_is_engine(card, "draw" in roles))

    # Derived primary, by precedence. Planeswalkers are threats before they
    # are removal — nearly all of them can remove something, and nearly none
    # of them are played as removal.
    front = front_type_line(card)
    primary = None
    for role in PRIMARY_ORDER:
        if role == "planeswalker":
            if "Planeswalker" in front:
                primary = "threat"
                break
            continue
        if role in derived:
            primary = role
            break
    primary = primary or "utility"

    low = derived == {"utility"} and not is_land(card)
    return Classification(name, primary, tuple(sorted(derived)), cost,
                          "derived", cost.reason, low_confidence=low,
                          engine=_is_engine(card, "draw" in derived))
