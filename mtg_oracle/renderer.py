"""Plain-text renderers for query results.

Shared by the CLI (`scripts/mtg_cli.py`) and the Textual app
(`mtg_oracle.tui`). Every function takes a Python dict / list as returned
by `mtg_oracle.queries` and returns a single string of ASCII (no unicode
borders, so Windows consoles render it cleanly).

This module imports nothing else from the project, deliberately: a
renderer that could reach for the database would start answering
questions instead of formatting answers. Anything a renderer needs to
know is passed in.
"""
from __future__ import annotations

import textwrap
from dataclasses import dataclass
from datetime import datetime, timezone
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


def render_combo_list(
    combos: list[dict],
    header: str,
    *,
    numbered: bool = False,
    links: Optional[list[LinkSpan]] = None,
) -> str:
    """A combo list, one row per combo, wrapping at ' + ' boundaries.

    `numbered=False` labels each row with its Spellbook id — the CLI, where
    the id is what you'd paste into the next command.

    `numbered=True` labels rows `[  1]`..`[  N]` and records each label as a
    click target, for the TUI where `combo-info <N>` resolves against the
    list the user is looking at. Showing raw ids there made the numbered side
    pane and the output pane disagree about what `3` meant.
    """
    if not combos:
        return "(no matching combos)"
    lines = [header]
    for i, c in enumerate(combos, 1):
        cards_str = c.get("cards") or c.get("combo_name") or ""
        ci = c.get("color_identity") or "-"
        # A combo whose steps name a card slot `combo_cards` doesn't
        # enumerate ('your commander', 'the affinity permanent') needs more
        # cards than it lists — say so rather than under-report.
        plus = "+" if c.get("has_template_vars") else ""
        label = f"[{i:>3}]" if numbered else f"[{c['id']:>14}]"
        row_header = f"{INDENT}{label} {ci:<5} ({c['card_count']}{plus} cards) "
        first_line_no = len(lines)
        lines.extend(wrap_combo_row(row_header, cards_str).split("\n"))
        # The label sits at a known column on the row's first line, so record
        # it directly rather than searching for `[  3]` in text that also
        # contains card names.
        if numbered and links is not None:
            links.append(LinkSpan(
                first_line_no, len(INDENT), len(INDENT) + len(label),
                "combo", (i,),
            ))
    if numbered:
        lines.append(f"{INDENT}(click a row number, or type `combo-info <N>`)")
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


def render_search_nav_hint(has_next: bool, has_prev: bool) -> str:
    """The `| \\`next\\` | \\`prev\\` | ...` line under a page of results."""
    parts = []
    if has_next:
        parts.append("`next`")
    if has_prev:
        parts.append("`prev`")
    parts.append("`card <N>` to expand row")
    return "| " + "  |  ".join(parts)


def render_deck_filter_notice(filters) -> str:
    """Announce the filters a deck added to a search — never applied silently.

    A search that returns fewer cards than expected reads as missing data
    unless the reason is on screen.
    """
    if not filters:
        return ""
    return (
        f"[deck filter: {'  '.join(filters)}"
        f"  (`cd ..` to search the full pool)]"
    )


# --- formats -----------------------------------------------------------

def render_format_effect(info: Optional[dict], *, singleton: bool = False) -> str:
    """Spell out which rules a format actually switches on.

    `info` is `queries.resolve_format()`'s answer; `singleton` is asked
    separately because a community format with no definition file
    (`highlander`) resolves to None here and still has singleton enforced.
    """
    bits = []
    if info:
        if info["legality_key"]:
            pool = (f"card pool + ban list from {info['legality_key']}"
                    if info["custom"] else "card pool + ban list")
            bits.append(f"  legality: {pool}")
        if info.get("points_budget") is not None:
            bits.append(f"  points:   budget {info['points_budget']} per deck")
    if singleton:
        bits.append("  singleton: one copy of each non-basic card")
    if not info:
        if not bits:
            return ("  (not a format this build has rules for — the label is "
                    "stored, but no legality / singleton / points checks apply)")
        # Known by name only: no ban list, but the one-copy rule still fires.
        return ("  -> not a format with a card pool this build knows, but:\n"
                + "\n".join(bits))
    if not bits:
        bits.append("  (no rules attached to this format)")
    return f"  -> {info['label']}\n" + "\n".join(bits)


