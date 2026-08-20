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
    print(f"{'role':<26}{'primary':>8}{'reach':>7}{'diff':>7}{'engines':>9}")
    for role in REPORT_ROLES:
        prim = st.mean([p.counts.get(role, 0) for p in profiles])
        reach = st.mean([sum(p.role_mv.get(role, {}).values()) for p in profiles])
        eng = st.mean([p.engines.get(role, 0) for p in profiles])
        print(f"{roles.LABELS[role]:<26}{prim:>8.1f}{reach:>7.1f}"
              f"{reach - prim:>+7.1f}{(f'{eng:.1f}' if eng else '-'):>9}")
    print("  (engines = permanents that keep producing the effect rather than "
          "resolving once;\n   a planeswalker that draws every turn is not "
          "interchangeable with Memory Deluge)")

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


def _arrow(delta: float) -> str:
    if delta > 0:
        return f"+{delta:g}"
    return f"{delta:g}" if delta else "="


def print_comparison(cmp, args) -> None:
    """The comparison tables: ranges, curve deltas, and the card-level diff."""
    subj, refs = cmp.subject, cmp.reference
    n = len(refs)
    print(f"\n\n{'=' * 76}")
    print(f"COMPARISON — {subj.name!r} against {n} reference list(s)")
    print("=" * 76)
    if subj.size != 100:
        print(f"  note: subject is {subj.size} cards; the draw maths still "
              f"treats the deck as 100, so the unfilled slots count as blanks")

    print(f"\n=== IN RANGE? (range, not mean — a range nobody left is the rule) ===")
    print(f"{'role':<26}{'yours':>7}{'ref mean':>10}{'range':>10}{'delta':>8}   verdict")
    order = {role: i for i, role in enumerate(REPORT_ROLES + ["land"])}
    for r in sorted(cmp.roles, key=lambda r: order.get(r.role, 99)):
        mark = {"under": "<-- BELOW every list", "over": "--> ABOVE every list",
                "in": ""}[r.verdict]
        print(f"{roles.LABELS[r.role]:<26}{r.subject:>7}{r.ref_mean:>10.1f}"
              f"{f'{r.ref_min}-{r.ref_max}':>10}{_arrow(r.delta):>8}   {mark}")
    m = cmp.mana_sources
    print(f"{'MANA SOURCES':<26}{m.subject:>7}{m.ref_mean:>10.1f}"
          f"{f'{m.ref_min}-{m.ref_max}':>10}{_arrow(m.delta):>8}   "
          + {"under": "<-- BELOW every list", "over": "--> ABOVE every list",
             "in": ""}[m.verdict])
    mine_mv, ref_mv = cmp.avg_mv
    print(f"{'avg effective MV':<26}{mine_mv:>7.2f}{ref_mv:>10.2f}"
          f"{'':>10}{_arrow(round(mine_mv - ref_mv, 2)):>8}")

    out = cmp.out_of_range
    print(f"\n  {len(out)} role(s) outside the reference range"
          + (f": {', '.join(roles.LABELS[r.role] for r in out)}" if out else
             " — this deck sits inside the archetype on every axis"))

    # A reference set spanning two archetypes has a mean that describes
    # neither, so say so rather than letting the delta column imply there is
    # one right answer to be closer to.
    widest = max(cmp.roles, key=lambda r: r.ref_max - r.ref_min)
    if widest.ref_max - widest.ref_min >= 6:
        print(f"\n  CAUTION: the reference set spans {widest.ref_min}-"
              f"{widest.ref_max} on {roles.LABELS[widest.role].lower()}, so it "
              f"holds more than one\n  build and the mean above describes "
              f"neither. Use the nearest-list line, or re-run\n  with only the "
              f"lists you actually want to resemble.")

    print(f"\n=== NEAREST REFERENCE LIST (role-density distance) ===")
    for name, dist in cmp.nearest[:5]:
        print(f"   {dist:>6.2f}  {name}")
    print("   (lower is more alike; the axis with the widest spread in the "
          "reference set dominates, which is the axis that defines the build)")

    label = "on the draw" if args.on_draw else "on the play"
    print(f"\n=== ON CURVE, yours minus the reference mean ({label}) ===")
    print(f"{'role':<26}" + "".join(f"{'T'+str(t):>7}" for t in TURNS))
    for role in REPORT_ROLES:
        d = cmp.curve_delta.get(role, {})
        cells = "".join(f"{d.get(t, 0)*100:>+6.0f}p" for t in TURNS)
        print(f"{roles.LABELS[role]:<26}{cells}")
    print("  (percentage points; + means this deck does it more often)")

    if cmp.missing:
        shown = [c for c in cmp.missing if c.n_lists > 1] or list(cmp.missing)
        print(f"\n=== CARDS THE REFERENCE PLAYS THAT THIS DECK DOESN'T "
              f"({len(cmp.missing)}) ===")
        for role in REPORT_ROLES + ["land"]:
            g = [c for c in shown if c.role == role]
            if not g:
                continue
            print(f"\n  -- {roles.LABELS[role]}")
            for c in g:
                print(f"       {c.n_lists:>2}/{c.of_lists}  MV{c.mv:<3} {c.name}")
        if len(shown) < len(cmp.missing):
            print(f"\n  ({len(cmp.missing) - len(shown)} more played by exactly "
                  f"one list — pass --min-share 0 and read the JSON for those)")

    if cmp.unique:
        print(f"\n=== CARDS ONLY THIS DECK PLAYS ({len(cmp.unique)}) ===")
        print("  Not a criticism — this is where your build is its own thing.")
        for c in cmp.unique:
            print(f"       {roles.LABELS[c.role]:<26} MV{c.mv:<3} {c.name}")


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
    args.ranking, args.low_confidence = build_ranking(decks, len(profiles))

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
