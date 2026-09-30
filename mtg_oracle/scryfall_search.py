"""Scryfall-style search query language for `mtg.db`.

Supported syntax (Level 2):
    # Term operators (all case-insensitive on field names)
    o:TEXT / oracle:TEXT        oracle_text contains TEXT
    t:TEXT / type:TEXT          type_line contains TEXT
    n:TEXT / name:TEXT          name contains TEXT (substring)
    n=TEXT                      name is exactly TEXT (case-insensitive)
    kw:KEYWORD                  has a card_tags row with category='keyword'
    c:COLORS  / c=COLORS        colors subset-contains / equals exactly
    ci:COLORS / ci<=COLORS      color identity ⊆ COLORS (commander legality)
    ci=COLORS                   color identity exactly COLORS
    ci>=COLORS                  color identity ⊇ COLORS
    mv:N, mv=N, mv>N, mv<N,
    mv>=N, mv<=N, mv!=N         mana value compared to integer N
    pow:S  pow=S  pow>N ...     power (string equality for `:`/`=`, numeric for compares)
    tou:S  tou=S  tou>N ...     toughness
    r:RARITY / rarity:RARITY    rarity equals (common|uncommon|rare|mythic|bonus|special)
    layout:LAYOUT               layout equals (normal|transform|modal_dfc|split|flip|...)

    # Format legality + printing metadata
    f:FORMAT / format: / legal: legal (or restricted) in FORMAT
    banned:FORMAT               on FORMAT's ban list
    restricted:FORMAT           restricted in FORMAT — one copy only in
                                Vintage / Old School, but "not as your
                                commander" in `duel` and `tlr`
    game:paper|arena|mtgo       card exists in that game — `game:paper`
                                excludes Arena-only Alchemy rebalances
    is:reserved                 on the Reserved List
    Formats: alchemy brawl commander competitivebrawl duel future gladiator
             historic legacy modern oathbreaker oldschool pauper
             paupercommander penny pioneer predh premodern standard
             standardbrawl timeless tlr vintage
             (aliases: edh, pdh, duelcommander, pennydreadful, cbrawl;
              spaces and hyphens are ignored, so f:"competitive brawl" works)

    # Sort
    order:asc_FIELD             sort ascending  (sort: is an alias)
    order:desc_FIELD            sort descending
    Fields: mv (mana value), name, power, toughness, rarity (tier order),
            color, ci (number of colors in color identity),
            edhrec (popularity rank — asc_edhrec is most-played first).
    Direction prefix is REQUIRED — bare `order:mv` is rejected. A stable
    tiebreaker on name is always appended.

    # Boolean
    TERM1 TERM2 ...             AND (implicit via whitespace)
    TERM1 or TERM2              OR
    not TERM1 / -TERM1          negation
    ( ... )                     grouping

    # Bare words and quoted strings (free text)
    bolt                        name, type line OR oracle text contains it
    "enters the battlefield"    quoted: the phrase, in any of the three
    Without `order:`, cards whose NAME matches come first (exact, then
    prefix, then anywhere), then type-line matches, then the rest.

    # More
    m:{U}{U} / m:2uu            mana cost has at least these symbols
    m={1}{U}                    mana cost is exactly these symbols
    c:m                         multicolored (two or more colors)
    otag:removal / function:    Scryfall Tagger tag, or one of its children
                                (`removal-creature`); `mana-rock` = `mana rock`
    is:commander|permanent|spell|historic|dfc|mdfc|split|reserved

Examples:
    o:flash t:creature c:u mv<=3
    kw:flying (c:w or c:u) -t:artifact
    t:planeswalker pow>=4
    "enters the battlefield" o:"draw a card"
    c=wu t:instant
    f:competitivebrawl ci<=UR t:instant order:asc_edhrec
    f:commander game:paper -banned:commander t:artifact mv<=2

Quick parser semantics:
    - AND binds tighter than OR. `a or b c` parses as `a or (b c)`.
    - Negation binds tighter than AND. `-a b` is `(-a) AND b`.
    - Comparison operators (`>`, `>=`, `<`, `<=`, `!=`) only make sense on
      numeric fields (mv, pow, tou); the compiler raises on misuse.
    - For `o:` and `t:`, `:` and `=` are synonyms and do a substring match
      (exact match on a whole oracle text or type line is never useful).
      For `n:`, `=` means exact name — consistent with `c=` / `ci=`. Use
      quotes for values with spaces.

This module is self-contained — tokenizer, parser and SQL compiler live
together so the ~300-line grammar is readable end-to-end.

The docstring above is the implementer's reference: it covers operator
precedence and compiler behaviour a user never needs. `SYNTAX_HELP` below is
the user-facing version, and it lives here rather than in the interfaces so
that adding an operator and documenting it are the same edit. It used to be
copied into both the TUI and the CLI, which is how the CLI's copy ended up
never mentioning that `n:` is a substring match.
"""
from __future__ import annotations

import re
import sqlite3
from dataclasses import dataclass, field as dc_field
from pathlib import Path
from typing import Any, Iterator, Optional

