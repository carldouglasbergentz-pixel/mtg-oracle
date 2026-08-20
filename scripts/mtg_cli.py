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
import sqlite3
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))
from mtg_oracle import queries as q
from mtg_oracle.scryfall_search import SYNTAX_HELP
from mtg_oracle import decks as d
from mtg_oracle import services as svc
from mtg_oracle.renderer import (
    render_card as _render_card,
    render_rulings as _render_rulings,
    render_combo as _render_combo,
    render_combo_list as _render_combo_list,
    render_rule as _render_rule,
    render_rules_search as _render_rules_search,
    render_search as _render_search,
    render_corrections as _render_corrections,
    render_deck as _render_deck,
    render_deck_list as _render_deck_list,
    render_folder_list as _render_folder_list,
    render_import_result as _render_import_result,
)


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
        print(SYNTAX_HELP)
        return 0
    try:
        page = svc.search(query, page=args.page, page_size=args.limit)
    except svc.ServiceError as e:
        print(f"search error: {e}\n\nType `search help` for syntax.")
        return 2
    if args.json:
        print(json.dumps({"total": page.total, "page": page.page,
                          "page_size": page.page_size, "rows": page.rows},
                         indent=2, default=str))
    else:
        print(_render_search(page.rows, page=page.page, total=page.total,
                             page_size=page.page_size))
    return 0 if page.rows else 1


def _cmd_correction(args) -> int:
    rows = q.get_corrections(card=args.card, topic=args.topic, limit=args.limit)
    if args.json:
        print(json.dumps(rows, indent=2, default=str))
    else:
        print(_render_corrections(rows))
    return 0 if rows else 1


# --- Deck commands -----------------------------------------------------

def _cmd_folders(args) -> int:
    folders = d.list_folders()
    if args.json:
        print(json.dumps(folders, indent=2, default=str))
    else:
        print(_render_folder_list(folders))
    return 0


def _cmd_decks(args) -> int:
    decks = d.list_decks(folder=args.folder)
    if args.json:
        print(json.dumps(decks, indent=2, default=str))
    else:
        print(_render_deck_list(decks, flat=bool(args.folder)))
    return 0


# `deck` shares one flat flag set across all its actions, so argparse can't
# enforce which flags each action needs. Declaring it here turns a stray
# AttributeError deep in the deck layer into a usage message.
_DECK_REQUIRED_FLAGS = {
    "rename": [("new_name", "--new-name")],
    "add": [("card", "--card")],
    "remove": [("card", "--card")],
}


def _cmd_deck(args) -> int:
    """Dispatch for `deck <action> ...` sub-subcommands."""
    action = args.action
    for attr, flag in _DECK_REQUIRED_FLAGS.get(action, []):
        if getattr(args, attr, None) in (None, ""):
            print(f"deck {action}: {flag} is required")
            return 2
    try:
        if action == "show":
            deck = d.get_deck(args.name, folder=args.folder)
            if not deck:
                print(f"(deck not found: {args.name})")
                return 1
            if args.json:
                print(json.dumps(deck, indent=2, default=str))
            else:
                print(_render_deck(deck))
            return 0
        if action == "new":
            d.create_deck(args.name, folder=args.folder, format=args.format)
            print(f"OK created deck {args.name!r}"
                  + (f" in folder {args.folder!r}" if args.folder else ""))
            return 0
        if action == "delete":
            d.delete_deck(args.name, folder=args.folder)
            print(f"OK deleted deck {args.name!r}")
            return 0
        if action == "rename":
            d.rename_deck(args.name, args.new_name, folder=args.folder)
            print(f"OK renamed to {args.new_name!r}")
            return 0
        if action == "move":
            d.move_deck(args.name, args.new_folder, folder=args.folder)
            print(f"OK moved {args.name!r} -> {args.new_folder or '(unsorted)'}")
            return 0
        if action == "add":
            qty = args.qty if args.qty is not None else 1
            canonical = d.add_card_to_deck(
                args.name, args.card, quantity=qty,
                is_commander=args.commander, is_sideboard=args.sideboard,
                folder=args.folder,
            )
            print(f"OK {qty}x {canonical}")
            return 0
        if action == "remove":
            canonical, removed, remaining = d.remove_card_from_deck(
                args.name, args.card, quantity=args.qty, folder=args.folder,
            )
            tail = f" ({remaining} remaining)" if remaining else ""
            print(f"OK removed {removed}x {canonical}{tail}")
            return 0
        if action == "import":
            ref = svc.DeckRef(deck=args.name, folder=args.folder)
            try:
                result = svc.create_deck_from_text(
                    ref, _read_deck_source(args), format=args.format,
                )
            except svc.ServiceError as e:
                print(f"deck import: {e}")
                return 1
            print(_render_import_result(args.name, result))
            return 0
        if action == "combos":
            combos = d.combos_in_deck(args.name, folder=args.folder)
            if args.json:
                print(json.dumps(combos, indent=2, default=str))
            else:
                print(_render_combo_list(
                    combos, f"{len(combos)} combo(s) fully contained in {args.name!r}:"
                ))
            return 0
    except d.DeckError as e:
        print(f"deck error: {e}")
        return 2
    print(f"unknown deck action: {action}")
    return 2


