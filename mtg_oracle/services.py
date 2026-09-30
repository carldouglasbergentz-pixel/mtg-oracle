"""Use-case layer: one function per user intent.

The layer that sits between the data modules (`queries`, `decks`,
`scryfall_search`) and the interfaces (`mtg_oracle.tui`, `scripts/mtg_cli.py`,
and eventually a tool-calling LLM layer). Everything here takes plain values,
returns plain data, and raises `ServiceError` — no argparse namespaces, no
widget state, no printing.

What belongs here is composition: an intent that needs more than one data
call, or that needs a decision made before the call. A one-line pass-through
to `queries` or `decks` does not belong here — call those directly.

The rule is what the duplication taught us: `paste` and `deck import` both
grew their own copy of "parse a deckstring and load it", and only one copy
was right. Anything two interfaces both need lives here once.
"""
from __future__ import annotations

from dataclasses import dataclass, field, replace
from pathlib import Path
from typing import Optional

from mtg_oracle import decks as d
from mtg_oracle import forge_client as fc
from mtg_oracle import forge_data as fd
from mtg_oracle import forge_format as ff
from mtg_oracle import probability
from mtg_oracle import queries as q
from mtg_oracle import roles
from mtg_oracle import scryfall_search as ss
from mtg_oracle.deck_parser import parse_deckstring


class ServiceError(Exception):
    """Anything a caller should show the user rather than crash on."""


# --- where the user is -------------------------------------------------

@dataclass(frozen=True)
class DeckRef:
    """A deck, a folder, or neither — the caller's current scope.

    The TUI holds this as a cwd, the CLI passes it per invocation as
    `--folder` plus a deck name. Same value either way, so the services
    below take one argument instead of two positional Optionals whose
    order is easy to swap.
    """
    deck: Optional[str] = None
    folder: Optional[str] = None

    def __bool__(self) -> bool:
        """Truthy only when it points at a deck."""
        return self.deck is not None

    @property
    def path(self) -> str:
        """Unix-style path, for status lines and breadcrumbs."""
        if self.deck:
            base = f"/{self.folder}" if self.folder else ""
            return f"{base}/{self.deck}"
        return f"/{self.folder}" if self.folder else "/"


ROOT = DeckRef()


# --- card lookup -------------------------------------------------------

def card_profile(name: str, ref: DeckRef = ROOT) -> Optional[dict]:
    """A full card profile, with its combo list narrowed to the deck.

    Inside a deck whose commanders define a color identity, the embedded
    "top combos featuring this card" list is restricted to combos that deck
    could actually play — otherwise Ashnod's Altar in a BG deck advertises
    its UB and W combos.
    """
    restrict_to_ci = None
    if ref:
        restrict_to_ci = _deck_color_identity(ref)
    return q.get_card(name, restrict_to_ci=restrict_to_ci)


def _deck_color_identity(ref: DeckRef) -> Optional[list[str]]:
    """The deck's commander CI, or None (no commanders, or no such deck)."""
    try:
        return d.get_deck_color_identity(ref.deck, folder=ref.folder)
    except d.DeckError:
        return None


def _deck_format_info(ref: DeckRef) -> Optional[dict]:
    """`queries.resolve_format()` for the deck's format, or None."""
    try:
        return d.get_deck_format_info(ref.deck, folder=ref.folder)
    except d.DeckError:
        return None


# --- search ------------------------------------------------------------