from mtg_oracle.queries import (
    BUSY_TIMEOUT_S,       # wait out a running sync
    LEGALITY_FORMATS,     # for SYNTAX_HELP's format list
    like_literal,         # user input with % and _ taken literally
    normalize_format,     # 'Competitive Brawl' -> 'competitivebrawl'
)

DB_PATH = Path(__file__).parent.parent / "data" / "mtg.db"


SYNTAX_HELP = f"""\
Scryfall-style search. AND is implicit (space-separated). OR, NOT, and
parentheses are supported. `-` is a shortcut for NOT.

Operators:
  o:TEXT      oracle text contains TEXT  (quote for spaces: o:"draw a card")
  t:TEXT      type line contains TEXT    (t:creature, t:planeswalker)
  n:TEXT      name contains TEXT         (substring)
  n=TEXT      name is exactly TEXT       (n:"Lightning Bolt" also matches
                                          'Emeritus of Conflict // Lightning Bolt')
  kw:KW       card has keyword ability   (flying, trample, prowess, ward, ...)
  c:COLORS    colors subset-contains     (c:u any-blue; c:wu contains W and U)
  c=COLORS    colors equal exactly       (c=wu exactly W+U, not tri-colored)
  ci<=COLORS  color identity fits        (commander legality; ci:, ci=, ci>= too)
  mv:N        mana value comparisons     (also mv=, mv<, mv>, mv<=, mv>=, mv!=)
  pow:S       power                      (string match on :/=, numeric for <, >, etc.)
  tou:S       toughness                  (same shape as pow)
  r:RARITY    rarity                     (common | uncommon | rare | mythic | bonus | special)
  layout:X    card layout                (normal | transform | modal_dfc | split | flip | ...)

Format legality:
  f:FORMAT    legal (or restricted) in FORMAT   (aliases: format:, legal:)
  banned:F    on that format's ban list
  restricted:F  restricted in that format. Means "one copy only" in
              Vintage / Old School, but "may not be your commander" in
              Duel Commander (`duel`) and Tiny Leaders (`tlr`).
  game:X      paper | arena | mtgo — `game:paper` drops Arena-only
              Alchemy rebalances (the `A-` cards)
  is:reserved on the Reserved List

  Formats: {", ".join(sorted(LEGALITY_FORMATS))}
  Spaces and hyphens are ignored, so f:"competitive brawl" works.
  Aliases: edh, pdh, duelcommander, pennydreadful, cbrawl.

Sorting:
  order:asc_FIELD / order:desc_FIELD  (alias sort:) — direction is required.
  Fields: mv, name, power, toughness, rarity, color, ci, edhrec.
  `order:asc_edhrec` is most-played-first. NULLs always sort last.

Boolean:
  A B         both (implicit AND)
  A or B      either
  -A / not A  negation
  (A or B) C  grouping

Mana, function and card kind:
  m:{{U}}{{U}}    mana cost has at least these symbols (also m:2uu; m= is exact)
  c:m         multicolored
  otag:TAG    Scryfall Tagger function (otag:removal, otag:ramp, otag:mana-rock;
              a tag also finds its children, removal -> removal-creature, ...)
  is:X        commander, permanent, spell, historic, dfc, mdfc, split, reserved

Colors can be letters (`u`, `uw`), words (`blue`, `white`, `blue white`), or
braced (`{{W}}{{U}}`).

Free text: a bare word or a quoted phrase matches the name, the type line
or the oracle text, so `bolt` finds Lightning Bolt and `goblin` every
Goblin. Without `order:`, name matches come first. Use o:, t: or n: for
one field only.

Examples:
    counterspell
    goblin mv<=2 c:r
    o:"enters the battlefield" t:creature c:u mv<=3
    otag:removal c:w mv<=2 order:asc_edhrec
    kw:flying (c:w or c:u) -t:artifact
    f:competitivebrawl ci<=UR t:instant order:asc_edhrec
    f:commander game:paper t:artifact mv<=2 order:asc_edhrec
    banned:commander
    c=wu t:instant
    pow>=4 t:creature r:mythic
    (kw:flying or kw:trample) c:g mv<=3
"""


# --- AST nodes ---------------------------------------------------------

@dataclass
class Term:
    field: str
    op: str
    value: str


@dataclass
class Free:
    """A bare word or quoted phrase: name, type line or oracle text."""
    value: str


@dataclass
class Not:
    expr: Any


@dataclass
class And:
    exprs: list = dc_field(default_factory=list)


@dataclass
class Or:
    exprs: list = dc_field(default_factory=list)


class SearchError(ValueError):
    """Raised for tokenizer / parser / compile errors — surfaces to UI."""


# --- Tokenizer --------------------------------------------------------

# Tokens emitted:
#   ('LPAREN', '(')
#   ('RPAREN', ')')
#   ('OR', 'or')
#   ('NOT', 'not')
#   ('MINUS', '-')     negation prefix when immediately before a term
#   ('TERM', (field, op, value))
#   ('BAREWORD', word)  a loose word or quoted string with no operator
_OP_CHARS = set(":=<>!")
_OP_LONG = {">=", "<=", "!="}


