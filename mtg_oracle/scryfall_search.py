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
    restricted:FORMAT           restricted in FORMAT (Vintage / Old School)
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

    # Bare words and quoted strings
    enters                      equivalent to o:enters (oracle-text default)
    "enters the battlefield"    quoted value with spaces

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
"""
from __future__ import annotations

import re
import sqlite3
from dataclasses import dataclass, field as dc_field
from pathlib import Path
from typing import Any, Iterator, Optional

from mtg_oracle.queries import (
    BUSY_TIMEOUT_S,       # wait out a running sync
    normalize_format,     # 'Competitive Brawl' -> 'competitivebrawl'
)

DB_PATH = Path(__file__).parent.parent / "data" / "mtg.db"


# --- AST nodes ---------------------------------------------------------

@dataclass
class Term:
    field: str
    op: str
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
            # Otherwise fall through into word parsing (shouldn't really happen).
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
            # Bare word / quoted string default to oracle-text search.
            self._eat()
            return Term("o", ":", v)
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
}

_GAMES = frozenset({"paper", "mtgo", "arena", "astral", "sega"})

# `is:` predicates. Small on purpose — one entry per genuinely useful flag.
_IS_PREDICATES = {
    "reserved": "c.reserved = 1",
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


def _digits_only(col: str) -> str:
    """SQL predicate: this TEXT column holds nothing but digits.

    `GLOB '[0-9]*'` only pins the FIRST character, so '1+*' passed the old
    guard and CAST('1+*' AS INTEGER) is 1 — Tarmogoyf silently matched
    `pow<=1`. Requiring every character to be a digit is the real test.
    NULL columns compare to NULL and are filtered out either way.
    """
    return f"(c.{col} <> '' AND c.{col} NOT GLOB '*[^0-9]*')"


def _pt_op(col: str, op: str, value: str) -> tuple[str, list]:
    """Power/toughness stored as TEXT. Use string equality for `:`/`=`, numeric
    comparisons via a digits-only guard + CAST so '*' / '1+*' never match."""
    if op in (":", "="):
        return f"c.{col} = ?", [value]
    try:
        n = int(value)
    except ValueError:
        raise SearchError(f"expected integer for {col} {op} comparison, got {value!r}")
    sql_op = {"!=": "!=", ">": ">", "<": "<", ">=": ">=", "<=": "<="}[op]
    return (
        f"({_digits_only(col)} AND CAST(c.{col} AS INTEGER) {sql_op} ?)",
        [n],
    )


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
        return "c.oracle_text LIKE ? COLLATE NOCASE", [f"%{val}%"]
    if field == "t":
        if op not in (":", "="):
            raise SearchError(f"type line supports only ':' or '=', got {op!r}")
        return "c.type_line LIKE ? COLLATE NOCASE", [f"%{val}%"]
    if field == "n":
        # `=` means "exactly" everywhere else in this language (c=, ci=), so
        # it means exact name here too. Substring is `n:`. Without this,
        # `n:"Lightning Bolt"` also matches
        # 'Emeritus of Conflict // Lightning Bolt', which is a real card.
        if op == "=":
            return "c.name = ? COLLATE NOCASE", [val]
        if op != ":":
            raise SearchError(f"name supports only ':' or '=', got {op!r}")
        return "c.name LIKE ? COLLATE NOCASE", [f"%{val}%"]
    if field == "kw":
        if op not in (":", "="):
            raise SearchError(f"kw supports only ':' or '='")
        return (
            "EXISTS (SELECT 1 FROM card_tags kt "
            "WHERE kt.card_name = c.name AND kt.category='keyword' AND kt.tag=?)",
            [val.lower()],
        )
    if field == "c":
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
    raise SearchError(f"unknown field mapping: {field!r}")


def compile_ast(node) -> tuple[str, list]:
    if isinstance(node, Term):
        return compile_term(node)
    if isinstance(node, Not):
        inner, params = compile_ast(node.expr)
        return f"NOT ({inner})", params
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
_ORDER_TOKEN_RE = re.compile(
    r"(?:^|\s)(?:order|sort)[:=]([a-zA-Z_]+)(?=\s|$)",
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
    "ci": "LENGTH(COALESCE(c.color_identity, '')) - "
          "(LENGTH(REPLACE(COALESCE(c.color_identity, ''), ',', '')))",
    # EDHREC popularity rank: 1 = most played. `order:asc_edhrec` is
    # "most popular first", which is what you want when browsing
    # candidates for a deck. NULL (unranked) sorts last as usual.
    "edhrec": "c.edhrec_rank",
}


def _extract_order(query: str) -> tuple[str, list[tuple[str, str]]]:
    """Pull `order:` / `sort:` tokens out of the raw query string.

    Returns (cleaned_query, [(field, direction), ...]) where direction is
    'asc' or 'desc'. Raises SearchError if a token has malformed shape.

    Strict syntax: the value must be `asc_FIELD` or `desc_FIELD`. Bare
    `order:FIELD` is rejected so the direction is always explicit.
    """
    orders: list[tuple[str, str]] = []
    def _capture(m: re.Match) -> str:
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


def _build_order_by(orders: list[tuple[str, str]]) -> str:
    """Translate extracted (field, direction) pairs into ORDER BY SQL.

    A trailing tiebreaker on c.name keeps the result stable when two rows
    share the primary sort key.
    """
    if not orders:
        return "c.name COLLATE NOCASE ASC"
    parts: list[str] = []
    for field, direction in orders:
        dir_sql = "DESC" if direction == "desc" else "ASC"
        if field in ("pow", "power", "tou", "toughness"):
            col = "power" if field in ("pow", "power") else "toughness"
            # Numeric when digits-only ('3'), NULL otherwise ('*', '1+*') —
            # and NULLs are forced to sort last just below.
            expr = (
                f"CASE WHEN {_digits_only(col)} "
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
    return ", ".join(parts)


# --- Top-level query entry point --------------------------------------

def _compile_where(query: str) -> tuple[str, list]:
    return compile_ast(parse(query))


def _compile_where_or_all(cleaned_query: str) -> tuple[str, list]:
    """Like _compile_where but tolerates an empty cleaned query — produced
    when the user passes only `order:` tokens with no filters. Returns a
    no-op WHERE that matches all rows."""
    if not cleaned_query.strip():
        return "1=1", []
    return _compile_where(cleaned_query)


def run_query(query: str, limit: int = 50, offset: int = 0) -> list[dict]:
    if limit < 1 or limit > 1000:
        limit = 50
    if offset < 0:
        offset = 0
    cleaned, orders = _extract_order(query)
    where_sql, params = _compile_where_or_all(cleaned)
    order_sql = _build_order_by(orders)
    sql = (
        "SELECT c.name, c.type_line, c.mana_cost, c.mana_value "
        "FROM cards c WHERE " + where_sql +
        " ORDER BY " + order_sql + " LIMIT ? OFFSET ?"
    )
    params_all = list(params) + [limit, offset]
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
    cleaned, _ = _extract_order(query)
    where_sql, params = _compile_where_or_all(cleaned)
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
