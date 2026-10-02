# MTG Oracle

A local-first Magic: The Gathering workbench: a desktop app to look up cards, rules and combos, build decks with Scryfall-style search, analyse them against reference lists, and play them against Forge's AI on a board of its own. Everything works over one SQLite database of cards, rulings, the Comprehensive Rules, Commander Spellbook combos and your own decks.

It runs offline after the first sync. There is no network at query time, and card art is fetched once and cached.

The app (`app/`) is Kotlin and Compose Desktop, with Forge embedded ([ADR 0001](docs/adr/0001-standalone-jvm-app-with-embedded-forge.md)). It owns everything:
- the schema and its migrations;
- the data sync;
- the library and the deck workspace;
- lookups and analysis;
- play, recorded game by game;
- a command line for scripts.

It replaced a Python TUI and CLI in October 2026. That code lives at the git tag `python-final`.

## What's in the box

- **Cards.** 35k+ unique cards from the Scryfall bulk export: mana cost, colours, mana value, power/toughness, rarity, layout, and per-face data for DFCs, split and flip cards.
- **Rulings.** 78k+ official rulings, joined to cards by `oracle_id`.
- **Comprehensive Rules.** Every numbered rule (3,300+), parsed from the Wizards `.txt` release with parent and section links.
- **Combos.** 110k+ Commander Spellbook combos, with their cards, prerequisites, results and step-by-step play. Your own combos go alongside them (`combo add`).
- **Format legality.** Every card's status (`legal` / `restricted` / `banned`) in all 23 formats Scryfall tracks, plus which games it was printed in and its EDHREC rank. It is searchable and enforced per deck. `duel` *is* Duel Commander and `tlr` is Tiny Leaders: Reborn.
- **Community formats with points.** Canadian Highlander's points list, with the 10-point cap enforced on `add`. The definitions are curated JSON in [`data/formats/`](data/formats/). Legality is inherited from the Scryfall format they share a ban list with.
- **Tags.** Scryfall Tagger's oracle tags (what a card *does*: removal, ramp, a tutor), and locally derived ones:
  - CR 702 keywords;
  - types;
  - per-ability `has_target` / `produces_mana` / `is_mana_ability` (CR 605.1a/b).
- **Decks.** Folders, decks, sideboards, a considering list, and a full history in which every change can be undone. You can import from any common list format and export back.
- **Analysis.** What each card does and what it really costs, curves by role, and the chance of each role being castable on curve. You can also compare a deck with a reference set: where it steps outside the set's ranges, and the cards the set plays that the deck lacks.
- **Play.** Your decks against Forge's AI, or the AI against itself (simulated without a board), recorded in `games`. The AI can play a substituted copy of a deck.
- **Feedback loop.** A `corrections` table that keeps factual mistakes from coming back. It is attached to card lookups automatically.

## Quick start

Requirements: JDK 25, and Forge in `tools/forge-dc/` (git-ignored, GPL-3).

