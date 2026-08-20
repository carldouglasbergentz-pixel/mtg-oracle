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
import statistics as st
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from mtg_oracle import roles, services  # noqa: E402
from mtg_oracle.deck_parser import parse_deckstring  # noqa: E402

TURNS = list(range(1, 9))
REPORT_ROLES = ["counter", "spot", "sweeper", "cantrip", "draw", "threat",
                "tutor", "utility", "mana"]


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
    ids = [p.name for p in profiles]
    n = len(profiles)

    print(f"\n{n} list(s): {', '.join(ids)}")
    sizes = {p.name: p.size for p in profiles}
    odd = {k: v for k, v in sizes.items() if v != 100}
    if odd:
        print(f"  note: not 100 cards -> {odd}")
    for p in profiles:
        if p.unresolved:
            print(f"  {p.name}: {len(p.unresolved)} unresolved -> "
                  f"{', '.join(p.unresolved[:6])}")
    # Cards the text rules could not place. On a familiar archetype this is
    # empty; on your own deck it is the list worth a second look.
    unsure = sorted(args.low_confidence)
    if unsure:
        print(f"\n  {len(unsure)} card(s) fell through to 'utility' — "
              f"classification worth checking:")
        for name in unsure:
            print(f"    {name}")

    print("\n=== DENSITY (primary role; sums to deck size) ===")
    head = f"{'role':<26}{'mean':>6}{'med':>5}{'min':>5}{'max':>5}   "
    print(head + " ".join(f"{i[-6:]:>6}" for i in ids))
    for role in ["counter", "spot", "sweeper", "cantrip", "draw", "threat",
                 "tutor", "utility", "mana", "land"]:
        vals = [p.counts.get(role, 0) for p in profiles]
        print(f"{roles.LABELS[role]:<26}{st.mean(vals):>6.1f}{st.median(vals):>5.0f}"
              f"{min(vals):>5}{max(vals):>5}   "
              + " ".join(f"{v:>6}" for v in vals))
    src = [p.mana_sources for p in profiles]
    print(f"{'MANA SOURCES':<26}{st.mean(src):>6.1f}{st.median(src):>5.0f}"
          f"{min(src):>5}{max(src):>5}   " + " ".join(f"{v:>6}" for v in src))
    mv = [p.avg_mv for p in profiles]
    print(f"{'avg effective MV':<26}{st.mean(mv):>6.2f}{'':>5}{min(mv):>5.2f}{max(mv):>5.2f}   "
          + " ".join(f"{v:>6.2f}" for v in mv))

    print("\n=== REACH (every role a card can fill, not just its primary) ===")
    print(f"{'role':<26}{'primary':>8}{'reach':>7}{'diff':>7}")
    for role in REPORT_ROLES:
        prim = st.mean([p.counts.get(role, 0) for p in profiles])
        reach = st.mean([sum(p.role_mv.get(role, {}).values()) for p in profiles])
        print(f"{roles.LABELS[role]:<26}{prim:>8.1f}{reach:>7.1f}"
              f"{reach - prim:>+7.1f}")

    label = "on the draw" if args.on_draw else "on the play"
    print(f"\n=== ON CURVE — role is playable on turn T, {label} ===")
    print(f"{'role':<26}" + "".join(f"{'T'+str(t):>7}" for t in TURNS))
    for role in REPORT_ROLES:
        curves = [p.live_curve(role, TURNS, on_play=not args.on_draw)
                  for p in profiles]
        means = [st.mean([c[t] for c in curves]) for t in TURNS]
        print(f"{roles.LABELS[role]:<26}"
              + "".join(f"{m*100:>6.0f}%" for m in means))

    print(f"\n=== CEILING — holding one at all, mana ignored ===")
    print(f"{'role':<26}" + "".join(f"{'T'+str(t):>7}" for t in TURNS)
          + f"{'gap T4':>8}")
    for role in REPORT_ROLES:
        ceil = [p.ceiling(role, TURNS, on_play=not args.on_draw) for p in profiles]
        live = [p.live_curve(role, TURNS, on_play=not args.on_draw) for p in profiles]
        means = [st.mean([c[t] for c in ceil]) for t in TURNS]
        gap = means[3] - st.mean([c[4] for c in live])
        print(f"{roles.LABELS[role]:<26}"
              + "".join(f"{m*100:>6.0f}%" for m in means)
              + f"{gap*100:>7.0f}p")

    print("\n=== MOST PLAYED per role (sorted by effective mana value) ===")
    for role in REPORT_ROLES:
        rows = args.ranking.get(role, [])
        if not rows:
            continue
        print(f"\n  -- {roles.LABELS[role]} --")
        last = None
        for r in rows:
            if r["mv"] != last:
                print(f"     MV {r['mv']}")
                last = r["mv"]
            tag = "" if r["primary"] else "  (secondary)"
            why = f"   [{r['reason']}]" if r["reason"] else ""
            print(f"       {r['n']:>2}/{n}  {r['name'][:44]:<46}"
                  f"{r['cost']:<16}{tag}{why}")


