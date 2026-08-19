# MTG Oracle

Local-first Magic: The Gathering knowledge base + companion app. Cards, rulings, the complete Comprehensive Rules, Commander Spellbook combos, and your own decks — all in one queryable SQLite database, with a terminal TUI on top.

Runs offline after first sync. No network at query time.

## What's in the box

- **Cards** — 35k+ unique cards from the Scryfall bulk export, including mana cost, colors, mana value, power/toughness, rarity, layout, and per-face data for DFCs / split / flip cards.
- **Rulings** — 78k+ official Scryfall/Wizards rulings, joined to cards via `oracle_id`.
- **Comprehensive Rules** — every numbered rule (3,300+) parsed from the Wizards `.txt` release with parent / section links.
- **Combos** — 104k+ Commander Spellbook combos with cards, prerequisites, results, and step-by-step play sequences.
- **Format legality** — every card's status (`legal` / `restricted` / `banned`) in all 23 formats Scryfall tracks, plus which games it was printed in and its EDHREC popularity rank. Searchable, and enforced per deck. Includes several formats people assume aren't covered: `duel` *is* Duel Commander, `tlr` is Tiny Leaders: Reborn, and Oathbreaker / Pauper Commander / PreDH / Old School are all in there.
- **Community formats with points** — Canadian Highlander's 43-card points list, with the 10-point deck cap enforced on `add` and shown as a `[7/10 pts]` badge. Definitions are curated JSON in [`data/formats/`](data/formats/); legality is inherited from the Scryfall format they share a ban list with, so nothing is duplicated.
- **Structured tags** — auto-derived per card: CR 702 keywords, supertypes / types / subtypes, plus a `card_abilities` table with `has_target` / `produces_mana` / `is_mana_ability` per parsed ability (CR 605.1a/b).
- **Decks Lite** — your own deck-builder. Folders, decks, deck cards. Import from clipboard or text file. Auto-grouped render by card type. Combo detection inside a deck.
- **Feedback loop** — a `corrections` table that persists factual mistakes so they don't re-emerge in future sessions. Auto-attached to card lookups.

## Quick start

```bash
# 1. Install runtime deps (just textual for the TUI; everything else is stdlib)
pip install -r requirements.txt

# 2. Create the schema
python scripts/init_db.py

# 3. Pull data from upstream (Scryfall + Wizards + Commander Spellbook + local tagging)
python scripts/sync.py

# 4a. Launch the TUI
python scripts/mtg_app.py

# 4b. Or use the CLI for one-off lookups
python scripts/mtg_cli.py card "Deathrite Shaman"
python scripts/mtg_cli.py search "kw:flying c:u t:creature mv<=3"
```

`sync.py` is idempotent — it skips any source whose upstream version (timestamp / ETag / release date) hasn't moved. The end of every run prints a `=== changelog ===` summary of what actually changed.

`init_db.py` creates the complete schema, so a fresh install needs nothing else.

### Existing database from an earlier version?

`sync.py` self-heals: it runs the additive migrations (`scryfall_fields`, `tags`, `corrections`, `user_combos`) on every invocation and stays quiet unless one of them actually changes something. For older databases that predate the `oracle_id` / `mana_cost` / `decks` work, apply those three first:

```bash
python scripts/migrate_add_oracle_id.py        # oracle_id, layout, card_faces, sync_state
python scripts/migrate_add_mana_cost.py        # mana cost rendering
python scripts/migrate_add_decks.py            # decks + folders
python scripts/sync.py --force                 # repopulate everything
```

Two one-shot fixes are not in the self-heal list because they rebuild rather than add:

```bash
python scripts/migrate_fix_card_tags_pk.py     # card_tags PK -> (card_name, tag, category)
python scripts/prune_stale_cards.py            # dry run; --yes deletes stale card rows
```

Each migration only touches what it needs to. Running them on a fresh DB is harmless.

## TUI — terminal-style navigation

The Textual app behaves like a tiny shell. Three levels of "filesystem":

```
/                            <- root: folders + unsorted decks
/<folder>/                   <- decks inside a folder
/<folder>/<deck>/            <- cards inside a deck
```