def _read_deck_source(args) -> str:
    """Pick the deck source: --from-file, positional file path, or stdin."""
    if args.from_file:
        return Path(args.from_file).read_text(encoding="utf-8")
    if not sys.stdin.isatty():
        return sys.stdin.read()
    raise d.DeckError("need --from-file PATH (or pipe text on stdin)")


def _cmd_folder(args) -> int:
    """Dispatch for `folder <action> ...`."""
    try:
        if args.action == "new":
            d.create_folder(args.name)
            print(f"OK created folder {args.name!r}")
            return 0
        if args.action == "delete":
            d.delete_folder(args.name, force=args.force)
            print(f"OK deleted folder {args.name!r}")
            return 0
    except d.DeckError as e:
        print(f"folder error: {e}")
        return 2
    print(f"unknown folder action: {args.action}")
    return 2


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

    # --- decks & folders ---
    sp = sub.add_parser("folders", help="List all folders.")
    sp.set_defaults(func=_cmd_folders)

    sp = sub.add_parser("decks", help="List all decks (grouped by folder).")
    sp.add_argument("--folder", help="Filter to one folder.")
    sp.set_defaults(func=_cmd_decks)

    sp = sub.add_parser("folder", help="Manage folders: folder new|delete <name>.")
    sp.add_argument("action", choices=["new", "delete"])
    sp.add_argument("name")
    sp.add_argument("--force", action="store_true",
                    help="(delete) move contained decks to (unsorted) instead of refusing.")
    sp.set_defaults(func=_cmd_folder)

    sp = sub.add_parser("deck", help=(
        "Manage decks: deck (show|new|delete|rename|move|add|remove|import) <name> ..."
    ))
    sp.add_argument(
        "action",
        choices=["show", "new", "delete", "rename", "move", "add", "remove", "import", "combos"],
    )
    sp.add_argument("name", help="Deck name.")
    sp.add_argument("--folder", help="Folder the deck lives in (disambiguates duplicates).")
    sp.add_argument("--format", help="Informational format tag (commander, modern, ...).")
    sp.add_argument("--new-name", help="(rename) the new deck name.")
    sp.add_argument("--new-folder", help="(move) target folder; empty -> (unsorted).")
    sp.add_argument("--card", help="(add/remove) card name.")
    sp.add_argument(
        "--qty", type=int, default=None,
        help="(add) quantity, default 1. (remove) copies to take, default all.",
    )
    sp.add_argument("--commander", action="store_true", help="(add) add as commander.")
    sp.add_argument("--sideboard", action="store_true", help="(add) add to sideboard.")
    sp.add_argument("--from-file", help="(import) read deckstring from this file.")
    sp.set_defaults(func=_cmd_deck)

    return p


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return args.func(args)
    except FileNotFoundError as e:
        print(f"ERR {e}", file=sys.stderr)
        return 2
    except sqlite3.OperationalError as e:
        # A missing column or table means the database predates this build.
        print(f"ERR database: {e}", file=sys.stderr)
        if "no such column" in str(e) or "no such table" in str(e):
            print("    Your database predates this version. Run: "
                  "python scripts/sync.py", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