def tokenize(s: str) -> Iterator[tuple]:
    i = 0
    n = len(s)
    while i < n:
        ch = s[i]
        if ch.isspace():
            i += 1
            continue
        if ch == "(":
            yield ("LPAREN", "(")
            i += 1
            continue
        if ch == ")":
            yield ("RPAREN", ")")
            i += 1
            continue
        if ch == "-":
            # Negation prefix only if followed by a term (not whitespace/close paren).
            if i + 1 < n and not s[i + 1].isspace() and s[i + 1] not in ")":
                yield ("MINUS", "-")
                i += 1
                continue
            # A bare '-' used to fall through as a bareword and search oracle
            # text for a hyphen, silently narrowing `t:goblin -`.
            raise SearchError(f"dangling '-' at col {i}")
        if ch == '"':
            end = s.find('"', i + 1)
            if end == -1:
                raise SearchError(f"unterminated quoted string at col {i}")
            yield ("BAREWORD", s[i + 1:end])
            i = end + 1
            continue

        # Read identifier part (field name or bare word)
        j = i
        while j < n and not s[j].isspace() and s[j] not in "()" and s[j] not in _OP_CHARS and s[j] != '"':
            j += 1
        ident = s[i:j]

        # Check for op immediately after
        op: Optional[str] = None
        if j < n and s[j] in _OP_CHARS:
            if s[j:j + 2] in _OP_LONG:
                op = s[j:j + 2]
                j += 2
            else:
                op = s[j]
                j += 1

        if op is None:
            # bareword / keyword (or/not handled below)
            i = j
            lc = ident.lower()
            if lc == "or":
                yield ("OR", ident)
            elif lc == "not":
                yield ("NOT", ident)
            elif lc == "and":
                # Juxtaposition already means AND; Scryfall accepts the
                # explicit word too. As a bareword it became `o:and`.
                continue
            elif ident:
                yield ("BAREWORD", ident)
            continue

        # Read value
        if j < n and s[j] == '"':
            end = s.find('"', j + 1)
            if end == -1:
                raise SearchError(f"unterminated quoted string at col {j}")
            value = s[j + 1:end]
            i = end + 1
        else:
            k = j
            while k < n and not s[k].isspace() and s[k] != ")":
                k += 1
            value = s[j:k]
            i = k

        yield ("TERM", (ident, op, value))


# --- Parser -----------------------------------------------------------

class _Parser:
    def __init__(self, tokens: list[tuple]) -> None:
        self._toks = tokens
        self._pos = 0

    def _peek(self) -> tuple:
        return self._toks[self._pos] if self._pos < len(self._toks) else ("EOF", None)

    def _eat(self) -> tuple:
        tok = self._toks[self._pos]
        self._pos += 1
        return tok

    def parse(self):
        ast = self._or()
        if self._peek()[0] != "EOF":
            raise SearchError(f"unexpected token after query: {self._peek()!r}")
        return ast

    def _or(self):
        left = self._and()
        parts = [left]
        while self._peek()[0] == "OR":
            self._eat()
            parts.append(self._and())
        return parts[0] if len(parts) == 1 else Or(parts)

    def _and(self):
        parts = []
        while True:
            t = self._peek()[0]
            if t in ("EOF", "OR", "RPAREN"):
                break
            parts.append(self._not())
        if not parts:
            raise SearchError("empty expression")
        return parts[0] if len(parts) == 1 else And(parts)

    def _not(self):
        t = self._peek()[0]
        if t in ("MINUS", "NOT"):
            self._eat()
            return Not(self._not())
        return self._primary()

    def _primary(self):
        t, v = self._peek()
        if t == "LPAREN":
            self._eat()
            inner = self._or()
            if self._peek()[0] != "RPAREN":
                raise SearchError("expected ')'")
            self._eat()
            return inner
        if t == "TERM":
            self._eat()
            field, op, value = v
            return Term(field, op, value)
        if t == "BAREWORD":
            # Free text, as on Scryfall and Moxfield, but wider: name, type
            # line or oracle text, so `goblin` finds every Goblin.
            self._eat()
            return Free(v)
        raise SearchError(f"unexpected token: {t}:{v!r}")


def parse(query: str):
    tokens = list(tokenize(query))
    if not tokens:
        raise SearchError("empty query")
    return _Parser(tokens).parse()


# --- SQL compiler -----------------------------------------------------

# Field aliases: alias -> canonical
_FIELD_ALIAS = {
    "oracle": "o", "o": "o",
    "type": "t", "t": "t",
    "name": "n", "n": "n",
    "keyword": "kw", "kw": "kw",
    "color": "c", "c": "c",
    "ci": "ci", "coloridentity": "ci", "color_identity": "ci", "id": "ci",
    "mv": "mv", "cmc": "mv",
    "pow": "pow", "power": "pow",
    "tou": "tou", "toughness": "tou",
    "rarity": "r", "r": "r",
    "layout": "layout",
    # Format legality (card_legalities) + printing metadata.
    "f": "f", "format": "f", "legal": "f",
    "banned": "banned",
    "restricted": "restricted",
    "game": "game",
    "is": "is",
    "m": "m", "mana": "m",
    "otag": "otag", "function": "otag", "oracletag": "otag",
}