Forge is, for now, a build of our Duel Commander branch (Card-Forge/forge#12090), because no release plays Duel Commander yet. In the fork's worktree (`D:/Projekt/forge-dc/forge-verify`), on the branch's committed head, run `mvn -pl forge-gui-desktop -am package -DskipTests -Dcheckstyle.skip` and then `python D:/Projekt/forge-dc/tools/stage_for_mtg_oracle.py D:/Projekt/forge-dc/forge-verify tools/forge-dc`. That copies the release layout the app builds against: the desktop jar, `res/`, and the zipped card scripts. `tools/forge-dc/build.txt` names the commit. Restage it whenever the branch moves. `-PforgeDir=tools/forge` builds against an unpacked Forge 2.0.14 release instead, which has no Duel Commander. Once Forge releases Duel Commander, the app goes back to a release.

```bat
cd app
gradlew :app:installLocal       :: builds a snapshot into app\dist\<timestamp>\
run-mtg-oracle.cmd              :: plays the newest snapshot (run-mtg-oracle.sh elsewhere)
```

On a first start there is no database. The app creates an empty one and asks you to press **[ Sync ]**. That fetches the cards, rulings, rules, combos, tags and printings, which takes a few minutes and mostly goes on Spellbook's 600 MB export and Scryfall's 80 MB of printings. After that, [ Sync ] (or `sync` on the command line) fetches only what moved upstream.

Play from the snapshot, not `gradlew :app:run`. A build replaces the class files under a running game. Each snapshot is stamped with its git hash, which the window title shows, and the three newest are kept.

### The command line

`app\mtg.cmd` runs the newest snapshot's command line. Its text output is what the app's output pane shows, and `--json` gives structured output for scripts:

```bat
mtg.cmd card "Deathrite Shaman"
mtg.cmd search kw:flying c:u t:creature mv<=3 --json
mtg.cmd profile "Duel Commander"
mtg.cmd compare "Duel Commander/Elminster Boomer Wizard" --against "Duel Commander" --json
mtg.cmd sync
mtg.cmd help
```

The first `mtg.cmd sync` of a new install creates the database too.

### Schema and backups

The schema version is `PRAGMA user_version` ([`Schema.kt`](app/data/src/main/kotlin/mtgoracle/data/Schema.kt)). The app migrates the database at start, after a backup in `data/backups/` (the three newest are kept).

Two Gradle tasks work on the schema alone:
- `gradlew :app:migrate` migrates and does nothing else;
- `gradlew :app:checkSchema` checks without writing.

A database the old Python migrations made (version 0) is checked against version 1 and adopted unchanged. If one is missing part of version 1, the app stops and names what is missing. The fix is to run `python scripts/self_heal.py` from the `python-final` tag.

## In the app

The library lists your folders and decks, with the selected deck's analysis above it. Enter (or `cd <deck>`) opens a deck in the workspace: the deck beside a search, its considering list, its history and the AI's copy. The command line (`:` or Ctrl+K) does the rest. `help` lists every command, and `help search` gives the search syntax. In outline:

| | |
|---|---|
| Lookup | `card`, `ruling`, `rule`, `search-rules`, `correction` |
| Search | `search <query>`, or any line that is no command; `next` / `prev` / `page N` |
| Combos | `combo <card>`, `combos A; B`, `combo-info <id>`, `combo add` / `combo remove` |
| Decks | `cd`, `add [--sb] [--force]`, `remove`, `consider`, `commander`, `undo`, `history` |
| Analysis | `profile [<deck>\|<folder>]`, `compare <deck>\|<folder>` |
| Data | `sync [--force] [<source> ...]`, `prune [--yes]` |
| Games | `results [<deck>]` |

A card name in any output opens its profile when clicked, and shows the card in the zoom pane on hover.

### Search

```
o:"enters the battlefield" t:creature c:u mv<=3
kw:flying (c:w or c:u) -t:artifact
ci<=BG t:creature order:asc_mv
f:canlander t:land order:desc_mv
otag:removal c:w mv<=2
```

The search language is Scryfall's, minus printings:
- **Free text.** A bare word or a quoted phrase matches the name, the type line or the oracle text, with name matches first.
- **Fields:** `o:` `t:` `n:` `n=` `kw:` `c:` `c=` `ci:` `mv:` `pow:` `tou:` `r:` `m:` `layout:` `otag:` `is:` `f:` `banned:` `restricted:` `game:`.
- **Comparisons and logic:** comparisons on the numeric fields, plus `or`, `-`, and parentheses.
- **Sorting:** `order:asc_FIELD` / `order:desc_FIELD`.

Inside a deck, a search finds only what the deck can play: its commander's colour identity and its format.

Card names are forgiving: `lim-duls vault`, `thassas oracle`, `fire/ice` and `delver of secrets` all resolve.

### Format-aware decks

A deck's format is the switch for every rule. With a format and a commander, `add` checks:
- colour identity;
- legality, banned and pool;
- singleton, with basics and "any number" cards exempt;
- the points budget.

Each refusal names its reason, and `--force` overrides for one call. A list import is always loaded verbatim. Replacing a deck from a list keeps its commander when the list has no Commander section.

## Data sources

| Source | Fetch | Cadence | Skipped when unchanged by |
|---|---|---|---|
| Cards + rulings | Scryfall `oracle_cards` + `rulings` bulk, gzipped JSON Lines | daily | `updated_at` |
| Comprehensive Rules | the latest `.txt` linked from `magic.wizards.com/en/rules` | ~6 a year | release date in the URL |
| Combos | Commander Spellbook `variants.json` | weekly | HTTP ETag |
| Oracle tags | Scryfall Tagger bulk | daily | `updated_at` |
| Local tags | derived from `oracle_text` and the type line | every sync | rebuilt (fast) |
| Community formats | curated JSON in `data/formats/` | when the list changes | rebuilt |
| Printings (card art) | Scryfall `default_cards` bulk the first time, then only the sets whose card count moved (`/sets` + search) | as sets release | each set's card count |

## Development

Build and test from `app/`:

```bat
gradlew test                    :: every module
gradlew :app:installLocal       :: the snapshot to play
gradlew :app:run                :: the window from the build outputs, for development only
gradlew :app:cli -Pargs="card Sol Ring --json"
gradlew :app:scriptedGame -Pdata=<dir with a DB copy>
```

`-Pdata=<dir>` points any task at another data directory, relative to the repo root.

The tests never write `data/mtg.db`. Most of them run on a frozen fixture instead (`app/data/src/testFixtures/fixture/`):
- a 3,000-card cut of the upstream exports;
- the reference decklists;
- the answers the Python original gave on that data.

The app's own sync builds a fresh database from the fixture for each test run. The tests that read the user's own database skip when it is absent.

## Project layout

```
mtg-oracle/
├── CLAUDE.md                     Project rules for Claude Code (read first)
├── CHANGELOG.md                  Per-feature history (Keep a Changelog)
├── app/                          Gradle build, five modules, one-way dependencies
│   ├── core/                     Plain data and pure logic: board and prompts, decks and their rules,
│   │                             the search language, the analysis engine
│   ├── data/                     data/mtg.db over JDBC: schema, lookups, search SQL, deck writes, sync
│   ├── forge/                    Every Forge import: runtime, the seat, printings, images, matches
│   ├── ui/                       The house-style kit and the screens
│   ├── app/                      Entry point, wiring, the window, the command line, headless modes
│   ├── mtg.cmd                   The command line, from the newest snapshot
│   └── run-mtg-oracle.cmd / .sh  Play the newest snapshot
├── docs/
│   ├── project-plan.md           Where we are (read first)
│   ├── app-design.md             The look, the board, the interaction model
│   ├── feature-parity.md         The port from Python, row by row (history)
│   ├── adr/                      Architecture decisions
│   └── reports/                  Deck-analysis reports (Swedish) and their reference decklists
├── data/
│   ├── formats/                  Community-format definitions (tracked)
│   ├── raw/, backups/, app/, game_logs/   Downloads, backups, the app's own files (git-ignored)
│   └── mtg.db                    The database (git-ignored)
└── tools/forge-dc/               Forge from the Duel Commander branch, staged (git-ignored)
```

## Conventions

- All SQL is parameterised. Reads use a read-only connection. Writes happen only through the data layer's writers (the sync and prune, DeckWriter and LibraryWriter, game records, substitutions, user combos), one transaction each.
- Card names are case-insensitive everywhere, and diacritics, ligatures and apostrophes fold.
- Don't fetch from Scryfall, Wizards or Spellbook at query time: the sync is the refresh path. Don't edit `data/raw/`, which every sync overwrites.
- Spellbook is comprehensive for known combos, not exhaustive. Your own combos fill the gap.

See [`CHANGELOG.md`](CHANGELOG.md) for the history and [`docs/project-plan.md`](docs/project-plan.md) for what's next.
