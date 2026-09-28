"""MTG Oracle CLI — query the local knowledge base from the command line.

Subcommands:
    mtg_cli.py card <name>
    mtg_cli.py ruling <name>
    mtg_cli.py combo <card>                   # combos featuring a card
    mtg_cli.py combos <card1> <card2> [...]   # combos containing ALL listed cards
    mtg_cli.py combo-info <combo_id>          # full combo detail
    mtg_cli.py rule <rule_number>
    mtg_cli.py search-rules <text>
    mtg_cli.py search <query> [--limit N] [--page N]   # `search help` for syntax
    mtg_cli.py correction [--card X] [--topic Y]
    mtg_cli.py folders | decks [--folder F] | folder new|delete <name>
    mtg_cli.py deck <action> <name> [--folder F] ...   # `deck -h` for flags
    mtg_cli.py deck import <name> --from-file PATH     # or pipe text on stdin
    mtg_cli.py deck import <name> --replace [--force]  # existing deck := the list
    mtg_cli.py deck history <name> [--limit N] | deck undo <name>
    mtg_cli.py deck export <name> [--to-file PATH]     # paste into Moxfield

`--json` is a global flag and goes before the subcommand:
    mtg_cli.py --json card "Sol Ring"

Output is human-readable, monospace-friendly ASCII (no unicode borders)
so it renders cleanly on Windows consoles. Card, rule and combo lookups
are read-only; the `deck` and `folder` commands write to your decks.
"""
from __future__ import annotations

import argparse
import json
import sqlite3
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))
sys.path.insert(0, str(Path(__file__).parent))
import self_heal  # noqa: E402 — scripts/ sibling
from mtg_oracle import queries as q
from mtg_oracle.scryfall_search import SYNTAX_HELP
from mtg_oracle import decks as d
from mtg_oracle import renderer as r
from mtg_oracle import roles
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
    "compare": [("against", "--against")],
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
            # Not in _DECK_REQUIRED_FLAGS because "" is a valid value there
            # (move to unsorted). A forgotten flag used to mean the same
            # thing and silently pulled the deck out of its folder.
            if args.new_folder is None:
                print('deck move: --new-folder is required '
                      '(--new-folder "" for unsorted)')
                return 2
            d.move_deck(args.name, args.new_folder, folder=args.folder)
            print(f"OK moved {args.name!r} -> {args.new_folder or '(unsorted)'}")
            return 0
        if action == "add" and args.commander:
            return _add_commander(args)
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
            if args.sideboard or args.commander:
                # remove_card_from_deck has no section argument: it takes
                # main-deck copies first. Honouring the flag by ignoring it
                # removed the very copies the user meant to keep.
                print("deck remove: --sideboard / --commander are not supported; "
                      "remove takes main-deck copies first, then sideboard")
                return 2
            canonical, removed, remaining = d.remove_card_from_deck(
                args.name, args.card, quantity=args.qty, folder=args.folder,
            )
            tail = f" ({remaining} remaining)" if remaining else ""
            print(f"OK removed {removed}x {canonical}{tail}")
            return 0
        if action == "import" and args.replace:
            return _replace_from_text(args)
        if action == "import":
            if args.force:
                print("deck import: --force only applies with --replace "
                      "(a new deck's import already loads the list verbatim)")
                return 2
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
        if action == "export":
            ref = svc.DeckRef(deck=args.name, folder=args.folder)
            exported = svc.export_deck_text(
                ref, front_face=args.front_face, group_by_role=args.grouped)
            if args.to_file:
                Path(args.to_file).write_text(exported.text, encoding="utf-8")
                print(f"OK wrote {exported.cards} cards to {args.to_file}")
            else:
                print(exported.text, end="")
            return 0
        if action in ("profile", "compare"):
            turns = list(range(1, 9))
            order = [x for x in roles.ROLES if x != "land"]
            fmt = {"role_labels": roles.LABELS, "role_order": order,
                   "turns": turns}
            deck = svc.deck_cards_for_analysis(
                svc.DeckRef(deck=args.name, folder=args.folder))
            if action == "profile":
                profile = svc.profile_deck(deck["name"], deck["cards"])
                _, low = svc.rank_cards([deck], order)
                print(r.render_profile([profile], low_confidence=low, **fmt))
                return 0
            other = svc.deck_cards_for_analysis(svc.DeckRef(
                deck=args.against, folder=args.against_folder))
            cmp = svc.compare_decks(deck, [other], turns=turns)
            print(r.render_comparison(cmp, **fmt))
            return 0
        if action == "history":
            revisions = d.deck_history(args.name, folder=args.folder,
                                       limit=args.limit or 20)
            if args.json:
                print(json.dumps(revisions, indent=2, default=str))
            else:
                print(r.render_deck_history(revisions))
            return 0
        if action == "undo":
            diff = d.undo_last_change(args.name, folder=args.folder)
            if args.json:
                print(json.dumps(diff, indent=2, default=str))
            else:
                print(r.render_deck_diff(diff))
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
    except svc.ServiceError as e:
        # `ServiceError` is the type the service layer raises for anything the
        # user should see, and it was never caught here — so `deck export
        # "No Such Deck"` printed a traceback instead of the message the
        # exception was carrying.
        print(f"deck error: {e}")
        return 2
    print(f"unknown deck action: {action}")
    return 2