_GAMES = frozenset({"paper", "mtgo", "arena", "astral", "sega"})

# The front face's type line: a two-faced card is what its front is (the
# deck renderer's rule), so a Sorcery // Land is a spell, not a permanent.
_FRONT_TYPE = (
    "(CASE WHEN instr(c.type_line, ' // ') > 0 "
    "THEN substr(c.type_line, 1, instr(c.type_line, ' // ') - 1) "
    "ELSE COALESCE(c.type_line, '') END)"
)

# `is:` predicates. One entry per flag people actually search for.
_IS_PREDICATES = {
    "reserved": "c.reserved = 1",
    # A legendary creature, or a card that says it can be your commander.
    "commander": (
        f"(({_FRONT_TYPE} LIKE '%Legendary%' AND {_FRONT_TYPE} LIKE '%Creature%')"
        " OR c.oracle_text LIKE '%can be your commander%')"
    ),
    "permanent": (
        f"({_FRONT_TYPE} LIKE '%Artifact%' OR {_FRONT_TYPE} LIKE '%Creature%'"
        f" OR {_FRONT_TYPE} LIKE '%Enchantment%' OR {_FRONT_TYPE} LIKE '%Land%'"
        f" OR {_FRONT_TYPE} LIKE '%Planeswalker%' OR {_FRONT_TYPE} LIKE '%Battle%')"
    ),
    "spell": f"({_FRONT_TYPE} NOT LIKE '%Land%')",
    "historic": (
        f"({_FRONT_TYPE} LIKE '%Legendary%' OR {_FRONT_TYPE} LIKE '%Artifact%'"
        f" OR {_FRONT_TYPE} LIKE '%Saga%')"
    ),
    "dfc": "c.layout IN ('transform', 'modal_dfc', 'reversible_card')",
    "mdfc": "c.layout = 'modal_dfc'",
    "split": "c.layout = 'split'",
}


def _legality_term(op: str, value: str, statuses: tuple[str, ...]) -> tuple[str, list]:
    """EXISTS against card_legalities for one format and a status set."""
    if op not in (":", "="):
        raise SearchError(f"format filters support only ':' or '=', got {op!r}")
    try:
        fmt = normalize_format(value)
    except ValueError as e:
        raise SearchError(str(e))
    placeholders = ",".join("?" * len(statuses))
    return (
        f"EXISTS (SELECT 1 FROM card_legalities cl "
        f"WHERE cl.card_name = c.name AND cl.format = ? "
        f"AND cl.status IN ({placeholders}))",
        [fmt, *statuses],
    )

_ALL_COLORS = ("W", "U", "B", "R", "G")

_COLOR_WORDS = {
    "white": "W", "w": "W",
    "blue": "U", "u": "U",
    "black": "B", "b": "B",
    "red": "R", "r": "R",
    "green": "G", "g": "G",
    "colorless": "", "c": "",
}


def _parse_colors(raw: str) -> list[str]:
    """Accept 'uw', 'U,W', 'blue white', 'blue,white', '{U}{W}'.

    Returns sorted unique letters ([] for colorless).
    """
    s = raw.strip().lower()
    if s in _COLOR_WORDS:
        v = _COLOR_WORDS[s]
        return [v] if v else []

    # Word form first: every space/comma/slash-separated token must be a
    # known color word. 'blue white' used to fall through to the letter
    # branch and die on the 'l' in 'blue'.
    words = [w for w in re.split(r"[\s,;/]+", s) if w]
    if len(words) > 1 and all(w in _COLOR_WORDS for w in words):
        return sorted({_COLOR_WORDS[w] for w in words if _COLOR_WORDS[w]})

    # Letter form: 'uw', 'U,W', '{U}{W}'.
    cleaned = "".join(ch for ch in s if ch not in "{},;/ \t")
    result: set[str] = set()
    for ch in cleaned:
        if ch in "wubrg":
            result.add(ch.upper())
        else:
            raise SearchError(f"unknown color token: {ch!r} in {raw!r}")
    return sorted(result)


def _numeric_op(field_sql: str, op: str, value: str) -> tuple[str, list]:
    """For mv (stored as INTEGER)."""
    try:
        n = int(value)
    except ValueError:
        raise SearchError(f"expected integer for numeric comparison, got {value!r}")
    # Map `:` to `=` for equality on numeric fields.
    sql_op = {":": "=", "=": "=", "!=": "!=", ">": ">", "<": "<", ">=": ">=", "<=": "<="}.get(op)
    if sql_op is None:
        raise SearchError(f"unsupported op {op!r} on numeric field")
    return f"{field_sql} {sql_op} ?", [n]