The status bar always shows where you are. Commands change meaning by depth:

| Anywhere | At root | In a folder | In a deck |
|---|---|---|---|
| `pwd`, `ls`, `ls all` | `mkdir`, `rmdir` | `add <deck>`, `remove <deck>` | `add <card>`, `remove <card>` |
| `cd <name>`, `cd ..`, `cd /` | `show <deck>` | `rename`, `move`, `show <deck>` | `commander`, `points`, `combos`, `paste`, `import <filepath>` |

`add` and `remove` change meaning with your location — there are no separate `new` / `delete` verbs. `help decks` prints the full model in the app.

### Mouse

Both panes are clickable, and clickable text underlines when you hover it:

| Click | Does |
|---|---|
| a folder or deck in the left tree | `cd` into it |
| a card name in the live deck pane, a search result, or a `show` render | opens its full profile in the right pane |
| a combo's `[ N ]` row number | expands that combo |

Every click echoes the equivalent command, so the mouse is a shortcut to the
keyboard interface rather than a parallel one. `Shift+drag` still selects text.

Plus, on every screen:
- `card <name-or-N>` — full card profile (or expand the N-th row of the last search)
- `ruling <name>`, `combo <card>`, `combos A; B`, `rule <number>`, `search-rules <text>`
- `search <scryfall-style-query>` — see the syntax guide below
- `correction [<card-or-topic>]` — list applicable corrections from the feedback loop

### Scryfall-style search

Type `search help` in the app for the full guide. Examples:

```
search o:"enters the battlefield" t:creature c:u mv<=3
search kw:flying (c:w or c:u) -t:artifact
search ci<=BG t:creature order:asc_mv      # Golgari color-identity, lowest mana value first
search c=wu t:instant
search pow>=4 t:creature r:mythic order:desc_rarity
```

Operators: `o:` `t:` `n:` (substring) `n=` (exact) `kw:` `c:` `c=` `ci:` `mv:` `pow:` `tou:` `r:` `layout:` plus comparisons (`<`, `>`, `<=`, `>=`, `!=`) on numeric fields. Boolean `or`, `not` (or `-` prefix), parentheses for grouping.

Format legality: `f:FORMAT` (legal or restricted), `banned:FORMAT`, `restricted:FORMAT`, `game:paper|arena|mtgo`, `is:reserved`. All 23 formats Scryfall tracks, from `alchemy` to `vintage` — including `competitivebrawl`, whose ban list differs from `brawl`'s by 33 cards. Spaces and hyphens are ignored (`f:"competitive brawl"`), and `edh` / `pdh` / `cbrawl` are aliases. `game:paper` drops the 216 Arena-only Alchemy `A-` rebalances.

Sorting: `order:asc_FIELD` / `order:desc_FIELD` (alias `sort:`). Fields: `mv`, `name`, `power`, `toughness`, `rarity` (tier order common→mythic), `color`, `ci` (number of colors), `edhrec` (popularity — `asc` is most-played first). Direction prefix is required. NULLs always sort last.

Pagination: `next`, `prev`, `page <N>`. `card <N>` expands the N-th row of the most recent search into a full profile. Tab / right arrow accepts the autofill suggestion that appears as gray ghost text.

### Card-name resolution is forgiving

Lowercase, missing accents, missing apostrophes, and DFC front-face-only names all work:

```
card lorien revealed       -> Lórien Revealed
card aether vial           -> Aether Vial
card lim-duls vault        -> Lim-Dûl's Vault
card thassas oracle        -> Thassa's Oracle
card delver of secrets     -> Delver of Secrets // Insectile Aberration
card fire/ice              -> Fire // Ice
```

### Decks Lite workflow

Build / edit a deck without leaving the app:

```
> mkdir Modern
> cd Modern
> new UR Murktide
> cd UR Murktide

# Either:
> paste                          # reads deckstring from system clipboard

# Or:
> import path/to/deck.txt        # plain-text file

> ls                             # render the deck, grouped by type
> add Sol Ring                   # add a single card
> add Mountain 4                 # add multiple
> remove Sol Ring
> combos                         # combos whose cards are all in this deck
> cd ..                          # back to /Modern
```

