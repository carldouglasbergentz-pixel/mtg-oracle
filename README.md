# MTG Oracle

A local-first Magic: The Gathering workbench for Windows. Look up cards, rules and combos; build decks with Scryfall-style search; analyse them against reference lists; and play them against Forge's AI, or against a friend over the internet, on a board of its own. Everything works over one SQLite database of cards, rulings, the Comprehensive Rules, Commander Spellbook combos and your own decks.

Lookups never go to the network: the data is synced once a day and card art is fetched once and cached. The network is used for that sync, for checking for a new version, and for playing a friend.

## Getting the app

Download `MTG-Oracle-<version>-windows-x64.zip` from the release, unpack it anywhere and start **`MTG Oracle.exe`**. It brings its own Java; nothing else needs installing. Everything it keeps (the database, settings, logs, card images, exports) lives in `data\` beside the exe, so the folder can be moved or backed up as it is.

On a first start the database is empty: press **[ Sync ]** (or let the daily sync do it within a minute). The first sync downloads about 800 MB, most of it Commander Spellbook's combos and Scryfall's printings, and takes a few minutes; after that only what moved upstream is fetched.

**Updating.** The app looks for a newer release a few seconds after it starts, and `update` installs it: the download is checked against its `.sha256`, and `data\` is never touched. No login is needed. By hand: unpack the new zip over the old folder, keeping `data\`.

`mtg.exe`, beside the window's exe, is the command line (see below).

## What it does

### Your library

- **Folders and decks** in the library, the selected deck's analysis above them. **Enter** (or `cd <deck>`) opens a deck in the workspace: the deck beside a search, its considering list, its history and the AI's copy.
- **Lists in and out.** Import from any common decklist format, export back, MTGO and Arena included. A list import is loaded verbatim.
- **History.** Every change to a deck is one revision, and `undo` takes it back (undo of undo is redo).
- **Printings.** A card can be set to the printing you own; its art follows on the board too.
- **Format-aware decks.** A deck's format is the switch for every rule. With a format and a commander, `add` checks colour identity, legality (banned and pool), singleton (basics and "any number" cards exempt) and a points budget (Canadian Highlander). Each refusal names its reason, and `--force` overrides for one call. `duel` *is* Duel Commander, `tlr` Tiny Leaders: Reborn.
- **Packages.** A `.mtgoracle` package carries decks with everything they hold (history, printings, the AI copy, the considering list), the games played with them and your own combos. Right-click a deck or a folder to export one; copy a package and press **Import**, or put it in `data\import\` for the next start. The import asks first, overwrites nothing, and takes a backup before it writes.

### Looking things up

The command line (`:` or Ctrl+K) does the lookups; `help` lists every command and `help search` the search syntax.

| | |
|---|---|
| Lookup | `card`, `ruling`, `rule`, `search-rules`, `correction` |
| Search | `search <query>`, or any line that is no command; `next` / `prev` / `page N` |
| Combos | `combo <card>`, `combos A; B`, `combo-info <id>`, `combo add` / `combo remove` |
| Decks | `cd`, `add [--sb] [--force]`, `remove`, `consider`, `commander`, `undo`, `history` |
| Analysis | `profile [<deck>\|<folder>]`, `compare <deck>\|<folder>` |
| Games | `results [<deck>]` |
| The app | `sync`, `update`, `guide`, `prune` |

A card name in any output opens its profile when clicked, and shows the card in the zoom pane on hover. Card names are forgiving: `lim-duls vault`, `thassas oracle` and `fire/ice` all resolve.

**Search** is Scryfall's language, minus printings:

```
o:"enters the battlefield" t:creature c:u mv<=3
kw:flying (c:w or c:u) -t:artifact
ci<=BG t:creature order:asc_mv
f:canlander t:land order:desc_mv
otag:removal c:w mv<=2
```

Free text matches the name, type line or oracle text; fields are `o:` `t:` `n:` `n=` `kw:` `c:` `c=` `ci:` `mv:` `pow:` `tou:` `r:` `m:` `layout:` `otag:` `is:` `f:` `banned:` `restricted:` `game:`, with comparisons, `or`, `-`, parentheses and `order:`. Inside a deck, a search finds only what the deck can play.

### Analysis

What each card does and what it really costs, curves by role, and the chance of each role being castable on curve. `compare` sets a deck against one deck (head to head) or a folder of reference lists: where it steps outside the set's ranges, and the cards the set plays that it lacks.

### Playing

The **lobby** pairs your deck with an opponent of the same game type and sets the match: best of 1, 3 or 5, with sideboarding between games.

- **Against Forge's AI.** The AI plays its *AI copy* of a deck when it has one: cards it can't play swapped for substitutes. In the deck builder, a card the AI won't play or Forge lacks is flagged, and its `[!]` goes straight to choosing a substitute.
- **Watch** the AI play your deck, or **simulate** a number of AI-vs-AI games without a board, each recorded.
- **The board** is a table of type zones in the app's own style, with card art and a zoom pane, a floating stack, phase stops (F2 pass, F4 end turn, F6 skip the turn), a game log of the whole match, and nothing hidden shown to you that a real table wouldn't.
- **Results.** Every game is recorded; `results` and the lobby show your record against each deck, played and simulated apart.
- **Achievements.** Forge's achievements, earned as you play, in a view of their own.

### Playing a friend

Each on your own computer. Choose your deck in the lobby, then under **network play**:

- **Host a room.** Your router opens a port to your computer (UPnP), and you get an invite (`MTG-…`, put on the clipboard) to send your friend. Windows may ask whether the app may accept connections: allow it. The match format is yours.
- **Join.** Copy the invite you were sent, then **Join from the clipboard**.

Only the host needs a router that lets someone in; the guest connects outward, which every network allows. When hosting can't work the lobby says why (UPnP turned off, another router in front of yours, or a provider that shares one address among its customers): then let your friend host. Everything between the two apps is encrypted with a secret only the invite holds, and a room lets in no one without it. Your name at a table is set in the lobby. Both sides record the games, the opponent as `deck (person)`; the host's game log keeps the whole match, for going through it afterwards.

### Making it yours

- **Playmats.** A picture of your own under your half of the table, and one under the AI's. Add one from the clipboard in the lobby (a picture, or its file copied in Explorer), or put pictures in `data\playmats\`. Drag the preview to place it, zoom up to 300 %, and set a dim to keep the cards readable. At a network table your mat goes along as pixels, never as a file, and the other's shows only if you choose.
- **Looks.** F8 picks a theme (several, a Windows 95 among them), F7 switches card art and text, Ctrl+= / Ctrl+- size everything.
- **Getting started.** A first start shows a checklist from no card data to a first game, a short tour of the library, and a few tips over a first game's table. **Guide** in the toolbar (or `guide`) brings it back.

### The command line

`mtg.exe` (`app\mtg.cmd` in a development checkout) runs the same commands as the window's command line, and `--json` gives structured output for scripts:

```bat
mtg card "Deathrite Shaman"
mtg search kw:flying c:u t:creature mv<=3 --json
mtg profile "Duel Commander"
mtg compare "Duel Commander/Elminster Boomer Wizard" --against "Duel Commander" --json
mtg sync
mtg help
```

## What's in the data

- **Cards.** 35k+ unique cards from Scryfall: costs, colours, types, per-face data for two-faced cards, legality in all 23 formats Scryfall tracks, which games they are on (every printing's), EDHREC rank.
- **Printings.** Every paper printing, for choosing a card's art.
- **Rulings.** 78k+ official rulings.
- **Comprehensive Rules.** Every numbered rule, with parent and section links.
- **Combos.** 110k+ Commander Spellbook combos with their cards, prerequisites, results and steps; your own combos beside them.
- **Tags.** Scryfall Tagger's oracle tags (what a card *does*: removal, ramp, a tutor), and locally derived keywords, types and abilities.
- **Community formats with points.** Canadian Highlander's list, curated in [`data/formats/`](data/formats/).
- **Corrections.** A table of factual mistakes, so they don't come back.

| Source | Fetch | Cadence | Skipped when unchanged by |
|---|---|---|---|
| Cards + rulings | Scryfall `oracle_cards` + `rulings` bulk, gzipped JSON Lines | daily | `updated_at` |
| Comprehensive Rules | the latest `.txt` linked from `magic.wizards.com/en/rules` | ~6 a year | release date in the URL |
| Combos | Commander Spellbook `variants.json` | weekly | HTTP ETag |
| Oracle tags | Scryfall Tagger bulk | daily | `updated_at` |
| Local tags | derived from `oracle_text` and the type line | every sync | rebuilt (fast) |
| Community formats | curated JSON in `data/formats/` | when the list changes | rebuilt |
| Printings | Scryfall `default_cards` bulk the first time, then only the sets whose card count moved | as sets release | each set's card count |

## Building from source

The app (`app/`) is Kotlin and Compose Desktop with Forge embedded ([ADR 0001](docs/adr/0001-standalone-jvm-app-with-embedded-forge.md)); network play is [ADR 0002](docs/adr/0002-network-play.md). It replaced a Python TUI and CLI in October 2026; that code lives at the git tag `python-final`.

Requirements: JDK 25, Maven, Python 3, and Forge in `tools/forge-dc/` (git-ignored, GPL-3). Forge is, for now, a build of our branch [`mtg-oracle-forge`](https://github.com/carldouglasbergentz-pixel/forge/tree/mtg-oracle-forge): the Duel Commander work ([Card-Forge/forge#12090](https://github.com/Card-Forge/forge/pull/12090)), because no Forge release plays Duel Commander yet. To build it:

```bat
git clone -b mtg-oracle-forge https://github.com/carldouglasbergentz-pixel/forge.git ..\forge
cd ..\forge
mvn -pl forge-gui-desktop -am package -DskipTests -Dcheckstyle.skip
cd ..\mtg-oracle
python tools\stage_forge.py ..\forge tools\forge-dc
```

`tools/stage_forge.py` copies Forge's desktop jar and `res/` into the layout the app builds against, and `tools/forge-dc/build.txt` names the commit it came from; restage whenever the branch moves. `-PforgeDir=tools/forge` builds against an unpacked Forge release instead (2.0.14 has no Duel Commander).

**When Forge moves.** Forge is inside the app, so a Forge update is a new release of the app, and network play (which needs the same version on both sides) moves with it. Rebuild and restage Forge, then run `gradlew test`: it holds the seam to Forge's internal API, which isn't stable, and `ForgeLimitedTest` holds the packs a seed opens to recorded digests, which change when Forge's packs do (new sets, mended booster sheets: update the digests on purpose). New cards reach the database by the daily sync whatever Forge does; the packs and the rules of a new set come with Forge. Once a Forge release plays Duel Commander, the app goes back to Forge's own releases and the branch goes.

From `app/`:

```bat
gradlew test                    :: every module
gradlew :app:installLocal       :: a snapshot to play, into app\dist\<timestamp>\
run-mtg-oracle.cmd              :: play the newest snapshot (run-mtg-oracle.sh elsewhere)
gradlew :app:run                :: the window from the build outputs, for development only
gradlew :app:cli -Pargs="card Sol Ring --json"
gradlew :app:scriptedGame -Pdata=<dir with a DB copy>
gradlew :app:localDuel -Pargs="Jori En;Phelia Doggo"   :: network play on one machine: a host's window and a guest's
gradlew :app:migrate            :: bring the database to this build's schema (a backup first)
gradlew :app:checkSchema        :: check it, without writing
```

Play from a snapshot, not `gradlew :app:run`: a build replaces the class files under a running game. Each snapshot is stamped with its git hash, which the window title shows; the three newest are kept. `-Pdata=<dir>` points any task at another data directory, relative to the repo root.

**The schema** version is `PRAGMA user_version` ([`Schema.kt`](app/data/src/main/kotlin/mtgoracle/data/Schema.kt)). The app migrates at start, after a backup in `data/backups/` (three kept). A database the old Python migrations made is adopted unchanged when it has all of version 1.

**The tests** never write `data/mtg.db`. Most run on a frozen fixture (`app/data/src/testFixtures/fixture/`): a 3,000-card cut of the upstream exports, reference decklists, and the answers the Python original gave on that data. The few that read your own database skip when it is absent.

### A release

```bat
gradlew :app:packageRelease -PreleaseVersion=0.5.1
gh release create v0.5.1 <zip> <zip>.sha256
```

This builds `app\app\build\release\MTG Oracle\` and its zip (about 150 MB) with a `.sha256` beside it: `MTG Oracle.exe` (the window), `mtg.exe` (the command line), `runtime\` (a Java of its own), `app\` (the jars, Forge's assets, the points lists), and `LICENSE.txt`, `NOTICE.txt` and `FORGE-SOURCE.txt` (the Forge commit inside, whose source the GPL says goes with it: it must be pushed to the fork). A release is built from a clean tree (`-PallowDirty` for a trial), versioned `0.5.1+<commit>`, and published from a pushed tag; without its `.sha256` it can't be installed by `update`.

## Licence and credits

GPL-3.0 ([`LICENSE`](LICENSE)), because Forge is embedded. Card data and images come from Scryfall, combos from Commander Spellbook, the rules from Wizards of the Coast: see [`NOTICE.md`](NOTICE.md). MTG Oracle is unofficial Fan Content permitted under the Fan Content Policy. Not approved/endorsed by Wizards. Portions of the materials used are property of Wizards of the Coast. ©Wizards of the Coast LLC.

## Project layout

```
mtg-oracle/
├── CLAUDE.md                     Project rules for Claude Code (read first)
├── CHANGELOG.md                  Per-feature history (Keep a Changelog)
├── app/                          Gradle build, six modules, one-way dependencies
│   ├── core/                     Plain data and pure logic: board and prompts, decks and their rules,
│   │                             the search language, the analysis engine
│   ├── data/                     data/mtg.db over JDBC: schema, lookups, search SQL, deck writes, sync
│   ├── forge/                    Every Forge import: runtime, the seat, printings, images, matches
│   ├── ui/                       The house-style kit and the screens
│   ├── net/                      Network play: the wire, the invite and sealed link, rooms, UPnP
│   ├── app/                      Entry point, wiring, the window, the command line, headless modes
│   ├── mtg.cmd                   The command line, from the newest snapshot
│   └── run-mtg-oracle.cmd / .sh  Play the newest snapshot
├── docs/
│   ├── project-plan.md           Where we are (read first)
│   ├── app-design.md             The look, the board, the interaction model
│   ├── adr/                      Architecture decisions
│   └── reports/                  Deck-analysis reports (Swedish) and their reference decklists
├── data/
│   ├── formats/                  Community-format definitions (tracked)
│   ├── raw/, backups/, app/, game_logs/, playmats/, exports/, import/   Git-ignored
│   └── mtg.db                    The database (git-ignored)
└── tools/forge-dc/               Forge from the Duel Commander branch, staged (git-ignored)
```

## Conventions

- All SQL is parameterised. Reads use a read-only connection; writes happen only through the data layer's writers, one transaction each.
- Card names are case-insensitive everywhere, and diacritics, ligatures and apostrophes fold.
- Don't fetch from Scryfall, Wizards or Spellbook at query time: the sync is the refresh path. Don't edit `data/raw/`, which every sync overwrites.
- Spellbook is comprehensive for known combos, not exhaustive; your own combos fill the gap.

See [`CHANGELOG.md`](CHANGELOG.md) for the history and [`docs/project-plan.md`](docs/project-plan.md) for what's next.