def _integer_only(col: str) -> str:
    """SQL predicate: this TEXT column holds a plain integer, optionally negative.

    `GLOB '[0-9]*'` only pins the FIRST character, so '1+*' passed the old
    guard and CAST('1+*' AS INTEGER) is 1 — Tarmogoyf silently matched
    `pow<=1`. Requiring every character to be a digit is the real test;
    one leading '-' is allowed because Spinal Parasite is -1/-1.
    """
    digits = f"(CASE WHEN c.{col} LIKE '-%' THEN substr(c.{col}, 2) ELSE c.{col} END)"
    return f"({digits} <> '' AND {digits} NOT GLOB '*[^0-9]*')"


def _pt_op(col: str, op: str, value: str) -> tuple[str, list]:
    """Power/toughness stored as TEXT. Use string equality for `:`/`=`, numeric
    comparisons via a digits-only guard + CAST so '*' / '1+*' never match."""
    if op in (":", "="):
        return f"c.{col} = ?", [value]
    try:
        n = int(value)
    except ValueError:
        raise SearchError(f"expected integer for {col} {op} comparison, got {value!r}")
    sql_op = {"!=": "!=", ">": ">", "<": "<", ">=": ">=", "<=": "<="}.get(op)
    if sql_op is None:
        raise SearchError(f"unsupported op {op!r} on {col}")
    return (
        f"({_integer_only(col)} AND CAST(c.{col} AS INTEGER) {sql_op} ?)",
        [n],
    )


def _contains(value: str) -> str:
    """LIKE pattern for "contains value" with the user's % and _ taken
    literally — `n:_____` otherwise matched every name of five letters or more."""
    return f"%{like_literal(value)}%"


def compile_term(t: Term) -> tuple[str, list]:
    raw_field = t.field.lower()
    field = _FIELD_ALIAS.get(raw_field)
    if field is None:
        raise SearchError(f"unknown field: {t.field!r}")
    op = t.op
    val = t.value

    if field == "o":
        if op not in (":", "="):
            raise SearchError(f"oracle text supports only ':' or '=', got {op!r}")
        return "c.oracle_text LIKE ? ESCAPE '!' COLLATE NOCASE", [_contains(val)]
    if field == "t":
        if op not in (":", "="):
            raise SearchError(f"type line supports only ':' or '=', got {op!r}")
        return "c.type_line LIKE ? ESCAPE '!' COLLATE NOCASE", [_contains(val)]
    if field == "n":
        # `=` means "exactly" everywhere else in this language (c=, ci=), so
        # it means exact name here too. Substring is `n:`. Without this,
        # `n:"Lightning Bolt"` also matches
        # 'Emeritus of Conflict // Lightning Bolt', which is a real card.
        if op == "=":
            return "c.name = ? COLLATE NOCASE", [val]
        if op != ":":
            raise SearchError(f"name supports only ':' or '=', got {op!r}")
        return "c.name LIKE ? ESCAPE '!' COLLATE NOCASE", [_contains(val)]
    if field == "kw":
        if op not in (":", "="):
            raise SearchError(f"kw supports only ':' or '='")
        return (
            "EXISTS (SELECT 1 FROM card_tags kt "
            "WHERE kt.card_name = c.name AND kt.category='keyword' AND kt.tag=?)",
            [val.lower()],
        )
    if field == "c":
        if val.strip().lower() in ("m", "multicolor", "multicolored"):
            if op != ":":
                raise SearchError("c:m (multicolored) supports only ':'")
            return "c.colors LIKE '%,%'", []
        colors = _parse_colors(val)
        if op == "=":
            return "c.colors = ?", [",".join(colors)]
        if op == ":":
            # subset-contains: card.colors ⊇ query.colors
            if not colors:
                return "c.colors = ''", []
            parts = ["c.colors LIKE ?" for _ in colors]
            params = [f"%{ch}%" for ch in colors]
            return "(" + " AND ".join(parts) + ")", params
        raise SearchError(f"color supports ':' or '=', got {op!r}")
    if field == "ci":
        # Color identity. Scryfall convention:
        #   ci:WUB / ci<=WUB → card.CI ⊆ {W,U,B}  (commander-deck legality)
        #   ci=WUB           → card.CI exactly {W,U,B}
        #   ci>=WUB          → card.CI ⊇ {W,U,B}
        colors = _parse_colors(val)
        if op == "=":
            return "c.color_identity = ?", [",".join(colors)]
        if op in (":", "<="):
            # Subset: card has none of the colors NOT in the query set.
            excluded = [ch for ch in _ALL_COLORS if ch not in colors]
            if not excluded:
                # Query covers all five colors → every card is a subset.
                return "1=1", []
            parts = ["(c.color_identity IS NULL OR c.color_identity NOT LIKE ?)" for _ in excluded]
            params = [f"%{ch}%" for ch in excluded]
            return "(" + " AND ".join(parts) + ")", params
        if op == ">=":
            # Superset: card has every color in the query set.
            if not colors:
                return "1=1", []
            parts = ["c.color_identity LIKE ?" for _ in colors]
            params = [f"%{ch}%" for ch in colors]
            return "(" + " AND ".join(parts) + ")", params
        raise SearchError(f"color identity supports ':', '=', '<=', '>=', got {op!r}")
    if field == "mv":
        return _numeric_op("c.mana_value", op, val)
    if field == "pow":
        return _pt_op("power", op, val)
    if field == "tou":
        return _pt_op("toughness", op, val)
    if field == "r":
        if op not in (":", "="):
            raise SearchError(f"rarity supports only ':' or '='")
        return "c.rarity = ?", [val.lower()]
    if field == "layout":
        if op not in (":", "="):
            raise SearchError(f"layout supports only ':' or '='")
        return "c.layout = ?", [val.lower()]
    if field == "f":
        # Restricted cards ARE legal to play (limited to one copy), so
        # `f:vintage` has to include them — matching Scryfall's semantics.
        return _legality_term(op, val, ("legal", "restricted"))
    if field == "banned":
        return _legality_term(op, val, ("banned",))
    if field == "restricted":
        return _legality_term(op, val, ("restricted",))
    if field == "game":
        if op not in (":", "="):
            raise SearchError(f"game supports only ':' or '='")
        game = val.strip().lower()
        if game not in _GAMES:
            raise SearchError(
                f"unknown game: {val!r}. Valid: {', '.join(sorted(_GAMES))}"
            )
        # games is a sorted CSV; no game name is a substring of another.
        return "c.games LIKE ?", [f"%{game}%"]
    if field == "is":
        if op not in (":", "="):
            raise SearchError(f"is: supports only ':' or '='")
        pred = _IS_PREDICATES.get(val.strip().lower())
        if pred is None:
            raise SearchError(
                f"unknown is: predicate {val!r}. Valid: "
                f"{', '.join(sorted(_IS_PREDICATES))}"
            )
        return pred, []
    if field == "m":
        return _mana_cost_term(op, val)
    if field == "otag":
        if op not in (":", "="):
            raise SearchError("otag supports only ':' or '='")
        # Stored with spaces ('mana rock'); Scryfall writes them with
        # hyphens ('mana-rock'). A tag also finds its children, which Tagger
        # names `<tag>-<kind>` ('removal-creature'): there is no plain
        # 'removal' tag at all.
        written = val.strip().lower()
        spaced = written.replace("-", " ")
        return (
            "EXISTS (SELECT 1 FROM card_oracle_tags ot WHERE ot.card_name = c.name "
            "AND (ot.tag IN (?, ?) OR ot.tag LIKE ? ESCAPE '!' OR ot.tag LIKE ? ESCAPE '!'))",
            [written, spaced, f"{like_literal(written)}-%", f"{like_literal(spaced)}-%"],
        )
    raise SearchError(f"unknown field mapping: {field!r}")


