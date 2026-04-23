"""MTG Oracle CLI — query the local knowledge base from the command line.

Subcommands:
    mtg_cli.py card <name>
    mtg_cli.py ruling <name>
    mtg_cli.py combo <card>                   # combos featuring a card
    mtg_cli.py combos <card1> <card2> [...]   # combos containing ALL listed cards
    mtg_cli.py combo-info <combo_id>          # full combo detail
    mtg_cli.py rule <rule_number>
    mtg_cli.py search-rules <text>
    mtg_cli.py search [--name X] [--tag Y] [--type Z] [--mana-ability] [--limit N]
    mtg_cli.py correction [--card X] [--topic Y]

Output is human-readable, monospace-friendly ASCII (no unicode borders)
so it renders cleanly on Windows consoles. All queries are read-only.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))
from mtg_oracle import queries as q
from mtg_oracle import scryfall_search as ss
from mtg_oracle.renderer import (
    render_card as _render_card,
    render_rulings as _render_rulings,
    render_combo as _render_combo,
    render_combo_list as _render_combo_list,
    render_rule as _render_rule,
    render_rules_search as _render_rules_search,
    render_search as _render_search,
    render_corrections as _render_corrections,
)


SEARCH_HELP = """\
Scryfall-style card search. Supports AND (implicit via space), OR, NOT,
parentheses, negation with `-`, and numeric range operators.

Operators:
  o:TEXT      oracle text contains TEXT (quoted for spaces)
  t:TEXT      type line contains TEXT
  n:TEXT      name contains TEXT
  kw:KW       card has keyword ability (flying, trample, prowess, ...)
  c:COLORS    colors subset-contains (c:u = any card including blue)
  c=COLORS    colors equal exactly (c=wu = exactly W+U)
  mv:N        mana value comparisons (:, =, <, >, <=, >=, !=)
  pow:S       power (string match with :/=, numeric with <, >, etc.)
  tou:S       toughness (same shape as pow)
  r:RARITY    rarity (common|uncommon|rare|mythic|bonus|special)
  layout:X    layout (normal|transform|modal_dfc|split|flip|meld|...)

Boolean:
  A B         both (implicit AND)
  A or B      either
  -A  /  not A  negation
  (A or B) C  grouping

Colors can be letters (u, uw), words (blue, white), or brace form ({W}{U}).
Bare words and quoted strings default to oracle-text search.

Examples:
  o:"enters the battlefield" t:creature c:u mv<=3
  kw:flying (c:w or c:u) -t:artifact
  "counter target spell" c:u mv:2
  c=wu t:instant
  pow>=4 t:creature r:mythic