def _add_commander(args) -> int:
    """`deck add --commander` goes through set_commander, as the TUI's
    `commander` verb does. add_card_to_deck with is_commander=True skipped
    what set_commander owns: promoting a copy already in the deck instead of
    adding a second row, forcing quantity 1, and auto-setting the format.
    Raises DeckError for _cmd_deck to report."""
    if args.sideboard:
        print("deck add: --commander and --sideboard are mutually exclusive")
        return 2
    if args.qty not in (None, 1):
        print("deck add: a commander is always 1 copy; drop --qty")
        return 2
    canonical, action, format_set = d.set_commander(
        args.name, args.card, folder=args.folder)
    print({
        "promoted":  f"OK {canonical} promoted to commander",
        "added":     f"OK {canonical} added as commander",
        "unchanged": f"OK {canonical} is already a commander (no change)",
    }[action])
    if format_set:
        print(f"   deck format auto-set to {format_set!r}")
    return 0


def _replace_from_text(args) -> int:
    """`deck import <name> --replace`: the existing deck becomes exactly the
    list, as one revision `deck undo` can revert."""
    if args.format:
        print("deck import --replace: --format does not apply; the deck keeps "
              "its own (use `deck new` / `format` to change it)")
        return 2
    ref = svc.DeckRef(deck=args.name, folder=args.folder)
    try:
        diff = svc.replace_deck_from_text(
            ref, _read_deck_source(args), force=args.force)
    except svc.ServiceError as e:
        print(f"deck import --replace: {e}")
        return 1
    if args.json:
        print(json.dumps(diff, indent=2, default=str))
    else:
        print(r.render_deck_diff(diff))
    return 0


def _read_deck_source(args) -> str:
    """Pick the deck source: --from-file, or text piped on stdin."""
    if args.from_file:
        # utf-8-sig: Notepad saves UTF-8 with a BOM, and a BOM glued to the
        # first line hides a section header like `Commander`.
        return Path(args.from_file).read_text(encoding="utf-8-sig")
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
        "Manage decks: deck (show|new|delete|rename|move|add|remove|import|"
        "export|profile|compare|combos|history|undo) <name> ..."
    ))
    sp.add_argument(
        "action",
        choices=["show", "new", "delete", "rename", "move", "add", "remove",
                 "import", "export", "profile", "compare", "combos",
                 "history", "undo"],
    )
    sp.add_argument("name", help="Deck name.")
    sp.add_argument("--folder", help="Folder the deck lives in (disambiguates duplicates).")
    sp.add_argument("--format", help=(
        "(new/import) the deck's format: commander, duel, canlander, ... "
        "It switches on legality, singleton and points checks. Defaults to "
        "the folder's format."))
    sp.add_argument("--new-name", help="(rename) the new deck name.")
    sp.add_argument("--new-folder",
                    help='(move, required) target folder; "" -> (unsorted).')
    sp.add_argument("--card", help="(add/remove) card name.")
    sp.add_argument(
        "--qty", type=int, default=None,
        help="(add) quantity, default 1. (remove) copies to take, default all.",
    )
    sp.add_argument("--commander", action="store_true",
                    help="(add) add as commander, or promote a copy already in the deck.")
    sp.add_argument("--sideboard", action="store_true", help="(add) add to sideboard.")
    sp.add_argument("--from-file", help="(import) read deckstring from this file.")
    sp.add_argument("--replace", action="store_true",
                    help="(import) make an EXISTING deck exactly this list, as "
                         "one revision `deck undo` reverts.")
    sp.add_argument("--force", action="store_true",
                    help="(import --replace) replace even when some names are "
                         "not found, leaving them out.")
    sp.add_argument("--limit", type=int, default=None,
                    help="(history) revisions to show, newest first (default 20).")
    sp.add_argument("--to-file", help="(export) write the decklist here instead of stdout.")
    sp.add_argument("--front-face", action="store_true",
                    help="(export) shorten two-faced names to the front face "
                         "(split cards keep their full name — 'Fire' is not a card).")
    sp.add_argument("--grouped", action="store_true",
                    help="(export) add `//` role headers; importers skip them.")
    sp.add_argument("--against", metavar="DECK",
                    help="(compare) the deck to measure this one against. "
                         "For a whole reference set of lists, use "
                         "scripts/analyse_archetype.py --dir instead.")
    sp.add_argument("--against-folder", metavar="FOLDER",
                    help="(compare) folder the --against deck lives in, when "
                         "its name exists in more than one.")
    sp.set_defaults(func=_cmd_deck)

    return p


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    # Heal the database the data layer actually uses, so a new build's
    # tables exist before the first write. Reports go to stderr: stdout may
    # be --json for another program.
    reports, _failures = self_heal.run(db_path=q.DB_PATH)
    for report in reports:
        print(report, file=sys.stderr)
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
