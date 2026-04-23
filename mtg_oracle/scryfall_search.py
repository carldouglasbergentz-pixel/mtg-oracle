"""Scryfall-style search query language for `mtg.db`.

Supported syntax (Level 2):
    # Term operators (all case-insensitive on field names)
    o:TEXT / oracle:TEXT        oracle_text contains TEXT
    t:TEXT / type:TEXT          type_line contains TEXT
    n:TEXT / name:TEXT          name contains TEXT
    kw:KEYWORD                  has a card_tags row with category='keyword'
    c:COLORS  / c=COLORS        colors subset-contains / equals exactly
    mv:N, mv=N, mv>N, mv<N,
    mv>=N, mv<=N, mv!=N         mana value compared to integer N
    pow:S  pow=S  pow>N ...     power (string equality for `:`/`=`, numeric for compares)
    tou:S  tou=S  tou>N ...     toughness
    r:RARITY / rarity:RARITY    rarity equals (common|uncommon|rare|mythic|bonus|special)
    layout:LAYOUT               layout equals (normal|transform|modal_dfc|split|flip|...)

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

Quick parser semantics:
    - AND binds tighter than OR. `a or b c` parses as `a or (b c)`.
    - Negation binds tighter than AND. `-a b` is `(-a) AND b`.
    - Comparison operators (`>`, `>=`, `<`, `<=`, `!=`) only make sense on
      numeric fields (mv, pow, tou); the compiler raises on misuse.
    - For string fields (`o:`, `t:`, `n:`), `:` and `=` are synonyms and
      do substring match (case-insensitive). Use quotes for values with
      spaces.

This module is self-contained — tokenizer, parser and SQL compiler live
together so the ~300-line grammar is readable end-to-end.
"""
from __future__ import annotations

import sqlite3
from dataclasses import dataclass, field as dc_field
from pathlib import Path
from typing import Any, Iterator, Optional

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
    "mv": "mv", "cmc": "mv",
    "pow": "pow", "power": "pow",
    "tou": "tou", "toughness": "tou",
    "rarity": "r", "r": "r",
    "layout": "layout",
}

_COLOR_WORDS = {
    "white": "W", "w": "W",
    "blue": "U", "u": "U",
    "black": "B", "b": "B",
    "red": "R", "r": "R",
    "green": "G", "g": "G",
    "colorless": "", "c": "",
}


def _parse_colors(raw: str) -> list[str]:
    """Accept 'uw', 'U,W', 'blue white', '{U}{W}', etc. Return sorted unique letters."""
    s = raw.strip().lower()
    if s in _COLOR_WORDS:
        v = _COLOR_WORDS[s]
        return [v] if v else []
    # Strip delimiters
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


def _pt_op(col: str, op: str, value: str) -> tuple[str, list]:
    """Power/toughness stored as TEXT. Use string equality for `:`/`=`, numeric
    comparisons via GLOB-guard + CAST so '*' / '1+*' are excluded safely."""
    if op in (":", "="):
        return f"c.{col} = ?", [value]
    try:
        n = int(value)
    except ValueError:
        raise SearchError(f"expected integer for {col} {op} comparison, got {value!r}")
    sql_op = {"!=": "!=", ">": ">", "<": "<", ">=": ">=", "<=": "<="}[op]
    # Guard: only rows whose value is digits-only. Casts like CAST('*' AS INTEGER)
    # return 0 which would produce false matches; the GLOB filter avoids that.
    return f"(c.{col} GLOB '[0-9]*' AND CAST(c.{col} AS INTEGER) {sql_op} ?)", [n]


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
        if op not in (":", "="):
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


# --- Top-level query entry point --------------------------------------

def run_query(query: str, limit: int = 50) -> list[dict]:
    if limit < 1 or limit > 1000:
        limit = 50
    ast = parse(query)
    where_sql, params = compile_ast(ast)
    sql = (
        "SELECT c.name, c.type_line, c.mana_cost, c.mana_value "
        "FROM cards c WHERE " + where_sql +
        " ORDER BY c.name COLLATE NOCASE LIMIT ?"
    )
    params_all = list(params) + [limit]

    if not DB_PATH.exists():
        raise FileNotFoundError(
            f"MTG Oracle database not found at {DB_PATH}. "
            "Run `python scripts/init_db.py` then `python scripts/sync.py`."
        )
    conn = sqlite3.connect(f"file:{DB_PATH}?mode=ro", uri=True)
    try:
        conn.row_factory = sqlite3.Row
        cur = conn.cursor()
        cur.execute(sql, params_all)
        return [dict(r) for r in cur.fetchall()]
    finally:
        conn.close()