"""


# --- CLI dispatch ------------------------------------------------------

def _cmd_card(args) -> int:
    card = q.get_card(args.name)
    if not card:
        print(f"(card not found: {args.name})")
        return 1
    if args.json:
        print(json.dumps(card, indent=2, default=str))
    else:
        print(_render_card(card))
    return 0


def _cmd_ruling(args) -> int:
    rulings = q.get_rulings(args.name)
    if args.json:
        print(json.dumps(rulings, indent=2, default=str))
    else:
        print(_render_rulings(args.name, rulings))
    return 0 if rulings else 1


def _cmd_combo(args) -> int:
    combos = q.find_combos_with_card(args.card, limit=args.limit)
    if args.json:
        print(json.dumps(combos, indent=2, default=str))
    else:
        print(_render_combo_list(
            combos,
            f"{len(combos)} combo(s) featuring {args.card}:",
        ))
    return 0 if combos else 1


def _cmd_combos(args) -> int:
    combos = q.find_combos_with_all(args.cards, limit=args.limit)
    if args.json:
        print(json.dumps(combos, indent=2, default=str))
    else:
        joined = " + ".join(args.cards)
        print(_render_combo_list(combos, f"{len(combos)} combo(s) containing ALL of: {joined}"))
    return 0 if combos else 1


def _cmd_combo_info(args) -> int:
    combo = q.get_combo(args.combo_id)
    if not combo:
        print(f"(combo not found: {args.combo_id})")
        return 1
    if args.json:
        print(json.dumps(combo, indent=2, default=str))
    else:
        print(_render_combo(combo))
    return 0


def _cmd_rule(args) -> int:
    rule = q.get_rule(args.rule_number)
    if not rule:
        print(f"(rule not found: {args.rule_number})")
        return 1
    if args.json:
        print(json.dumps(rule, indent=2, default=str))
    else:
        print(_render_rule(rule))
    return 0


def _cmd_search_rules(args) -> int:
    rules = q.search_rules(args.text, limit=args.limit)
    if args.json:
        print(json.dumps(rules, indent=2, default=str))
    else:
        print(_render_rules_search(args.text, rules))
    return 0 if rules else 1


def _cmd_search(args) -> int:
    query = " ".join(args.query).strip()
    if not query or query.lower() in ("help", "?"):
        print(SEARCH_HELP)
        return 0
    try:
        total = ss.count_query(query)
        offset = (max(1, args.page) - 1) * args.limit
        cards = ss.run_query(query, limit=args.limit, offset=offset)
    except ss.SearchError as e:
        print(f"search error: {e}\n\nType `search help` for syntax.")
        return 2
    if args.json:
        print(json.dumps({"total": total, "page": args.page,
                          "page_size": args.limit, "rows": cards},
                         indent=2, default=str))
    else:
        print(_render_search(cards, page=args.page, total=total,
                             page_size=args.limit))
    return 0 if cards else 1


def _cmd_correction(args) -> int:
    rows = q.get_corrections(card=args.card, topic=args.topic, limit=args.limit)
    if args.json:
        print(json.dumps(rows, indent=2, default=str))
    else:
        print(_render_corrections(rows))
    return 0 if rows else 1


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="mtg", description=__doc__)
    p.add_argument("--json", action="store_true", help="Emit JSON instead of formatted text.")
    sub = p.add_subparsers(dest="cmd", required=True)

    sp = sub.add_parser("card", help="Full card profile by exact name.")
    sp.add_argument("name")
    sp.set_defaults(func=_cmd_card)

    sp = sub.add_parser("ruling", help="Rulings for a card.")
    sp.add_argument("name")
    sp.set_defaults(func=_cmd_ruling)

    sp = sub.add_parser("combo", help="Combos featuring a single card.")
    sp.add_argument("card")
    sp.add_argument("--limit", type=int, default=25)
    sp.set_defaults(func=_cmd_combo)

    sp = sub.add_parser("combos", help="Combos containing ALL the listed cards.")
    sp.add_argument("cards", nargs="+")
    sp.add_argument("--limit", type=int, default=25)
    sp.set_defaults(func=_cmd_combos)

    sp = sub.add_parser("combo-info", help="Full detail of a combo by ID.")
    sp.add_argument("combo_id")
    sp.set_defaults(func=_cmd_combo_info)

    sp = sub.add_parser("rule", help="Rule text by exact rule number.")
    sp.add_argument("rule_number")
    sp.set_defaults(func=_cmd_rule)

    sp = sub.add_parser("search-rules", help="Find rules whose text matches a substring.")
    sp.add_argument("text")
    sp.add_argument("--limit", type=int, default=25)
    sp.set_defaults(func=_cmd_search_rules)

    sp = sub.add_parser(
        "search",
        help="Scryfall-style card search. `search help` prints the syntax guide.",
    )
    sp.add_argument("query", nargs="*", help="Query string, e.g. `t:creature c:u mv<=3`")
    sp.add_argument("--limit", type=int, default=50, help="Page size (default 50).")
    sp.add_argument("--page", type=int, default=1, help="1-based page number (default 1).")
    sp.set_defaults(func=_cmd_search)

    sp = sub.add_parser("correction", help="List corrections (feedback loop).")
    sp.add_argument("--card", help="Filter to corrections relating to a card name.")
    sp.add_argument("--topic", help="Filter by topic keyword.")
    sp.add_argument("--limit", type=int, default=25)
    sp.set_defaults(func=_cmd_correction)

    return p


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return args.func(args)
    except FileNotFoundError as e:
        print(f"ERR {e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