_MANA_SYMBOL_RE = re.compile(r"\{[^{}]+\}")


def _mana_symbols(raw: str) -> list[str]:
    """'{2}{U}{U}' or the shorthand '2uu' -> ['{2}', '{U}', '{U}']."""
    s = raw.strip()
    if "{" in s:
        symbols = [m.upper() for m in _MANA_SYMBOL_RE.findall(s)]
        if "".join(symbols) != s.upper().replace(" ", ""):
            raise SearchError(f"can't read mana cost {raw!r}")
        return symbols
    symbols = []
    for part in re.findall(r"[0-9]+|.", s.lower()):
        if part.isdigit():
            symbols.append("{" + part + "}")
        elif part in "wubrgcxs":
            symbols.append("{" + part.upper() + "}")
        else:
            raise SearchError(f"unknown mana symbol {part!r} in {raw!r}")
    return symbols


def _mana_cost_term(op: str, value: str) -> tuple[str, list]:
    """`m:` has at least these symbols (counted, not a substring: {U}{1}
    finds {1}{U}); `m=` exactly these and no others."""
    if op not in (":", "="):
        raise SearchError(f"mana cost supports only ':' or '=', got {op!r}")
    symbols = _mana_symbols(value)
    if not symbols:
        raise SearchError("m: needs at least one mana symbol")
    counts: dict[str, int] = {}
    for sym in symbols:
        counts[sym] = counts.get(sym, 0) + 1
    # How often `?` occurs in the cost: the length it loses without it, over its own length.
    occurs = ("((LENGTH(COALESCE(c.mana_cost, '')) - "
              "LENGTH(REPLACE(COALESCE(c.mana_cost, ''), ?, ''))) / ?)")
    cmp = ">=" if op == ":" else "="
    parts, params = [], []
    for sym, n in counts.items():
        parts.append(f"{occurs} {cmp} ?")
        params.extend([sym, len(sym), n])
    if op == "=":
        # ...and nothing else: the cost holds as many `{` as there are symbols.
        parts.append(f"{occurs} = ?")
        params.extend(["{", 1, len(symbols)])
    return "(" + " AND ".join(parts) + ")", params


