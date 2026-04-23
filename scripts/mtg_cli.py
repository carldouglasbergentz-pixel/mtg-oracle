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
import textwrap
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))
from mtg_oracle import queries as q


# --- Rendering helpers -------------------------------------------------

BOX_H = "-"
BOX_V = "|"
CORNER = "+"
INDENT = "  "
WRAP_COLS = 70


def _wrap(text: str, indent: str = INDENT) -> str:
    if not text:
        return ""
    lines = []
    for para in str(text).split("\n"):
        lines.append(textwrap.fill(
            para, width=WRAP_COLS, initial_indent=indent,
            subsequent_indent=indent, break_long_words=False,
        ))
    return "\n".join(lines)


def _hr(width: int = WRAP_COLS) -> str:
    return CORNER + BOX_H * (width - 2) + CORNER


# --- Renderers ---------------------------------------------------------

def _render_card(card: dict) -> str:
    lines = []
    lines.append(_hr())
    lines.append(f"{BOX_V} {card['name']}")
    if card.get("type_line"):
        lines.append(f"{BOX_V} {card['type_line']}")
    lines.append(_hr())
    if card.get("oracle_text"):
        lines.append(_wrap(card["oracle_text"], indent=""))

    tags = card.get("tags") or {}
    if tags:
        lines.append("")
        lines.append("Tags:")
        for cat in ("supertype", "type", "subtype", "keyword"):
            if cat in tags:
                lines.append(f"{INDENT}{cat:10} {', '.join(tags[cat])}")

    abilities = card.get("abilities") or []
    if abilities:
        lines.append("")
        lines.append("Parsed abilities:")
        for a in abilities:
            flags = []
            if a.get("has_target"): flags.append("target")
            if a.get("produces_mana"): flags.append("produces mana")
            if a.get("is_mana_ability"): flags.append("IS MANA ABILITY")
            flag_str = f" [{', '.join(flags)}]" if flags else ""
            lines.append(f"{INDENT}- {a['ability_type']}{flag_str}")
            if a.get("cost"):
                lines.append(f"{INDENT}  cost:   {a['cost']}")
            if a.get("effect"):
                lines.append(_wrap(f"effect: {a['effect']}", indent=INDENT * 2))

    rulings = card.get("rulings") or []
    if rulings:
        lines.append("")
        lines.append(f"Rulings ({len(rulings)}):")
        for r in rulings:
            lines.append(f"{INDENT}[{r['date']}]")
            lines.append(_wrap(r["text"], indent=INDENT * 2))

    combos = card.get("combos") or []
    if combos:
        lines.append("")
        lines.append(f"Top combos featuring this card ({len(combos)}):")
        for c in combos:
            name = c.get("combo_name") or "(unnamed)"
            ci = c.get("color_identity") or "-"
            lines.append(
                f"{INDENT}[{c['id']:>14}] {ci:<5} ({c['card_count']} cards) {name[:45]}"
            )

    corrections = card.get("corrections") or []
    if corrections:
        lines.append("")
        lines.append(f"NOTE — {len(corrections)} correction(s) apply to this card:")
        for cr in corrections:
            lines.append(f"{INDENT}#{cr['id']} [{cr['source']}] {cr['topic']}")
            lines.append(_wrap(f"-> {cr['correct_claim']}", indent=INDENT * 2))

    return "\n".join(lines)


def _render_rulings(card_name: str, rulings: list[dict]) -> str:
    if not rulings:
        return f"(no rulings found for {card_name})"
    lines = [f"{card_name} — {len(rulings)} ruling(s):"]
    for r in rulings:
        lines.append(f"{INDENT}[{r['date']}]")
        lines.append(_wrap(r["text"], indent=INDENT * 2))
    return "\n".join(lines)


def _render_combo_list(combos: list[dict], header: str) -> str:
    if not combos:
        return "(no matching combos)"
    lines = [header]
    for c in combos:
        cards_str = c.get("cards") or c.get("combo_name") or ""
        ci = c.get("color_identity") or "-"
        lines.append(
            f"{INDENT}[{c['id']:>14}] {ci:<5} ({c['card_count']} cards) {cards_str[:60]}"
        )
    return "\n".join(lines)