def render_known_formats(scryfall, community) -> str:
    """The two columns of format names `format <name>` accepts.

    Listing only the Scryfall keys omitted the one format the points feature
    exists for, so the community formats are named alongside their aliases.
    """
    lines = [
        textwrap.fill(", ".join(scryfall), width=68,
                      initial_indent="  with rules:  ",
                      subsequent_indent="               ")
    ]
    if community:
        names = ", ".join(
            spec["name"]
            + (f" ({', '.join(spec['aliases'][:2])})" if spec.get("aliases") else "")
            for spec in community
        )
        lines.append(textwrap.fill(names, width=68,
                                   initial_indent="  community:   ",
                                   subsequent_indent="               "))
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
    # Weighed for the deck, not in it: last, and in no total above.
    if deck.get("considering"):
        ordered.append(("Considering", deck["considering"]))

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


def _clip(text: str, width: int) -> str:
    """One nav row cut to `width`, marked `..` the way card names are."""
    if len(text) <= width:
        return text
    return text[:max(1, width - 2)] + ".."


def _pack(parts: list[str], width: int, sep: str, indent: str = "") -> list[str]:
    """Join `parts` into as few rows as fit `width`, never splitting a part.

    `lands: 35 (36 with MDFC)` broken across two rows reads as two facts, so a
    part that doesn't fit moves to the next row whole; only a part wider than
    the pane on its own gets clipped. When everything fits on one row the
    result is exactly `sep.join(parts)`.
    """
    rows: list[str] = []
    current = ""
    for part in parts:
        candidate = current + sep + part if current else part
        if current and len(candidate) > width:
            rows.append(current)
            current = indent + part
        else:
            current = candidate
    if current:
        rows.append(current)
    return [_clip(row, width) for row in rows]


def _prose(text: str, width: int) -> list[str]:
    """A sentence-like nav row, word-wrapped under a two-space hang."""
    return textwrap.wrap(text, width=width, subsequent_indent="  ",
                         break_long_words=True) or [""]