@dataclass(frozen=True)
class SearchPage:
    """One page of search results, plus everything needed to page around it.

    `effective` is the query that actually ran, which is not always what the
    user typed — inside a deck it carries the deck's filters. Pagination
    re-runs `effective`, never the original.
    """
    effective: str
    rows: list[dict]
    total: int
    page: int
    page_size: int
    filters: tuple[str, ...] = ()

    @property
    def last_page(self) -> int:
        return max(1, (self.total + self.page_size - 1) // self.page_size)

    @property
    def has_next(self) -> bool:
        return self.page < self.last_page

    @property
    def has_prev(self) -> bool:
        return self.page > 1


@dataclass(frozen=True)
class SearchScope:
    """A query rewritten for where the user is standing.

    `filters` are human labels for what was added — they are announced
    rather than applied silently, because a search that quietly returns
    fewer cards than the user expects looks like missing data.
    """
    effective: str
    filters: tuple[str, ...] = ()


def deck_search_scope(query: str, ref: DeckRef) -> SearchScope:
    """Restrict a query to what a deck can actually play.

    Two filters, both hard: the commanders' color identity and the deck's
    format legality. A deck with neither gets the query unchanged, which is
    also what root and folder scope get.
    """
    if not ref:
        return SearchScope(query)

    # Sort tokens come out before the query is parenthesised and go back on
    # the end: `(t:x order:asc_mv)` hid the token from the extractor, which
    # needs whitespace after it, and the parser then saw a field `order`.
    effective, orders = ss.extract_order(query)
    filters: list[str] = []

    def restrict(term: str) -> str:
        return f"({effective}) {term}" if effective else term

    deck_ci = _deck_color_identity(ref)
    if deck_ci is not None:
        # An empty CI is colorless, which the search language spells `c`.
        effective = restrict(f"ci<={''.join(deck_ci) or 'c'}")
        filters.append(f"ci<={''.join(deck_ci) or 'C'}")

    info = _deck_format_info(ref)
    if info and info["legality_key"]:
        effective = restrict(f"f:{info['legality_key']}")
        # Show the deck's own format name, but name the inherited pool too:
        # "f:Canadian Highlander" alone would look like a filter we have
        # legality data for, and we don't — we have Vintage's.
        label = f"f:{info['label']}"
        if info["custom"]:
            label += f" (={info['legality_key']} pool)"
        filters.append(label)

    effective += "".join(f" order:{direction}_{key}" for key, direction in orders)
    return SearchScope(effective.strip(), tuple(filters))


def search(
    effective: str,
    *,
    page: int = 1,
    page_size: int = 50,
    filters: tuple[str, ...] = (),
) -> SearchPage:
    """Run one page of an already-scoped query.

    `effective` must be the full query including any deck filters — this is
    what `next` / `prev` / `page <N>` re-run, so scoping happens once, in
    `deck_search`, not on every page turn.
    """
    offset = (max(1, page) - 1) * page_size
    try:
        total = ss.count_query(effective)
        rows = ss.run_query(effective, limit=page_size, offset=offset)
    except ss.SearchError as e:
        raise ServiceError(str(e)) from e
    return SearchPage(effective, rows, total, max(1, page), page_size, filters)


def deck_search(query: str, ref: DeckRef, *, page_size: int = 50) -> SearchPage:
    """Search, scoped to the deck the caller is in. Page 1."""
    scope = deck_search_scope(query, ref)
    return search(scope.effective, page_size=page_size, filters=scope.filters)


# --- deck editing ------------------------------------------------------

def import_text_into_deck(ref: DeckRef, text: str) -> dict:
    """Parse a pasted / file deckstring and append it to a deck.

    One implementation for every caller, which is the point: `paste` and
    `deck import` each had their own, and the TUI's dropped rows silently.
    A pasted list loads verbatim (`force=True` inside
    `decks.load_parsed_into_deck`) — the report names every row that didn't
    make it in.
    """
    if not ref:
        raise ServiceError("no deck selected")
    parsed = _parse_or_fail(text)
    # A DeckError here is structural — the deck vanished, the name is
    # ambiguous — and it is exactly what the user needs to read, but the
    # interfaces only catch ServiceError: the TUI showed `ERR DeckError`.
    try:
        return d.load_parsed_into_deck(ref.deck, parsed, folder=ref.folder)
    except d.DeckError as e:
        raise ServiceError(str(e)) from e


def create_deck_from_text(
    ref: DeckRef, text: str, *, format: Optional[str] = None,
) -> dict:
    """Create a new deck and fill it from a deckstring."""
    if not ref:
        raise ServiceError("no deck name given")
    parsed = _parse_or_fail(text)
    try:
        return d.import_deck(ref.deck, parsed, folder=ref.folder, format=format)
    except d.DeckError as e:
        raise ServiceError(str(e)) from e


def replace_deck_from_text(ref: DeckRef, text: str, *, force: bool = False) -> dict:
    """Make an existing deck hold exactly this deckstring, as one revision.

    Returns `decks.replace_deck_contents`' diff. An unresolved card name
    aborts with ServiceError and changes nothing, unless `force`.
    """
    if not ref:
        raise ServiceError("no deck selected")
    parsed = _parse_or_fail(text)
    try:
        return d.replace_deck_contents(ref.deck, parsed, folder=ref.folder,
                                       force=force)
    except d.DeckError as e:
        raise ServiceError(str(e)) from e


def _parse_or_fail(text: str) -> list[dict]:
    parsed = parse_deckstring(text)
    if not parsed:
        raise ServiceError("no card lines recognized in input")
    return parsed


# --- formats -----------------------------------------------------------

@dataclass(frozen=True)
class FormatCatalog:
    """Every format name the app understands, split by where it came from.

    `scryfall` are legality keys with upstream ban lists; `community` are
    `custom_formats` rows (name + aliases), which is where Canadian
    Highlander lives.
    """
    scryfall: tuple[str, ...] = ()
    community: tuple[dict, ...] = ()

    def names(self) -> list[str]:
        """Everything `format <name>` accepts, for autofill.

        Both the canonical key and every alias, because the hard part is
        remembering whether a format went in as `canlander` or `canadian
        highlander`. Sorted so a prefix matching two entries is stable.
        """
        names = set(self.scryfall)
        for spec in self.community:
            names.add(spec["format"])
            names.update(spec.get("aliases") or [])
        return sorted(names)


def format_catalog() -> FormatCatalog:
    """The format catalog, deduped by spec.

    `queries.get_custom_formats()` is keyed by name *and* by every alias, so
    the same spec appears several times — dedupe before presenting it.
    """
    seen: dict[str, dict] = {}
    for spec in q.get_custom_formats().values():
        seen[spec["format"]] = spec
    return FormatCatalog(
        scryfall=tuple(sorted(q.LEGALITY_FORMATS)),
        community=tuple(sorted(seen.values(), key=lambda s: s["name"])),
    )


def format_rules(raw: Optional[str]) -> dict:
    """Which rules a format name actually switches on.

    `resolve_format` answers for formats we have a definition or a Scryfall
    key for. Singleton is asked separately because it has one more source:
    the community fallback set covers formats with neither (`highlander`),
    and those *do* get singleton enforced by `decks.add_card_to_deck`. Going
    through `resolve_format` alone reported "no rules apply" for a deck that
    was in fact rejecting second copies.
    """
    return {
        "info": q.resolve_format(raw),
        "singleton": q.is_singleton_format(raw),
    }


# --- archetype analysis ------------------------------------------------

@dataclass(frozen=True)
class DeckProfile:
    """One deck, classified: what its cards do and what they really cost.

    `counts` uses each card's PRIMARY role, so it sums to the deck size and
    two decks can be compared directly. `role_mv` uses EVERY role a card can
    fill — a Cryptic Command appears under counters and card advantage both,
    because at the table it is available as either.
    """
    name: str
    size: int
    counts: dict[str, int]
    role_mv: dict[str, dict[int, int]]
    curve: dict[int, int]
    lands: int
    rocks: int
    land_backs: int
    unresolved: tuple[str, ...] = ()
    # Per role, how many of those cards are permanents that keep producing
    # the effect rather than resolving once. Five planeswalkers that draw is
    # an engine count; fourteen spells that draw is a resource count, and the
    # two are not interchangeable when deciding how many you need.
    engines: dict = field(default_factory=dict)
    # The category each role's live curve draws from — `role_mv` minus the
    # rocks, except for `mana` itself. See `live_curve`.
    on_curve_mv: dict = field(default_factory=dict)

    @property
    def mana_sources(self) -> int:
        """Lands, modal-DFC land backs and rocks — everything that makes mana."""
        return self.lands + self.rocks

    @property
    def avg_mv(self) -> float:
        total = sum(self.curve.values())
        return (sum(mv * n for mv, n in self.curve.items()) / total) if total else 0.0

    # `deck_size` is the deck's own size, never probability's 100-card
    # default: a 60-card list modelled as 100 read its turn-one odds at half
    # their true value, and a list over 100 cards raised.
    def live_curve(self, role: str, turns=range(1, 9), on_play: bool = True):
        """P(this deck can play `role` on each turn). See `probability`.

        `category_live` partitions the deck into lands, rocks and the rest,
        and a card may sit in only one pile — a rock passed as a rock AND as
        a category card is two cards. So a rock powers every other role's
        curve and is never part of one: Mind Stone's draw is in `role_mv`
        (reach) but not in the draw curve. For `mana` itself the rocks ARE
        the category, so they join the spell pile and no rock pays for
        another — conservative by the rare rock-into-rock line.
        """
        n_rocks = 0 if role == "mana" else self.rocks
        return probability.curve(
            self.on_curve_mv.get(role, {}), self.lands, n_rocks, turns, on_play,
            deck_size=self.size)

    def ceiling(self, role: str, turns=range(1, 9), on_play: bool = True):
        """The same, ignoring mana — the upper bound the mana base caps."""
        return probability.ceiling(
            sum(self.role_mv.get(role, {}).values()), turns, on_play,
            deck_size=self.size)


def profile_deck(
    name: str,
    cards: dict[str, int],
    *,
    strict: bool = False,
    x_value: int = roles.X_VALUE,
    miracle: bool = False,
) -> DeckProfile:
    """Classify a deck given `{card name: quantity}`.

    `strict=True` raises when a name doesn't resolve; otherwise unresolved
    names are counted in `unresolved` and left out of the analysis, because
    a single typo should not throw away the other 99 cards.

    `miracle=True` costs the miracle cards at their miracle cost. Off by
    default because miracle depends on draw order and treating Terminus as a
    one-mana sweeper claims a deck can wrath on turn one — but a pilot who
    says "I will never hardcast Entreat the Angels" is describing their own
    deck accurately, and the printed cost is then the wrong number for them.
    """
    facts = q.get_card_facts(cards)
    tags = q.get_oracle_tags(cards)
    missing = tuple(sorted(n for n in cards if n not in facts))
    if missing and strict:
        raise ServiceError(f"unknown card(s): {', '.join(missing)}")

    counts = {r: 0 for r in roles.ROLES}
    role_mv: dict[str, dict[int, int]] = {r: {} for r in roles.ROLES}
    on_curve_mv: dict[str, dict[int, int]] = {r: {} for r in roles.ROLES}
    engines: dict[str, int] = {}
    curve: dict[int, int] = {}
    lands = rocks = land_backs = size = 0

    for card_name, qty in cards.items():
        fact = facts.get(card_name)
        if fact is None:
            continue
        size += qty
        if roles.is_land(fact):
            counts["land"] += qty
            lands += qty
            continue

        cl = roles.classify(fact, x_value, tags=tags.get(card_name, ()))
        if miracle and cl.cost.alternative is not None:
            cl = replace(cl, cost=replace(cl.cost, effective=cl.cost.alternative,
                                          reason=cl.cost.alternative_reason))
        # A modal DFC with a land back is a land EVERYWHERE, not just in the
        # mana count. The draw maths partitions the deck into lands, rocks and
        # spells and a card can only sit in one bucket, so crediting the spell
        # half to `role_mv` while `lands` also counted it made a role's total
        # exceed the spell pile — `probability.category_live` raises on that,
        # correctly. Consistency is forced here, not chosen: the model says
        # Sink into Stupor is played as a land, so it is a land in the density
        # table too, and `mana_sources` reports it separately (`39 lands, 42
        # with MDFCs`) so the flexibility is still visible.
        if roles.has_land_back(fact):
            counts["land"] += qty
            lands += qty
            land_backs += qty
            continue

        counts[cl.primary] += qty
        mv = cl.cost.effective
        is_rock = cl.primary == "mana"
        for role in cl.roles:
            if role in role_mv:
                role_mv[role][mv] = role_mv[role].get(mv, 0) + qty
                if cl.engine:
                    engines[role] = engines.get(role, 0) + qty
                # One pile per card — see `DeckProfile.live_curve`.
                if not is_rock or role == "mana":
                    on_curve_mv[role][mv] = on_curve_mv[role].get(mv, 0) + qty
        if is_rock:
            rocks += qty
        curve[mv] = curve.get(mv, 0) + qty

    return DeckProfile(name, size, counts, role_mv, curve,
                       lands, rocks, land_backs, missing, engines, on_curve_mv)


def profile_decks(decks, **kw) -> list[DeckProfile]:
    """`profile_deck` over `[(name, {card: qty}), ...]` or `[{name, cards}]`."""
    out = []
    for d in decks:
        if isinstance(d, dict):
            out.append(profile_deck(d["name"], d["cards"], **kw))
        else:
            out.append(profile_deck(d[0], d[1], **kw))
    return out


# --- comparing a deck against a reference set --------------------------

@dataclass(frozen=True)
class RoleDelta:
    """One role's count in a deck, against what a reference set does."""
    role: str
    subject: int
    ref_mean: float
    ref_min: int
    ref_max: int
    ref_median: float

    @property
    def delta(self) -> float:
        return round(self.subject - self.ref_mean, 1)

    @property
    def verdict(self) -> str:
        """`under`, `in` or `over` — relative to the reference RANGE.

        The range, not the mean: being two cards off an average that spans
        nine cards is noise, while stepping outside the range is a choice
        nobody in the reference set made.
        """
        if self.subject < self.ref_min:
            return "under"
        if self.subject > self.ref_max:
            return "over"
        return "in"


@dataclass(frozen=True)
class CardDiff:
    """A card one side plays and the other doesn't."""
    name: str
    role: str
    mv: int
    n_lists: int
    of_lists: int

    @property
    def share(self) -> float:
        return self.n_lists / self.of_lists if self.of_lists else 0.0


@dataclass(frozen=True)
class Comparison:
    """A deck measured against a reference set of decks.

    Answers the three questions a deckbuilder actually has: am I inside the
    ranges the reference set uses, where do my draw probabilities differ, and
    which specific cards am I missing or playing alone.
    """
    subject: DeckProfile
    reference: tuple[DeckProfile, ...]
    roles: tuple[RoleDelta, ...]
    mana_sources: RoleDelta
    avg_mv: tuple[float, float]          # (subject, reference mean)
    curve_delta: dict[str, dict[int, float]]
    missing: tuple[CardDiff, ...]
    unique: tuple[CardDiff, ...]
    nearest: tuple[tuple[str, float], ...]

    @property
    def out_of_range(self) -> tuple[RoleDelta, ...]:
        """The roles where this deck is outside every reference deck."""
        return tuple(r for r in self.roles if r.verdict != "in")

    def role(self, name: str) -> Optional[RoleDelta]:
        return next((r for r in self.roles if r.role == name), None)


def _canonical_index(names):
    """Resolve every spelling to one card: `(canonical, facts, tags)`.

    A two-faced card arrives in three forms — `Sink into Stupor`, `Sink into
    Stupor / Soporific Springs`, `Sink into Stupor // Soporific Springs` —
    and keying on the raw string made those three different cards. The same
    card then appeared in `missing` AND `unique` at once, and a list count
    undercounted it by however many lists spelled it the other way.

    `canonical(name)` is the stored card name, or the input for a name that
    doesn't resolve; `facts` and `tags` are keyed by that canonical name.
    """
    everything = sorted(set(names))
    facts = q.get_card_facts(everything)
    tags = q.get_oracle_tags(everything)

    def canonical(name: str) -> str:
        fact = facts.get(name)
        return fact["name"] if fact else name

    canon_facts = {canonical(n): facts[n] for n in everything if n in facts}
    canon_tags = {canonical(n): tags[n] for n in everything if n in tags}
    return canonical, canon_facts, canon_tags


def _lists_playing(decks, canonical) -> dict[str, int]:
    """How many of `decks` play each card, by canonical name.

    A set per list: two spellings of one card in one list is still one list
    playing it.
    """
    counts: dict[str, int] = {}
    for deck in decks:
        for name in {canonical(n) for n in deck["cards"]}:
            counts[name] = counts.get(name, 0) + 1
    return counts


def _profile_distance(a: DeckProfile, b: DeckProfile) -> float:
    """Euclidean distance between two decks' role-density vectors.

    Raw counts, deliberately unnormalised. The role with the widest spread in
    a reference set is the axis that defines the build — for UW control that
    is threats, 4 to 13 — so letting it dominate the distance is the point,
    not a flaw to correct for.
    """
    return round(sum(
        (a.counts.get(r, 0) - b.counts.get(r, 0)) ** 2 for r in roles.ROLES
    ) ** 0.5, 2)


def compare_decks(
    subject: dict,
    reference: list[dict],
    *,
    turns=range(1, 9),
    on_play: bool = True,
    min_share: float = 0.0,
    x_value: int = roles.X_VALUE,
    miracle: bool = False,
) -> Comparison:
    """Measure one deck against a set of others.

    `subject` and each entry of `reference` are `{"name": str, "cards": {name: qty}}`
    — the same shape `profile_decks` takes, so a decklist file and a deck from
    the database compare without either being converted first.

    `min_share` filters the `missing` list: 0.5 means "only cards half the
    reference set plays". The default reports everything, because a card one
    good list plays is still a lead.
    """
    if not reference:
        raise ServiceError("nothing to compare against")

    subj = profile_deck(subject["name"], subject["cards"], x_value=x_value,
                        miracle=miracle)
    refs = tuple(profile_decks(reference, x_value=x_value, miracle=miracle))
    n = len(refs)

    def stats(values):
        srt = sorted(values)
        mid = len(srt) // 2
        median = srt[mid] if len(srt) % 2 else (srt[mid - 1] + srt[mid]) / 2
        return sum(values) / len(values), min(values), max(values), median

    role_deltas = []
    for role in roles.ROLES:
        mean, lo, hi, med = stats([p.counts.get(role, 0) for p in refs])
        role_deltas.append(RoleDelta(role, subj.counts.get(role, 0),
                                     round(mean, 1), lo, hi, med))
    mean, lo, hi, med = stats([p.mana_sources for p in refs])
    mana = RoleDelta("mana_sources", subj.mana_sources, round(mean, 1), lo, hi, med)

    turns = list(turns)
    curve_delta = {}
    for role in roles.ROLES:
        if role == "land":
            continue
        mine = subj.live_curve(role, turns, on_play)
        theirs = [p.live_curve(role, turns, on_play) for p in refs]
        curve_delta[role] = {
            t: round(mine[t] - sum(c[t] for c in theirs) / n, 4) for t in turns
        }

    # --- card-level diff, which is the actionable half -----------------
    #
    # Keyed on the CANONICAL name, not the string the list happened to write
    # — see `_canonical_index`.
    canonical, canon_facts, canon_tags = _canonical_index(
        [n for d_ in [subject, *reference] for n in d_["cards"]])
    subj_cards = {canonical(n) for n in subject["cards"]}
    counts = _lists_playing(reference, canonical)

    def describe(name, n_lists):
        fact = canon_facts.get(name)
        if fact is None:
            return CardDiff(name, "?", 99, n_lists, n)
        cl = roles.classify(fact, x_value, tags=canon_tags.get(name, ()))
        return CardDiff(fact["name"], cl.primary, cl.cost.effective, n_lists, n)

    missing = [describe(name, c) for name, c in counts.items()
               if name not in subj_cards and c / n >= min_share]
    missing.sort(key=lambda c: (-c.n_lists, c.mv, c.name))
    unique = [describe(name, 0) for name in subj_cards if name not in counts]
    unique.sort(key=lambda c: (c.role, c.mv, c.name))

    nearest = tuple(sorted(
        ((p.name, _profile_distance(subj, p)) for p in refs),
        key=lambda kv: kv[1]))

    return Comparison(
        subject=subj, reference=refs, roles=tuple(role_deltas),
        mana_sources=mana,
        avg_mv=(round(subj.avg_mv, 2),
                round(sum(p.avg_mv for p in refs) / n, 2)),
        curve_delta=curve_delta, missing=tuple(missing),
        unique=tuple(unique), nearest=nearest,
    )


# --- exporting a deck --------------------------------------------------

@dataclass(frozen=True)
class DeckExport:
    """A deck as plain text, plus what went into it."""
    text: str
    cards: int
    rows: int
    sections: dict[str, int]


def deck_cards_for_analysis(ref: DeckRef) -> dict:
    """A stored deck reduced to `{"name", "cards"}` — what the analysis takes.

    The same shape a parsed decklist file produces, so `profile_decks`,
    `rank_cards` and `compare_decks` never need to know which one they got.
    Sideboard rows are dropped: the draw maths is about the 100 you shuffle.
    """
    try:
        deck = d.get_deck(ref.deck, folder=ref.folder)
    except d.AmbiguousDeckError as e:
        raise ServiceError(str(e)) from e
    if not deck:
        raise ServiceError(f"no deck named {ref.deck!r}")
    cards: dict[str, int] = {}
    for row in deck.get("cards", []):
        if row.get("is_sideboard"):
            continue
        name = row["card_name"]
        cards[name] = cards.get(name, 0) + row["quantity"]
    return {"name": deck["name"], "cards": cards, "files": [deck["name"]]}


def rank_cards(decks, role_order=None) -> tuple[dict, set[str]]:
    """Per role, which cards the most lists play — and which fell through.

    Returns `({role: [row, ...]}, low_confidence_names)`. Each row carries the
    card, how many lists play it, its effective and printed cost, whether the
    role is that card's primary, and why the cost was adjusted if it was.
    Rows are sorted by effective cost, then by how many lists play the card.

    Lands are skipped: `1/9 Snow-Covered Swamp` is not a finding, and the
    mana base is measured by `profile_deck` instead. Lives here rather than in
    `analytics` because it needs two data calls, which is the test for what
    belongs in this layer.

    Cards are keyed on their canonical name, exactly as in `compare_decks`:
    `Fire // Ice` in one list and `Fire/Ice` in another is one card played by
    two lists, not two cards played by one each.
    """
    order = list(role_order) if role_order is not None else list(roles.ROLES)
    n = len(decks)
    if not n:
        return {r: [] for r in order}, set()
    canonical, facts, tags = _canonical_index(
        [name for deck in decks for name in deck["cards"]])
    played = _lists_playing(decks, canonical)

    out: dict[str, list] = {r: [] for r in order}
    low: set[str] = set()
    for name in sorted(played):
        fact = facts.get(name)
        if fact is None or roles.is_land(fact):
            continue
        cl = roles.classify(fact, tags=tags.get(name, ()))
        if cl.low_confidence:
            low.add(name)
        for role in cl.roles:
            if role not in out:
                continue
            out[role].append({
                "name": name,
                "n": played[name],
                "pct": round(100 * played[name] / n),
                "mv": cl.cost.effective,
                "printed": cl.cost.printed,
                "cost": fact.get("mana_cost") or "",
                "primary": cl.primary == role,
                "reason": cl.cost.reason if cl.cost.adjusted else "",
                "source": cl.source,
            })
    for role in out:
        out[role].sort(key=lambda row: (row["mv"], -row["n"], row["name"]))
    return out, low


# Section headers `deck_parser` understands, so an exported deck re-imports
# into the same deck. That round-trip is asserted in the tests.
_EXPORT_ORDER = (("commander", "Commander"), ("main", "Deck"),
                 ("sideboard", "Sideboard"))


def export_deck_text(
    ref: DeckRef,
    *,
    front_face: bool = False,
    headers: bool = True,
    group_by_role: bool = False,
) -> DeckExport:
    """A deck as a `N Card Name` list, ready to paste into a deck site.

    Names are the full canonical Scryfall names by default, because those are
    unambiguous by construction and every Scryfall-backed importer (Moxfield,
    Archidekt) takes them. `front_face=True` shortens two-faced names to the
    front face for sites that prefer it — **except split cards**, where the
    front face is not a card name at all: there is no card called `Fire`, only
    `Fire // Ice`.

    `group_by_role` inserts `//` comment lines per role, which importers skip
    and humans find readable.
    """
    if not ref:
        raise ServiceError("no deck selected")
    try:
        deck = d.get_deck(ref.deck, folder=ref.folder)
    except d.AmbiguousDeckError as e:
        raise ServiceError(str(e)) from e
    if not deck:
        raise ServiceError(f"no deck named {ref.deck!r}")

    buckets: dict[str, dict[str, int]] = {k: {} for k, _ in _EXPORT_ORDER}
    # The printing goes back out as ` (SET) number`, the shape Moxfield
    # writes and the parser keeps, so export -> import preserves the art.
    printings: dict[tuple[str, str], str] = {}
    for row in deck.get("cards", []):
        if row.get("is_sideboard"):
            section = "sideboard"
        elif row.get("is_commander"):
            section = "commander"
        else:
            section = "main"
        name = row["card_name"]
        buckets[section][name] = buckets[section].get(name, 0) + row["quantity"]
        if row.get("set_code") and (section, name) not in printings:
            number = row.get("collector_number")
            printings[(section, name)] = (f" ({row['set_code'].upper()})"
                                          + (f" {number}" if number else ""))

    all_names = [n for b in buckets.values() for n in b]
    facts = q.get_card_facts(all_names) if (front_face or group_by_role) else {}
    tags = q.get_oracle_tags(all_names) if group_by_role else {}

    def display(name: str) -> str:
        if not front_face or " // " not in name:
            return name
        fact = facts.get(name)
        # A split card's name is the whole `A // B`; shortening it produces a
        # string that resolves to nothing.
        if fact and (fact.get("layout") or "") == "split":
            return name
        return name.split(" // ", 1)[0]

    lines: list[str] = []
    counts: dict[str, int] = {}
    for key, header in _EXPORT_ORDER:
        entries = buckets[key]
        if not entries:
            continue
        counts[key] = sum(entries.values())
        if headers:
            if lines:
                lines.append("")
            lines.append(header)
        if group_by_role:
            by_role: dict[str, list[str]] = {}
            for name in entries:
                fact = facts.get(name)
                role = (roles.classify(fact, tags=tags.get(name, ())).primary
                        if fact else "utility")
                by_role.setdefault(role, []).append(name)
            for role in roles.ROLES:
                names = sorted(by_role.get(role, []))
                if not names:
                    continue
                lines.append(f"// {roles.LABELS[role]} ({sum(entries[n] for n in names)})")
                lines.extend(f"{entries[n]} {display(n)}"
                             f"{printings.get((key, n), '')}" for n in names)
        else:
            lines.extend(f"{entries[n]} {display(n)}{printings.get((key, n), '')}"
                         for n in sorted(entries))

    text = "\n".join(lines) + ("\n" if lines else "")
    return DeckExport(text, sum(counts.values()),
                      sum(len(b) for b in buckets.values()), counts)


# --- Forge -------------------------------------------------------------
#
# Playtesting in Forge: export decks as .dck files, give Forge's AI playable
# substitutes for cards it can't pilot, run AI-vs-AI sims and keep the
# results. The files and processes are forge_client's, the formats
# forge_format's, the rows forge_data's; these functions decide and compose.

COMMANDER_NOTE = (
    "Forge plays Commander at 40 life, with 21 commander damage lethal — not "
    "Duel Commander's 20 life. Read these games as a Commander proxy.")
_WINNER = {1: "a", 2: "b", None: "draw"}


class ForgeUnknownCardsError(ServiceError):
    """Export refused: the deck names cards Forge doesn't have."""

    def __init__(self, deck: str, unknown: list[str]):
        self.deck = deck
        self.unknown = unknown
        super().__init__(
            f"{len(unknown)} card(s) in {deck!r} are unknown to Forge: "
            f"{', '.join(unknown)}. Substitute them (`forge sub add`), or "
            f"re-run with --force to leave them out.")


@dataclass(frozen=True)
class ForgeExport:
    """What `forge_export` wrote, and what the user should know about it."""
    deck: str
    deck_id: int
    game_type: str                      # 'constructed' | 'commander'
    path: Path                          # <deck>.dck
    ai_path: Optional[Path]             # <deck> (AI).dck, with substitutions
    unknown: list[str]                  # left out (only with force)
    ai_unplayable: list[str]            # AI:RemoveDeck:All, no substitute
    ai_situational: list[tuple[str, str]]  # (card, flag): played situationally
    substitutions: list[tuple[str, str]]   # (card, substitute) applied
    notes: list[str] = field(default_factory=list)


def _forge_install(config: Optional[fc.ForgeConfig]) -> fc.ForgeInstall:
    try:
        return fc.validate(config or fc.load_config())
    except fc.ForgeError as e:
        raise ServiceError(str(e)) from e


def _forge_deck(ref: DeckRef, *, require_cards: bool = True) -> dict:
    if not ref:
        raise ServiceError("no deck given")
    try:
        deck = d.get_deck(ref.deck, folder=ref.folder)
    except d.AmbiguousDeckError as e:
        raise ServiceError(str(e)) from e
    if not deck:
        raise ServiceError(f"no deck named {ref.deck!r}")
    if require_cards and not deck["cards"]:
        raise ServiceError(f"{deck['name']!r} is empty")
    return deck


def _game_type(deck: dict) -> str:
    """Commander when the deck has a commander row; Forge can't run a
    Commander game without one, whatever `decks.format` says."""
    return ("commander" if any(c["is_commander"] for c in deck["cards"])
            else "constructed")


def _forge_rows(rows: list[dict], index: dict) -> tuple[list[dict], list[str]]:
    """Rows renamed to Forge's exact spelling; plus the names Forge lacks."""
    out, unknown = [], set()
    for row in rows:
        front = ff.forge_card_name(row["card_name"])
        known = index.get(front.casefold())
        if known:
            out.append({**row, "card_name": known["name"]})
        else:
            unknown.add(front)
    return out, sorted(unknown, key=str.casefold)


def _export_deck(install: fc.ForgeInstall, deck: dict, index: dict, *,
                 force: bool, overwrite: bool) -> ForgeExport:
    game_type = _game_type(deck)
    rows = deck["cards"]
    in_deck = {r["card_name"].casefold() for r in rows}
    subs, notes = [], []
    for s in fd.list_substitutions(deck["id"]):
        if s["card_name"].casefold() in in_deck:
            subs.append((s["card_name"], s["substitute"]))
        else:
            notes.append(f"substitution for {s['card_name']} skipped: the "
                         f"card is no longer in the deck")
    subbed = {card.casefold() for card, _ in subs}

    forge_rows, unknown = _forge_rows(rows, index)
    ai_rows, ai_unknown = _forge_rows(
        ff.apply_substitutions(rows, dict(subs)), index)
    unknown_all = sorted(set(unknown) | set(ai_unknown), key=str.casefold)
    if unknown_all and not force:
        raise ForgeUnknownCardsError(deck["name"], unknown_all)

    unplayable, situational = [], []
    for row in rows:
        entry = index.get(ff.forge_card_name(row["card_name"]).casefold())
        flag = entry["ai"] if entry else None
        name = row["card_name"]
        if flag == "All" and name.casefold() not in subbed:
            unplayable.append(name)
        elif flag == "Random" or (flag == "NonCommander"
                                  and game_type == "constructed"):
            situational.append((name, flag))

    # Rows keep their printing through _forge_rows; the index turns it into
    # Forge's `|CODE|[number]`, or drops it when Forge has no such printing.
    editions = (fc.load_edition_index(install)
                if any(r.get("set_code") for r in rows) else {})
    try:
        path = fc.write_deck(install.config, deck["name"], deck["id"],
                             ff.dck_sections(forge_rows, editions), game_type,
                             overwrite=overwrite)
        ai_path = None
        if subs:
            ai_path = fc.write_deck(install.config, f"{deck['name']} (AI)",
                                    deck["id"], ff.dck_sections(ai_rows, editions),
                                    game_type, overwrite=overwrite)
    except (fc.ForgeError, OSError) as e:
        raise ServiceError(str(e)) from e
    if game_type == "commander":
        notes.append(COMMANDER_NOTE)
    return ForgeExport(
        deck=deck["name"], deck_id=deck["id"], game_type=game_type,
        path=path, ai_path=ai_path, unknown=unknown_all,
        ai_unplayable=sorted(set(unplayable), key=str.casefold),
        ai_situational=sorted(set(situational), key=lambda s: s[0].casefold()),
        substitutions=subs, notes=notes)


def forge_export(ref: DeckRef, *, force: bool = False, overwrite: bool = False,
                 config: Optional[fc.ForgeConfig] = None) -> ForgeExport:
    """Write the deck as `<deck>.dck` (and `<deck> (AI).dck`) for Forge.

    Refuses (ForgeUnknownCardsError) when Forge lacks a card, unless
    `force`, which leaves those cards out. Cards Forge's AI can't play at
    all come back in `ai_unplayable` — the fix is a substitution, which
    only the AI copy uses. `overwrite` replaces a .dck we didn't write.
    """
    install = _forge_install(config)
    deck = _forge_deck(ref)
    return _export_deck(install, deck, fc.load_card_index(install),
                        force=force, overwrite=overwrite)


def forge_play(*, config: Optional[fc.ForgeConfig] = None) -> int:
    """Open Forge's own GUI, detached. Returns the process id."""
    install = _forge_install(config)
    try:
        return fc.launch_gui(install)
    except OSError as e:
        raise ServiceError(f"could not start Forge: {e}") from e


# --- AI substitutions ---

def forge_substitutions(ref: DeckRef) -> list[dict]:
    """The deck's substitutions: [{card_name, substitute, added_at}]."""
    return fd.list_substitutions(_forge_deck(ref, require_cards=False)["id"])


def forge_add_substitution(ref: DeckRef, card: str, substitute: str, *,
                           config: Optional[fc.ForgeConfig] = None) -> dict:
    """Have the deck's AI copy play `substitute` wherever the deck has `card`.

    Judged as a real `add` would judge it, against the deck with every
    substitution applied (`decks.check_swaps`): the substitute must fit the
    commander's colour identity, be legal in the format, not already be in
    a singleton deck, and keep the points budget. Forge must know it, and
    its AI must be able to play it.

    Returns {deck, card, substitute, replaced}; `replaced` is the substitute
    this one replaced, or None.
    """
    install = _forge_install(config)
    deck = _forge_deck(ref)
    target = (q.resolve_card_name(card) or card).casefold()
    others = [(s["card_name"], s["substitute"])
              for s in fd.list_substitutions(deck["id"])
              if s["card_name"].casefold() != target]
    try:
        checked = d.check_swaps(deck["name"], others + [(card, substitute)],
                                folder=deck["folder"])
    except d.DeckError as e:
        raise ServiceError(str(e)) from e
    card_name, sub_name = checked[-1]
    known = fc.load_card_index(install).get(ff.forge_card_name(sub_name).casefold())
    if not known:
        raise ServiceError(f"{sub_name!r} is unknown to Forge {install.version}")
    if known["ai"] == "All":
        raise ServiceError(f"Forge's AI can't play {sub_name!r} either "
                           f"(AI:RemoveDeck:All); pick another substitute")
    replaced = fd.set_substitution(deck["id"], card_name, sub_name)
    return {"deck": deck["name"], "card": card_name, "substitute": sub_name,
            "replaced": replaced}


def forge_remove_substitution(ref: DeckRef, card: str) -> dict:
    """Drop one substitution. Returns {deck, card, substitute}."""
    deck = _forge_deck(ref, require_cards=False)
    removed = fd.remove_substitution(deck["id"], q.resolve_card_name(card) or card)
    if removed is None:
        raise ServiceError(f"no substitution for {card!r} in {deck['name']!r}")
    return {"deck": deck["name"], "card": card, "substitute": removed}


# --- sims and results ---

@dataclass(frozen=True)
class ForgeSimResult:
    match_id: str
    deck_a: str
    deck_b: str
    ai_variant_a: bool
    ai_variant_b: bool
    game_type: str
    games: list[dict]           # {game_no, winner 'a'|'b'|'draw', turns, duration_ms, clock_draw}
    wins_a: int
    wins_b: int
    draws: int
    log_path: Path
    forge_version: str
    notes: list[str] = field(default_factory=list)


def forge_sim(ref_a: DeckRef, ref_b: DeckRef, games: int = 1, *,
              use_ai_variant: bool = True, overwrite: bool = False,
              config: Optional[fc.ForgeConfig] = None) -> ForgeSimResult:
    """Export both decks, have Forge's AI play them `games` times, store it.

    Uses each deck's AI copy when it has one, unless `use_ai_variant` is
    False. A deck with cards Forge lacks is refused rather than simulated
    without them. Both decks must be the same game type: a Commander deck
    can't play a constructed one in Forge.
    """
    if not 1 <= games <= fc.MAX_GAMES:
        raise ServiceError(f"games must be between 1 and {fc.MAX_GAMES}")
    install = _forge_install(config)
    deck_a, deck_b = _forge_deck(ref_a), _forge_deck(ref_b)
    index = fc.load_card_index(install)
    export_a = _export_deck(install, deck_a, index, force=False, overwrite=overwrite)
    export_b = (export_a if deck_b["id"] == deck_a["id"] else
                _export_deck(install, deck_b, index, force=False, overwrite=overwrite))
    if export_a.game_type != export_b.game_type:
        raise ServiceError(
            f"{deck_a['name']!r} is a {export_a.game_type} deck and "
            f"{deck_b['name']!r} a {export_b.game_type} one; Forge can only "
            f"play two of the same kind")
    use_a = use_ai_variant and export_a.ai_path is not None
    use_b = use_ai_variant and export_b.ai_path is not None
    try:
        run = fc.run_sim(install,
                         export_a.ai_path if use_a else export_a.path,
                         export_b.ai_path if use_b else export_b.path,
                         games=games, game_type=export_a.game_type)
    except fc.ForgeError as e:
        raise ServiceError(str(e)) from e

    match = ff.parse_sim_output(run.stdout)
    if not match.games:
        reasons = ff.sim_errors(run.stdout + "\n" + run.stderr)[:3]
        raise ServiceError(
            f"Forge played no games ({'; '.join(reasons) or 'no reason given'}). "
            f"Full output: {run.log_path}")
    rows = [{"game_no": g.game_no, "winner": _WINNER[g.winner],
             "turns": g.turns, "duration_ms": g.duration_ms,
             "clock_draw": g.clock_draw} for g in match.games]
    fd.record_games(
        match_id=run.match_id, deck_a=deck_a["name"], deck_b=deck_b["name"],
        deck_a_id=deck_a["id"], deck_b_id=deck_b["id"],
        ai_variant_a=use_a, ai_variant_b=use_b, game_type=export_a.game_type,
        forge_version=install.version, log_path=str(run.log_path), games=rows)

    notes = []
    if len(rows) != games:
        notes.append(f"Forge reported {len(rows)} of {games} games; "
                     f"see {run.log_path}")
    clock = sum(1 for g in match.games if g.clock_draw)
    if clock:
        notes.append(f"{clock} game(s) hit Forge's 120 s clock and count as "
                     f"draws (Forge itself reports them as wins for "
                     f"{deck_a['name']})")
    for export in (export_a, export_b) if export_b is not export_a else (export_a,):
        if export.ai_unplayable:
            notes.append(f"Forge's AI can't play {', '.join(export.ai_unplayable)} "
                         f"in {export.deck}; `forge sub add` gives it a substitute")
    if export_a.game_type == "commander":
        notes.append(COMMANDER_NOTE)
    return ForgeSimResult(
        match_id=run.match_id, deck_a=deck_a["name"], deck_b=deck_b["name"],
        ai_variant_a=use_a, ai_variant_b=use_b, game_type=export_a.game_type,
        games=rows, wins_a=match.wins[1], wins_b=match.wins[2],
        draws=match.draws, log_path=run.log_path,
        forge_version=install.version, notes=notes)


@dataclass(frozen=True)
class MatchupRecord:
    """One deck's record against one opponent, over every stored game."""
    deck: str
    opponent: str
    games: int
    wins: int
    losses: int
    draws: int
    avg_turns: Optional[float]   # over decided games


@dataclass(frozen=True)
class ForgeResults:
    focus: Optional[str]          # the deck asked about, or None for all
    records: list[MatchupRecord]  # both orientations when focus is None
    decks: list[str]              # every deck that appears, sorted


def forge_results(ref: Optional[DeckRef] = None) -> ForgeResults:
    """Win records from every stored sim, for one deck or as a matrix.

    Games are keyed by the deck names as they were run; a match of B
    against A counts toward A against B. The AI copy and the deck itself
    are the same deck here — the per-game rows keep which one played.
    """
    focus = _forge_deck(ref, require_cards=False) if ref else None
    tallies: dict[tuple[str, str], list] = {}
    for row in fd.game_rows(focus["id"] if focus else None):
        sides = (("a", row["deck_a"], row["deck_a_id"], row["deck_b"]),
                 ("b", row["deck_b"], row["deck_b_id"], row["deck_a"]))
        for side, name, deck_id, opponent in sides:
            if focus and deck_id != focus["id"]:
                continue
            if focus:
                name = focus["name"]
            tally = tallies.setdefault((name, opponent), [0, 0, 0, 0, []])
            tally[0] += 1
            if row["winner"] == "draw":
                tally[3] += 1
            elif row["winner"] == side:
                tally[1] += 1
            else:
                tally[2] += 1
            if row["winner"] != "draw" and row["turns"]:
                tally[4].append(row["turns"])
            if row["deck_a_id"] is not None and row["deck_a_id"] == row["deck_b_id"]:
                break  # a mirror is one game, counted from deck A's side
    records = [
        MatchupRecord(deck=deck, opponent=opp, games=t[0], wins=t[1],
                      losses=t[2], draws=t[3],
                      avg_turns=round(sum(t[4]) / len(t[4]), 1) if t[4] else None)
        for (deck, opp), t in sorted(tallies.items(),
                                     key=lambda kv: (kv[0][0].casefold(),
                                                     kv[0][1].casefold()))]
    names = sorted({r.deck for r in records} | {r.opponent for r in records},
                   key=str.casefold)
    return ForgeResults(focus=focus["name"] if focus else None,
                        records=records, decks=names)
