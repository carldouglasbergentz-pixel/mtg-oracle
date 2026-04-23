"""Plain-text renderers for query results.

Shared by the CLI (`scripts/mtg_cli.py`) and the Textual app
(`mtg_oracle/app.py`). Every function takes a Python dict / list as
returned by `mtg_oracle.queries` and returns a single string of ASCII
(no unicode borders, so Windows consoles render it cleanly).
"""
from __future__ import annotations

import textwrap

BOX_H = "-"
BOX_V = "|"
CORNER = "+"
INDENT = "  "
WRAP_COLS = 70


def wrap(text: str, indent: str = INDENT) -> str:
    if not text:
        return ""
    lines = []
    for para in str(text).split("\n"):
        lines.append(textwrap.fill(
            para, width=WRAP_COLS, initial_indent=indent,
            subsequent_indent=indent, break_long_words=False,
        ))
    return "\n".join(lines)


def hr(width: int = WRAP_COLS) -> str:
    return CORNER + BOX_H * (width - 2) + CORNER


def render_card(card: dict) -> str:
    lines = []
    lines.append(hr())
    lines.append(f"{BOX_V} {card['name']}")
    if card.get("type_line"):
        lines.append(f"{BOX_V} {card['type_line']}")
    lines.append(hr())
    if card.get("oracle_text"):
        lines.append(wrap(card["oracle_text"], indent=""))

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
                lines.append(wrap(f"effect: {a['effect']}", indent=INDENT * 2))

    rulings = card.get("rulings") or []
    if rulings:
        lines.append("")
        lines.append(f"Rulings ({len(rulings)}):")
        for r in rulings:
            lines.append(f"{INDENT}[{r['date']}]")
            lines.append(wrap(r["text"], indent=INDENT * 2))

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
        lines.append(f"NOTE - {len(corrections)} correction(s) apply to this card:")
        for cr in corrections:
            lines.append(f"{INDENT}#{cr['id']} [{cr['source']}] {cr['topic']}")
            lines.append(wrap(f"-> {cr['correct_claim']}", indent=INDENT * 2))

    return "\n".join(lines)


def render_rulings(card_name: str, rulings: list[dict]) -> str:
    if not rulings:
        return f"(no rulings found for {card_name})"
    lines = [f"{card_name} - {len(rulings)} ruling(s):"]
    for r in rulings:
        lines.append(f"{INDENT}[{r['date']}]")
        lines.append(wrap(r["text"], indent=INDENT * 2))
    return "\n".join(lines)


def render_combo_list(combos: list[dict], header: str) -> str:
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


def render_combo(combo: dict) -> str:
    lines = []
    lines.append(hr())
    lines.append(f"{BOX_V} Combo {combo['id']}  {combo.get('color_identity') or '-'}")
    if combo.get("name"):
        lines.append(f"{BOX_V} {combo['name']}")
    lines.append(hr())

    if combo.get("description"):
        lines.append(wrap(combo["description"], indent=""))

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
            lines.append(wrap(f"- {p}", indent=INDENT))

    if combo.get("steps"):
        lines.append("")
        lines.append("Steps:")
        for i, s in enumerate(combo["steps"], 1):
            lines.append(wrap(f"{i}. {s}", indent=INDENT))

    if combo.get("results"):
        lines.append("")
        lines.append("Results:")
        for r in combo["results"]:
            lines.append(wrap(f"- {r}", indent=INDENT))
    return "\n".join(lines)


def render_rule(rule: dict) -> str:
    lines = []
    section = f" ({rule['section_title']})" if rule.get("section_title") else ""
    lines.append(f"[{rule['rule_number']}]{section}")
    lines.append(wrap(rule["text"], indent=""))
    children = rule.get("children") or []
    if children:
        lines.append("")
        lines.append("Child rules:")
        for c in children:
            lines.append(f"{INDENT}[{c['rule_number']}]")
            lines.append(wrap(c["text"], indent=INDENT * 2))
    return "\n".join(lines)


def render_rules_search(pattern: str, rules: list[dict]) -> str:
    if not rules:
        return f"(no rules matching '{pattern}')"
    lines = [f"{len(rules)} rule(s) matching '{pattern}':"]
    for r in rules:
        lines.append(f"{INDENT}[{r['rule_number']}] ({r.get('section_title') or '-'})")
        lines.append(wrap(r["text"][:300], indent=INDENT * 2))
    return "\n".join(lines)


def render_search(cards: list[dict]) -> str:
    if not cards:
        return "(no cards matching filters)"
    lines = [f"{len(cards)} card(s):"]
    for c in cards:
        lines.append(f"{INDENT}{c['name']}  ({c.get('type_line') or ''})")
    return "\n".join(lines)


def render_corrections(rows: list[dict]) -> str:
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
        lines.append(wrap(f"wrong:   {c['incorrect_claim']}", indent=INDENT * 2))
        lines.append(wrap(f"correct: {c['correct_claim']}", indent=INDENT * 2))
        if c.get("explanation"):
            lines.append(wrap(f"why:     {c['explanation']}", indent=INDENT * 2))
        if rel_str:
            lines.append(f"{INDENT * 2}re:      {rel_str}")
    return "\n".join(lines)
