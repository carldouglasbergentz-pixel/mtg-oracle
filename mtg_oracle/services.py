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

from dataclasses import dataclass
from typing import Optional

from mtg_oracle import decks as d
from mtg_oracle import queries as q
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

    effective = query
    filters: list[str] = []

    deck_ci = _deck_color_identity(ref)
    if deck_ci is not None:
        # An empty CI is colorless, which the search language spells `c`.
        effective = f"({effective}) ci<={''.join(deck_ci) or 'c'}"
        filters.append(f"ci<={''.join(deck_ci) or 'C'}")

    info = _deck_format_info(ref)
    if info and info["legality_key"]:
        effective = f"({effective}) f:{info['legality_key']}"
        # Show the deck's own format name, but name the inherited pool too:
        # "f:Canadian Highlander" alone would look like a filter we have
        # legality data for, and we don't — we have Vintage's.
        label = f"f:{info['label']}"
        if info["custom"]:
            label += f" (={info['legality_key']} pool)"
        filters.append(label)

    return SearchScope(effective, tuple(filters))


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
    return d.load_parsed_into_deck(
        ref.deck, _parse_or_fail(text), folder=ref.folder,
    )


def create_deck_from_text(
    ref: DeckRef, text: str, *, format: Optional[str] = None,
) -> dict:
    """Create a new deck and fill it from a deckstring."""
    if not ref:
        raise ServiceError("no deck name given")
    return d.import_deck(
        ref.deck, _parse_or_fail(text), folder=ref.folder, format=format,
    )


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