def read_db_deck(name: str, folder: str | None) -> dict:
    """One of the user's own decks, in the same shape as a decklist file."""
    from mtg_oracle import decks as dk
    deck = dk.get_deck(name, folder=folder)
    if not deck:
        raise services.ServiceError(f"no deck named {name!r}")
    cards: dict[str, int] = {}
    for row in deck.get("cards", []):
        if row.get("is_sideboard"):
            continue
        cards[row["card_name"]] = cards.get(row["card_name"], 0) + row["quantity"]
    return {"name": deck["name"], "cards": cards, "files": [deck["name"]]}


def build_ranking(decks, n) -> dict:
    """card -> how many lists play it, per role, sorted by effective cost."""
    from mtg_oracle import queries as q
    all_names = sorted({name for d in decks for name in d["cards"]})
    facts = q.get_card_facts(all_names)
    counts = Counter()
    for d in decks:
        for name in d["cards"]:
            counts[name] += 1

    out: dict[str, list] = {r: [] for r in REPORT_ROLES}
    low: set[str] = set()
    for name in all_names:
        fact = facts.get(name)
        if fact is None or roles.is_land(fact):
            continue
        cl = roles.classify(fact)
        if cl.low_confidence:
            low.add(name)
        for role in cl.roles:
            if role not in out:
                continue
            out[role].append({
                "name": name, "n": counts[name],
                "pct": round(100 * counts[name] / n),
                "mv": cl.cost.effective, "printed": cl.cost.printed,
                "cost": fact.get("mana_cost") or "",
                "primary": cl.primary == role,
                "reason": cl.cost.reason if cl.cost.adjusted else "",
                "source": cl.source,
            })
    for role in out:
        out[role].sort(key=lambda r: (r["mv"], -r["n"], r["name"]))
    return out, low


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dir", type=Path, action="append", default=[],
                    help="folder of decklist .txt files (repeatable)")
    ap.add_argument("--deck", action="append", default=[],
                    help="a deck from your own collection (repeatable)")
    ap.add_argument("--folder", help="folder the --deck lives in")
    ap.add_argument("--keep-duplicates", action="store_true",
                    help="treat identical files as separate data points")
    ap.add_argument("--on-draw", action="store_true",
                    help="probabilities for the player on the draw (default: on the play)")
    ap.add_argument("--x-value", type=int, default=roles.X_VALUE,
                    help=f"what {{X}} is costed at (default {roles.X_VALUE})")
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

    if not decks:
        print("nothing to analyse", file=sys.stderr)
        return 1

    profiles = services.profile_decks(decks, x_value=args.x_value)
    args.ranking, args.low_confidence = build_ranking(decks, len(profiles))

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
            "conventions": {
                "x_value": args.x_value,
                "delve_yard": roles.DELVE_YARD,
                "cantrip_max_mana": roles.CANTRIP_MAX_MANA,
                "castable_second_faces": sorted(roles.CASTABLE_SECOND_FACE),
                "on_play": not args.on_draw,
            },
        }, indent=1, default=str))
        return 0

    print_report(profiles, args)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
