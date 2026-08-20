"""Analyse decklists: what the cards do, and when you can actually cast them.

Point it at a folder of decklist text files, at decks in your own collection,
or at both. It classifies every card into functional roles, prices each one at
what it *effectively* costs rather than its printed mana value, and computes
the exact probability that each role is playable on each turn.

    # a folder of reference lists
    python scripts/analyse_archetype.py --dir "docs/sample decklists-uw canlander"

    # your own deck, compared against those lists
    python scripts/analyse_archetype.py --dir "docs/sample decklists-uw canlander" \
        --deck "My UW Deck" --folder "Canadian Highlander"

    # compare ONE deck against the rest: where am I outside their ranges,
    # which cards am I missing, and which do I play alone
    python scripts/analyse_archetype.py --compare "My UW Deck"
        --folder "Canadian Highlander" --dir "docs/sample decklists-uw canlander"

    # machine-readable, for the LLM layer or a spreadsheet
    python scripts/analyse_archetype.py --dir <path> --json > analysis.json

Identical files are collapsed: three of the twelve sample lists are byte-for-byte
duplicates, and counting them twice would skew every "played in N lists" figure.
Pass --keep-duplicates if each file really is a separate data point.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from mtg_oracle import renderer, roles, services  # noqa: E402
from mtg_oracle.deck_parser import parse_deckstring  # noqa: E402

TURNS = list(range(1, 9))
# Every role except `land`, which is reported on its own line with the mana
# base. Derived rather than listed so adding a role to the taxonomy shows up
# in the report instead of silently going unmeasured.
REPORT_ROLES = [r for r in roles.ROLES if r != "land"]


def read_dir(path: Path, keep_duplicates: bool) -> list[dict]:
    """Every .txt decklist in a folder, with identical files collapsed."""
    seen: dict[str, dict] = {}
    order: list[str] = []
    for f in sorted(path.glob("*.txt")):
        raw = f.read_bytes()
        parsed = parse_deckstring(raw.decode("utf-8-sig"))
        if not parsed:
            print(f"  (skipped {f.name}: no card lines)", file=sys.stderr)
            continue
        cards: dict[str, int] = {}
        for row in parsed:
            if row["section"] == "sideboard":
                continue
            cards[row["name"]] = cards.get(row["name"], 0) + row["quantity"]
        key = f.stem if keep_duplicates else hashlib.md5(raw).hexdigest()
        if key in seen:
            seen[key]["files"].append(f.stem)
            continue
        seen[key] = {"name": f.stem, "cards": cards, "files": [f.stem]}
        order.append(key)
    return [seen[k] for k in order]


def print_report(profiles, args) -> None:
    """The profile tables plus the most-played ranking, to stdout."""
    print()
    print(renderer.render_profile(
        profiles, role_labels=roles.LABELS, role_order=REPORT_ROLES,
        turns=TURNS, on_play=not args.on_draw,
        low_confidence=args.low_confidence))
    print()
    print(renderer.render_ranking(
        args.ranking, len(profiles),
        role_labels=roles.LABELS, role_order=REPORT_ROLES))


def read_db_deck(name: str, folder: str | None) -> dict:
    """One of the user's own decks, in the same shape as a decklist file."""
    return services.deck_cards_for_analysis(
        services.DeckRef(deck=name, folder=folder))


def read_subject(target: str, folder: str | None) -> dict:
    """The deck under test — a .txt path or a deck in the collection."""
    path = Path(target)
    if path.suffix.lower() == ".txt":
        if not path.is_file():
            raise services.ServiceError(f"no such file: {target}")
        parsed = parse_deckstring(path.read_bytes().decode("utf-8-sig"))
        if not parsed:
            raise services.ServiceError(f"no card lines in {target}")
        cards: dict[str, int] = {}
        for row in parsed:
            if row["section"] == "sideboard":
                continue
            cards[row["name"]] = cards.get(row["name"], 0) + row["quantity"]
        return {"name": path.stem, "cards": cards, "files": [path.stem]}
    return read_db_deck(target, folder)


def print_comparison(cmp, args) -> None:
    """The comparison tables: ranges, curve deltas, and the card-level diff."""
    print()
    print(renderer.render_comparison(
        cmp, role_labels=roles.LABELS, role_order=REPORT_ROLES,
        turns=TURNS, on_play=not args.on_draw))