def _render_combo(combo: dict) -> str:
    lines = []
    lines.append(_hr())
    lines.append(f"{BOX_V} Combo {combo['id']}  {combo.get('color_identity') or '-'}")
    if combo.get("name"):
        lines.append(f"{BOX_V} {combo['name']}")
    lines.append(_hr())

    if combo.get("description"):
        lines.append(_wrap(combo["description"], indent=""))

    if combo.get("cards"):
        lines.append("")
        lines.append("Cards:")
        for c in combo["cards"]:
            qty = c.get("quantity", 1)
            q_str = f" x{qty}" if qty and qty > 1 else ""
            lines.append(f"{INDENT}- {c['card_name']}{q_str}")

    if combo.get("prerequisites"):
        lines.append("")
        lines.append("Prerequisites:")
        for p in combo["prerequisites"]:
            lines.append(_wrap(f"- {p}", indent=INDENT))

    if combo.get("steps"):
        lines.append("")
        lines.append("Steps:")
        for i, s in enumerate(combo["steps"], 1):
            lines.append(_wrap(f"{i}. {s}", indent=INDENT))

    if combo.get("results"):
        lines.append("")
        lines.append("Results:")
        for r in combo["results"]:
            lines.append(_wrap(f"- {r}", indent=INDENT))
    return "\n".join(lines)


def _render_rule(rule: dict) -> str:
    lines = []
    section = f" ({rule['section_title']})" if rule.get("section_title") else ""
    lines.append(f"[{rule['rule_number']}]{section}")
    lines.append(_wrap(rule["text"], indent=""))
    children = rule.get("children") or []
    if children:
        lines.append("")
        lines.append("Child rules:")
        for c in children:
            lines.append(f"{INDENT}[{c['rule_number']}]")
            lines.append(_wrap(c["text"], indent=INDENT * 2))
    return "\n".join(lines)


def _render_rules_search(pattern: str, rules: list[dict]) -> str:
    if not rules:
        return f"(no rules matching '{pattern}')"
    lines = [f"{len(rules)} rule(s) matching '{pattern}':"]
    for r in rules:
        lines.append(f"{INDENT}[{r['rule_number']}] ({r.get('section_title') or '-'})")
        lines.append(_wrap(r["text"][:300], indent=INDENT * 2))
    return "\n".join(lines)


def _render_search(cards: list[dict]) -> str:
    if not cards:
        return "(no cards matching filters)"
    lines = [f"{len(cards)} card(s):"]
    for c in cards:
        lines.append(f"{INDENT}{c['name']}  ({c.get('type_line') or ''})")
    return "\n".join(lines)


def _render_corrections(rows: list[dict]) -> str:
    if not rows:
        return "(no corrections)"
    lines = [f"{len(rows)} correction(s):"]
    for c in rows:
        rel = c.get("relates_to")
        if isinstance(rel, list):
            rel_str = ", ".join(rel)
        else:
            rel_str = str(rel) if rel else ""
        lines.append(
            f"{INDENT}#{c['id']} [{c['category']}/{c['source']}] {c['topic']}"
        )
        lines.append(_wrap(f"wrong:   {c['incorrect_claim']}", indent=INDENT * 2))
        lines.append(_wrap(f"correct: {c['correct_claim']}", indent=INDENT * 2))
        if c.get("explanation"):
            lines.append(_wrap(f"why:     {c['explanation']}", indent=INDENT * 2))
        if rel_str:
            lines.append(f"{INDENT * 2}re:      {rel_str}")
    return "\n".join(lines)


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
    cards = q.search_cards(
        name_like=args.name,
        tag=args.tag,
        card_type=args.type,
        is_mana_ability=args.mana_ability or None,
        limit=args.limit,
    )
    if args.json:
        print(json.dumps(cards, indent=2, default=str))
    else:
        print(_render_search(cards))
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

    sp = sub.add_parser("search", help="Filter the cards table.")
    sp.add_argument("--name", help="Substring of the card name.")
    sp.add_argument("--tag", help="Exact tag (keyword or type).")
    sp.add_argument("--type", help="Exact type / subtype / supertype.")
    sp.add_argument("--mana-ability", action="store_true",
                    help="Only cards with >=1 parsed mana ability.")
    sp.add_argument("--limit", type=int, default=50)
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
