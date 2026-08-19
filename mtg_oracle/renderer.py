"""Plain-text renderers for query results.

Shared by the CLI (`scripts/mtg_cli.py`) and the Textual app
(`mtg_oracle/app.py`). Every function takes a Python dict / list as
returned by `mtg_oracle.queries` and returns a single string of ASCII
(no unicode borders, so Windows consoles render it cleanly).
"""
from __future__ import annotations

import textwrap
from dataclasses import dataclass
from typing import Optional

BOX_H = "-"
BOX_V = "|"
CORNER = "+"
INDENT = "  "
WRAP_COLS = 70


@dataclass
class LinkSpan:
    """A clickable region in a rendered block, in (line, column) coordinates.

    Renderers know exactly where they put things — column widths, truncation,
    padding — so they report the spans rather than leaving the UI to re-derive
    them by pattern-matching the output it was just handed. `kind` and `args`
    are opaque here; the TUI decides what clicking one means.
    """
    line: int
    start: int
    end: int
    kind: str
    args: tuple


def _collect(
    links: Optional[list[LinkSpan]],
    lines: list[str],
    text: str,
    kind: str,
    *args,
    column: Optional[int] = None,
) -> None:
    """Record a link for `text` on the line that is about to be appended.

    Call this straight after appending the line: `len(lines) - 1` is its
    index. `column` pins the start when the same text could occur twice on
    one line; otherwise the first occurrence wins.
    """
    if links is None or not text:
        return
    line_no = len(lines) - 1
    start = lines[line_no].find(text) if column is None else column
    if start < 0:
        return
    links.append(LinkSpan(line_no, start, start + len(text), kind, args))


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


# Wide enough that the longest label ('1 copy only', 'not as cmdr') still
# leaves a gap before the body column.
_LABEL_W = 13


def _label_row(label: str, body: str) -> str:
    """`  label      body...` with the wrap hanging under the body column."""
    return textwrap.fill(
        body, width=WRAP_COLS,
        initial_indent=f"{INDENT}{label:<{_LABEL_W}}",
        subsequent_indent=INDENT + " " * _LABEL_W,
        break_long_words=False,
    )


def _points_badge(points: Optional[dict]) -> str:
    """`[7/10 pts]` for a points-list format, `!` suffixed when over budget."""
    if not points:
        return ""
    over = "!" if points.get("over") else ""
    return f"[{points['total']}/{points['budget']} pts{over}]"


def _name_with_points(name: str, points: Optional[int], width: int) -> str:
    """`Card Name (3)`, truncating the NAME so the marker is never cut off.

    Long names are exactly the case where the marker matters and exactly the
    case naive truncation loses it — 'Tamiyo, Inquisitive Student // Tamiyo,
    Seasoned Scholar' is 53 characters and its `(1)` fell off the end.
    """
    suffix = f" ({points})" if points else ""
    available = width - len(suffix)
    if len(name) > available:
        name = name[:max(1, available - 2)] + ".."
    return name + suffix


def _points_headline(points: dict) -> str:
    """`Points   10 / 10   (0 left)` — the one-line budget summary."""
    remaining = points["budget"] - points["total"]
    tail = (f"({abs(remaining)} over budget)" if remaining < 0
            else f"({remaining} left)")
    return f"Points   {points['total']} / {points['budget']}   {tail}"


def render_points(
    points: dict, *, headline: bool = True, width: Optional[int] = None,
) -> str:
    """The pointed cards in a deck, dearest first, with points after each.

    `headline=False` omits the summary line for callers that already show it
    in a header (the full deck view puts it under the format).
    `width` truncates names to fit a narrow pane; without it, names are
    printed in full.
    """
    lines = [_points_headline(points)] if headline else []
    if not points["cards"]:
        lines.append(f"{INDENT}(no pointed cards in this deck)")
        return "\n".join(lines)
    for name, pts, qty, subtotal in points["cards"]:
        qty_str = f"{qty}x " if qty > 1 else ""
        total_str = f" = {subtotal}" if qty > 1 else ""
        if width is not None:
            budget = width - len(INDENT) - len(qty_str) - len(total_str)
            label = _name_with_points(name, pts, budget)
        else:
            label = f"{name} ({pts})"
        lines.append(f"{INDENT}{qty_str}{label}{total_str}")
    return "\n".join(lines)


