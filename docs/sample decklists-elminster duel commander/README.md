# Elminster reference lists (Duel Commander)

Other people's builds of the same deck. Kept apart from
[`../sample decklists-duel commander 2026/`](../sample%20decklists-duel%20commander%202026/)
on purpose, because the two folders answer different questions:

| folder | question | how to read a deviation |
|---|---|---|
| `sample decklists-duel commander 2026` | **what am I playing against?** | a gap is a hole in the gameplan |
| `sample decklists-elminster duel commander` | **how do other people build this?** | a gap is a build choice, mine or theirs |

Mixing them would average a control deck's counterspell count against a
mono-red burn deck's and describe neither — the meta folder already warns
about that when its threat range spans 1–35.

## Dropping a list in

One `.txt` per list, any of the usual export shapes. `deck_parser` tolerates
leading counts (`1 Elminster`), trailing counts (`Elminster x1`), set codes
and collector numbers (`(WOE) 123`), foil markers, `#` / `//` comments, and
the section headers `Commander` / `Deck` / `Sideboard` under all their common
aliases — so a Moxfield, Archidekt or mtgtop8 export pastes in unedited.

Identical files are collapsed by `read_dir`, so a list saved twice costs
nothing. Pass `--keep-duplicates` if two pilots really did register the
same 100 cards and you want both counted.

## Naming

`YYYYMMDD-commander-archetype-placement-event.txt`, matching the meta folder:

```
20260816-elminster-uw-control-1st-bordeaux.txt
20260818-elminster-esper-2nd-mtgo.txt
```

The date is the event, not the download. Placement and event are what make a
list evidence rather than an opinion — an unplaced list from an unnamed source
is still useful, just say so in the filename (`-unplaced-reddit`).

## Running it

```
python scripts/analyse_archetype.py --dir "docs/sample decklists-elminster duel commander"
python scripts/analyse_archetype.py --dir "docs/sample decklists-elminster duel commander" \
       --compare "Elminster Boomer Wizard"
```

Three lists is enough for a range to mean something; below that the "range
nobody left" verdict is noise. Watch the CAUTION line — if it fires here it
means the Elminster lists themselves disagree about what the deck is, which
is worth knowing before treating any of them as a target.
