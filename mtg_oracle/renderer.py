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


def wrap_combo_row(
    header: str,
    cards_str: str,
    row_width: int = WRAP_COLS,
    continuation_prefix: str = "    + ",
) -> str:
    """Render a combo-list row that may wrap at ' + ' boundaries.

    `header` is the leading metadata (e.g. `  [  1] UB    (2 cards) `).
    `cards_str` is the ' + '-joined card list. Continuation lines start
    with `continuation_prefix` (default indented '+ ' marker) so a long
    card name is never cut mid-word and wrapped lines read like a list.
    """
    if not cards_str:
        return header + "(unnamed)"
    if len(header) + len(cards_str) <= row_width:
        return header + cards_str

    parts = cards_str.split(" + ")
    lines: list[str] = []
    current = header
    first_on_line = True
    for p in parts:
        sep = "" if first_on_line else " + "
        if len(current) + len(sep) + len(p) <= row_width:
            current = current + sep + p
            first_on_line = False
        else:
            # Flush current line; start continuation with the prefix marker.
            lines.append(current)
            current = continuation_prefix + p
            first_on_line = False
    if current:
        lines.append(current)
    return "\n".join(lines)


def _header_line(name: str, mana_cost: str) -> str:
    """Render name + mana cost on one line, Scryfall-style.

    Falls back to stacking cost on its own line when name is too long
    to fit both within the box width. Empty mana_cost (lands) just
    renders the name alone.
    """
    mc = (mana_cost or "").strip()
    if not mc:
        return f"{BOX_V} {name}"
    # content width after "| ": WRAP_COLS - 2
    content_w = WRAP_COLS - 2
    available = content_w - len(mc) - 1  # keep >=1 space between
    if len(name) <= available:
        pad = " " * max(1, available - len(name))
        return f"{BOX_V} {name}{pad} {mc}"
    return f"{BOX_V} {name}\n{BOX_V} {mc}"


def render_card(card: dict) -> str:
    lines = []
    lines.append(hr())
    lines.append(_header_line(card["name"], card.get("mana_cost") or ""))
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
            cards_str = c.get("cards") or c.get("combo_name") or ""
            ci = c.get("color_identity") or "-"
            header = f"{INDENT}[{c['id']:>14}] {ci:<5} ({c['card_count']} cards) "
            lines.append(wrap_combo_row(header, cards_str))

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
        row_header = f"{INDENT}[{c['id']:>14}] {ci:<5} ({c['card_count']} cards) "
        lines.append(wrap_combo_row(row_header, cards_str))
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


def render_search(
    cards: list[dict],
    *,
    page: int = 1,
    total: int | None = None,
    page_size: int = 50,
    nav_hint: str | None = None,
) -> str:
    """Render a paginated search result list.

    Rows are numbered 1..N within the current page (not absolute across all
    results). Each row shows mana cost + truncated type line after the name.
    If `total` and `page` are provided, a 'Page X of Y' header is shown.
    `nav_hint` is an optional trailing line (e.g., 'type next / prev / card <N>').
    """
    if not cards:
        return "(no cards matching filters)"

    lines: list[str] = []
    if total is not None:
        last_page = max(1, (total + page_size - 1) // page_size)
        offset = (page - 1) * page_size
        first = offset + 1
        last = min(offset + len(cards), total)
        header = (
            f"{total} card(s) — showing {first}-{last} (page {page} of {last_page})"
        )
    else:
        header = f"{len(cards)} card(s):"
    lines.append(header)

    # Column widths for a reasonably tidy monospace render. Fall back to raw
    # values if a name overflows; the indent keeps continuation visually
    # attached to the row.
    NAME_W = 42
    COST_W = 14
    for i, c in enumerate(cards, 1):
        name = c["name"]
        type_line = c.get("type_line") or ""
        mana_cost = c.get("mana_cost") or ""
        # Truncate type line so the row fits inside WRAP_COLS (~70).
        type_trunc = type_line if len(type_line) <= 30 else type_line[:28] + ".."
        name_trunc = name if len(name) <= NAME_W else name[:NAME_W - 2] + ".."
        lines.append(
            f"{INDENT}[{i:>3}] {name_trunc:<{NAME_W}} {mana_cost:<{COST_W}} {type_trunc}"
        )

    if nav_hint:
        lines.append(f"{INDENT}{nav_hint}")

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