def comparison_json(cmp) -> dict:
    return {
        "subject": cmp.subject.name,
        "reference": [p.name for p in cmp.reference],
        "roles": [{"role": r.role, "label": roles.LABELS[r.role],
                   "subject": r.subject, "ref_mean": r.ref_mean,
                   "ref_min": r.ref_min, "ref_max": r.ref_max,
                   "ref_median": r.ref_median, "delta": r.delta,
                   "verdict": r.verdict} for r in cmp.roles],
        "mana_sources": {"subject": cmp.mana_sources.subject,
                         "ref_mean": cmp.mana_sources.ref_mean,
                         "ref_min": cmp.mana_sources.ref_min,
                         "ref_max": cmp.mana_sources.ref_max,
                         "verdict": cmp.mana_sources.verdict},
        "avg_mv": {"subject": cmp.avg_mv[0], "reference": cmp.avg_mv[1]},
        "out_of_range": [r.role for r in cmp.out_of_range],
        "nearest": [{"name": nm, "distance": d} for nm, d in cmp.nearest],
        "curve_delta": {r: {str(t): v for t, v in d.items()}
                        for r, d in cmp.curve_delta.items()},
        "missing": [{"name": c.name, "role": c.role, "mv": c.mv,
                     "n_lists": c.n_lists, "of_lists": c.of_lists,
                     "share": round(c.share, 3)} for c in cmp.missing],
        "unique": [{"name": c.name, "role": c.role, "mv": c.mv}
                   for c in cmp.unique],
    }


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dir", type=Path, action="append", default=[],
                    help="folder of decklist .txt files (repeatable)")
    ap.add_argument("--deck", action="append", default=[],
                    help="a deck from your own collection (repeatable)")
    ap.add_argument("--folder", help="folder --deck and --compare live in")
    ap.add_argument("--compare", metavar="DECK",
                    help="measure this deck against everything else given; "
                         "accepts a deck name from your collection or a .txt path")
    ap.add_argument("--min-share", type=float, default=0.0, metavar="F",
                    help="(--compare) only report missing cards played by at "
                         "least this share of the reference set, e.g. 0.5")
    ap.add_argument("--keep-duplicates", action="store_true",
                    help="treat identical files as separate data points")
    ap.add_argument("--on-draw", action="store_true",
                    help="probabilities for the player on the draw (default: on the play)")
    ap.add_argument("--x-value", type=int, default=roles.X_VALUE,
                    help=f"what {{X}} is costed at (default {roles.X_VALUE})")
    ap.add_argument("--miracle", action="store_true",
                    help="cost miracle cards at their miracle cost. Off by "
                         "default: miracle depends on draw order. Turn it on "
                         "if you genuinely never hardcast them.")
    ap.add_argument("--json", action="store_true", help="emit JSON instead of tables")
    args = ap.parse_args(argv)

    if not args.dir and not args.deck:
        ap.error("give me something to analyse: --dir and/or --deck")

    # Both sources reduce to {name, cards}, so profiling and ranking happen
    # once over one list of decks rather than twice over two shapes.
    decks = []
    for path in args.dir:
        if not path.is_dir():
            print(f"ERR not a directory: {path}", file=sys.stderr)
            return 2
        decks.extend(read_dir(path, args.keep_duplicates))
    for name in args.deck:
        try:
            decks.append(read_db_deck(name, args.folder))
        except services.ServiceError as e:
            print(f"ERR {e}", file=sys.stderr)
            return 2

    subject = None
    if args.compare:
        try:
            subject = read_subject(args.compare, args.folder)
        except services.ServiceError as e:
            print(f"ERR {e}", file=sys.stderr)
            return 2
        # A deck cannot be its own reference: comparing a list to itself
        # reports zero deviation and hides the ones that matter.
        before = len(decks)
        decks = [d_ for d_ in decks if d_["name"] != subject["name"]]
        if len(decks) < before:
            print(f"(excluded {subject['name']!r} from the reference set)",
                  file=sys.stderr)
        if not decks:
            print("ERR --compare needs a reference set: add --dir or another "
                  "--deck", file=sys.stderr)
            return 2

    if not decks:
        print("nothing to analyse", file=sys.stderr)
        return 1

    profiles = services.profile_decks(decks, x_value=args.x_value,
                                  miracle=args.miracle)
    args.ranking, args.low_confidence = services.rank_cards(decks, REPORT_ROLES)

    comparison = None
    if subject is not None:
        comparison = services.compare_decks(
            subject, decks, turns=TURNS, on_play=not args.on_draw,
            min_share=args.min_share, x_value=args.x_value,
            miracle=args.miracle)

    if args.json:
        print(json.dumps({
            "lists": [{"name": p.name, "size": p.size, "counts": p.counts,
                       "role_mv": {r: {str(k): v for k, v in mvs.items()}
                                   for r, mvs in p.role_mv.items() if mvs},
                       "curve": {str(k): v for k, v in sorted(p.curve.items())},
                       "lands": p.lands, "rocks": p.rocks,
                       "land_backs": p.land_backs,
                       "mana_sources": p.mana_sources,
                       "avg_mv": round(p.avg_mv, 3),
                       "unresolved": list(p.unresolved),
                       "on_curve": {r: {str(t): round(v, 4) for t, v in
                                        p.live_curve(r, TURNS, not args.on_draw).items()}
                                    for r in REPORT_ROLES},
                       "ceiling": {r: {str(t): round(v, 4) for t, v in
                                       p.ceiling(r, TURNS, not args.on_draw).items()}
                                   for r in REPORT_ROLES}}
                      for p in profiles],
            "ranking": args.ranking,
            "low_confidence": sorted(args.low_confidence),
            "comparison": comparison_json(comparison) if comparison else None,
            "conventions": {
                "x_value": args.x_value,
                "delve_yard": roles.DELVE_YARD,
                "cantrip_max_mana": roles.CANTRIP_MAX_MANA,
                "miracle_costed": args.miracle,
                "castable_second_faces": sorted(roles.CASTABLE_SECOND_FACE),
                "on_play": not args.on_draw,
            },
        }, indent=1, default=str))
        return 0

    print_report(profiles, args)
    if comparison is not None:
        print_comparison(comparison, args)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