def render_analytics_compact(analytics: dict, width: int = 48) -> str:
    """Compact analytics block for the left navigation pane.

    Sections: mana-value summary, mana curve histogram (0/1/2/3/4/5/6+),
    colored-pip count for non-lands (WUBRG), mana sources for lands
    (WUBRG + C). Width-aware: at the default width every section is one or
    two rows; a narrower pane reflows them rather than overflowing.
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
    lands = [f"lands: {land_str}"]
    if len(lands[0]) > width and mdfc:
        # The MDFC count is the part worth keeping whole, so it gets a row.
        lands = [f"lands: {land}", f"({land_total} with MDFC)"]
    lines.extend(_pack([f"avg MV: {mv_avg:.2f}", f"non-lands: {nonland}",
                        *lands], width, sep="   "))
    lines.append("")
    buckets = ("0", "1", "2", "3", "4", "5", "6+")
    heads = "curve  " + "  ".join(f"{b:>2}" for b in buckets)
    counts = "       " + "  ".join(f"{curve[i]:>2}" for i in range(7))
    if max(len(heads), len(counts)) <= width:
        lines.extend([heads, counts])
    else:
        # Too narrow for the label column: the label takes its own row and
        # the columns close up to one space, which fits the narrowest pane.
        lines.append("curve")
        lines.append(_clip(" ".join(f"{b:>2}" for b in buckets), width))
        lines.append(_clip(" ".join(f"{curve[i]:>2}" for i in range(7)), width))

    def labelled(label: str, gap: str, parts: list[str]) -> list[str]:
        row = label + gap + " ".join(parts)
        if len(row) <= width:
            return [row]
        return _pack([label, *parts], width, sep=" ", indent="  ")

    if pip_total > 0:
        lines.append("")
        lines.extend(labelled(f"pips ({pip_total}):", "    ",
                              # `C` is {C} in a cost — counted in the total,
                              # so listed too. `.get`: older analytics dicts
                              # have no C bucket.
                              [f"{c}:{pips.get(c, 0)}" for c in "WUBRGC"
                               if pips.get(c, 0)]))

    if land_total > 0:
        lines.extend(labelled(f"sources ({land_total}):", " ",
                              [f"{c}:{sources[c]}" for c in "WUBRGC" if sources[c]]))

    return "\n".join(lines)


# The nav deck header's clickable "export this deck to Forge" control. ASCII,
# like the rest of the pane, and short enough for the narrowest one.
FORGE_EXPORT_TOKEN = "[-> forge]"


# Below this many columns left for card names, the combo row header drops its
# `(N cards)` count: a header that fills the row pushes every card name onto
# continuation lines, which is harder to read than a missing count.
_COMBO_NAME_ROOM = 12


def render_combos_compact(
    combos: list[dict],
    width: int = 48,
    links: Optional[list[LinkSpan]] = None,
) -> str:
    """Numbered combo list for the left navigation pane.

    Shape mirrors `render_combo_list(numbered=True)` — the same `[  N]`
    labels, recorded as `combo` links carrying the row number — but laid out
    narrower for the side pane. Each combo gets a header line plus its card
    list wrapping at ' + '.
    """
    lines = ["COMBOS", "-" * min(width, 6)]
    if not combos:
        lines.extend(_prose("  (no combos fully contained in this deck)", width))
        return "\n".join(lines)
    lines.extend(_pack([f"{len(combos)} combo(s) —", "`combo-info <N>`",
                        "to expand"], width, sep=" "))
    for i, c in enumerate(combos, 1):
        cards_str = c.get("cards") or c.get("combo_name") or ""
        ci = c.get("color_identity") or "-"
        plus = "+" if c.get("has_template_vars") else ""
        index_label = f"[{i:>3}]"
        header = f"  {index_label} {ci:<5} ({c['card_count']}{plus} cards) "
        if width - len(header) < _COMBO_NAME_ROOM:
            header = f"  {index_label} {ci} "
        row = wrap_combo_row(header, cards_str, row_width=width)
        # A wrapped row spans several lines; the index only exists on the
        # first, so record the link before extending past it.
        first_line_no = len(lines)
        # A single card name longer than the pane still can't be broken at
        # ' + ', so clip it; the label at columns 2-7 always survives.
        lines.extend(_clip(line, width) for line in row.split("\n"))
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

    No row is wider than `width`: the pane doesn't wrap, so an overlong row
    is silently cut off at the border.
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

    name_line = _clip(deck.get("name") or "(deck)", width)

    # Every element of `lines` must be exactly one physical row: LinkSpan line
    # numbers index into it, and a multi-line sub-render appended as a single
    # element shifted every link below it — the combo numbers landed on the
    # analytics rows.
    lines: list[str] = []
    # The export-to-Forge control: right-aligned on the name row when both
    # fit, else on a row of its own so the name keeps its full width. Always
    # shown — whether Forge is installed is the click's question, not the
    # render's (nothing that touches the filesystem belongs in a render).
    token = FORGE_EXPORT_TOKEN
    if len(name_line) + 1 + len(token) <= width:
        lines.append(name_line + " " * (width - len(name_line) - len(token)) + token)
    else:
        lines.extend([name_line, token.rjust(width)])
    if links is not None:
        links.append(LinkSpan(len(lines) - 1, len(lines[-1]) - len(token),
                              len(lines[-1]), "forge_export",
                              (deck.get("folder"), deck.get("name"))))
    lines.extend(_pack(bits, width, sep=" / "))
    badges = [b for b in (_ci_badge(deck.get("commander_ci")),
                          _points_badge(deck.get("points"))) if b]
    if badges:
        lines.extend(_pack(badges, width, sep=" "))
    if no_format_hint:
        lines.extend(_prose(no_format_hint, width))
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
    # Weighed for the deck, not in it: last, and in no total above.
    if deck.get("considering"):
        ordered.append(("Considering", deck["considering"]))

    points = deck.get("points")
    points_by_card = {n.lower(): p for n, p, _q, _s in (points or {}).get("cards", [])}

    for bucket, cards in ordered:
        subtotal = sum(c["quantity"] for c in cards)
        lines.append("")
        # A user-named category can be any length, and the count is the
        # useful half — so it is the name that gets cut, not the count.
        lines.append(_name_with_points(bucket, subtotal, width))
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
        # `_points_headline` joins its three facts with three spaces; split on
        # that so a narrow pane stacks them instead of cutting `(0 left)` off.
        lines.extend(_pack(_points_headline(points).split("   "), width, sep="   "))
        lines.extend(render_points(points, headline=False, width=width).split("\n"))

    if analytics is not None:
        lines.append("")
        lines.extend(render_analytics_compact(analytics, width=width).split("\n"))
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
    considering = result.get("considering") or 0
    if considering:
        lines.append(f"NOTE {considering} maybeboard card(s) went onto the considering list.")
    format_set = result.get("format_set")
    if format_set:
        lines.append(
            f"NOTE deck format auto-set to {format_set!r} because the list "
            f"names a commander — its legality and singleton rules now apply."
        )
    return "\n".join(lines)


def _printing_label(set_code, number) -> str:
    """`(C18) 263`, `(STA)`, or `(no printing)`."""
    if not set_code:
        return "(no printing)"
    return f"({set_code.upper()})" + (f" {number}" if number else "")


def _change_line(change: dict) -> str:
    """`+2 Brazen Borrower (ELD) 39`, `-1 Opt`, `Counterspell 1 -> 2`,
    `Sol Ring (C18) 263 -> (CMR) 472`, with the section marked unless it is
    the main deck."""
    before, after = change["before"], change["after"]
    set_before = change.get("set_code_before")
    set_after = change.get("set_code_after")
    number_before = change.get("collector_number_before")
    number_after = change.get("collector_number_after")
    moved = before and after and (set_before, number_before) != (set_after, number_after)
    if not before:
        text = f"+{after} {change['card']}"
        if set_after:
            text += f" {_printing_label(set_after, number_after)}"
    elif not after:
        text = f"-{before} {change['card']}"
    elif before == after:
        text = (f"{change['card']} {_printing_label(set_before, number_before)}"
                f" -> {_printing_label(set_after, number_after)}")
    else:
        text = f"{change['card']} {before} -> {after}"
        if moved:
            text += (f", {_printing_label(set_before, number_before)}"
                     f" -> {_printing_label(set_after, number_after)}")
    if change["section"] != "main":
        text += f" [{change['section']}]"
    return text


def _change_summary(changes: list[dict]) -> str:
    added = sum(1 for c in changes if not c["before"])
    removed = sum(1 for c in changes if not c["after"])
    return f"+{added} -{removed} ~{len(changes) - added - removed}"


def render_deck_diff(diff: dict) -> str:
    """A replace or undo result (`decks.replace_deck_contents` /
    `decks.undo_last_change`): what changed, then anything left out."""
    changes = diff.get("added", []) + diff.get("removed", []) + diff.get("changed", [])
    order = {"commander": 0, "main": 1, "sideboard": 2}
    changes.sort(key=lambda c: (order.get(c["section"], 1), c["card"].lower()))
    deck = diff.get("deck", "?")
    if diff.get("action") == "undo":
        undone = diff.get("undone") or {}
        head = (f"Undid #{undone.get('id')} ({undone.get('action')}) "
                f"on {deck!r}")
    else:
        head = f"Replaced {deck!r}"
    if not changes:
        lines = [f"{head}: no changes — the deck already matches."]
    else:
        lines = [f"{head}: {_change_summary(changes)} "
                 f"(revision #{diff.get('revision_id')})"]
        lines.extend(f"{INDENT}{_change_line(c)}" for c in changes)
    unresolved = diff.get("unresolved") or []
    if unresolved:
        lines.append(f"WARNING {len(unresolved)} card(s) not found and left out:")
        lines.extend(f"{INDENT}- {name}" for name in unresolved)
    rejected = diff.get("rejected") or []
    if rejected:
        lines.append(f"WARNING {len(rejected)} line(s) rejected; those cards "
                     f"keep their current quantity:")
        lines.extend(f"{INDENT}- {name}: {reason}" for name, reason in rejected)
    if not diff.get("considering"):
        lines.append("NOTE the list has no maybeboard, so the considering list was left as it was.")
    if diff.get("format_set"):
        lines.append(f"NOTE deck format auto-set to {diff['format_set']!r} "
                     f"because the list names a commander.")
    return "\n".join(lines)


def _local_time(iso: str) -> str:
    """A stored UTC timestamp as local `YYYY-MM-DD HH:MM`.

    Revisions are stored in UTC, and printing that raw put a change made at
    13:14 at 11:14. A timestamp with no offset is UTC by the same convention;
    one that doesn't parse is shown as stored rather than hidden.
    """
    if not iso:
        return ""
    try:
        at = datetime.fromisoformat(iso.replace("Z", "+00:00"))
    except ValueError:
        return iso.replace("T", " ")[:16]
    if at.tzinfo is None:
        at = at.replace(tzinfo=timezone.utc)
    return at.astimezone().strftime("%Y-%m-%d %H:%M")


def render_deck_history(revisions: list[dict]) -> str:
    """`decks.deck_history` output, newest first, each revision's changes
    indented under it."""
    if not revisions:
        return "(no recorded changes)"
    lines = [f"{len(revisions)} revision(s), newest first:"]
    for rev in revisions:
        changes = rev.get("changes") or []
        when = _local_time(rev.get("at") or "")
        note = f"  {rev['note']}" if rev.get("note") else ""
        lines.append(f"#{rev['id']:<5} {when}  {rev['action']:<8} "
                     f"{_change_summary(changes)}{note}")
        lines.extend(f"{INDENT * 3}{_change_line(c)}" for c in changes)
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


# --- deck analysis ------------------------------------------------------
#
# These three render the output of `services.profile_deck`, `services.
# rank_cards` and `services.compare_decks`. They take the role vocabulary as
# arguments rather than importing `roles`, for the reason at the top of this
# module: a renderer that could reach for the taxonomy would start deciding
# what a card does instead of formatting the decision.
#
# `profiles` and `cmp` are duck-typed. The dataclasses live in `services`, and
# reading their attributes needs no import.

ROLE_COL = 26          # width of the leading role-label column
_TURN_COL = 7          # width of one turn column in the curve tables


def _mean(values) -> float:
    values = list(values)
    return sum(values) / len(values) if values else 0.0


def _median(values) -> float:
    values = sorted(values)
    if not values:
        return 0.0
    mid = len(values) // 2
    if len(values) % 2:
        return float(values[mid])
    return (values[mid - 1] + values[mid]) / 2


def _signed(delta: float) -> str:
    """`+3`, `-2`, or `=` when there is no difference."""
    if delta > 0:
        return f"+{delta:g}"
    return f"{delta:g}" if delta else "="


def render_profile(
    profiles,
    *,
    role_labels: dict,
    role_order: list,
    turns: list,
    on_play: bool = True,
    low_confidence=(),
) -> str:
    """Density, reach, on-curve and ceiling tables for one or more decks.

    `role_order` is the reporting order and excludes `land`, which gets its
    own row beneath the spells because it is the one row that is not one.
    """
    lines: list[str] = []
    ids = [p.name for p in profiles]
    n = len(profiles)

    lines.append(f"{n} list(s): {', '.join(ids)}")
    odd = {p.name: p.size for p in profiles if p.size != 100}
    if odd:
        lines.append(f"  note: not 100 cards -> {odd}")
    for p in profiles:
        if p.unresolved:
            lines.append(f"  {p.name}: {len(p.unresolved)} unresolved -> "
                         f"{', '.join(p.unresolved[:6])}")
    # Cards nothing could place. On a familiar archetype this is empty; on
    # your own deck it is the list worth a second look.
    unsure = sorted(low_confidence)
    if unsure:
        lines.append("")
        lines.append(f"  {len(unsure)} card(s) fell through to 'utility' - "
                     f"classification worth checking:")
        lines.extend(f"    {name}" for name in unsure)

    lines.append("")
    lines.append("=== DENSITY (primary role; sums to deck size) ===")
    lines.append(f"{'role':<{ROLE_COL}}{'mean':>6}{'med':>5}{'min':>5}{'max':>5}   "
                 + " ".join(f"{i[-6:]:>6}" for i in ids))
    # A role every list has zero of is noise in a nine-column table.
    rows = [r for r in role_order
            if any(p.counts.get(r, 0) for p in profiles)] + ["land"]
    for role in rows:
        vals = [p.counts.get(role, 0) for p in profiles]
        lines.append(
            f"{role_labels[role]:<{ROLE_COL}}{_mean(vals):>6.1f}"
            f"{_median(vals):>5.0f}{min(vals):>5}{max(vals):>5}   "
            + " ".join(f"{v:>6}" for v in vals))
    src = [p.mana_sources for p in profiles]
    lines.append(f"{'MANA SOURCES':<{ROLE_COL}}{_mean(src):>6.1f}"
                 f"{_median(src):>5.0f}{min(src):>5}{max(src):>5}   "
                 + " ".join(f"{v:>6}" for v in src))
    mv = [p.avg_mv for p in profiles]
    lines.append(f"{'avg effective MV':<{ROLE_COL}}{_mean(mv):>6.2f}{'':>5}"
                 f"{min(mv):>5.2f}{max(mv):>5.2f}   "
                 + " ".join(f"{v:>6.2f}" for v in mv))

    lines.append("")
    lines.append("=== REACH (every role a card can fill, not just its primary) ===")
    lines.append(f"{'role':<{ROLE_COL}}{'primary':>8}{'reach':>7}{'diff':>7}"
                 f"{'engines':>9}")
    for role in role_order:
        prim = _mean([p.counts.get(role, 0) for p in profiles])
        reach = _mean([sum(p.role_mv.get(role, {}).values()) for p in profiles])
        eng = _mean([p.engines.get(role, 0) for p in profiles])
        lines.append(f"{role_labels[role]:<{ROLE_COL}}{prim:>8.1f}{reach:>7.1f}"
                     f"{reach - prim:>+7.1f}{(f'{eng:.1f}' if eng else '-'):>9}")
    lines.append("  (engines = permanents that keep producing the effect rather "
                 "than resolving once;")
    lines.append("   a planeswalker that draws every turn is not interchangeable "
                 "with Memory Deluge)")

    label = "on the play" if on_play else "on the draw"
    lines.append("")
    lines.append(f"=== ON CURVE - role is playable on turn T, {label} ===")
    lines.append(f"{'role':<{ROLE_COL}}"
                 + "".join(f"{'T' + str(t):>{_TURN_COL}}" for t in turns))
    for role in role_order:
        curves = [p.live_curve(role, turns, on_play=on_play) for p in profiles]
        means = [_mean([c[t] for c in curves]) for t in turns]
        lines.append(f"{role_labels[role]:<{ROLE_COL}}"
                     + "".join(f"{m * 100:>6.0f}%" for m in means))

    lines.append("")
    lines.append("=== CEILING - holding one at all, mana ignored ===")
    lines.append(f"{'role':<{ROLE_COL}}"
                 + "".join(f"{'T' + str(t):>{_TURN_COL}}" for t in turns)
                 + f"{'gap T4':>8}")
    for role in role_order:
        ceil = [p.ceiling(role, turns, on_play=on_play) for p in profiles]
        live = [p.live_curve(role, turns, on_play=on_play) for p in profiles]
        means = [_mean([c[t] for c in ceil]) for t in turns]
        # The gap is what mana costs you: held by turn 4 minus castable then.
        gap = (means[3] - _mean([c[turns[3]] for c in live])
               if len(turns) > 3 else 0.0)
        lines.append(f"{role_labels[role]:<{ROLE_COL}}"
                     + "".join(f"{m * 100:>6.0f}%" for m in means)
                     + f"{gap * 100:>7.0f}p")
    return "\n".join(lines)


def render_ranking(ranking: dict, n: int, *, role_labels: dict,
                   role_order: list) -> str:
    """How many lists play each card, per role, grouped by effective cost."""
    lines = ["=== MOST PLAYED per role (sorted by effective mana value) ==="]
    for role in role_order:
        rows = ranking.get(role, [])
        if not rows:
            continue
        lines.append("")
        lines.append(f"  -- {role_labels[role]} --")
        last = None
        for row in rows:
            if row["mv"] != last:
                lines.append(f"     MV {row['mv']}")
                last = row["mv"]
            tag = "" if row["primary"] else "  (secondary)"
            why = f"   [{row['reason']}]" if row["reason"] else ""
            lines.append(f"       {row['n']:>2}/{n}  {row['name'][:44]:<46}"
                         f"{row['cost']:<16}{tag}{why}")
    return "\n".join(lines)


_VERDICT_MARK = {"under": "<-- BELOW every list",
                 "over": "--> ABOVE every list",
                 "in": ""}


def render_comparison(cmp, *, role_labels: dict, role_order: list,
                      turns: list, on_play: bool = True) -> str:
    """One deck against a reference set: ranges, curve deltas, card diff.

    A single reference deck is rendered as a head-to-head instead. The range
    verdicts exist to say "nobody in the reference set went there", and with
    one list the range is a point - every difference would read as stepping
    outside it, which is noise wearing the clothes of a finding.
    """
    subj, refs = cmp.subject, cmp.reference
    n = len(refs)
    solo = n == 1
    lines = ["", "=" * 76]
    lines.append(f"HEAD TO HEAD - {subj.name!r} against {refs[0].name!r}" if solo
                 else f"COMPARISON - {subj.name!r} against {n} reference list(s)")
    lines.append("=" * 76)
    if subj.size != 100:
        lines.append(f"  note: subject is {subj.size} cards; the draw maths still "
                     f"treats the deck as 100, so the unfilled slots count as "
                     f"blanks")

    lines.append("")
    if solo:
        lines.append("=== ROLE COUNTS ===")
        lines.append(f"{'role':<{ROLE_COL}}{'yours':>7}{'theirs':>10}{'delta':>8}")
    else:
        lines.append("=== IN RANGE? (range, not mean - a range nobody left is "
                     "the rule) ===")
        lines.append(f"{'role':<{ROLE_COL}}{'yours':>7}{'ref mean':>10}"
                     f"{'range':>10}{'delta':>8}   verdict")
    order = {role: i for i, role in enumerate(list(role_order) + ["land"])}

    def role_row(label, d):
        if solo:
            return (f"{label:<{ROLE_COL}}{d.subject:>7}{d.ref_mean:>10.0f}"
                    f"{_signed(d.delta):>8}")
        return (f"{label:<{ROLE_COL}}{d.subject:>7}{d.ref_mean:>10.1f}"
                f"{f'{d.ref_min}-{d.ref_max}':>10}{_signed(d.delta):>8}   "
                f"{_VERDICT_MARK[d.verdict]}")

    for d in sorted(cmp.roles, key=lambda r: order.get(r.role, 99)):
        lines.append(role_row(role_labels[d.role], d))
    lines.append(role_row("MANA SOURCES", cmp.mana_sources))
    mine_mv, ref_mv = cmp.avg_mv
    lines.append(f"{'avg effective MV':<{ROLE_COL}}{mine_mv:>7.2f}{ref_mv:>10.2f}"
                 f"{_signed(round(mine_mv - ref_mv, 2)):>{8 if solo else 18}}")

    if solo:
        lines.append("")
        lines.append(f"  role-density distance: {cmp.nearest[0][1]:.2f}"
                     f"   (0 would be the same 100 cards by role)")
    else:
        out = cmp.out_of_range
        lines.append("")
        lines.append(f"  {len(out)} role(s) outside the reference range"
                     + (f": {', '.join(role_labels[r.role] for r in out)}" if out
                        else " - this deck sits inside the archetype on every axis"))
        # A reference set spanning two archetypes has a mean that describes
        # neither, so say so rather than letting the delta column imply there
        # is one right answer to be closer to.
        widest = max(cmp.roles, key=lambda r: r.ref_max - r.ref_min)
        if widest.ref_max - widest.ref_min >= 6:
            lines.append("")
            lines.append(f"  CAUTION: the reference set spans {widest.ref_min}-"
                         f"{widest.ref_max} on {role_labels[widest.role].lower()}, "
                         f"so it holds more than one")
            lines.append("  build and the mean above describes neither. Use the "
                         "nearest-list line, or re-run")
            lines.append("  with only the lists you actually want to resemble.")
        lines.append("")
        lines.append("=== NEAREST REFERENCE LIST (role-density distance) ===")
        for name, dist in cmp.nearest[:5]:
            lines.append(f"   {dist:>6.2f}  {name}")
        lines.append("   (lower is more alike; the axis with the widest spread in "
                     "the reference set dominates, which is the axis that "
                     "defines the build)")

    label = "on the play" if on_play else "on the draw"
    lines.append("")
    lines.append(f"=== ON CURVE, yours minus "
                 f"{'theirs' if solo else 'the reference mean'} ({label}) ===")
    lines.append(f"{'role':<{ROLE_COL}}"
                 + "".join(f"{'T' + str(t):>{_TURN_COL}}" for t in turns))
    for role in role_order:
        d = cmp.curve_delta.get(role, {})
        lines.append(f"{role_labels[role]:<{ROLE_COL}}"
                     + "".join(f"{d.get(t, 0) * 100:>+6.0f}p" for t in turns))
    lines.append("  (percentage points; + means this deck does it more often)")

    if cmp.missing:
        # "Played by more than one list" would hide everything when there is
        # only one list to be played by.
        shown = list(cmp.missing) if solo else (
            [c for c in cmp.missing if c.n_lists > 1] or list(cmp.missing))
        lines.append("")
        lines.append(f"=== {'CARDS THEY PLAY' if solo else 'CARDS THE REFERENCE PLAYS'}"
                     f" THAT THIS DECK DOESN'T ({len(cmp.missing)}) ===")
        for role in list(role_order) + ["land"]:
            group = [c for c in shown if c.role == role]
            if not group:
                continue
            lines.append("")
            lines.append(f"  -- {role_labels[role]}")
            for c in group:
                count = "" if solo else f"{c.n_lists:>2}/{c.of_lists}  "
                lines.append(f"       {count}MV{c.mv:<3} {c.name}")
        if len(shown) < len(cmp.missing):
            lines.append("")
            lines.append(f"  ({len(cmp.missing) - len(shown)} more played by "
                         f"exactly one list - pass --min-share 0 and read the "
                         f"JSON for those)")

    if cmp.unique:
        lines.append("")
        lines.append(f"=== CARDS ONLY THIS DECK PLAYS ({len(cmp.unique)}) ===")
        lines.append("  Not a criticism - this is where your build is its own "
                     "thing.")
        for c in cmp.unique:
            lines.append(f"       {role_labels.get(c.role, c.role):<{ROLE_COL}} "
                         f"MV{c.mv:<3} {c.name}")
    return "\n".join(lines)


# --- Forge ----------------------------------------------------------------
# Each takes a services.Forge* value duck-typed, never imported.

def render_forge_export(export) -> str:
    """What `forge export` wrote and what it wants the user to act on."""
    lines = [f"Exported {export.deck!r} for Forge ({export.game_type}):",
             f"{INDENT}{export.path}"]
    if export.ai_path:
        swaps = ", ".join(f"{card} -> {sub}" for card, sub in export.substitutions)
        lines.append(f"{INDENT}{export.ai_path}  (AI copy: {swaps})")
    if export.unknown:
        lines.append(f"WARNING {len(export.unknown)} card(s) unknown to Forge "
                     f"were left out:")
        lines.extend(f"{INDENT}- {name}" for name in export.unknown)
    if export.ai_unplayable:
        lines.append(f"WARNING Forge's AI can't play {len(export.ai_unplayable)} "
                     f"card(s); give each a substitute with `forge sub add`:")
        lines.extend(f"{INDENT}- {name}" for name in export.ai_unplayable)
    if export.ai_situational:
        lines.append("NOTE the AI plays these only situationally:")
        lines.extend(f"{INDENT}- {name} ({flag})"
                     for name, flag in export.ai_situational)
    lines.extend(f"NOTE {note}" for note in export.notes)
    return "\n".join(lines)


def render_forge_substitutions(deck_name: str, subs: list[dict]) -> str:
    """`forge_data.list_substitutions` rows for one deck."""
    if not subs:
        return f"(no Forge substitutions in {deck_name!r})"
    width = max(len(s["card_name"]) for s in subs)
    lines = [f"Forge AI substitutions in {deck_name!r} ({len(subs)}):"]
    lines.extend(f"{INDENT}{s['card_name']:<{width}}  ->  {s['substitute']}"
                 for s in subs)
    return "\n".join(lines)


def _record(wins: int, losses: int, draws: int) -> str:
    return f"{wins}-{losses}" + (f"-{draws}" if draws else "")


def render_forge_sim(result) -> str:
    """One sim run: the score, then each game."""
    def label(name, ai):
        return f"{name} (AI copy)" if ai else name

    a = label(result.deck_a, result.ai_variant_a)
    b = label(result.deck_b, result.ai_variant_b)
    lines = [f"{a}  {result.wins_a} - {result.wins_b}  {b}"
             + (f"   ({result.draws} draw{'s' if result.draws != 1 else ''})"
                if result.draws else ""),
             f"{INDENT}{len(result.games)} game(s), {result.game_type}, "
             f"Forge {result.forge_version}"]
    for g in result.games:
        winner = {"a": result.deck_a, "b": result.deck_b}.get(g["winner"], "draw")
        turns = f"turn {g['turns']}" if g.get("turns") else "turn ?"
        clock = "  (stopped by Forge's clock)" if g.get("clock_draw") else ""
        lines.append(f"{INDENT}game {g['game_no']:>2}: {winner:<28} {turns:<8} "
                     f"{g['duration_ms'] / 1000:5.1f}s{clock}")
    lines.extend(f"NOTE {note}" for note in result.notes)
    lines.append(f"{INDENT}log: {result.log_path}")
    return "\n".join(lines)


def render_forge_results(results) -> str:
    """Per-matchup records for one deck, or a win matrix for all of them.

    Matrix cells are the row deck's record against the column deck,
    wins-losses[-draws]; columns are numbered to keep the table narrow.
    """
    if not results.records:
        return "(no Forge sims recorded yet)"
    if results.focus:
        width = max(len(r.opponent) for r in results.records)
        lines = [f"Forge record for {results.focus!r}:"]
        for r in results.records:
            turns = f"  avg turn {r.avg_turns}" if r.avg_turns else ""
            lines.append(f"{INDENT}vs {r.opponent:<{width}}  "
                         f"{_record(r.wins, r.losses, r.draws):>7}  "
                         f"({r.games} game(s)){turns}")
        return "\n".join(lines)
    cells = {(r.deck, r.opponent): _record(r.wins, r.losses, r.draws)
             for r in results.records}
    names = results.decks
    width = max(len(n) for n in names) + 5
    cell = max([7] + [len(v) for v in cells.values()]) + 1
    lines = ["Forge win matrix (row vs column, wins-losses[-draws]):",
             " " * width + "".join(f"{i + 1:>{cell}}" for i in range(len(names)))]
    for i, row in enumerate(names):
        head = f"{i + 1:>2}. {row}"[:width - 1]
        lines.append(f"{head:<{width}}" + "".join(
            f"{cells.get((row, col), '.'):>{cell}}" for col in names))
    return "\n".join(lines)