def compile_ast(node) -> tuple[str, list]:
    if isinstance(node, Term):
        return compile_term(node)
    if isinstance(node, Free):
        pattern = _contains(node.value)
        return (
            "(c.name LIKE ? ESCAPE '!' COLLATE NOCASE"
            " OR c.type_line LIKE ? ESCAPE '!' COLLATE NOCASE"
            " OR c.oracle_text LIKE ? ESCAPE '!' COLLATE NOCASE)",
            [pattern, pattern, pattern],
        )
    if isinstance(node, Not):
        inner, params = compile_ast(node.expr)
        # A comparison against a NULL column is NULL, and NOT NULL is still
        # NULL — so `-pow>=4` dropped every non-creature. Unknown is "no match".
        return f"NOT COALESCE(({inner}), 0)", params
    if isinstance(node, And):
        parts = [compile_ast(e) for e in node.exprs]
        sql = "(" + " AND ".join(p[0] for p in parts) + ")"
        params: list = []
        for p in parts:
            params.extend(p[1])
        return sql, params
    if isinstance(node, Or):
        parts = [compile_ast(e) for e in node.exprs]
        sql = "(" + " OR ".join(p[0] for p in parts) + ")"
        params = []
        for p in parts:
            params.extend(p[1])
        return sql, params
    raise SearchError(f"unknown AST node: {node!r}")


# --- Order extraction + ORDER BY compiler -----------------------------

# Matches `order:asc_FIELD`, `order:desc_FIELD`, `sort:asc_FIELD`, etc.
# The token must be word-bounded (start of string, or after whitespace) so
# it doesn't collide with substrings like `disorder:foo` or `o:"order:..."`.
# Quoted strings are matched first and passed through untouched, so
# `o:"x order:asc_mv"` searches for that text rather than sorting.
_ORDER_TOKEN_RE = re.compile(
    r'"[^"]*"|(?:^|(?<=\s))(?:order|sort)[:=]([a-zA-Z_]+)(?=\s|$)',
    re.IGNORECASE,
)

# Fields whose sort expression is a simple column. Power and toughness are
# handled separately because they're TEXT and need a numeric coercion.
_SORT_FIELD_SQL = {
    "mv": "c.mana_value",
    "cmc": "c.mana_value",
    "name": "c.name COLLATE NOCASE",
    "rarity": (
        "CASE c.rarity "
        "WHEN 'common' THEN 1 "
        "WHEN 'uncommon' THEN 2 "
        "WHEN 'rare' THEN 3 "
        "WHEN 'mythic' THEN 4 "
        "WHEN 'bonus' THEN 5 "
        "WHEN 'special' THEN 6 "
        "ELSE 7 END"
    ),
    "color": "COALESCE(c.colors, '')",
    # ci sort is by *number of colors* in the color identity — useful for
    # going from mono to multicolor (or vice-versa) within a result set.
    "ci": "CASE WHEN COALESCE(c.color_identity, '') = '' THEN 0 ELSE "
          "LENGTH(c.color_identity) - LENGTH(REPLACE(c.color_identity, ',', '')) + 1 END",
    # EDHREC popularity rank: 1 = most played. `order:asc_edhrec` is
    # "most popular first", which is what you want when browsing
    # candidates for a deck. NULL (unranked) sorts last as usual.
    "edhrec": "c.edhrec_rank",
}


def extract_order(query: str) -> tuple[str, list[tuple[str, str]]]:
    """Pull `order:` / `sort:` tokens out of the raw query string.

    Returns (cleaned_query, [(field, direction), ...]) where direction is
    'asc' or 'desc'. Raises SearchError if a token has malformed shape.

    Strict syntax: the value must be `asc_FIELD` or `desc_FIELD`. Bare
    `order:FIELD` is rejected so the direction is always explicit.
    """
    orders: list[tuple[str, str]] = []
    def _capture(m: re.Match) -> str:
        if m.group(1) is None:
            return m.group(0)
        spec = m.group(1).lower()
        if "_" not in spec:
            raise SearchError(
                f"sort token must be 'asc_FIELD' or 'desc_FIELD', got "
                f"{m.group(0).strip()!r}"
            )
        direction, _, field = spec.partition("_")
        if direction not in ("asc", "desc"):
            raise SearchError(
                f"sort direction must be 'asc' or 'desc', got {direction!r} "
                f"in {m.group(0).strip()!r}"
            )
        if not field:
            raise SearchError(f"sort token missing field name: {m.group(0).strip()!r}")
        orders.append((field, direction))
        # Replace with a single space so the surrounding query still tokenizes.
        return " "
    cleaned = _ORDER_TOKEN_RE.sub(_capture, query).strip()
    return cleaned, orders


def free_words(node) -> list[str]:
    """The free text the query asks FOR, in order — not what is under a
    NOT: `-bolt` must not rank Lightning Bolt first."""
    if isinstance(node, Free):
        return [node.value]
    if isinstance(node, (And, Or)):
        return [w for e in node.exprs for w in free_words(e)]
    return []