def _ci_badge(commander_ci) -> str:
    """Render a deck's commander color identity as a compact badge.

    `commander_ci` is None (no commander set), [] (colorless commander),
    or a sorted list of letters like ['B','G']. The badge is a stable,
    Scryfall-style token that fits inline with deck headers.
    """
    if commander_ci is None:
        return ""
    if not commander_ci:
        return "[CI: C]"
    return f"[CI: {''.join(commander_ci)}]"


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

    legalities = card.get("legalities") or {}
    flags = []
    if card.get("reserved"):
        flags.append("Reserved List")
    if card.get("games"):
        flags.append(card["games"].replace(",", "/"))
    if card.get("edhrec_rank"):
        flags.append(f"EDHREC #{card['edhrec_rank']:,}")
    if legalities or flags:
        lines.append("")
        lines.append("Legality:")
        # `legal` is the long list; the rest are the facts that change a
        # decision, so each gets its own row. A format with no row at all
        # is simply not legal — see sync_cards.py. `no_commander` is
        # Duel Commander / Tiny Leaders' "may be in the deck, not as your
        # commander"; queries.get_card splits it out from `restricted`.
        for status, label in (("legal", "legal"),
                              ("restricted", "1 copy only"),
                              ("no_commander", "not as cmdr"),
                              ("banned", "banned")):
            formats = legalities.get(status)
            if formats:
                lines.append(_label_row(label, ", ".join(formats)))
        if flags:
            lines.append(_label_row("printings", "  ·  ".join(flags)))

    rulings = card.get("rulings") or []
    if rulings:
        lines.append("")
        lines.append(f"Rulings ({len(rulings)}):")
        for r in rulings:
            lines.append(f"{INDENT}[{r['date']}]")
            lines.append(wrap(r["text"], indent=INDENT * 2))

    combos = card.get("combos") or []
    ci_filter = card.get("combos_filtered_by_ci")
    if combos:
        lines.append("")
        if ci_filter is not None:
            lines.append(
                f"Top combos featuring this card ({len(combos)}, "
                f"filtered to deck CI {ci_filter}):"
            )
        else:
            lines.append(f"Top combos featuring this card ({len(combos)}):")
        for c in combos:
            cards_str = c.get("cards") or c.get("combo_name") or ""
            ci = c.get("color_identity") or "-"
            plus = "+" if c.get("has_template_vars") else ""
            header = (
                f"{INDENT}[{c['id']:>14}] {ci:<5} "
                f"({c['card_count']}{plus} cards) "
            )
            lines.append(wrap_combo_row(header, cards_str))
    elif ci_filter is not None:
        # No combos passed the filter — make it explicit instead of silent.
        lines.append("")
        lines.append(
            f"Top combos featuring this card: 0 applicable to deck CI {ci_filter} "
            f"(card may be unplayable here)"
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
        plus = "+" if c.get("has_template_vars") else ""
        row_header = (
            f"{INDENT}[{c['id']:>14}] {ci:<5} "
            f"({c['card_count']}{plus} cards) "
        )
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
    links: Optional[list[LinkSpan]] = None,
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
        prefix = f"{INDENT}[{i:>3}] "
        lines.append(
            f"{prefix}{name_trunc:<{NAME_W}} {mana_cost:<{COST_W}} {type_trunc}"
        )
        _collect(links, lines, name_trunc, "card", name, column=len(prefix))

    if nav_hint:
        lines.append(f"{INDENT}{nav_hint}")

    return "\n".join(lines)


def _type_bucket(type_line: str) -> str:
    """Auto-categorize a card by its type_line for deck view grouping.

    For multi-face cards (DFC / split / flip / modal) we look only at
    the FRONT face. Otherwise a card like Waterlogged Teachings (Instant
    on the front, Land on the back) ends up in the Lands group purely
    because 'Land' appears in the combined type_line.
    """
    if not type_line:
        return "Other"
    front = type_line.split(" // ", 1)[0].lower()
    if "land" in front:
        return "Lands"
    if "creature" in front:
        return "Creatures"
    if "planeswalker" in front:
        return "Planeswalkers"
    if "battle" in front:
        return "Battles"
    if "instant" in front:
        return "Instants"
    if "sorcery" in front:
        return "Sorceries"
    if "artifact" in front:
        return "Artifacts"
    if "enchantment" in front:
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


def render_deck_list(
    decks: list[dict],
    header: str = "Decks:",
    *,
    flat: bool = False,
) -> str:
    """One-line-per-deck summary.

    By default (`flat=False`) groups by folder, with an `(unsorted)`
    section for decks that have no folder. Use `flat=True` when the
    caller has already scoped the listing (e.g. `ls` inside a folder)
    — repeating the folder name is just noise.
    """
    if not decks:
        return "(no decks)"

    def _row(d: dict) -> str:
        fmt = f" [{d['format']}]" if d.get("format") else ""
        ci = _ci_badge(d.get("commander_ci"))
        ci = f" {ci}" if ci else ""
        updated = (d.get("updated_at") or "")[:10]
        return (
            f"{INDENT}{d['name']:<40} {d['card_count']:>4} cards{fmt}{ci}  "
            f"updated {updated}"
        )

    if flat:
        return "\n".join([header] + [_row(d) for d in decks])

    by_folder: dict[str | None, list[dict]] = {}
    for d in decks:
        by_folder.setdefault(d.get("folder"), []).append(d)
    lines = [header]
    folders = sorted(
        (f for f in by_folder.keys() if f is not None),
        key=lambda s: s.lower(),
    )
    if None in by_folder:
        folders.insert(0, None)
    for folder in folders:
        label = folder if folder is not None else "(unsorted)"
        lines.append(f"\n{label}:")
        for d in by_folder[folder]:
            lines.append(_row(d))
    return "\n".join(lines)


def render_folder_list(folders: list[dict]) -> str:
    if not folders:
        return "(no folders yet — use `mkdir <name>`)"
    lines = ["Folders:"]
    for f in folders:
        created = (f.get("created_at") or "")[:10]
        lines.append(
            f"{INDENT}{f['name']:<30} {f['deck_count']:>3} deck(s)  "
            f"{'created ' + created if created else ''}"
        )
    return "\n".join(lines)


def render_deck(deck: dict, links: Optional[list[LinkSpan]] = None) -> str:
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
    ci = _ci_badge(deck.get("commander_ci"))
    if ci:
        header_right.append(ci)
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
    # Points get their own header row, directly under the format line — in a
    # points format it's the first thing you check, not a footnote.
    points = deck.get("points")
    if points:
        lines.append(f"{BOX_V} {_points_headline(points)}")
    lines.append(hr())
    if points:
        lines.append("")
        lines.append(render_points(points, headline=False))

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

    # Mark pointed cards where the eye already is — right after the name,
    # inside the name column so the mana-cost column stays aligned.
    points_by_card = {n.lower(): p for n, p, _q, _s in (points or {}).get("cards", [])}
    NAME_W = 38

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
            pts = points_by_card.get((c["card_name"] or "").lower())
            label = _name_with_points(c["card_name"], pts, NAME_W)
            prefix = f"{INDENT}{qty:>2}x "
            lines.append(f"{prefix}{label:<{NAME_W}} {cost:<14} {type_trunc}")
            _collect(links, lines, label, "card", c["card_name"],
                     column=len(prefix))

    return "\n".join(lines)


def render_analytics_compact(analytics: dict, width: int = 48) -> str:
    """Compact analytics block for the left navigation pane.

    Sections: mana-value summary, mana curve histogram (0/1/2/3/4/5/6+),
    colored-pip count for non-lands (WUBRG), mana sources for lands
    (WUBRG + C). Width-aware: pip / source rows collapse to one line.
    """
    curve = analytics["mana_curve"]
    nonland = analytics["nonland_count"]
    land = analytics["land_count"]
    mdfc = analytics.get("mdfc_land_count", 0)
    land_total = analytics.get("land_total", land)
    mv_avg = analytics["mv_avg"]
    pips = analytics["color_pips"]
    pip_total = analytics["pip_total"]
    sources = analytics["mana_sources"]

    # A modal DFC with a land back is both a spell and a land drop, so it is
    # counted in both columns. Spelling out the second number keeps that from
    # looking like the totals don't add up.
    land_str = f"{land} ({land_total} with MDFC)" if mdfc else str(land)

    lines: list[str] = []
    lines.append("ANALYTICS")
    lines.append("-" * min(width, 9))
    lines.append(f"avg MV: {mv_avg:.2f}   non-lands: {nonland}   lands: {land_str}")
    lines.append("")
    buckets = ("0", "1", "2", "3", "4", "5", "6+")
    lines.append("curve  " + "  ".join(f"{b:>2}" for b in buckets))
    lines.append("       " + "  ".join(f"{curve[i]:>2}" for i in range(7)))

    if pip_total > 0:
        present = " ".join(f"{c}:{pips[c]}" for c in "WUBRG" if pips[c])
        lines.append("")
        lines.append(f"pips ({pip_total}):    {present}")

    if land_total > 0:
        present = " ".join(f"{c}:{sources[c]}" for c in "WUBRGC" if sources[c])
        lines.append(f"sources ({land_total}): {present}")

    return "\n".join(lines)


def render_combos_compact(
    combos: list[dict],
    width: int = 48,
    links: Optional[list[LinkSpan]] = None,
) -> str:
    """Numbered combo list for the left navigation pane.

    Shape mirrors `_render_numbered_combo_list` (so `combo-info <N>` still
    works against `_last_combos`) but laid out narrower for the side pane.
    Each combo gets a header line plus its card list wrapping at ' + '.
    """
    if not combos:
        return "COMBOS\n" + "-" * min(width, 6) + "\n  (no combos fully contained in this deck)"
    lines = ["COMBOS", "-" * min(width, 6)]
    lines.append(f"{len(combos)} combo(s) — `combo-info <N>` to expand")
    for i, c in enumerate(combos, 1):
        cards_str = c.get("cards") or c.get("combo_name") or ""
        ci = c.get("color_identity") or "-"
        plus = "+" if c.get("has_template_vars") else ""
        index_label = f"[{i:>3}]"
        header = f"  {index_label} {ci:<5} ({c['card_count']}{plus} cards) "
        row = wrap_combo_row(header, cards_str, row_width=width)
        # A wrapped row spans several lines; the index only exists on the
        # first, so record the link before extending past it.
        first_line_no = len(lines)
        lines.extend(row.split("\n"))
        if links is not None:
            links.append(LinkSpan(first_line_no, 2, 2 + len(index_label),
                                  "combo", (i,)))
    return "\n".join(lines)


def render_deck_compact(
    deck: dict,
    width: int = 36,
    analytics: Optional[dict] = None,
    combos: Optional[list[dict]] = None,
    links: Optional[list[LinkSpan]] = None,
) -> str:
    """Narrow deck render for the left navigation pane.

    Shows qty + truncated card name, type-grouped, then optional Analytics
    and Combos sections at the bottom. The optional sections are rendered
    inline rather than written separately so the whole side pane refreshes
    atomically when a card is added / removed.
    """
    name_w = max(8, width - 6)  # `  NNx ` prefix takes 5–6 chars

    bits: list[str] = []
    if deck.get("folder"):
        bits.append(deck["folder"])
    if deck.get("format"):
        bits.append(deck["format"])
    bits.append(f"{deck.get('total_main', 0)} cards")
    side = deck.get("total_side", 0)
    if side:
        bits.append(f"+{side} side")
    # An absent format is why legality, singleton and points are all silent.
    # Say so where the user is looking for them, rather than just omitting
    # the rows and leaving them to wonder.
    no_format_hint = None if deck.get("format") else "no format — see `format`"

    name_line = deck.get("name") or "(deck)"
    if len(name_line) > width:
        name_line = name_line[:width - 2] + ".."

    lines: list[str] = [name_line, " / ".join(bits)]
    badges = [b for b in (_ci_badge(deck.get("commander_ci")),
                          _points_badge(deck.get("points"))) if b]
    if badges:
        lines.append(" ".join(badges))
    if no_format_hint:
        lines.append(no_format_hint)
    lines.append("=" * min(width, len(name_line)))

    commanders: list[dict] = []
    main_buckets: dict[str, list[dict]] = {}
    sideboard: list[dict] = []
    for c in deck.get("cards", []):
        if c.get("is_sideboard"):
            sideboard.append(c); continue
        if c.get("is_commander"):
            commanders.append(c); continue
        bucket = c.get("category") or _type_bucket(c.get("type_line") or "")
        main_buckets.setdefault(bucket, []).append(c)

    # Pinned commander section: render distinctly above the regular buckets
    # so the commander stays visually anchored even if the deck is long.
    if commanders:
        lines.append("")
        lines.append("-- COMMANDER --")
        for c in commanders:
            qty = c["quantity"]
            cn = c["card_name"]
            if len(cn) > name_w:
                cn = cn[:name_w - 2] + ".."
            lines.append(f"  {qty:>2}x {cn}")
            _collect(links, lines, cn, "card", c["card_name"])
        lines.append("-" * min(width, 16))

    ordered: list[tuple[str, list[dict]]] = []
    for b in _BUCKET_ORDER:
        if b == "Commander":
            continue  # already rendered as the pinned section above
        if b in main_buckets:
            ordered.append((b, main_buckets.pop(b)))
    for k in sorted(main_buckets, key=str.lower):
        ordered.append((k, main_buckets[k]))
    if sideboard:
        ordered.append(("Sideboard", sideboard))

    points = deck.get("points")
    points_by_card = {n.lower(): p for n, p, _q, _s in (points or {}).get("cards", [])}

    for bucket, cards in ordered:
        subtotal = sum(c["quantity"] for c in cards)
        lines.append("")
        lines.append(f"{bucket} ({subtotal})")
        for c in cards:
            qty = c["quantity"]
            pts = points_by_card.get((c["card_name"] or "").lower())
            label = _name_with_points(c["card_name"], pts, name_w)
            lines.append(f"  {qty:>2}x {label}")
            _collect(links, lines, label, "card", c["card_name"])

    if points:
        lines.append("")
        lines.append("POINTS")
        lines.append("-" * min(width, 6))
        lines.append(render_points(points, width=width))

    if analytics is not None:
        lines.append("")
        lines.append(render_analytics_compact(analytics, width=width))
    if combos is not None:
        lines.append("")
        # The combo block renders standalone, so its link line numbers are
        # relative to itself — shift them into this block's coordinates.
        sub: Optional[list[LinkSpan]] = [] if links is not None else None
        block = render_combos_compact(combos, width=width, links=sub)
        offset = len(lines)
        lines.extend(block.split("\n"))
        for span in sub or ():
            links.append(LinkSpan(span.line + offset, span.start, span.end,
                                  span.kind, span.args))

    return "\n".join(lines)


def render_import_result(deck_name: str, result: dict) -> str:
    copies = result.get("copies")
    copies_str = f" ({copies} copies)" if copies is not None else ""
    lines = [
        f"Loaded into {deck_name!r}: {result['added']} of "
        f"{result['total_input']} input lines{copies_str}."
    ]
    unresolved = result.get("unresolved") or []
    if unresolved:
        lines.append(
            f"WARNING {len(unresolved)} card(s) not found in database and skipped:"
        )
        for u in unresolved:
            lines.append(f"{INDENT}- {u}")
    # Rejections come from deck-layer validation (CI / singleton). They used
    # to vanish without a trace, which read as a silent data loss.
    rejected = result.get("rejected") or []
    if rejected:
        lines.append(f"WARNING {len(rejected)} card(s) rejected by deck rules:")
        for name, reason in rejected:
            lines.append(f"{INDENT}- {name}: {reason}")
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