The plain-text parser tolerates every common format: `4 Card`, `4x Card`, `Card x4`, set/collector tails like `(CLB) 146`, foil markers `*F*`, `#`/`//` comments, and section headers (`Deck`, `Sideboard`, `Commander`, `Maybeboard`).

#### Commander / singleton formats

Set `format = commander` (or `edh`, `canadian highlander`, `brawl`, `oathbreaker`, `highlander`, ...) on a deck and `is_commander = 1` on the commander card to unlock format-aware behavior:

- **Color-identity filter on search.** Inside the deck, every `search` is hard-filtered by `ci<=<commander CI>`. Multiple commander rows union (Partner / Background / Friends Forever). The filter is announced inline; `cd ..` exits it.
- **Format-legality filter on search.** When the deck's format maps to a Scryfall legality key, `search` is *also* filtered by `f:<format>` — so inside a Competitive Brawl deck you only ever see cards you can actually register. Both active filters are shown above the results.
- **CI validation on add.** `add` rejects cards whose color identity isn't a subset of the deck CI. `add --force <card>` overrides for one call. The commander itself is never CI-checked — it *defines* the CI.
- **Legality validation on add.** Banned cards and cards outside the format's pool are rejected with distinct messages. This check *does* apply to the commander.
- **Points budget.** In a format with a points list (Canadian Highlander), `add` refuses a card that wouldn't fit the 10-point cap and says what the deck is currently spending. `points` breaks down where the budget went; `show` marks pointed cards `<3p>`.
- **Singleton enforcement on add.** Adding a 2nd copy of any non-basic, non-"any-number-of" card is rejected. Basics and cards like *Relentless Rats* / *Dragon's Approach* are exempt automatically. Sideboard rows are validated independently from the main deck.
- **Combos pre-filtered to the deck.** `card <name>` inside a commander deck filters the embedded "Top combos featuring this card" list to combos whose CI fits — so an artifact like *Ashnod's Altar* in a Savra (BG) deck only lists combos you can actually play.
- **Commander pinned in the live deck pane** plus a `[CI: XY]` badge in the deck-list, full deck view, and live deck view headers.
- **Auto combos-in-deck on `cd`.** Stepping into a deck immediately renders the combos fully contained in the deck, so you don't have to type `combos` separately.

## CLI

Same query layer as the TUI; one-shot commands. Useful for scripting, JSON output, or just quick lookups.

```bash
python scripts/mtg_cli.py card "Phage the Untouchable"
python scripts/mtg_cli.py rule 605.1a
python scripts/mtg_cli.py combos "Thassa's Oracle" "Demonic Consultation"
python scripts/mtg_cli.py search "o:flash t:creature c:u" --page 2
python scripts/mtg_cli.py deck show "Izzet Delver"
python scripts/mtg_cli.py deck import "Izzet Delver" --from-file canlander.txt
python scripts/mtg_cli.py --json card "Sol Ring"
```

Every command has `--help`.

## Data sources

| Source | Fetch | Cadence | Idempotency key |
|---|---|---|---|
| Cards + rulings | Scryfall `oracle_cards` + `rulings` bulk, gzipped JSONL (`jsonl_download_uri`) | daily upstream | `updated_at` timestamp |
| Comprehensive Rules | scrape `magic.wizards.com/en/rules` for the latest `.txt` | ~6 / year (set releases) | release date in the URL (`MagicCompRules YYYYMMDD.txt`) |
| Combos | Commander Spellbook `variants.json` | weekly | HTTP ETag |
| Tags | local regex on `oracle_text` + type-line | every sync | rebuilt fresh — fast (~3 s) |
| Community formats | curated JSON in `data/formats/` | manual (list changes ~quarterly) | `verified_at` in the file |
| Decks | user-supplied (text / clipboard / file) | manual | n/a |

## Project layout

