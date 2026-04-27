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


def _type_bucket(type_line: str) -> str:
    """Auto-categorize a card by its type_line for deck view grouping."""
    tl = (type_line or "").lower()
    # Commander tables / emblems / tokens shouldn't appear in decks; fall through.
    if "land" in tl:
        return "Lands"
    if "creature" in tl:
        return "Creatures"
    if "planeswalker" in tl:
        return "Planeswalkers"
    if "battle" in tl:
        return "Battles"
    if "instant" in tl:
        return "Instants"
    if "sorcery" in tl:
        return "Sorceries"
    if "artifact" in tl:
        return "Artifacts"
    if "enchantment" in tl:
        return "Enchantments"
    return "Other"


_BUCKET_ORDER = [
    "Commander",
    "Creatures", "Planeswalkers", "Battles",
    "Instants", "Sorceries",
    "Artifacts", "Enchantments",
    "Lands",
    "Other",
    "Sideboard",
]


def render_deck_list(decks: list[dict], header: str = "Decks:") -> str:
    """One-line-per-deck summary. Groups by folder (with a 'not in folder'
    section for unsorted decks)."""
    if not decks:
        return "(no decks)"
    by_folder: dict[str | None, list[dict]] = {}
    for d in decks:
        by_folder.setdefault(d.get("folder"), []).append(d)

    lines = [header]
    # None (unsorted) first if present; then alphabetical by folder name.
    folders = sorted(
        (f for f in by_folder.keys() if f is not None),
        key=lambda s: s.lower(),
    )
    if None in by_folder:
        folders.insert(0, None)
    for folder in folders:
        label = folder if folder is not None else "(unsorted)"
        entries = by_folder[folder]
        lines.append(f"\n{label}:")
        for d in entries:
            fmt = f" [{d['format']}]" if d.get("format") else ""
            updated = (d.get("updated_at") or "")[:10]  # just the date
            lines.append(
                f"{INDENT}{d['name']:<40} {d['card_count']:>4} cards{fmt}  "
                f"updated {updated}"
            )
    return "\n".join(lines)


def render_folder_list(folders: list[dict]) -> str:
    if not folders:
        return "(no folders yet — use `folder new <name>`)"
    lines = ["Folders:"]
    for f in folders:
        created = (f.get("created_at") or "")[:10]
        lines.append(
            f"{INDENT}{f['name']:<30} {f['deck_count']:>3} deck(s)  "
            f"{'created ' + created if created else ''}"
        )
    return "\n".join(lines)


def render_deck(deck: dict) -> str:
    """Full deck view, auto-grouped by type.

    Handles large lists (100+ cards for Commander / Canadian Highlander)
    by bucketing by type_line. Cards with an explicit user-set category
    keep that category label; otherwise the bucket name is used.
    """
    total = deck.get("total_main", 0)
    header_right = []
    if deck.get("folder"):
        header_right.append(deck["folder"])
    if deck.get("format"):
        header_right.append(deck["format"])
    header_right.append(f"{total} cards")
    side_total = deck.get("total_side", 0)
    if side_total:
        header_right.append(f"+{side_total} sideboard")
    right = "  ·  ".join(header_right)

    name = deck["name"]
    # Center-ish header: name on the left, metadata on the right.
    pad = max(1, WRAP_COLS - len(name) - len(right) - 4)
    lines = [hr(), f"{BOX_V} {name}{' ' * pad}{right}"]
    if deck.get("description"):
        lines.append(f"{BOX_V} {deck['description']}")
    lines.append(hr())

    # Group cards
    commanders: list[dict] = []
    main_buckets: dict[str, list[dict]] = {}
    sideboard: list[dict] = []
    for c in deck.get("cards", []):
        if c.get("is_sideboard"):
            sideboard.append(c)
            continue
        if c.get("is_commander"):
            commanders.append(c)
            continue
        bucket = c.get("category") or _type_bucket(c.get("type_line") or "")
        main_buckets.setdefault(bucket, []).append(c)

    # Keep a stable, predictable order.
    ordered: list[tuple[str, list[dict]]] = []
    if commanders:
        ordered.append(("Commander", commanders))
    for b in _BUCKET_ORDER:
        if b in main_buckets:
            ordered.append((b, main_buckets.pop(b)))
    # Any remaining user-named categories, alphabetical.
    for k in sorted(main_buckets, key=str.lower):
        ordered.append((k, main_buckets[k]))
    if sideboard:
        ordered.append(("Sideboard", sideboard))

    for bucket, cards in ordered:
        subtotal = sum(c["quantity"] for c in cards)
        lines.append("")
        lines.append(f"{bucket} ({subtotal}):")
        for c in cards:
            qty = c["quantity"]
            cost = c.get("mana_cost") or ""
            type_line = c.get("type_line") or ""
            # Truncate type line; whole row must stay within WRAP_COLS-ish.
            type_trunc = type_line if len(type_line) <= 30 else type_line[:28] + ".."
            lines.append(
                f"{INDENT}{qty:>2}x {c['card_name']:<38} {cost:<14} {type_trunc}"
            )

    return "\n".join(lines)


def render_import_result(deck_name: str, result: dict) -> str:
    lines = [
        f"Imported deck {deck_name!r}: "
        f"{result['added']} cards added "
        f"from {result['total_input']} input lines."
    ]
    unresolved = result.get("unresolved") or []
    if unresolved:
        lines.append(
            f"WARNING {len(unresolved)} card(s) not found in database and skipped:"
        )
        for u in unresolved:
            lines.append(f"{INDENT}- {u}")
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