def _relevance(words: list[str]) -> tuple[str, list]:
    """Name matches first — exact, then prefix, then every word somewhere in
    the name — then type-line matches, then the rest (oracle text only)."""
    phrase = " ".join(words)
    in_name = " AND ".join("c.name LIKE ? ESCAPE '!' COLLATE NOCASE" for _ in words)
    in_type = " AND ".join("c.type_line LIKE ? ESCAPE '!' COLLATE NOCASE" for _ in words)
    sql = (
        "CASE WHEN c.name = ? COLLATE NOCASE THEN 0 "
        "WHEN c.name LIKE ? ESCAPE '!' COLLATE NOCASE THEN 1 "
        f"WHEN {in_name} THEN 2 WHEN {in_type} THEN 3 ELSE 4 END"
    )
    params = [phrase, f"{like_literal(phrase)}%"]
    params += [_contains(w) for w in words] + [_contains(w) for w in words]
    return sql, params


def _build_order_by(orders: list[tuple[str, str]], words=()) -> tuple[str, list]:
    """Translate extracted (field, direction) pairs into ORDER BY SQL.

    A trailing tiebreaker on c.name keeps the result stable when two rows
    share the primary sort key. With no sort asked for, free text ranks by
    relevance (`_relevance`), then by name.
    """
    if not orders:
        if words:
            sql, params = _relevance(list(words))
            return f"{sql}, c.name COLLATE NOCASE ASC", params
        return "c.name COLLATE NOCASE ASC", []
    parts: list[str] = []
    for field, direction in orders:
        dir_sql = "DESC" if direction == "desc" else "ASC"
        if field in ("pow", "power", "tou", "toughness"):
            col = "power" if field in ("pow", "power") else "toughness"
            # Numeric when digits-only ('3'), NULL otherwise ('*', '1+*') —
            # and NULLs are forced to sort last just below.
            expr = (
                f"CASE WHEN {_integer_only(col)} "
                f"THEN CAST(c.{col} AS REAL) END"
            )
        elif field in _SORT_FIELD_SQL:
            expr = _SORT_FIELD_SQL[field]
        else:
            valid = sorted(set(_SORT_FIELD_SQL) | {"power", "toughness"})
            raise SearchError(
                f"unknown sort field: {field!r}. Valid: {', '.join(valid)}"
            )
        # NULL last regardless of direction — piggy-back an IS-NULL boolean
        # as the primary sub-key so unknown/missing values never lead the
        # result set in `asc_*` mode.
        parts.append(f"({expr}) IS NULL")
        parts.append(f"({expr}) {dir_sql}")
    parts.append("c.name COLLATE NOCASE ASC")
    return ", ".join(parts), []


# --- Top-level query entry point --------------------------------------

def _parse_or_none(cleaned_query: str):
    """The syntax tree, or None for an empty query — produced when the user
    passes only `order:` tokens, and matching every card."""
    return parse(cleaned_query) if cleaned_query.strip() else None


def _compile_where_or_all(ast) -> tuple[str, list]:
    return ("1=1", []) if ast is None else compile_ast(ast)


def run_query(query: str, limit: int = 50, offset: int = 0) -> list[dict]:
    limit = max(1, min(limit, 1000))
    if offset < 0:
        offset = 0
    cleaned, orders = extract_order(query)
    ast = _parse_or_none(cleaned)
    where_sql, params = _compile_where_or_all(ast)
    order_sql, order_params = _build_order_by(orders, free_words(ast))
    sql = (
        "SELECT c.name, c.type_line, c.mana_cost, c.mana_value "
        "FROM cards c WHERE " + where_sql +
        " ORDER BY " + order_sql + " LIMIT ? OFFSET ?"
    )
    params_all = list(params) + order_params + [limit, offset]
    _ensure_db()
    conn = sqlite3.connect(
        f"file:{DB_PATH}?mode=ro", uri=True, timeout=BUSY_TIMEOUT_S,
    )
    try:
        conn.row_factory = sqlite3.Row
        cur = conn.cursor()
        cur.execute(sql, params_all)
        return [dict(r) for r in cur.fetchall()]
    finally:
        conn.close()


def count_query(query: str) -> int:
    """Return the total number of matches for a query (no LIMIT / OFFSET).

    Order tokens are stripped — they don't affect the row count."""
    cleaned, _ = extract_order(query)
    where_sql, params = _compile_where_or_all(_parse_or_none(cleaned))
    sql = f"SELECT COUNT(*) FROM cards c WHERE {where_sql}"
    _ensure_db()
    conn = sqlite3.connect(
        f"file:{DB_PATH}?mode=ro", uri=True, timeout=BUSY_TIMEOUT_S,
    )
    try:
        cur = conn.cursor()
        cur.execute(sql, list(params))
        return cur.fetchone()[0]
    finally:
        conn.close()


def _ensure_db() -> None:
    if not DB_PATH.exists():
        raise FileNotFoundError(
            f"MTG Oracle database not found at {DB_PATH}. "
            "Run `python scripts/init_db.py` then `python scripts/sync.py`."
        )