```
mtg-oracle/
├── CLAUDE.md                        Project context for Claude Code
├── CHANGELOG.md                     Per-feature history (Keep a Changelog)
├── README.md                        You are here
├── requirements.txt                 textual; rest is stdlib
├── .claude/
│   ├── settings.json                Tool permissions (project-shared)
│   └── commands/                    /sync, /ruling, /combo, /correction
├── docs/
│   ├── project-plan.md              Phased plan w/ exit criteria + parked items
│   └── app-design.md                Aesthetic intent (terminal / monochrome)
├── data/
│   ├── formats/                     Community-format definitions (tracked)
│   ├── raw/                         Cached upstream payloads (gitignored)
│   └── mtg.db                       The database (gitignored)
├── mtg_oracle/                      Importable package
│   ├── queries.py                   get_card / find_combos / get_rule / get_corrections / ...
│   ├── decks.py                     Deck CRUD, name resolution, combos-in-deck
│   ├── deck_parser.py               Tolerant plain-text deckstring parser
│   ├── scryfall_search.py           Tokenizer + parser + SQL compiler for `search`
│   ├── renderer.py                  Plain-ASCII renderers, shared by CLI + TUI
│   └── app.py                       Textual TUI app
└── scripts/
    ├── init_db.py                   Create the full schema on a fresh DB
    ├── sync.py                      Orchestrator (cards + rules + combos + tags)
    ├── sync_cards.py                Scryfall bulk (gzipped JSONL, streamed)
    ├── load_custom_formats.py       data/formats/*.json -> custom_formats
    ├── prune_stale_cards.py         Drop card rows upstream no longer ships
    ├── sync_rules.py                Wizards CR scrape
    ├── sync_combos.py               Commander Spellbook
    ├── tag_cards.py                 Local regex tagger
    ├── migrate_add_*.py             Idempotent schema migrations
    ├── mtg_cli.py                   CLI front-end
    └── mtg_app.py                   TUI launcher
```

## Phases — where we are

See [`docs/project-plan.md`](docs/project-plan.md) for the living plan. Roughly:

- **Phase 0 — Foundation** ✓ data tables, idempotent sync, changelog diff, feedback loop
- **Phase 1a — Query library + CLI** ✓ shared `mtg_oracle.queries` + CLI front-end
- **Phase 2 — Desktop TUI app** ✓ Textual, terminal aesthetic, autofill, pagination, mana costs, ASCII-fold name lookup, sortable search, persistent theme
- **Phase 3 — Decks Lite** ✓ folders, decks, deck cards, parser, paste, type-grouped render, combos-in-deck
- **Phase 3b — Format-aware deck behavior** ✓ commander color-identity filter on search/add, singleton enforcement, pinned commander section, CI-aware combo lookup
- **Phase 1b — HTTP API** parked (only when a remote client needs it)
- **Phase 3c — Scryfall legalities + custom-format points** parked (per-format legality matrix + Canlander points list)
- **Phase 4 — LLM layer** parked (Claude API + tool use over the same queries)
- **Phase 5 — Mobile port** parked (desktop-first to maximize iteration speed)

Parked / idea list also lives in the project plan.

## Conventions and don'ts

- All SQL is parameterized. The DB is opened read-only for queries (`file:...?mode=ro`); writes only happen in sync scripts and deck CRUD.
- Card names are case-insensitive everywhere; ASCII fold-down handles diacritics, ligatures, and apostrophes.
- The `corrections` table is the project's memory of past factual mistakes. Card lookups auto-attach applicable corrections so future answers don't repeat them.
- Don't fetch from Scryfall / Wizards / Spellbook live at query time — running `sync.py` is the supported refresh path.
- Don't modify `data/raw/` — it's an upstream payload cache, overwritten on every sync.
- Spellbook is comprehensive-for-known-combos, not exhaustive. Homebrew combos exist outside their database.

## Status today

All phases marked ✓ above are implemented and verified end-to-end. See [`docs/project-plan.md`](docs/project-plan.md) for the live phase status and parked items, and [`CHANGELOG.md`](CHANGELOG.md) for per-feature history.
