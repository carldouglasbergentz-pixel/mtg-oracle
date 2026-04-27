# MTG Oracle

Local-first Magic: The Gathering knowledge base + companion app. Cards, rulings, the complete Comprehensive Rules, Commander Spellbook combos, and your own decks — all in one queryable SQLite database, with a terminal TUI on top.

Runs offline after first sync. No network at query time.

## What's in the box

- **Cards** — 34k+ unique cards from the Scryfall bulk export, including mana cost, colors, mana value, power/toughness, rarity, layout, and per-face data for DFCs / split / flip cards.
- **Rulings** — 75k+ official Scryfall/Wizards rulings, joined to cards via `oracle_id`.
- **Comprehensive Rules** — every numbered rule (3,400+) parsed from the Wizards `.txt` release with parent / section links.
- **Combos** — 84k+ Commander Spellbook combos with cards, prerequisites, results, and step-by-step play sequences.
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

### Existing database from an earlier version?

The repo ships several idempotent migrations that incrementally extend the schema. Apply them in order:

```bash
python scripts/migrate_add_oracle_id.py        # original Scryfall migration
python scripts/migrate_add_mana_cost.py        # mana cost rendering
python scripts/migrate_add_scryfall_fields.py  # colors, power, toughness, rarity, mana_value
python scripts/migrate_add_tags.py             # card_tags + card_abilities
python scripts/migrate_add_corrections.py      # feedback loop
python scripts/migrate_add_decks.py            # decks + folders
python scripts/sync.py --force                 # repopulate everything from cached bulk
```

Each migration only adds columns / tables that don't exist yet. Running them on a fresh DB is harmless.

## TUI — terminal-style navigation

The Textual app behaves like a tiny shell. Three levels of "filesystem":

```
/                            <- root: folders + unsorted decks
/<folder>/                   <- decks inside a folder
/<folder>/<deck>/            <- cards inside a deck
```

The status bar always shows where you are. Commands change meaning by depth:

| Anywhere | At root or in folder | In a deck |
|---|---|---|
| `pwd`, `ls`, `ls all` | `mkdir`, `rmdir`, `new`, `delete` | `add`, `remove`, `combos` |
| `cd <name>`, `cd ..`, `cd /` | `rename`, `move`, `show <deck>` | `paste`, `import <filepath>` |

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
search c=wu t:instant
search pow>=4 t:creature r:mythic
```

Operators: `o:` `t:` `n:` `kw:` `c:` `c=` `mv:` `pow:` `tou:` `r:` `layout:` plus comparisons (`<`, `>`, `<=`, `>=`, `!=`) on numeric fields. Boolean `or`, `not` (or `-` prefix), parentheses for grouping.

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
| Cards + rulings | Scryfall `oracle_cards` + `rulings` bulk JSON | daily upstream | `updated_at` timestamp |
| Comprehensive Rules | scrape `magic.wizards.com/en/rules` for the latest `.txt` | ~6 / year (set releases) | release date in the URL (`MagicCompRules YYYYMMDD.txt`) |
| Combos | Commander Spellbook `variants.json` | weekly | HTTP ETag |
| Tags | local regex on `oracle_text` + type-line | every sync | rebuilt fresh — fast (~3 s) |
| Decks | user-supplied (text / clipboard / Moxfield CLI flag) | manual | n/a |

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
│   ├── raw/                         Cached upstream payloads (gitignored)
│   └── mtg.db                       The database (gitignored)
├── mtg_oracle/                      Importable package
│   ├── queries.py                   get_card / search_cards / find_combos / get_rule / ...
│   ├── decks.py                     Deck CRUD, name resolution, combos-in-deck
│   ├── deck_parser.py               Tolerant plain-text deckstring parser
│   ├── scryfall_search.py           Tokenizer + parser + SQL compiler for `search`
│   ├── renderer.py                  Plain-ASCII renderers, shared by CLI + TUI
│   └── app.py                       Textual TUI app
└── scripts/
    ├── init_db.py                   Create schema on a fresh DB
    ├── sync.py                      Orchestrator (cards + rules + combos + tags)
    ├── sync_cards.py                Scryfall bulk
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
- **Phase 2 — Desktop TUI app** ✓ Textual, terminal aesthetic, autofill, pagination, mana costs, ASCII-fold name lookup
- **Phase 3 — Decks Lite** ✓ folders, decks, deck cards, parser, paste, type-grouped render, combos-in-deck
- **Phase 1b — HTTP API** parked (only when a remote client needs it)
- **Phase 3b — Format validation** parked (Scryfall covers 22 formats; custom-format fetchers for Canlander etc. are designed but not built)
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

25+ commits on `master`. All features above are implemented and verified end-to-end (a 100-card Canadian Highlander Izzet Delver list parsed and renders correctly, including DFC front-face categorization). No GitHub remote yet — set one up locally with `git remote add origin <url>` when you're ready to publish.
