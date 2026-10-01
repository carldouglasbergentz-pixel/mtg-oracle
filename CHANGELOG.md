# Changelog

All notable changes to MTG Oracle are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed
- **"max affordable" on the X prompt prices the cost actually paid, with the mana you really have.**
  - **The cost.** It is taken from the spell on the stack, so a miracle is priced by its miracle cost. Entreat the Angels by miracle with 8 Plains now suggests 6, where it said 2 from the printed {X}{X}{W}{W}{W}.
  - **The mana.** Each X is checked with Forge's own payment check, which knows which sources make mana and in what colours. It used to count every land as a mana. With Wrath of the Skies and 8 lands, including Arid Mesa and Urza's Saga with no lore counter, it now says 4, where it said 6.
  - An activated ability with X keeps the old estimate, since there is nothing on the stack yet. `AffordableXTest` covers the user's cases.
- **Undo after a mana ability is no longer stopped by the floating-mana warning.** Library of Alexandria tapped by mistake left "Undo (1)" on the cancel button, but pressing it asked "{C} is floating … pass anyway?", because Cancel at priority counted as a pass. The prompt now says when Cancel is Forge's Undo (`InputPrompt.cancelUndoes`). Undo goes through at once and gives the mana back. As End Turn, Cancel still asks. `UndoManaTest` plays the user's case.

### Removed (step 7: Python retired)
- **The Python code:** `mtg_oracle/` (the TUI and its layers), `scripts/` (the sync, migrations, the CLI, `analyse_archetype.py`), `tests/` and `requirements.txt`. The last commit with them is tagged `python-final`.

### Changed
- **The parity tests compare with recorded answers, not a live Python.** `app/data/src/testFixtures/fixture/` holds the frozen data and the answers:
  - a 3,000-card cut of the upstream exports: cards, rulings, tags, 126 Spellbook combos, the full rules and the points list;
  - the reference decklists;
  - Python's version-0 schema;
  - `expected/`, what Python answered on that data. That covers search counts and pages, names, the deck engine's ops with the decks and history they leave, the parser, every card's classification, deck analytics, archetype reports, and the sync's table digests.

  `FixtureDb` builds the database once per test run with the app's own migrate, sync and imports. SearchParityTest, DeckParityTest, DeckParserParityTest, AnalysisParityTest, SyncParityTest and SchemaTest need neither Python nor `data/mtg.db`. SyncParityTest is no longer opt-in: on the fixture it takes seconds.
- **A first start makes the database.** With no `data/mtg.db` the app creates an empty one at the current schema and asks for [ Sync ]. Before, it stopped with "run `python scripts/sync.py`". `mtg.cmd sync` also creates it. Other CLI commands without a database exit with code 2 and the way to make one.
- An old Python-made database that is short of schema version 1 now points to `self_heal.py` at the `python-final` tag.
- `/sync` runs `app\mtg.cmd sync`.
- README and CLAUDE.md describe the app alone. `docs/feature-parity.md` is kept as history.

### Added (step 6c: the last of what Python did)
- **`app\mtg.cmd`, the command line,** from the newest snapshot (the app's `cli` mode; `gradlew :app:cli -Pargs="..."` too). The commands:
  - lookups: `card`, `search [--page N]`, `rule`, `combo`, `combos`, `combo-info`;
  - decks: `deck list | show | export [--front-face] [--grouped]`;
  - analysis: `profile <deck>|<folder>`, `compare <deck> --against <deck>|<folder>`;
  - games and data: `results`, `sync`, `prune`.

  Text is the app's own output, through the same commands and renderings. `--json` is structured, and for `profile` and `compare` it has the shape `analyse_archetype.py --json` had. The console is switched to UTF-8 while it runs.
- **`combo add <card>; <card>[; ...]`:** a combo of your own, for the ones Spellbook doesn't list. It asks what the combo does and, optionally, its name, takes the cards' colour identity, and shows the combo as every lookup will. `combo remove <user-NNN>` removes one after a yes; Spellbook's can't be removed.
- **`prune [--yes]`:** card rows no export writes any more, which read as colourless in every `ci<=`.
  - Without `--yes` it is a dry run. With it, the app asks first; in the terminal `--yes` is the confirmation.
  - A card a deck names is always kept, and more stale rows than is believable deletes nothing.
  - The lookup is rebuilt afterwards. The live database has none today.

### Removed
- `scripts/export_land_fetchers.py` and `scripts/export_typal_matters.py`: one-off tests, and the search language answers both (`otag:`, `t:`).

### Added (step 6b: the data sync in the app)
- **[ Sync ] in the library, `sync [--force] [<source> ...]` on the command line, `gradlew :app:sync` in a terminal.**
  - It fetches what moved upstream: Scryfall cards, legalities and rulings; the Comprehensive Rules; Commander Spellbook's combos; the local tags; Tagger's oracle tags; the community formats.
  - Each source is skipped when its marker hasn't moved, unless `--force`. Sources run in their own order whatever order they are named in, and a failing one is reported without stopping the rest.
  - It ends with a changelog in the output (added / removed / modified per table) and each source's upstream marker.
  - It runs in the background. When the cards changed, the lookup is rebuilt, so new cards can be searched at once, with the scrollback, the command history and the open deck kept. Decks are never touched.
- **A port of scripts/sync*.py** (`data/sync/`), with its rules: cards upserted on name and never deleted; a name collision won only by a strictly better printing (legal somewhere, not a novelty set); legalities and rulings as full snapshots; the rules text's irregular numbers (`704.5aa`, `606.5` with no period) and glossary senses kept out; combos with `INSERT OR REPLACE` and their cards `INSERT OR IGNORE`; direct oracle taggings only; a format naming an unknown card stops the load.
  - `card_faces` and aliases are written byte for byte as Python's `json.dumps` did, so a sync doesn't rewrite every row.
  - Spellbook's 600 MB export is read one variant at a time instead of parsed whole.
  - Requests carry a User-Agent and Accept header, as Scryfall asks.
- **SyncParityTest** (`gradlew :data:test -PsyncParity`, opt-in: a couple of minutes) runs Python's own sync functions, their network calls served from `data/raw`, and ours, into two copies emptied of everything the sync writes. All 14 tables come out identical, row for row and type for type (35,228 cards, 371,825 legalities, 607,775 combo steps...). SyncTest covers the rules offline with tiny exports, and SyncAppTest covers the app's command, the report and the rebuilt lookup.
- **The first real sync, from the app's code** (2026-10-01, after a backup in `data/backups/mtg-2026-10-01-pre-first-sync.db`): +15 cards and 1 changed, +758 rulings, +10,384 and -1,865 combos (Spellbook's export was six weeks old), +187 oracle taggings; the rules were already current. All 12 decks and 1,069 deck rows were untouched, and no deck card lost its data.

### Changed (step 6a: the app owns the schema)
- **The Kotlin app creates and migrates the database** (`Schema`, `MtgDb.migrate`), with the version in `PRAGMA user_version`:
  - Version 1 is `scripts/init_db.py`'s schema, verbatim (`schema-v1.sql`).
  - A database the Python migrations made is checked against it and adopted unchanged; one missing part of it stops the app with the one command that completes it.
  - An empty file gets the whole schema.
  - Before any write, the stamp included, the database is copied to `data/backups/mtg-<time>-pre-vN.db`. The app's three newest copies are kept, and hand-made copies are never pruned.
  - `gradlew :app:migrate` does just that. `checkSchema` checks read-only, against the shape the version must have (tables, columns, indexes, foreign keys as sets), instead of a hand-kept list.
- **Version 2 drops `forge_matches`,** the Python simulator's table, when it is empty. Simulations are rows in `games` now.
- **`self_heal.py` leaves a versioned database alone,** so nothing the app drops comes back. The Python TUI and CLI keep working over the same data. `forge sim` / `forge results` have nowhere to store, and their tests skip.
- **The tests migrate their copies with the app's migrations,** not with `self_heal`. SchemaTest holds version 1 to Python's init_db + self_heal, and covers adoption, the backup, the kept table, a gap, a newer version and pruning.
- **Applied to the live database** with the user's go-ahead: v0 → v2, integrity ok. The backup is `data/backups/mtg-2026-09-30-170040-pre-v2.db`.

### Added (step 5c: the AI copies edited in the app)
- **A card's menu in the workspace has "AI substitute...":** what Forge's AI plays instead of that card, in games and simulations. The deck itself is untouched. The substitute is checked in this order:
  1. Against the deck's own rules, with every substitution applied together (a port of `check_swaps`: colour identity, legality, singleton, points), so two substitutes that only break singleton jointly are caught.
  2. Against Forge: it must know the card, and its AI must play it.

  A card's earlier substitute is replaced, and "no AI substitute" takes it back.
- **An AI copy tab (4) in the deck pane** lists the substitutions, each card a link, with `[x]` to stop one. The tabs are narrower, so all four fit the deck pane.
- The setup screen's notes point at the menu, not the TUI's `forge sub add`.
- `DeckRefusal.Kind.BAD_SUBSTITUTE`. DeckParityTest has 13 swap cases against `decks.check_swaps`: single and joint singleton, points, colour identity, a substitute for itself, and cards not in the deck or not found.

### Added (step 5c: simulations in the app)
- **[ Simulate N ] on the setup screen** (S, with N cycling 1 / 5 / 10 / 20 / 50) plays that many AI-vs-AI games of the chosen pairing, with no board, one after another in the app's own Forge. Both AIs play their AI copies while "AI copy" is on.
  - Each game is recorded as it ends, all under one `match_id` with `game_no` 1..N. A stopped simulation keeps what it played, and the game it stopped is not recorded.
  - A game past 150 s is stopped as a draw.
  - Progress is on the status line; S stops it. A game and a simulation exclude each other, since Forge holds one match at a time.
- **Simulated games take seconds, not a minute.** With nobody watching, Forge's spectator playback (`FControlGamePlayback`) slept on every land, cast and resolve. A simulation unsubscribes it just before the game begins; watching keeps its pace. Two games went from 110 s to 7 s.
- **`results [<deck>]`** gives each deck's record against each opponent: wins–losses–draws from that deck's side, your games apart from the AI's, and the average length. The setup screen shows your record against each opponent (`you 1–0 · AI 12–7–1`).
- **`games.deck_ai_variant`** (migration `migrate_add_game_deck_ai_variant`) records whether seat A played its AI copy, as `opponent_ai_variant` does for the opponent. Applied to the live database after a backup (`data/backups/mtg-2026-09-30-pre-game-ai-variant.db`), with the user's go-ahead. Simulations are stored in `games`, the app's own table; the Python CLI's `forge_matches` (empty) goes when the schema moves to Kotlin.

### Changed
- **Opening another deck empties the output pane,** so the last deck's searches and reports don't crowd the new one (from the user). The same deck again keeps its output, a `cd` keeps its own line, and `next` / `combo-info <N>` no longer reach into a list that is gone.

### Added (on curve in the full profile, after Moxfield)
- **`profile` has an "on curve" table by mana value,** after Moxfield's. It shows the cards at each value (and how many are permanents), and the chance, on the play and on the draw, that the lands and rocks drawn by turn N pay for a card costing N if you hold one. It is exact, like the rest of the draw maths. It counts rocks and land backs, and it uses the effective cost. Checked against Moxfield: with its assumptions (43 land sources, 99 cards, no rocks, on the draw) it gives the same 99.17% for one-drops.
- **A mana value in that table is a link to its cards,** each of them a link to the card.

### Changed (replace keeps the commander)
- **A replace from a list with no commander section keeps the deck's commanders,** in both apps. A pasted list without a `Commander` header had put Elminster into the main deck, and left a Duel Commander deck with no commander: no colour filter, no identity check, no Commander game. The commander now stays. If the list has it among the deck's cards, that copy *is* the commander, not a second one, and its printing goes with it. The preview and the result say so ("the list names no commander, so Elminster stays the commander"). A list that names a commander still replaces them. DeckParityTest has both cases; `test_deck_history` checks the rule.
- Elminster was made the commander of Elminster Boomer Wizard again, with the user's go-ahead (revision #12, `promote`, undoable).

### Changed (the analysis reports, from the user's first read)
- **The reports show only what the decks hold.** A role with no card that can fill it gets no row; one line under the table names what is missing ("No cards in the deck for: rituals, hand disruption").
- **Every table says what its columns mean,** in a note under it: cards, also, engines, castable, drawn by T4, gap.
- **One deck's profile is two tables, not four.**
  - *What the cards do*: cards by main job, also (a side job), engines.
  - *When can it happen*: castable by turn, then "drawn T4" and the gap, which is what the mana costs.

  The single-deck mean/median/min/max columns are gone, and so is the separate ceiling table.
- **Names say what they count.**
  - The role "Mana sources" is **Mana rocks / dorks**: it read as if it counted the lands.
  - The total is **Lands + rocks**.
  - The mana line names its cards: "40 lands (Sea Gate Restoration: a spell with a land back, counted as a land) + 2 rocks / dorks (Mox Sapphire, Sol Ring) = 42 mana sources".
- **A folder's decks are numbered** (`#1 = Izzet Delver`), not cut to their last six letters.
- **The tables fit the pane:** late turns and per-deck columns go before a row runs past the edge. A difference that rounds to nothing reads `0p`, not `-0p`.
- The numbers are unchanged and still held to Python by AnalysisParityTest. The layout is now the app's own, so the byte-for-byte render test was replaced by AnalysisRenderTest (only present roles, a note under every table, no line past the pane).

### Fixed
- **The last line of a pane was drawn under its bottom border** when the pane's height wasn't a whole number of rows. The border sits on the last whole row; the content now gets only the rows inside it, and is clipped there, so a wide table can't run into the next pane either.

### Added (step 5: analysis in the app)
- **An analysis block above the deck,** fixed at the top of the middle column. It sits above the deck in the library and above the search in the workspace, and it doesn't scroll away. It shows:
  - size, lands (with MDFCs), spells, and average MV printed and effective;
  - the curve, with pips and mana sources per colour;
  - the primary roles;
  - how often each role is castable on turns 2 and 4, on the play;
  - links to the full profile and to the deck's combos.

  It is recomputed on every edit. Classifications are cached per card and blocks per deck, so moving through the list re-reads nothing.
- **`profile [<deck>|<folder>]`:** density, reach, on-curve and ceiling tables. A folder profiles every deck in it side by side, plus the most-played cards per role; that is the reference-set report in the app.
- **`compare <deck>|<folder>`:** head to head, or against every deck in a folder (itself left out), with the range verdicts, the curve deltas, and the cards they play that it lacks. Importing reference lists into a folder makes them a reference set.
- **`combos` with no argument, in or on a deck:** the combos it holds whole.
- **Export grouped by role:** a third choice in Export, under `// Counterspells (15)` comments.
- **The analysis engine in Kotlin** (`core/analysis`): role classifier, effective mana, exact draw maths, deck analytics, profile, compare and rank. It is pure, with Python's rounding and float summation reproduced (`Py`).

  AnalysisParityTest checks it against Python:
  - every one of the ~35k cards' classification;
  - every deck's curve, pips and sources;
  - every profile, ranking and comparison over the reference folders and the user's decks, at 12 decimals.

  AnalysisRenderParityTest holds the tables to Python's byte for byte. DeckParityTest covers the grouped export.
- `kotlinx-serialization-json` (JetBrains), for `card_faces` now and the sync's upstream JSON in step 6.

### Changed (formats follow the folders, and read as players write them; from the user's play-through)
- **Moving a deck into a folder whose default format differs offers that format** (`Use Canadian Highlander` / `Keep Duel Commander (DC)`). A deck's format is its own, so it is asked, never changed silently.
- **A format with no command zone asks where the commander goes:** moving Elminster's deck to Canadian Highlander asks "Put Elminster in the deck?".
- **[ New deck ] and [ Import ] ask for the folder first.** The current folder is listed first, and `+ new folder...` makes one on the spot. A folder's own menu still puts the deck straight in it.
- **The folder-default question is clearer and only asked when it matters:** "Default format for Duel Commander: Duel Commander (DC). 2 deck(s) in the folder have no format: give them Duel Commander (DC) too?". A folder with no formatless decks isn't asked about.
- **Formats as players write them:**
  - a folder with a default shows its tag, `Duel Commander [DC]`, `Canadian Highlander [CHL]`, `[EDH]`;
  - a deck's title shows `DC` rather than `duel`;
  - the format lists read `Duel Commander (DC)`, `Commander (EDH)`;
  - **`f:dc` and `f:chl` work in search**, in both the app and the TUI.

### Added (step 4b: managing decks in the app)
- **The library:**
  - **[ New deck ]**, **[ New folder ]** and **[ Import ]**;
  - a right-click on a deck: open, play, rename, move to a folder, format, export, delete;
  - a right-click on a folder: new deck here, import here, default format (optionally stamped on its decks without one), delete;
  - every folder is shown, the empty ones too.
- **Nothing is written before you answer.** A name to type, a choice from a list or a confirmation opens above the command line, and Esc or Cancel changes nothing. Delete says the considering list and the history go with the deck.
- **Import from the clipboard,** as a new deck (which then opens) or into the open deck:
  - **add to it**, or **replace it**;
  - a replace runs dry first, its changes are listed in the output, and only then does it ask;
  - names that resolve to nothing are named, and are left out only when you say yes;
  - a list's maybeboard (`Maybeboard`, `Maybe`, `Considering`) fills the considering list.
- **Export to the clipboard,** with full names or front faces only (split cards stay whole). The printings go with it, so export and then import keeps the art.
- **Choose a card's printing:** a deck row's menu > choose printing… lists every printing Forge knows, newest first, and the zoom pane shows each one's art on hover. It is a `printing` revision and can be undone (the Jace-to-WWK-31 wish).
- **The engine behind it is ported and checked.** DeckParser is held to Python on every reference list. DeckParityTest runs 103 changes, and the library, paste, replace, printing and export operations agree with Python's.

### Changed (the repository follows the app-first scope)
- **The schema migrations moved to `scripts/migrations/`,** so `scripts/` holds the tools you run. They are still imported by their bare names (self_heal puts the folder on the path), so reports and tests name them as before. `python scripts/migrations/migrate_<name>.py` runs one directly.
- **Reports and their reference lists moved to `docs/reports/`:** `docs/reports/*-report.html`, `report-style.md`, and `docs/reports/decklists/<name>/` (`uw-canlander`, `elminster-duel-commander`, `duel-commander-2026`), without the spaces in the old folder names. `docs/` itself is the plan, the design and the decisions.
- **The README leads with the app:** what it is, how to build and play it (`gradlew :app:installLocal`, `run-mtg-oracle.cmd`), and the Python side as the data pipeline, with the TUI and CLI kept until parity.
- **What did not move:** the Python package and `scripts/` stay at the top level. They own the schema and the sync, and both apps and the docs name their paths. They move once the sync is ported (step 6).

### Added (step 4a: editing in the deck workspace, a considering list, and the history)
- **Edit by mouse:**
  - every search result has `[+] [sb] [?]`, which put it in the deck, the sideboard or the considering list;
  - every deck row has `[-] [+]`;
  - a right-click on a card opens its menu: to the sideboard, to considering, make commander, remove all, open;
  - the deck pane has three tabs, **Deck · Considering · History**.
- **Or by keyboard.** On a result, `+`, `S` and `C`. Tab moves to the deck, where `+`, `-` and `Delete` work on the row. `1`, `2` and `3` switch tabs. The commands are `add [--sb] [--force] <card> [N]`, `remove`, `consider`, `commander [--unset]`, `undo` and `history`.
- **The deck's rules as the TUI applies them:** colour identity, legality, banned, restricted, singleton (basics, "any number", "up to N") and points. A refused change says why, and **`[ add anyway ]`** (F) pushes it through. A forced change is marked in the status line.
- **The considering list** is Moxfield's maybeboard: cards weighed for the deck but not in it. They are not counted, exported or played. A card there that a rule would stop gets a `!` with the reason, and the rules apply when it moves into the deck. The TUI has `consider <card>` and lists it last in both deck views.
- **The History tab** shows every change, newest first, in local time. That includes changes made in the TUI, because both apps write the same history. `undo` works as before: undo of undo is redo.
- **Points:** the deck pane's title shows `9/10 pts`, and pointed cards show their points, `Mana Drain (1)`, in the deck and in the search results.
- **The engine behind it is a Kotlin port of `decks.py`.** `DeckParityTest` runs 68 changes over five decks through both and compares every outcome, row and history entry. The database gets `deck_considering` and a rebuilt `deck_changes` (`migrate_add_considering`); the backup taken first is `data/backups/mtg-2026-09-30-pre-considering.db`.

### Fixed (an ability on the stack showed as "hidden" once its source had left the battlefield)
- **The AI's Hawkeye trigger read "a hidden spell".** Hawkeye's Trick Arrows was on the stack when Condemn put Hawkeye on the bottom of the library. The "explosive" trigger that followed came from a card that was now in the library, so the stack box and the trail treated it as hidden. An ability on the stack is public together with its source (CR 400.2, 113.7a), so it now names Hawkeye, and so does an ability activated from a hand (cycling, channel). Only a face-down source stays unnamed. The tests for hidden information still pass: nothing hidden is named.

### Added (the deck workspace in the Kotlin app)
- **Enter opens a deck to work on, as Moxfield opens one:** the deck on the left, search in the middle, the zoom pane on the right. `cd <deck>` opens it too, and Esc or `cd ..` goes back to the library. **In the library, P now plays** (Enter used to).
- **Search follows the open deck:** the commander's colour identity and the deck's format, named in the search pane's title.
- **Results are a grid of cards, the house frames with their art**, and T switches them to lines; the choice is remembered. Only the newest page is drawn as a grid; older pages in the scrollback stay lines.
- **The arrow keys select a result** (Up/Down move a whole row of the grid), the zoom pane shows it, and Enter opens its profile. Shift+T switches the deck pane between lines (the default beside a search) and frames.
- Read-only for now: adding and removing from the results is step 4.

### Changed (the search language: free text, and more of Scryfall's syntax)
- **A bare word or a quoted phrase now matches the name, the type line or the oracle text**; it used to be oracle text only. `bolt` finds Lightning Bolt, `goblin` every Goblin. Without `order:`, cards whose name matches come first: the exact name, then names that start with it, then names that contain every word, then type-line matches. `o:`, `t:` and `n:` still search one field. The TUI and the app changed together.
- **New:**
  - `m:` / `mana:`: the mana cost has these symbols, counted, so `m:{U}{1}` finds `{1}{U}` (also `m:2uu`); `m=` means exactly these.
  - `c:m`: multicoloured.
  - `otag:` / `function:`: a Scryfall Tagger function. It takes Scryfall's hyphens (`otag:mana-rock`) and finds a tag's children, so `otag:removal` works although no tag is plain `removal`.
  - `is:commander`, `permanent`, `spell`, `historic`, `dfc`, `mdfc`, `split`. A two-faced card is its front face.

### Changed (the Kotlin app's command line)
- **Its own pane, the border in the accent while it has the keyboard, and a hint line under it.** The hint reads the line back: a command's usage, or the query in words (`type has "instant" · colours include U · mana value ≤ 2`) with the number of cards it finds, or what is wrong with it.
- **A line that is no command is a search,** as in an address bar. A command typo that finds nothing is named (`did you mean card?`), and so is a typo in the query (`typ:` → `type:`, `is:comander`, `f:comander`).
- **Autofill knows the search language.** After a field it offers that field's values, most used first: types, keywords, Tagger functions, formats, `is:`, `order:`. Free words complete to a card name.
- **A click in the panes ends typing,** so T and the arrows answer again. **F7 switches text and art everywhere**, even while typing. Typing that opens another screen (`cd`) keeps the keyboard in the line.

### Added (step 3: lookup and search in the Kotlin app)
- **A command line in the library** (`:` or Ctrl+K to type, Esc to leave). Everything the TUI looks up works there: `card <name|N>`, `ruling`, `rule`, `search-rules`, `combo`, `combos a; b`, `combo-info <id|N>`, `correction`, `search` with `next` / `prev` / `page N`, `help [search]`, `copy [last|all]`, `clear` (Ctrl+L) and `quit`. The messages are the TUI's.
- **The output replaces the deck in the middle pane,** and Tab switches between them. A new command scrolls to its own start, and the text re-flows to the pane's width.
- **The output is clickable:**
  - a card name opens its profile, and hovering it shows the card in the zoom pane;
  - a combo's `[ N ]` or id opens that combo;
  - a rule number opens that rule;
  - `next` and `prev` page through a search.

  A click works on the value itself, so a name containing `//` or `;` is never parsed again.
- **Search follows a deck after `cd <deck>`.** It is limited to the commander's colour identity and the deck's format, and the filters are named above the results. The prompt shows the deck (`UW Draw Go>`), and `cd ..` goes back to the whole pool. `cd` only navigates. Decks are still edited in the TUI.
- **Autofill and history.** Commands, card names (including the last `;` segment of `combos`), rule numbers, deck names and help topics complete in grey; Tab or → takes the suggestion. Up and Down walk the history, and Down past the newest entry restores what you were typing.
- **The Kotlin port of the search language and the lookups** (`core/lookup`, `data`). `SearchParityTest` runs 47 queries and 17 names through Python and Kotlin on the same database, and they agree on counts, order and resolved names. The app's schema check now names every table the lookups read.

### Fixed (search inside a deck, and the corrections filter)
- **`search … order:asc_mv` inside a deck failed** with `unknown field: 'order'`. The deck's filters wrapped the query in parentheses, and that hid the sort token from the extractor. The sort is now taken out first and put back at the end.
- **`correction 100%` matched every correction:** `%` and `_` in the text filter worked as wildcards. They are now literal, as in every other search.

### Fixed (the Kotlin board said "face-down" of cards that were only hidden, and the trail named a hideaway card)
- **"Face-down" now means face down.** A card you may not see (the opponent's hand, a card you aren't allowed to look at) is a "hidden card", and a hidden stack target is "a hidden card". Only a card that really lies face down (a hideaway card, a morph) is called face-down.
- **The trail no longer names a card the opponent exiled face down.** When the AI's Shelldock Isle hid a card, the trail showed its name ("Opposition Agent library → exile"), because Forge turns the card face down only after the move. A card moving out of a hidden zone is now named from what it is once it lands: "a face-down card library → exile" for a hideaway, and "Duress library → exile" for Laelia's face-up exile.
- **What was not found:** no moment where a card exiled face up showed as hidden. Laelia, Ragavan casting our Brainstorm, and Deep-Cavern Bat, replayed from the user's Bo3, name the card in every board they produce.

### Fixed (floating mana was lost to an auto-pass on the Kotlin board)
- **What happened:** you floated {W}{W} in response to Blood Moon, to cast Get Lost once it resolved. The AI passed after it resolved, and with no stop in the AI's main phase your priority was passed for you, so the phase ended with the mana.
- **Floating mana is now a stop.** While your pool holds mana that the end of the step empties, nothing passes your priority for you: not the stops, F4, F6, End Turn, or auto-yields. When the pool is empty again, stops work as before, and an F6 from the same turn carries on skipping.
- **Passing with mana floating and an empty stack asks first:** `{W}{W} is floating and empties when this step ends — pass anyway?`, with [ Pass ] (Enter) and [ Stay ] (Esc). With something on the stack it doesn't ask, because priority comes back in the same step with the mana still there. Mana that doesn't empty (Upwelling) never asks and never stops.

### Added (a snapshot of the Kotlin app to play from)
- **`gradlew :app:installLocal`** copies a runnable app (jars, Forge's assets, launchers) into `app/dist/<timestamp>/` and keeps the newest three. Run it with **`app\run-mtg-oracle.cmd`** (or `.sh`), which always starts the newest snapshot.
- **Why:** playing from `gradlew run` crashed mid-game with `NoClassDefFoundError` whenever a build replaced the class files under it. The X-prompt crashes in Wrath of the Skies and Entreat the Angels were this, not the prompt.
- **Version:** the window title and `app.log` name what is running: the git short hash, a `-dirty` flag and the install time.
- **Pruning:** a snapshot that is still running is never pruned. On Windows its open jars stop the move, and it is removed on a later install.

### Fixed (the Kotlin app's text uses the window's width)
- The new-game screen cut its lines at 110 columns whatever the window's width, and the crash and start-up screens cut theirs at 100. Every line now takes the width it is given. Deck names and other facts are cut only where the pane really ends, and help lines wrap onto a second line, indented under their text. The sideboard lists no longer stop at 60 columns, and the damage-assignment prompt's target column is as wide as its longest target instead of 34. A word longer than a line, such as a file path, now continues onto the next line instead of being cut.

### Fixed (a crash in the Kotlin app was recorded as a concession)
- **An error in the window no longer closes it.** Compose's default showed a dialog and closed the window, and closing records the game on as conceded, which is how a crash at Wrath of the Skies' X prompt became a concession. Now the error is logged in full to `data/app/app.log` and the game's log, a crash screen opens in a fresh window, and leaving from it records the game as unfinished (no winner, not conceded). An error on any other thread, Forge's included, is logged and shown on the board's error line.
- **X costs suggest what you can pay:** the number prompt starts at the largest X the mana you have now pays for (`max affordable 3`). Any value can still be typed, and ranges up to Int.MAX work as before.

### Changed (the Kotlin board's news moves to a header at the top)
- Turn, phase, priority, the stack's top item, combat, the match, the folded `stack: N` bar and the trail (two lines) now sit in a fixed header above the opponent's half. The midline is a plain rule, so the middle of the board no longer flickers.

### Fixed (the Kotlin board: lands at the player's own edge; no fixed stack pane)
- **Lands sit at each player's edge,** just above your hand and at the top of the opponent's half, with the nonland zones at the midline and the free rows between. Before, a side with only lands drew them under the midline. The halves stay even while both fit, so the lands don't move when creatures arrive.
- **The right column is the zoom pane and the log;** the fixed stack pane is gone. The floating box is the stack's view: click an item to target it, hover it for its full text and targets. The midline still names the top item, and now any combat too.

### Changed (the Kotlin board sizes its card frames to the window)
- **Frames step down before cards overlap:** full art, then a compact art frame (a two-row crop, the artist credit, stats with cost and type), then a text-sized frame. The table picks the largest that shows every card on each side, and the hand follows yours.
- **No stranded space:** each half gets the rows its bands need. The opponent's gives up what it doesn't use, and no empty band sits between zones.
- At 1600×900, the crowded test board (9 lands, 3 artifacts, 2 planeswalkers, 6 creatures) now shows every card at compact size. At 1280×720 it shows every card at text size, overlapped. Before, both hid cards behind "+N ▸".
- The zone column needs a row less: revealed cards now sit on the hand line.

### Changed (tapped cards are unmistakable on the Kotlin board)
- **A real tap:** in art mode, tapped permanents (lands and land piles too) turn a quarter clockwise, art included. The card is scaled the same both ways to fit its upright slot, so tapping or untapping moves no other card, and clicking and hovering hit the turned card. R turns rotation off (remembered); text mode stays upright.
- **A tapped tone in every theme:** a red of its own, at 4.5:1 or better against each theme's background (house red, Rosé Pine's love, VS Code's error red, a readable Solarized red, a dark red on paper). It is used for the TAPPED label, the tapped frame's border and tapped chips.

### Changed (the Kotlin board: type zones, nothing clipped, piles that hold still)
- **Each half is split into type zones** from the midline out: creatures, planeswalkers · battles, artifacts · enchantments, lands. Each is labelled, and they share rows when rows are short.
- **No card is cut off the board any more.** Rows overlap their cards MTGO-style (the title strip showing and clickable, the hovered card on top), and scroll with a `+N ▸` count only as a last resort. The two halves split the height by what their cards need. Before, a crowded side ran off the right edge ("Tef…").
- **The zone column never draws over itself.** Its graveyard and exile lists get what height is left, and the stop ladder folds to one line in a short window. Before, "graveyard 17", "[Remand]" and "library 63" overlapped.
- **Piles of the same land stay adjacent.** Tapping one of two Islands split the pile and sent the untapped one to the end of the row. Now names keep their first-seen order, untapped before tapped.
- The same rules apply to the hand.

### Added (the mana pool on the Kotlin board)
- **Each player's floating mana** sits on a line of its own under their life total, in the costs' symbols (`pool  {U}{U} {C}×4`, or `—`), and updates as mana is added, spent or emptied.
- **Paying from the pool:** during your payment prompt, each colour is a button that spends one of it, as a click on Forge's own pool does.
- **The trail notes mana a resolving ability adds**, e.g. `Mana Drain: +{C}×4`.

### Fixed (colourless mana never showed in the pool)
- The board read the pool with Forge's colour codes, in which colourless is 0. The pool is keyed by mana atoms, where colourless has its own bit, so `{C}` was always missing. Mana Drain's four colourless looked like nothing.

### Added (matches in the Kotlin app: best of 1 / 3 / 5, sideboarding, concede)
- **Best of 1, 3 or 5,** chosen in setup (B) and remembered. Forge's own match runs the games, and the loser chooses to play or draw.
- **Sideboarding between games** on a screen of its own: the deck and sideboard side by side, a click moves one copy, a line says whether the deck is legal, then Done. The AI sideboards as Forge's AI does. This replaces the auto-answer that kept the main deck.
- **A result panel between games:** game N's result, the match score, Continue. When the match is over, the way back to the library.
- **Leaving:** Ctrl+Q, Esc with nothing to cancel, or `[ concede ]` in the prompt opens a menu: concede this game (the match goes on), concede the match (back to the library), or cancel. Closing the window mid-game records that game as conceded, without waiting on Forge.
- **Recording:** each game is its own `games` row, with `match_id`, `game_no`, `match_format` and `conceded`. The Kotlin test copies of the database are now migrated by `scripts/self_heal.py` itself.

### Fixed (the Kotlin app dropped every one of the human's triggered abilities)
- **Our triggers never reached the stack**: surveil lands, attack triggers (Restless Anchorage's Map), delayed triggers (Mana Drain's mana, Teferi's end-step untap), a revealed miracle, Solitude's ETB. For each trigger Forge asks the GUI which ability to play, and ours answered null because a trigger's view is never "playable". Forge dropped the trigger without a log line. The AI's triggers never ask the GUI, which is why they worked. `TriggersTest` now plays 14 cases in real games, each checking that nothing was decided for us.
- **Long choice lists could not be answered.** A library search's options below the prompt's fifth row were clipped. Options now fill the rows in columns, and scroll past that.
- **No decision is made for the human silently.** Forge's static dialogs now come to our prompts. Anything still auto-answered is logged `UNHANDLED` and shown as a warning on the board's status line.

### Added (the Kotlin app's board: a table that holds still, the stack over it, the other side's actions — Phase 6 step 2)
- **Lands are cards on the table,** stacked MTGO-style (`Island ×3`) and split whenever state differs (tapped, counters, attachments, marks, how the prompt treats them). An animated manland stands with the creatures.
- **Nothing moves on a click but the cards that changed.** The halves, midline, hand, prompt (fixed height, buttons always on one row), zoom, stack list and every card cell have set sizes. `LayoutStabilityTest` plays a real turn and measures them all.
- **No card shows "nocost".** A card without a mana cost shows none, everywhere.
- **The stack floats over the table.** It is a draggable, foldable (S) panel showing each item's source art (a sacrificed fetchland's too), who, what kind, the text and the targets, which are marked `◄` on the board. It steps aside for any card the prompt wants clicked, and its place is remembered.
- **The opponent's actions stop you and show.** Anything they put on the stack gives you priority (F4 included; F6 and explicit auto-yields excepted): "AI activated Polluted Delta — respond?". A trail on the midline shows what never waits on the stack (land drops, sacrifices, fetched lands, life, draws counted but never named), and the cards touched are marked `*` until your next decision.
- **Themes** (F8): house, Rosé Pine, paper (light), code dark, Solarized dark. The choice is remembered, and the first run takes the TUI's `rose-pine`.
- **Resizable side panes:** drag the zone or right column's border, or press Ctrl(+Shift)+←/→. Widths are remembered and clamped to the window.

### Added (printings, and the app's `games` table — Phase 6 step 2e)
- **A deck row remembers its printing.** `deck_cards` has two new columns, `set_code` and `collector_number`, both in Scryfall's spelling.
    - The parser now keeps `(SET) number` instead of stripping it: `1 Sol Ring (C18) 263`, `(PLST) DDN-64`, `(7ED) 76★` (a typed `76*` becomes `★`).
    - Import, load, replace and `add` carry the printing through, and `export` writes it back, so export→import keeps the art.
    - Set codes are stored as pasted, not validated. The database has only oracle cards, so there is no list of printings to check against. A printing nothing recognises only loses its art.
- **Replace and history treat a printing change as a change.** A printing-only change is a revision row with equal quantities. `deck_changes` stores the printing before and after, so undo restores it.
    - A pasted line without a printing leaves the card's current printing alone.
    - Each (card, section) has one printing; if a list names two, the last one wins.
- **Forge export writes the printing.** The line is `Name|CODE|[number]`, in Forge's own edition code, mapped through each edition file's `ScryfallCode=` and picked by collector number: `plst` → `PLST`, `med` → the right one of three `MPS_*` sets.
    - When Forge lacks that number, the line falls back to `Name|CODE`.
    - When Forge lacks the printing altogether, it falls back to the name alone.
    - The rule is documented in CLAUDE.md for the Kotlin app.
- **`games`**: one row per game the Kotlin app plays. Python creates it and the app writes it. Deck ids go NULL when a deck is deleted, so the record survives the deck.
- Everything above ships in one self-heal migration, `scripts/migrate_add_printings_and_games.py`, mirrored in `init_db`.
- **Matches:** `games` gains `match_id`, `game_no`, `match_format` (`bo1` / `bo3` / `bo5`) and `conceded`, for the app's best-of-N and concede support (`scripts/migrate_add_game_matches.py`). Existing rows keep NULLs and count as single-game matches.

### Added (Forge integration v1: export, AI substitutions, play, simulate, results)
- **`forge export [<deck>]`** writes the deck as a Forge `.dck` (TUI, CLI, and a clickable `[-> forge]` in the nav pane's deck header).
    - Names map to Forge's front-face spelling; Forge names split cards by their first half too.
    - Cards Forge doesn't know stop the export unless `--force` is given.
    - Cards Forge marks `AI:RemoveDeck:All` are named, with the suggestion to substitute them. City of Traitors is one: played on turn 1 with nothing to cast, then sacrificed to the next land.
    - Commander decks go to Forge's `commander` folder. Forge ignores `-D` and absolute paths, so the folders are fixed.
- **`forge sub add <card> -> <substitute>`** stores a Forge-only swap for a deck. Export then also writes `<deck> (AI).dck`, which the AI pilots in sims.
    - The deck itself never changes.
    - Substitutes are checked jointly against the deck's rules, as `add` would check them: colour identity, legality, singleton, points. The checks run through `decks.check_swaps`, which applies the swaps and rolls back. Ancient Tomb (1 point, deck at 10/10) and a second Dark Confidant are refused.
- **`forge play`** opens Forge. **`forge sim <deck> <opponent> [N]`** runs AI vs AI in a background worker, stores every game (`forge_matches`) and keeps the full log in `data/forge_logs/`. **`forge results`** shows the record or the win matrix.
- **Forge quirks handled:**
    - Forge exits 0 when a deck fails to load, so success is judged by parsed games.
    - When its clock stops a game, Forge reports player 1 as the winner; those games count as draws.
    - Commander runs at 40 life, so it is a proxy, not Duel Commander.
- **Ownership marker.** An exported `.dck` carries it, and export refuses to overwrite a file mtg-oracle didn't write, one written for another deck, or one edited in Forge. `--overwrite` replaces it.
- **Security review** (`/security-review`): nothing met the bar. Hardening added anyway:
    - Filenames lose cmd.exe's metacharacters as well as Windows' forbidden ones.
    - A `.bat` / `.cmd` java launcher is refused, because cmd.exe would re-parse the arguments ("BatBadBut").
- 54 more tests, including a real one-game sim in a sandboxed Forge.

### Added (replace a deck from a pasted list, with a change history and undo)
- **`paste --replace` / `import <file> --replace` in the TUI, and `deck import --replace` in the CLI**, make an existing deck exactly the pasted list. Everything happens in one transaction, and the result is shown as a diff (`+1 Sea Gate Restoration`, `-1 Day of Judgment`, `Counterspell 1 -> 2`).
    - Rows whose quantity didn't change keep their category and date.
    - **An unrecognised name stops the whole replace, and nothing changes**; `--force` applies it without that card. The first real use caught `Meduseld, Golden Hall of Edoras`, which is the LOTR printing name of Castle Ardenvale that Moxfield exported. The safeguard exists so that a typo can't delete a card from the deck.
    - An empty paste can't wipe a deck.
    - Moxfield stays the one place decks are edited. There is no API bridge: Moxfield has no public API, and its private one sits behind a support-issued user agent and Cloudflare.
- **Every content change is a revision** (`deck_revisions` + `deck_changes`): add, remove, commander promote and demote, import, load and replace. One user action is one revision, so a 100-card import is one. `history [N]` / `deck history` shows them newest first, in local time.
- **`undo` / `deck undo`** reverts the latest revision and records that as a revision too, so running it twice redoes. It refuses when the deck has changed outside the history since.
- 44 more tests.

### Added (the app and CLI heal the schema on start)
- **The deck-history migration exposed a gap.** Every deck edit, not just the new commands, failed with `no such table: deck_revisions` until the user ran `sync`, because only `sync.py` ran the migrations.
    - The list now lives in `scripts/self_heal.py`, and `mtg_app.py` and `mtg_cli.py` run it before opening the database.
    - When the schema is current it costs about 5 ms, measured.
    - Reports go to stderr, so a `--json` consumer's stdout stays clean.
- **`self_heal.run()` heals only the database it is given, or each migration's own path.** A test that points the data layer at a copy heals the copy, never the real file.

### Fixed (whole-codebase review, 2026-09-28 — about 70 findings, each reproduced before it was fixed)
Five parallel reviewers covered queries and search, decks and the parser, services and analysis, the renderer and TUI, and scripts/sync. Every finding below was reproduced against `data/mtg.db` or a copy of it. 193 → 381 tests.

- **Search language.**
    - `-pow>=4` dropped every card whose power is NULL: `NOT (NULL)` is NULL. Negation is now `NOT COALESCE(…, 0)`.
    - `pow!3` crashed with a raw KeyError.
    - A bare `and` became `o:and`, and a dangling `-` searched oracle text for a hyphen.
    - User `%` / `_` were LIKE wildcards: `n:_____` matched 35,112 cards. They are now escaped (`queries.like_literal`, `ESCAPE '!'`).
    - `order:` was extracted from inside quoted values.
    - Negative P/T never matched (Spinal Parasite).
    - `order:*_ci` counted commas, so colourless and mono tied.
    - A limit over 1000 reset to 50, so paging could never reach rows 51+. It is now clamped, here and in five `queries` helpers.
- **Name tolerance and lookups.**
    - The ASCII fold never found names starting with a non-ASCII letter (`eomer` → Éomer). SQLite's `LOWER()` is ASCII-only, so the first-letter prefilter is gone.
    - Double quotes are optional (Kongming, "Sleeping Dragon").
    - `get_rulings`, `find_combos_with_card` and `find_combos_with_all` skipped `resolve_card_name`. The last also returned nothing when a name was repeated.
    - `get_card` attached corrections by raw substring ('Strangle' got Strangleroot Geist's). It now matches the quoted JSON element.
    - `get_card`'s embedded combos lacked user combos and the `+` template flag.
    - Rule numbers sort naturally: 702.2 before 702.10.
- **Import and parser.**
    - A UTF-8 BOM broke the first line.
    - `Commander (1)` / `Sideboard (15)` headers, `SB:` prefixes, Archidekt `[Category] ^tag^` suffixes and the `Tokens` section were read as cards.
    - Maybeboard rows are now reported, not silently dropped.
    - Quantities are bounded at 1–999 (`99999999999999999999 Mountain` overflowed SQLite).
    - **Import is one transaction.** One bad row used to leave a half-loaded deck. A list with a Commander section auto-sets the format, as `set_commander` does.
- **Deck rules.**
    - `set_commander` checked legality before auto-setting the format, so a commander banned in Commander got in.
    - "Up to N cards named" (Nazgûl, Seven Dwarves) failed the singleton check.
    - Vintage restricted is one copy across main and sideboard combined.
    - A card could be added as commander twice, or while already in the 99.
    - Promoting a sideboard card to commander dodged the points budget.
    - `set_commander` silently deleted extra copies.
- **Deck names are unique per folder, case-insensitively** (`migrate_unique_deck_names`).
    - `decks.UNSORTED` addresses decks outside a folder. It is a different question from "any folder", and conflating the two made unsorted decks unreachable.
    - `get_deck` raises `AmbiguousDeckError` instead of returning None.
    - `/` is refused in folder and deck names.
    - The TUI's `cd`, deck clicks, `show`, `profile` and `compare` use real paths (`cd (unsorted)/X`).
- **Analysis model** (changes the numbers in reports).
    - Alternative costs are priced: Baleful Mastery 4→2, not 0.
    - Split cards cost their cheapest castable half; aftermath halves are not castable from hand.
    - Phyrexian symbols are subtracted: Dismember 3→1.
    - Self-sacrificing and next-upkeep draws are not engines (the Baubles, Mind Stone).
    - The `-self` veto silences only the ambiguous labels, so The Scarab God keeps recursion.
    - A recognised tag with no role gives `utility` from the tags, instead of falling through to the text rules.
    - `mana egg` is `ritual`, so Lotus Petal is not a rock.
    - Path to Exile is `spot` only.
    - Rocks sat in two piles, which double-counted them and could raise in `category_live`.
    - Every deck was modelled as 100 cards: a 60-card deck showed 0.21 on T1 instead of 0.39.
    - Hybrid, twobrid and Phyrexian pips are counted, and `{C}` has a bucket.
    - Mana sources parse only `Add` clauses. Any-colour lands and fetches count, clipped to the deck's colours. Without a commander, those colours are the union of every card's colour identity, so deliberate off-colour duals, e.g. for Prismatic Ending, still count.
    - `rank_cards` merged spellings of one card.
- **TUI.**
    - Nav combo links pointed at the wrong lines.
    - Combo tickets now carry the Spellbook id, not a list index.
    - An exception in a click handler or the sync worker killed the app.
    - `paste` garbled non-ASCII on Windows (OEM codepage).
    - `config.json` with a BOM was read as empty and then overwritten.
    - Names ending in a number were split (`add Pip-Boy 3000`).
    - Ctrl+←/→ never fired while typing.
    - The saved nav width was restored unclamped.
    - The cwd went stale after rename, move and rmdir.
    - Nav rows overflowed the measured width.
    - Help text had drifted.
    - Output tickets leaked past `clear`.
- **Sync and scripts.**
    - A non-card printing (`front_card`) with a real card's name overwrote it: Blink, No Way Out and 7 more were colourless "Card"s passing every `ci<=` filter. Name collisions keep the entry that is legal somewhere.
    - Numbered glossary lines became rules 1–5, and all 277 `Example:` lines were dropped, along with 119.1d, 606.5 and 704.5aa.
    - A source's `sys.exit` stopped the whole orchestrator.
    - An `"unknown"` HEAD marker made combos skip forever.
    - `--only` ran sources in typed order.
    - The self-heal list missed four migrations, and `migrate_fix_card_tags_pk` dropped the NOCASE index.
    - Retired custom formats and more stale tables are cleaned.
    - CLI: `deck remove --sideboard` was silently ignored, `deck move` without `--new-folder` moved to unsorted, and `deck add --commander` bypassed `set_commander`.
    - `analyse_archetype` could compare a deck with itself.
- **Tests no longer write to the user's database.** `test_compare_export` created and deleted a deck in `data/mtg.db`. Deck-writing suites now run on `tests/db_sandbox.py`'s copy.
- **Applied to the live database** on 2026-09-28: the cards and rules re-ingest (which also brought in CR 20260925), the prune of 266 `front_card` rows, and correction #11's JSON rewrite.
- **Corrections match a card's face names.** A correction written as 'Emeritus of Ideation' now reaches 'Emeritus of Ideation // Ancestral Recall'.
- **Face burn is burn, whatever Tagger's `spot removal` says.** The 2026-09-28 Tagger export added `spot removal` to 522 cards, including damage that can only reach players and planeswalkers. Lava Spike's primary became `spot`. `spot` is now dropped when `spot removal` is its only source, every damage label is `burn player` / `burn planeswalker`, and the oracle text cannot damage, destroy, exile or fight a creature. That covers 60 cards.
    - The text is needed because `burn planeswalker` also labels bites: Bite Down, Kabira Takedown and Flourishing Grapple deal damage to "target creature or planeswalker", and all keep `spot`.
    - The text is only ever a veto here. It never adds a role to a tagged card.
- **Tests no longer assert upstream tag data they don't own.** Tagger labelled Lava Spike `spot removal` in the 2026-09-28 export, so the replace-not-union rule is now pinned with frozen tags. The migration test drops the index from its copy before exercising it.

### Fixed (`mana` outranked the answers, so Path to Exile was a mana source)
- **`mana` sat second in `PRIMARY_ORDER`**, so that a Mox or a dork would win over whatever else it happened to do. But Tagger tags the *drawback* as well as the effect, and `land ramp` fires on **Path to Exile** because the opponent gets the basic. Path to Exile, Erode and Emergency Eject all classified as mana sources — as did **Teferi, Hero of Dominaria** and three Chandras, whose `+1` untaps lands or adds mana.
    - Nothing is played as a mana source *and* as removal; nobody casts Path for the land. Moving `mana` below `spot` / `counter` / `sweeper` / `discard` corrected **eleven cards across 34 reference lists and broke none** — a real mana source has no answer role to lose to, so Sol Ring, the Moxen, Birds of Paradise and Utopia Sprawl are untouched. Measured before the change, not asserted after.
    - It also fixes planeswalkers: `planeswalker` already outranked everything but `land`, `ritual` and `mana`, so a walker with a mana ability slipped past its own rule.
    - Found while reviewing the user's Elminster deck card by card — the deck was reported as 8 removal / 3 mana sources when it is 10 removal / 0. `mana_sources` was overstated by the same 2.
- 3 more tests, 193 total.

### Fixed (thirteen real decklists, four silent data-loss bugs)
Every one of these was found by pointing the pipeline at 13 Elminster lists from mtgtop8 and Moxfield. All four are silent by nature — the parser returns a plausible deck, the count even looks right, and cards are simply gone.

- **mtgtop8 type-group headings were parsed as cards.** It groups the main deck by type and writes the size into the heading: `40 LANDS (42)`, `28 INSTANTS and SORC.`, `7 OTHER SPELLS`. `_is_section_header` rejects any line containing a digit — deliberately, so `1 Island` can never be a header — which left these to `_parse_line`. Four headings added **99 phantom cards to a 100-card deck**. Now recognised as groupings *within* the main deck: skipped without changing section, except that they end a commander block, which is the only thing mtgtop8 has to say the commander is over (preserving the section there filed all 100 cards as commanders).
    - Two guards, because a closed vocabulary was not enough: there really are cards named `Lands`, `Spells` and `Artifacts`, so the heading must also be **upper-case**, which is how mtgtop8 writes it. `40 LANDS` is a heading, `40 Lands` is a card, with no judgement call about which is more likely.
- **Collector-number shapes that lost the card.** `\d*[a-z]?` matched none of `(PLST) DDN-64`, `(PLST) A25-50`, `(MED) WS3`, `(PUMA) U5`, `(PWCS) 2022-5`, `(7ED) 76★` or `*E*` (etched). One list lost **17 of its 100 cards** that way — they parsed to names that resolve to nothing, so they were reported as unresolved and dropped from every number.
- **`Unearth (Theme)` resolved to `Unearth`.** The old tail-stripper ate any 2–6 character parenthesised group, and there are cards called `Unearth (Theme)`, `Hazmat Suit (Used)` and `Imaginary Friends (Plane)`. Pre-existing. Real set codes are all-upper or all-lower, never Title Case — that is now the discriminator. Verified against all 35,228 names in the database: **zero are altered**.
- **A card could be reported as both missing from a deck and unique to it.** `compare_decks` keyed the card-level diff on the raw string, and a two-faced card arrives in three spellings (`Sink into Stupor`, `Sink into Stupor / Soporific Springs`, `Sink into Stupor // Soporific Springs`). Those counted as three different cards, so the same card appeared in both lists and `n_lists` undercounted it by however many lists spelled it the other way. Keyed on the canonical name now, with one set per list so two spellings in one list is still one list playing it.

### Fixed (a modal DFC with a land back is a land everywhere, not just in the mana count)
- The previous entry's reach change credited an MDFC's spell half to `role_mv` while `lands` also counted it. The draw maths partitions the deck into lands, rocks and spells and a card sits in exactly one bucket, so a role's total could exceed the spell pile — `probability.category_live` raises on that, correctly, mid-report. **Consistency is forced here, not chosen:** the model says Sink into Stupor is played as a land, so it is a land in the density table too. `mana_sources` still reports the flexibility separately (`39 lands, 42 with MDFCs`), so nothing is hidden — it is just not counted twice.
    - Visible consequence: a deck with 3 land-back MDFCs reads as 42 lands rather than 39, and its counterspell count drops by one. The Elminster deck goes from 22 counterspells to 21, which matches a hand count.
    - The constraint is now asserted over all 21 sample lists. It was unwritten, which is why it could break.
- 21 more tests, 190 total.

### Added (deck analysis reaches the app — `profile` and `compare` in the TUI and CLI)
- **The analysis was only ever in `scripts/analyse_archetype.py`.** `export` had made it to all three surfaces; densities, on-curve odds and comparison had not, and the reason was presentation: `print_report` and `print_comparison` wrote fixed-width tables straight to stdout, so the TUI could not reuse a line of it. The tables are now **`renderer.render_profile` / `render_ranking` / `render_comparison`**, and all three surfaces emit the same bytes.
    - `profile [<deck>]` and `compare <deck>` in the TUI; `deck profile` and `deck compare --against <deck>` in the CLI. `profile` with no argument takes the deck you are in, like `export` and `points`.
    - **Deck-to-deck against the database, deliberately not deck-to-folder-of-files.** The command language knows decks and folders in the DB; letting filesystem paths in would make it a second, weaker CLI. `analyse_archetype.py --dir` stays the surface for a folder of reference lists, and remains the only one that reports ranges.
    - The renderers take `role_labels` and `role_order` as arguments rather than importing `roles`, and read `services` dataclasses duck-typed — so `renderer` still imports nothing from the project.
- **A comparison against one reference deck now renders as a head-to-head.** The range verdicts exist to say "nobody in the reference set went there"; with one list the range is a point, so every difference read as `--> ABOVE every list`. The solo view drops the verdict column, the nearest-list table and the two-archetype CAUTION, keeps the card-level diff, and stops filtering that diff by "played by more than one list" — with one list, that filter hid all of it.
- **`services.rank_cards()`** and **`services.deck_cards_for_analysis()`**, moved out of the script. `rank_cards` reads like it belongs in `analytics` and cannot live there: it needs `get_card_facts` *and* `get_oracle_tags`, and needing a data call is the test for this layer. `deck_cards_for_analysis` was the script's `read_db_deck`, which the two new TUI commands would otherwise each have re-implemented.
- **`tests/test_layering.py`** — the layering contract in CLAUDE.md had no mechanical check, and this change added code to the pure layer. Imports are read with `ast`, not by importing the modules: a module that imports something forbidden still imports *successfully*, which is exactly why the check has to look at the source. Includes a probe asserting the check can actually fail.
- The refactor is **provably behaviour-preserving**: the script's output was captured before the move and diffed after, and all four cases (folder report, folder + compare, single deck on the draw, `--json`) are byte-identical.
- `analyse_archetype.py`'s density table now hides roles no list plays, since fourteen rows across nine deck columns is noise — reach still reports every role, so nothing goes unmeasured.
- **The analysis tables are pure ASCII now**, which `renderer`'s docstring always promised and the code did not deliver: an em-dash in `=== ON CURVE — ... ===` raised `UnicodeEncodeError` on cp437 / cp850 and killed the output outright. Pre-existing — the committed script failed the same way — and only found because `deck profile` put those tables on a new surface. Asserted, so it cannot creep back. Card names are exempt and always will be: `Lórien Revealed` genuinely cannot be spelled in cp437, but the chrome around it has no excuse.
    - One consequence worth naming: `analyse_archetype.py` still fails on cp437, because `roles.py` writes cost reasons like `Phyrexian mana — payable with life` and the most-played table prints them. Left alone deliberately — those strings are also the `reason` field in `--json`, which the LLM layer is meant to consume, and changing a data contract to suit a legacy codepage is the wrong trade.
- 31 more tests, 169 total.

### Fixed (the CLI printed tracebacks for a deck that does not exist)
- **`_cmd_deck` caught `DeckError` but never `ServiceError`**, so `deck export "No Such Deck"` ended in a traceback instead of the message the exception was already carrying. Pre-existing; found while testing the error paths of the two new actions, which inherited it. Now `deck error: no deck named 'No Such Deck'` with exit 2, for every deck action.
- `deck compare` declares `--against` through the existing `_DECK_REQUIRED_FLAGS` table rather than an inline check, so the missing-flag message matches every other deck action.

### Added (Scryfall Tagger oracle tags — the classifier stopped guessing)
- **`card_oracle_tags` + `scripts/sync_oracle_tags.py`** — Tagger is Scryfall's community tagging project: 4,525 functional labels (`counterspell-soft`, `mana rock`, `burn player-each`, `reanimate-creature`) applied by hand, published as official bulk data and rebuilt daily. 227,528 taggings over 35,019 of 35,228 cards — **99.4% coverage**. `roles.classify(card, tags=...)` now asks the tags first and falls back to its own text rules where a card is untagged. Prompted by the user asking whether Scryfall shipped tags too; it does, and they are far better than any regex.
    - **Tags replace the text rules rather than union with them.** Lava Spike settled it: `"3 damage to target player"` matched the spot-removal pattern, and unioning would keep re-adding `spot` to a card that cannot point at a creature. The type line still supplies `threat` and `land` either way — there is no tag for "this is a creature".
    - **Only direct taggings are stored.** Tags also form a parent graph, and expanding it was measured across 608 reference-deck cards: it bought one correct verdict (Swift Reconfiguration → removal) and cost four wrong ones, including Seasoned Dungeoneer becoming a *tutor* via the initiative dungeon's basic-land room. A hop cap does not separate the two — the over-reach is at one hop as well.
    - `queries.get_oracle_tags(names)` mirrors `get_card_facts`: tolerant name resolution, chunked, keyed by input name. `roles.py` still imports nothing from the project, so tags are fetched in the query layer and passed in.
    - Registered as the `oracletags` source in `sync.py`, with `migrate_add_oracle_tags.py` in `SELF_HEAL_MIGRATIONS`.
- **Five new roles, because the old nine could not describe an aggro deck**: `discard` (hand disruption), `burn` (damage aimed at a player — a clock, not an answer), `ritual` (one-shot mana), `recursion` (brings something back from a graveyard), and `mana` extended to enchantments and dorks. Run against nine Duel Commander lists the fall-through count went **40 → 10**.
- `analyse_archetype.py`'s density table now derives its rows from `roles.ROLES` instead of a hardcoded list, so adding a role shows up in the report instead of silently going unmeasured.

### Fixed (six classification bugs the new roles exposed)
- **A Vehicle is a threat.** `Smuggler's Copter` read as **hand disruption** — its own loot trigger matched `discard a card` — `Flywheel Racer` read as a **Mox**, and `High-Speed Hoverbike` fell through to utility. A Vehicle is a creature with an extra step; every aggro deck plays them to attack. Hand attack now requires an opponent doing the discarding, so looting is selection again.
- **A creature that returns *itself* is not recursion.** `reanimate-self` / `regrowth-self` now veto the `recursion` role for the whole card, not just their own label — Gravecrawler carries `reanimate-self` **and** `reanimate-cast`, and suppressing one label left the other to turn Rakdos aggro into a deck with ten recursion spells. Chainer, Nightmare Adept has `reanimate-cast` and no `-self`, and stays an engine. Judith's threat count went 22 → 30, Ellie's 28 → 35.
- **A ritual is not a mana source.** `ritual` now outranks `mana` in `PRIMARY_ORDER`. Dark Ritual is tagged both `ritual` and `adds multiple mana`; with `mana` winning it was counted as a rock and the on-curve model handed the deck a permanent extra mana from turn one onward. The Spirit Guides are overridden to `ritual` for the same reason — Tagger calls them `ramp`, but they are pitched from hand once.
- **`burn ` as a prefix swept up flavour tags.** `burn with set's mechanic` describes Arcane, and `burn-you` is Fireblast's drawback; both made Lava Spike removal. The family is now matched exactly: only a spell that can point at a creature or a permanent earns `spot`, so player-or-planeswalker burn stays a clock.
- **`deanimate self` is not removal.** 125 cards carry it against 23 for `deanimate`, and a prefix match made Detective's Phoenix a removal spell. `TAG_EXACT_ROLES` can now map a label to *no* role, which recognises it and stops the prefix rules from claiming it.
- **`reach` could fall below `primary`.** `counts` credited an MDFC's spell half and `role_mv` did not, so the report showed 22 counterspells with a reach of 21. Reach now credits the spell half — a Sink into Stupor is a counterspell you have when you need one, which is the whole reason to play a modal card — while the mana base and the deck's mana curve still treat it as a land. Asserted as an invariant over all 21 sample lists, alongside "primaries sum to deck size" and "rocks never exceed the mana count".
- **`low_confidence` no longer flags tagged cards.** It is a work queue for a human; a curated tag saying `hate-graveyard` is an answer, not a gap, and Rest in Peace sitting on that list alongside the genuine unknowns made it useless.
- 41 more tests, 138 total.

### Fixed (a card override asserted something that is a property of the manabase)
- **`Prismatic Ending`'s override said "converge caps X at 2 in two colours".** Three mana is three mana whatever converge reaches; what it *kills* depends on how many colours the deck can spend, which belongs to the manabase and not to the card. Adding one source of a third colour turns the same three mana from "exile mana value 2" into "exile mana value 3". Surfaced when the user swapped an Underground Sea for a Raugrin Triome and the comment became false.

### Fixed ({X} was priced by one blanket rule, and the rule only fits removal)
- **The floor for an `{X}` spell is now the smallest X at which the card does the job it is counted for**, not a single global X=2.
    - X sizes an **answer** → 2, unchanged. The smallest X that kills a real card in this format; X=0 would make Wrath of the Skies a two-mana sweeper.
    - X sizes a **draw** → 2, unchanged. One card for four mana is not card advantage, it is a bad Divination.
    - X sizes a **body** → **1**. One 4/4 flying Angel for `{1}{W}{W}` is a threat, full stop, and charging for a second Angel the role does not need overpriced the card by a full mana. Entreat the Angels was 7 hardcast / 4 on miracle; it is now 5 / **3**. Forth Eorlingas! drops 4 → 3.
    - Reported by the user, who pointed out that Entreat's miracle cost `{X}{W}{W}` is three mana in practice. The original justification for X=2 was explicitly about *answering* something — it was never argued for token makers, and applying it there was over-reach.

### Added (`--miracle`)
- `scripts/analyse_archetype.py --miracle` and `services.profile_deck(miracle=True)` cost the miracle cards at their miracle cost. Off by default, because miracle depends on draw order and pricing Terminus at `{W}` claims a deck can wrath on turn one. On when the pilot says they never hardcast them, which is a true statement about their own deck and makes the printed cost the wrong number for them.

### Added (repeatable card advantage is not the same as a spell that draws)
- **`roles.Classification.engine`** — True for a permanent whose draw *repeats*, so five planeswalkers that draw every turn read as an engine count rather than blending into fourteen spells that draw once. Prompted by the user's observation that planeswalkers act as both threat and card advantage: they already did carry both roles, but the tool could not say that Elminster and Memory Deluge are not interchangeable.
    - Derived, not listed: a loyalty ability, an activated ability, or a recurring trigger that draws counts; `When this creature enters, ...` does not, which is why Snapcaster Mage and Thundertrap Trainer are correctly excluded. Connive nets zero cards, so Ledger Shredder is not card advantage at all.
    - Fixes a related misfire: the cantrip cost test demoted **Faerie Mastermind** to selection because it costs two. The cost test only makes sense for a spell that resolves once; a two-mana permanent that draws every turn is card advantage however cheap it is.
    - `DeckProfile.engines` counts them per role, and the CLI's reach table has an `engines` column.
    - Lands are deliberately never engines — Library of Alexandria does draw every turn, but the `land` role is exclusive and counting it in a spell role would double-count the slot. Asserted so the limitation stays deliberate.

### Fixed (a mill effect read as mass removal)
- **`Exile all cards from target player's library` matched the sweeper pattern**, so Jace, the Mind Sculptor's `-12` made him mass removal. A sweeper clears the *battlefield*; the pattern now excludes library and graveyard zones. Surfaced while checking how planeswalkers classify.

### Added (compare a deck against a reference set, and export it)
- **`services.compare_decks()`** — one deck measured against a set of others. Verdicts are against the reference **range**, not the mean: being two cards off an average that spans nine is noise, while stepping outside a range nobody left is a choice worth knowing about. Reports per-role deltas, per-turn on-curve deltas, the nearest reference deck by role-density distance, cards the reference plays that this deck doesn't (ordered by how many lists play them), and cards only this deck plays.
    - `--compare DECK` in `scripts/analyse_archetype.py`, accepting a deck name or a `.txt` path. The subject is excluded from its own reference set — comparing a list to itself reports zero deviation and hides everything that matters.
    - The distance metric is deliberately unnormalised: the role with the widest spread in a reference set is the axis that defines the build (for UW control, threats at 4–13), so letting it dominate is the point.
    - When the reference set spans more than one build, the output says so and points at the nearest-list line, because a mean across two archetypes describes neither.
- **`services.export_deck_text()`** plus `export` in the TUI and `deck export` in the CLI — the deck as a `N Card Name` list, clipboard by default because the point is getting it into Moxfield without a file in between. `export <path>` writes a file; `--front-face` shortens two-faced names; `--grouped` adds `//` role headers that importers skip.
    - Full canonical Scryfall names are the default: unambiguous by construction, and accepted by every Scryfall-backed importer. `--front-face` deliberately does **not** shorten split cards — there is no card called `Fire`, only `Fire // Ice`.
    - The section headers are the ones `deck_parser` already understands, so an exported deck re-imports into the same deck. That round-trip is asserted, including on basics (stacked, not repeated), modal DFCs and split cards.
- 23 more tests, 84 total.

### Added (deck analysis: what a card does, and what it really costs)
- **`mtg_oracle/roles.py`** — derives a card's functional roles (counterspell, sweeper, spot removal, cantrip, card advantage, threat, tutor, mana, utility) and its *effective* mana cost from oracle text, the same deterministic approach `tag_cards.py` takes to tags. **80% of a real archetype's cards classify from text alone**, which is the whole point: an analysis that only knows a hand-curated list of cards is useless the moment you point it at your own deck. `OVERRIDES` carries the 25 judgement calls text cannot settle, each with its reason, so what is a rule and what is an opinion stays visible.
    - Effective mana is the load-bearing part. Force of Will is not a five-drop, Dig Through Time is not an eight-drop, and any density analysis run on printed mana value is measuring the wrong deck. Conventions applied uniformly: free alternative costs → 0, Phyrexian → 0, evoke-that-exiles → 0, warp → the warp cost (CR 702.185a), delve → its coloured pips, `{X}` → X=2 (counted per `{X}`, so `{X}{X}{W}{W}{W}` at X=2 is seven), a cheaper second face → that face.
    - **Miracle is deliberately NOT applied.** A pitch cost is reliable — you decide to pay it. Miracle is conditional on draw order, and pricing Terminus at `{W}` claims a UW deck can wrath on turn one. `Cost.alternative` reports it so the upside can be shown alongside instead of baked in.
    - `{X}` creatures with a printed body are priced at X=0: Wan Shi Tong, Librarian is a 1/1 for `{U}{U}`, so X only buys counters on top. A base 0/0 that dies at X=0 is excluded.
- **`mtg_oracle/probability.py`** — exact hypergeometric draw maths, no database, validated against Monte Carlo. `category_live()` answers "can I play this role on turn T", and the model matters: the naive "hold a card with mv ≤ T **and** T mana sources" answers a different question and produces a curve that *falls* after turn three. Holding three lands on turn six does not mean you cannot counter anything — it means you cast the two-mana counter. Available mana therefore decides which cards qualify, and the curve is monotonically increasing as it must be.
- **`scripts/analyse_archetype.py`** — point it at a folder of decklists, at decks in your own collection, or both. Densities, role reach, on-curve probabilities per turn, the mana-gap against the no-mana ceiling, and a most-played ranking per role sorted by effective cost. `--json` for the LLM layer. Byte-identical files are collapsed by default, because three of the twelve UW sample lists are duplicates and counting them twice skews every "played in N lists" figure.
- **`queries.get_card_facts()`** — bulk-fetch of just the columns classification needs, keyed by the name the caller asked for. `get_card` also pulls tags, abilities, rulings and combos: right for one card on screen, four extra queries per card for a hundred-card deck. 181 names in 2.5 ms.
- **`services.profile_deck()` / `profile_decks()` / `deck_profile_from_db()`** — compose the above into a `DeckProfile` with `live_curve()` and `ceiling()` on it. Decklist files and decks in the database reduce to the same shape.
- **`tests/`** — 52 tests, stdlib `unittest`, no new dependency. `python -m unittest discover tests`. The probability suite checks the exact maths against 120k-draw simulations and asserts the properties the model must have (monotone in turn, bounded by the ceiling, free spells live on turn one, six-drops dead on turn one). The roles suite runs against real cards in the database, because both bugs this code has already had were invisible on hand-written fixtures.
- **`docs/uw-canlander-report.html`** — the UW Canadian Highlander write-up, regenerated from the in-repo pipeline so the report and the CLI cannot disagree about a number.

### Fixed (three card-classification bugs, two of them found by the user)
- **A preparation card's inset frame is not castable from hand.** CR 722.3: *"Preparation cards can't be cast using the alternative characteristics found within their inset frames."* Emeritus of Ideation's inset frame is literally Ancestral Recall for `{U}`, and assuming adventure-like behaviour turned a five-mana 4/4 into a one-mana draw-three across an entire analysis. `CASTABLE_SECOND_FACE` is now a **whitelist** — adventure, omen, modal_dfc — with the rule for each and the excluded layouts named. Correction #11.
- **Wan Shi Tong, Librarian is always castable for `{U}{U}`.** It is a printed 1/1, so X=0 is a two-mana flash flier with vigilance, not a 0/0 that dies. The blanket `{X}` → X=2 convention priced a card the deck never has to pay for.
- **A modal DFC's land back made the card read as a mana rock.** Oracle text for a two-faced card concatenates both faces, so `": Add {W}"` from Ondu Skyruins matched the mana-source pattern and beat `sweeper` in precedence — Ondu Inversion stopped being a wrath. Role derivation now reads the front face plus only those second faces you may cast from hand.
- **Overrides keyed on a card's front-face name silently never matched.** The database stores two-faced cards as `Marang River Regent // Coil and Catch`; decklists, players and the override table all write the front face. Every override for a two-faced card was being skipped.
- **Planeswalkers were classified by whatever they could remove.** Jace, the Mind Sculptor's `-12` exiles a library, which read as mass removal. Planeswalkers are threats before they are removal.

### Fixed (the CLI's copy of the search syntax was documenting a language it didn't have)
- **`search help` existed twice, in two versions, and they had drifted to 15 identical lines out of ~52.** The CLI's copy never mentioned that `n:` is a *substring* match — the exact trap that produced a false test failure earlier in this session, because `n:"Lightning Bolt"` also matches `Emeritus of Conflict // Lightning Bolt`. It also omitted the two meanings of `restricted:`, that `game:paper` is what drops the Arena-only Alchemy `A-` cards, and that `order:asc_edhrec` is most-played-first. Duplicated documentation isn't a style problem; it's documentation that lies about half the time.
- The user-facing syntax reference is now `scryfall_search.SYNTAX_HELP`, living with the parser that implements it — so adding an operator and documenting it are one edit. The TUI appends the one paragraph that is true of it and not of the CLI (searches inside a deck being scoped to what the deck can play). The module docstring stays separate on purpose: it documents operator precedence and compiler behaviour, which is the implementer's reference, not the user's.
- Every card name quoted in the help text is asserted to resolve, and every example query to compile.

### Changed (`mtg_oracle/app.py` → `mtg_oracle/tui/`)
- **2,293 lines split six ways.** `tui/help.py` (the three help texts + `COMMANDS`), `tui/suggester.py` (autofill — the one TUI piece with logic worth testing alone), `tui/divider.py` (the drag handle), `tui/config.py` (persisted preferences), `tui/clipboard.py` (reading the system clipboard), and `tui/app.py` (the App itself, 1,675 lines). `python scripts/mtg_app.py` is unchanged; it now imports `mtg_oracle.tui`.
- **The old module is gone rather than left as a shim.** There was exactly one real consumer and it moved in the same commit; a re-export would have let a stale import look like it still worked.
- **`tui/config.py` states the repo root once.** Every path built from `__file__` has to count directories, and this package is one level deeper — `_run_sync` computed `parent.parent / "scripts" / "sync.py"`, which after the move would have pointed inside `mtg_oracle/`. It now goes through `REPO_ROOT`, asserted to resolve.
- **Not done, deliberately:** the App class is still one file. Splitting the command handlers into mixins was considered and rejected — a mixin reaching into `self._cwd_deck` / `self._write` / `self._refresh_nav` costs a reader more than the line count saves.

### Changed (a service layer, so the CLI, the TUI and the LLM layer share one implementation)
- **`mtg_oracle/services.py`** — one function per user intent, taking plain values and returning plain data. It exists because of what the drift kept costing: `paste` and `deck import` each grew their own copy of "parse a deckstring and load it" and only one was correct, and `commander <card>` skipped the legality check `add` applied. Anything two interfaces both need now lives here once. The rule for what belongs is *composition* — an intent needing more than one data call, or a decision before the call. A one-line pass-through to `queries` or `decks` does not qualify; that would just be a second copy of the API.
    - `DeckRef` replaces the `(deck, folder)` pair that every deck call passed positionally. `card_profile`, `deck_search_scope`, `deck_search`, `search`, `import_text_into_deck`, `create_deck_from_text`, `format_catalog`, `format_rules`.
    - `SearchPage` owns the pagination arithmetic. `max(1, (total + page_size - 1) // page_size)` was written out five times — four in `app.py`, once in the renderer — and `next` / `prev` / `page <N>` each re-derived it before deciding whether they were allowed to move.
    - The deck-context search filters (commander CI + format legality, ~35 lines inlined in the TUI's `search`) are now `deck_search_scope`, which is what a deckbuilding assistant will need to ask "what can this deck play".
- **Presentation moved out of the App class into the renderer**, which is where the rest of it already lived: `render_format_effect`, `render_known_formats`, `render_search_nav_hint`, `render_deck_filter_notice`. `renderer` still imports nothing from this project — a caller wanting the singleton flag passes it in rather than the renderer reaching for a database.
- **`render_combo_list` absorbed the App's numbered copy.** Two near-identical 15-line renderers differing only in the row label; one function with `numbered=` now serves the CLI (Spellbook ids, which is what you'd paste into the next command) and the TUI (`[  1]`..`[  N]` plus click targets, matching what `combo-info <N>` resolves against).
- The TUI no longer imports `scryfall_search` or `deck_parser` at all, and `app.py` is down 281 lines.

### Fixed (`format` claimed no rules applied to a deck that was enforcing one)
- **`queries.resolve_format()` returned `singleton: None` for every Scryfall format**, leaving each caller to ask `decks._is_singleton_format()` — a private name reached across module boundaries — for the real answer. It now answers itself, and the constants live next to the rest of the format knowledge in `queries`.
- That gap was visible in the app: a deck with `format highlander` **does** reject a second copy (`highlander` is in the community fallback set), but `format` reported *"not a format this build has rules for — no legality / singleton / points checks apply"*, because `resolve_format` returns None for a format with no definition file and the effect text was derived from that alone. It now says what actually applies: no card pool, but singleton yes. Verified by rejecting a second Lightning Bolt in exactly such a deck.

### Fixed (two-faced cards were lands in analytics and spells in the decklist)
- **Analytics classified a card by its combined type line, the deck renderer by its front face.** `"Land" in type_line` made `Emeria's Call // Emeria, Shattered Skyclave` (`Sorcery // Land`) a pure land, so the same deck read 42 lands in the analytics block and 39 in the Lands group. Analytics now classifies by the front face, matching the renderer — and the two are asserted to agree card by card.
- **The consequence was worse than the inconsistency.** Counting an MDFC as a land meant `continue`, so its front face's mana value and coloured pips were dropped from the curve and the pip count entirely. In the reported deck three MDFCs were missing: the curve gained one card at MV 3 and two at 6+, and five pips appeared.
- **MDFC land backs are now reported separately**, in the shape asked for: `lands: 39 (42 with MDFC)`, with the sources header using the inclusive total. A modal DFC with a land back is both a spell you cast and a land you may play, so it counts in the curve, the pips *and* the mana sources — spelling out both numbers keeps that from looking like the totals don't add up. `land_count + nonland_count` still equals the deck size, asserted on every real deck.
- **Only layouts where you choose a face grant the land.** A `transform` back (`Arguel's Blood Fast // Temple of Aclazotz`) is reached by transforming, not by playing it, so it stays a spell and contributes no mana source. A card whose *front* is a land (`Land — Town // Sorcery — Adventure`, `Balamb Garden, SeeD Academy`) is a full land. A Pathway is `Land // Land` — a full land that is a source of **both** colours, since you pick a side; reading only the front face had it counting for one.
- `_is_land` now matches `Land` as a card type rather than as a substring. `get_deck` selects `layout` and `card_faces` so analytics can tell these cases apart at all.

### Added (`format` autofill)
- `format <prefix>` completes like a card name: `format canl` → `format canlander`, `format comp` → `format competitivebrawl`, `format canadian` → `format canadian highlander`, `format --un` → `format --unset`. Both the canonical keys and the custom formats' aliases are offered, which is the point — the hard part is remembering whether a format went in as `canlander` or `canadian highlander`.

### Fixed (the app appeared to hang when dragging the pane divider)
- **`set_nav_width` did the expensive half on every mouse-move.** A drag emits one `MouseMove` per column crossed, and each one re-rendered the whole nav pane and wrote `config.json`. Measured: **24 seconds for a 24-column drag**, 1 s per column. The divider now reports movement and completion as separate messages — `Dragged` only moves the boundary (0.01 ms), `DragEnded` does the re-render and the config write, once. Verified by counting: 0 re-renders and 0 config writes across 12 drag moves, exactly 1 of each after release.
- **The nav refresh was slow on its own — 801 ms — and it runs after every command.** The cause was a project-wide indexing gap, not the drag: every name index was on the default BINARY collation, and SQLite cannot use a BINARY index to satisfy a `COLLATE NOCASE` comparison. The convention the whole codebase follows was therefore forcing full table scans on its hottest queries. `get_deck`'s `LEFT JOIN cards ON c.name = dc.card_name COLLATE NOCASE` scanned all 35k cards *once per deck row*.
    - `scripts/migrate_add_nocase_indexes.py` adds an index per case-insensitively-compared column (8 of them). Measured on the real database: **`get_deck` join 747 ms → 0.8 ms (884×)**, name lookup 0.89 → 0.02 ms, legality lookup 14.7 → 0.05 ms, `combos_in_deck` 47 → 2.9 ms. Cost: ~23 MB on a 270 MB database, ~0.5 s to build. In the self-heal list, and in `init_db.py` for fresh databases.
    - This was never a drag-only problem: every `add`, `remove` and `cd` paid the 800 ms, because the TUI refreshes the deck pane after each command. A nav refresh is now 11 ms.
- Guessing wrong here was instructive: the first hypothesis was `combos_in_deck` against 104k combos. It was 47 ms of the 801. Profiling before fixing pointed at `get_deck` instead.

### Added (folder default format)
- **`deck_folders.format`** — a folder can carry a format, and decks created in it inherit it. This is what makes the organisation the user already has mean something: a deck dropped into a folder called "Canadian Highlander" now gets Canlander's rules without being told twice. It is a *default*, not an override — a deck's own format always wins, and the folder only fills it in at creation.
- `format` in a folder shows the default; `format <name>` sets it and says plainly that existing decks are unchanged; `format <name> --all` also stamps decks in the folder that have **no** format, never overwriting one that does; `format --unset` clears it. At root, `format` explains there's nothing to set there.
- The nav tree shows the default beside the folder name (`Canadian Highlander  [canlander]`) — where you create decks is where the inherited format belongs.
- Applied to the user's own folders: both Canlander decks are at 10/10 points, and `Azami, Lady of Scrolls` — the one deck that had no format — picked up `commander`.

### Added (navigation and layout by mouse)
- **Clickable breadcrumb at the top of the nav pane** — `/ Canadian Highlander / Blue Moon`, every segment a link. Inside a deck the pane switches to that deck's contents, so the tree that got you there is gone; before this there was no mouse route back to a folder or to root at all. Clicking a folder segment now uses an absolute `cd /<folder>`, because the bareword form is refused from inside a deck.
- **Draggable pane divider.** Textual has no splitter widget, so `PaneDivider` is the whole mechanism: capture the mouse on press, report the pointer column while it moves, release on let-go. The App decides what a column means for the pane width; the divider knows nothing about the panes it sits between. `Ctrl+Left` / `Ctrl+Right` do the same from the keyboard, the split is clamped so neither pane can be squeezed out, and it's remembered in `data/config.json` like the theme.
    - Resizing rebuilds the nav pane rather than reflowing it: the compact deck view truncates card names to the pane, so the contents have to be re-rendered at the new width. The renderer now takes the pane's *measured* `content_region.width` instead of a constant, which also means a resize can't silently invalidate it.
- **`format` command** — the missing switch for every format rule. There was no way to set a deck's format from the TUI at all: `add <deck>` created it with none, and only `commander <card>` ever set one (to `commander`). So a deck sitting in a "Canadian Highlander" folder had no points, no legality checks and no singleton rule, with nothing on screen explaining why.
    - `format` shows the current format and spells out exactly which rules it turns on (card pool + ban list, points budget, singleton), `format <name>` sets it, `format --unset` clears it. The name is stored verbatim even when unrecognised — `decks.format` has always been free text and refusing unknown names would stop people labelling decks for formats we don't model — but the reply says plainly that no rules apply.
    - The suggestion list includes custom formats, not just Scryfall keys. Listing only the latter omitted Canadian Highlander, which is the one format the points feature exists for.
- **The nav pane now says why points are missing.** `no format — see \`format\`` appears where the badge would be, instead of the rows silently not being there.
- The `folders / decks` header row is gone: the breadcrumb is the pane's heading now, and two separator lines in a row read as a rendering glitch.

### Fixed (mouse clicks did nothing — the action was in the wrong namespace)
- **The `@click` meta named an unqualified action.** A click is brokered with the *widget* it landed in as the default namespace, so `click_target(...)` was looked up on the `RichLog` — where it doesn't exist — and silently never ran. It has to be `app.click_target(...)`; Textual's valid namespaces are `app`, `screen` and `focused`. Textual's own widgets get away with bare names because their actions live on the widget (Markdown's `link`, Bar's `range_clicked`); ours live on the App.
- **The previous commit's testing note was wrong**, and the wrongness is what hid this. It claimed `pilot.click` couldn't deliver Click events in this harness, on the evidence that a spike using `pick(...)` failed even for Textual's own `Static`. The spike had the *same* missing namespace. With `app.pick(...)` it passes immediately — `pilot.click` works fine, and the "can't test end to end" conclusion was self-inflicted. The mouse suite now drives every path through real `pilot.click`, which is what would have caught this in the first place.
- **A link click left the keyboard nowhere useful.** `RichLog` is focusable (for scrolling), so clicking a pane moved focus off the command input and the next keystroke went to the log. Since a click is a shortcut for typing a command, `action_click_target` now hands focus back to the input.
- Removed a deck named `__adversarial__` left in the user's database by a review script that crashed before its own cleanup — the source of a stray `(unsorted)` section in the nav pane.

### Added (mouse support)
- **Clickable regions in both panes, with no change of widget.** The first assumption was that `RichLog` couldn't carry click targets and the panes would have to become `DataTable`/`OptionList` — which would have changed the look. Testing that assumption disproved it: `get_style_at` reads back `{'@click': ...}` on exactly the characters a card name occupies, so `RichLog` preserves Rich style meta perfectly, and `App._broker_event` dispatches it to an action. That's the same mechanism Textual's own Markdown widget uses for links.
    - **Left tree:** a folder or deck name → `cd` into it.
    - **Left pane inside a deck:** any card name (including the pinned commander) → its full profile in the right pane; a combo's `[ N ]` row number → expands that combo.
    - **Right pane:** search-result rows, `show` deck rows, and combo row numbers, same targets.
    - Clicks echo the equivalent command (`> card Ashnod's Altar`), so the mouse teaches the keyboard interface rather than hiding it.
- **Click targets are integer tickets, never interpolated names.** The `@click` meta is a string parsed by Textual's action parser, and card names are full of apostrophes, commas and `//` — `Thassa's Oracle` and `Jace, Vryn's Prodigy` would need escaping that has no good answer. Each region instead gets a ticket into a table the app owns. Nav-pane tickets are recycled when the pane re-renders (its old lines are gone); output-pane tickets persist, because scrollback stays clickable. An unknown ticket is a silent no-op.
- **Renderers report their own link spans.** `renderer.LinkSpan(line, start, end, kind, args)` — the renderers are the only code that knows column widths, padding and truncation rules, so they emit the coordinates instead of leaving the UI to re-derive them by pattern-matching output it was just handed. `render_search`, `render_deck`, `render_deck_compact` and `render_combos_compact` take an optional `links` list; the CLI passes nothing and is unaffected.
- Hover feedback via `link-style-hover: bold underline` — clickable text carries no decoration at rest, so the plain-text look is unchanged, and announces itself under the pointer. Everything remains reachable by typing; the mouse is a shortcut, not a second interface.

### Fixed (nav pane overflowed its own width)
- `_refresh_nav` passed `NAV_WIDTH - 2` to the compact renderer, subtracting the pane's padding but not its border, so the widest rows ran two characters past the visible area — `1x Jace, Vryn's Prodigy // Jace, Telepath Unb..` at 50 columns in a 48-column pane. Now `NAV_CONTENT_WIDTH = NAV_WIDTH - 4`, verified by asserting no rendered line exceeds the pane's measured content width.
- `render_points` ignored the pane width entirely, so a long pointed card name overflowed by 13 characters in the side pane. It now accepts `width` and truncates while keeping the points marker.
- Nav pane widened 48 → 52 columns: appending `(8)` markers to card names made the previous width truncate more aggressively than it used to.

### Changed (points visualisation)
- Points moved out of the header's metadata row onto their own line directly under the format — `Points   10 / 10   (0 left)` — because in a points format that's the first thing you check, not a footnote. The list of pointed cards moved with it, from the bottom of the deck view to right under the header.
- The list reads `Card Name (8)` rather than a right-hand points column, and the per-card marker in the decklist moved from a trailing `<8p>` (after the type line, easy to miss) to `(8)` immediately after the name, inside the name column so the mana-cost column stays aligned.
- The marker is appended *after* truncation, so it survives on long names. Naive truncation dropped it on exactly the cards where it matters: `Tamiyo, Inquisitive Student // Tamiyo, Seasoned Scholar` is 53 characters and lost its `(1)` in both the full and compact views.
- The side pane keeps the compact `[10/10 pts]` badge (with `!` when over), since a 46-column pane has no room for a headline row.

### Fixed (internal review of the 2026-08-19 work — 15 findings)

An adversarial pass over the same day's commit, run because the author of a
change is the worst reviewer of it. Two findings were severe enough that the
work should not have shipped without them.

- **`prune_stale_cards.py` could delete the entire `cards` table.** Its
  staleness test included `games IS NULL`, and a migration that *adds* a
  column sets it NULL on every row — so on any database where
  `migrate_add_legalities` had run but the card ingest had since been skipped
  (upstream unchanged, no `--force`), all 35,228 rows looked stale and `--yes`
  would have taken them. Each clause is now only used once its column has
  data behind it, and a sanity valve refuses to act when more than 5% of the
  table looks stale — a count near the table size means the premise is wrong,
  not that the database is full of junk. Verified against a copy of the real
  database in exactly that state: 0 rows deleted, while 3 genuinely stale rows
  in the same copy were still pruned.
- **A plain `sync` could leave the app unusable.** The self-heal migrations
  create `card_legalities` and `cards.games`, but `sync_cards` skipped the
  ingest that fills them whenever `updated_at` matched upstream — leaving an
  empty legality table, which makes every `f:` search return zero and every
  deck `add` fail with "not legal in commander". `updated_at` only says the
  upstream *file* is unchanged; it says nothing about whether this build has
  ever written the columns it now reads. `needs_backfill()` detects that and
  re-ingests, announcing why.
- **`commander <card>` bypassed every legality rule.** `set_commander()`
  flips an existing row in place and never went through `add_card_to_deck`'s
  validation, so in a Duel Commander deck it accepted both Edgar Markov
  (banned *as commander*) and an outright-banned Sol Ring. The check is now a
  shared `_assert_legal_in_format()` called from both paths, with
  `commander --force` to override. The promote path passes a quantity *delta*
  of 0, so promoting a Vintage-restricted card already in the deck isn't
  falsely rejected for exceeding its own one-copy limit.
- **`migrate_add_tags` still created the old two-column `card_tags` PK** — and
  it is in `sync.py`'s self-heal list, so on any database missing that table
  sync recreated the exact non-determinism this day's work removed from
  `init_db.py`.
- **Pointed sideboard cards were both under-enforced and unreported.**
  `_assert_points_fit` charged them against a budget `deck_points` computes
  from main-deck rows only, so two 8-point sideboard cards could both pass
  while the badge read `[2/10 pts]`. Sideboard rows are now explicitly not
  charged, matching what's measured and reported.
- **`load_parsed_into_deck` swallowed structural errors.** The rewrite
  dropped the original `raise` for non-validation `DeckError`s, so a deck
  deleted mid-paste produced 100 identical "rejected" lines and a success
  exit instead of one loud failure. With `force=True` — where no validation
  rule can fire — anything but "card not found" now propagates.
- **`_run_sync` had no `try`/`finally`**, so any exception or worker
  cancellation latched `_sync_running` True for the rest of the session and
  orphaned the child process against the same database.
- A typo in a format definition's `derives_from` became a legality key with
  no rows, rejecting every card with a confident "not in the format's card
  pool"; it's now validated against `LEGALITY_FORMATS` at load time.
- The points check ran before the singleton check, so a duplicate pointed
  card got budget arithmetic instead of "you already have one".
- An unmigrated database raised a bare `sqlite3.OperationalError: no such
  column: games`. Both front-ends now add "your database predates this
  version — run `sync`".
- Removed as dead on arrival: `decks.get_deck_legality_format()` (no callers),
  the `SINGLETON_FORMATS` alias (no readers), and
  `queries.legality_format_or_none()` — `resolve_format()` is the single
  entry point, as CLAUDE.md already claimed.
- `ingest_cards` accumulated all 367k legality rows in one list, undoing the
  flat-memory property the JSONL rewrite was for; it flushes every 20k rows.
- `download_bulk` deletes the superseded uncompressed `.json` cache — 198 MB
  of files no code path would ever read again, in a directory contributors
  are told not to hand-edit.
- `_suggest_card` was a line-for-line duplicate of `_suggest_from`; the
  `card_tags` PK rationale comment in `init_db.py` sat above the wrong table;
  and `get_deck` opened three redundant connections per call on the path
  `_refresh_nav` runs after every command.

### Added (community formats + points — phase 3c, second half)
- **`custom_formats` + `custom_format_points`** for what Scryfall structurally cannot express: Canadian Highlander doesn't ban its strongest cards, it *prices* them and caps a deck at 10 points. `derives_from` names the Scryfall format whose card pool is inherited, so legality is never duplicated. No `custom_format_bans` table yet — a format either inherits a pool or has none, and a bans table waits for a second concrete need.
- **`data/formats/canlander_points.json`** — the 43-card points list, curated and versioned in git rather than scraped. The list lives on an HTML page with no JSON, CSV or API and changes roughly quarterly; a scraper would be a parser waiting to break for a file you can retype in five minutes. The file carries `source_url`, `list_current_as_of` and a `verified_at` date so staleness is visible instead of assumed.
    - Tiers 1/2/3/5/7/8, budget 10. Ancestral Recall is the only 8; Black Lotus, Flash and Time Vault are 7.
    - Cross-checked against the March 2026 announcement's five changes (Thassa's Oracle 6→5, Time Walk 6→5, Tamiyo 0→1, The One Ring 0→1, Minsc & Boo 1→0) — all five agree — and the June 1 2026 announcement was "no changes". A first extraction attempt of the same page produced three mutually contradictory readings (42 cards / 47 cards / Black Lotus at 7 / Black Lotus absent), which is exactly why the file records how it was verified.
- **The ban side needed no new data at all.** The format's own page states "Canadian Highlander shares a banned list with Vintage", and every category it names separately — ante, sub-game, conspiracy, acorn, dexterity, silver-bordered, stickers/attractions — is already banned in Vintage (spot-checked with Contract from Below, Shahrazad, Advantageous Proclamation, `_____ Goblin`, Chaos Orb). So the Canlander pool is `f:vintage`, 31,742 cards, from data already in the database. Vintage's *restricted* list is moot: the format is singleton, so everything is one copy anyway.
- **`scripts/load_custom_formats.py`**, wired into `sync.py` as a fifth source (`--only formats`). No upstream fetch — it validates and loads `data/formats/*.json`. Every card name goes through `resolve_card_name` and an unresolvable name is a **hard failure**, not a warning: a typo silently under-counting a deck's points is the exact class of quiet wrong answer this project exists to prevent. The changelog table grew a `points` row.
- **`queries.resolve_format()`** — one place that answers "what is this format string": canonical key, human label, inherited legality key, points budget, singleton flag. Scryfall keys and custom formats both resolve through it, and the custom table is cached (cleared after a sync). `f:canlander` in search compiles to Vintage's pool; the strict `normalize_format()` now lists custom formats in its error message too.
- **Points enforcement.** `add` rejects a card whose points wouldn't fit — *"'Sol Ring' costs 3 point(s) in Canadian Highlander; the deck is at 8/10 and would go to 11"* — with `--force` to override. Going over budget is then reported, never hidden: the badge reads `[11/10 pts!]`.
- **Points display.** `[7/10 pts]` badge in the full and side-pane deck headers, a `<3p>` marker beside each pointed card in `show`, a points breakdown section (dearest first, with points remaining), and a `points` command in the TUI.
- Legality errors on a custom-format deck now name both the format and its inherited pool — *"banned in Canadian Highlander (inherits vintage's card pool)"* — because "not legal in Canadian Highlander" alone reads like a filter we have no data for.
- `_is_singleton_format` reads a custom format's own `singleton` flag, so the JSON is the single source of truth for Canadian Highlander rather than a hardcoded string set.
- Verified against the real deck in the database: *Izzet Delver* sits at exactly **10/10** — Ancestral Recall (8) + Tamiyo (1) + Treasure Cruise (1).

### Added (format legality — phase 3c, first half)
- **`card_legalities` table** — 367,217 rows across all 23 formats Scryfall tracks, straight out of the `oracle_cards` bulk export we already download (no new upstream source). Rows with status `not_legal` are dropped: they're ~55% of the payload and carry no information, so "no row" means "not legal" everywhere. Wiped and rebuilt on every card sync, because a stale `banned` row is worse than no row.
- **`cards.games`** (CSV of paper / mtgo / arena / astral / sega), **`cards.reserved`** (570 cards), **`cards.edhrec_rank`** (32,065 cards, 1 = most played). `scripts/migrate_add_legalities.py`, in `sync.py`'s self-heal list.
- **Search operators.** `f:FORMAT` (aliases `format:` / `legal:`) matches `legal` *or* `restricted`, since a restricted card is legal to play — one copy of it. `banned:FORMAT` and `restricted:FORMAT` for the ban and restricted lists. `game:paper|arena|mtgo`. `is:reserved`. Format names fold spaces, hyphens and underscores, so `f:"competitive brawl"` works, plus aliases `edh`, `pdh`, `duelcommander`, `pennydreadful`, `cbrawl`. An unknown format is an error listing the valid ones rather than an empty result set that reads as "nothing is legal".
- **`order:asc_edhrec` / `order:desc_edhrec`** — sort by EDHREC popularity. `asc` is most-played first, which is what you want when browsing candidates for a deck.
- **`n=NAME` — exact name match.** `=` means "exactly" everywhere else in the language (`c=`, `ci=`), and it now does for names too. `n:` stays a substring match — which is why `n:"Lightning Bolt"` returns two cards: there is also an `Emeritus of Conflict // Lightning Bolt`.
- **Deck-level enforcement.** `decks.format` now drives legality the same way `is_commander` drives color identity: inside a deck, `search` is hard-filtered by `f:<deck format>` alongside `ci<=<deck CI>`, with both filters announced above the results. `add` rejects cards that are banned in the format (one message) or outside its card pool (another), overridable with `--force`. Unlike the CI check this one applies to commanders too — an illegal commander is still illegal. Community formats with no upstream list (Canadian Highlander, Highlander) are left unchecked until custom formats with points lists land.
- `mtg_oracle.queries.LEGALITY_FORMATS` / `normalize_format()` / `legality_format_or_none()` — the strict variant backs the search language, the lenient one reads free-text `decks.format`. Both help screens generate their format list from the constant so they can't drift.
- `card <name>` shows a Legality block: legal / restricted / banned formats on their own rows, plus printings, Reserved List status, and EDHREC rank.
- Worked example — the ask this was built for: `f:competitivebrawl` is 15,747 cards, `ci<=UR` narrows it to 5,997, `t:instant` to 845. And `banned:brawl f:competitivebrawl` returns the 33 cards that separate the two Brawl ban lists (Force of Will among them).

### Fixed (format legality follow-up — `restricted` is two rules, not one)
- **`restricted` does not mean the same thing in every format.** In `vintage` / `oldschool` it's a one-copy limit; in `duel` (Duel Commander) and `tlr` (Tiny Leaders: Reborn) it means **banned as commander but legal in the deck**. Verified against duelcommander.org, whose "these cards cannot be used as your commander, but may be included in the 99" list is exactly Scryfall's 27 `duel` restricted rows; both `duel` and `tlr` restricted lists are 100% legendary, the copy-restriction lists are not. `queries.RESTRICTED_MEANS_NO_COMMANDER` encodes the split. `add` now rejects such a card only when `is_commander=True` ("banned as a commander in duel — it may still be in the deck"), and enforces a genuine one-copy cap in the copy-restriction formats, which matters for non-singleton decks (`vintage` allows 4x Lightning Bolt but 1x Black Lotus). `card <name>` renders the two as separate rows, `1 copy only` and `not as cmdr`.
- **Singleton enforcement missed the format strings people actually type.** `SINGLETON_FORMATS` was a flat list of long spellings, so `canlander` — the format on the user's own Izzet Delver deck — got no singleton check at all, and `gladiator`, `pauper commander`, `pdh`, `predh`, `tlr` were missing entirely. `decks._is_singleton_format()` now folds the string through the same alias table the search language uses and matches on `SINGLETON_LEGALITY_FORMATS` (the 10 singleton Scryfall formats) or `SINGLETON_COMMUNITY_FORMATS` (`canlander`, `canadianhighlander`, `highlander`, `ozhighlander`, `leviathan`). 29 spellings covered by test.
- `historicbrawl` → `brawl` and `tinyleaders` → `tlr` added to the format alias table; Arena renamed Historic Brawl to Brawl, and Scryfall's key for Tiny Leaders is `tlr`.
- `_LABEL_W` in the renderer was exactly as wide as its longest label, so `not as cmdr` and `1 copy only` ran into the value column (`not as cmdrduel`).

### Fixed (2026-08-19 audit — sync pipeline unbroken, review pass)
- **Scryfall bulk sync was dead.** Scryfall retired the uncompressed `download_uri` field in favour of `jsonl_download_uri` (gzipped JSON Lines) and `size` → `compressed_size`. `sync_cards.py` died with a bare `KeyError: 'download_uri'`, so cards and rulings had silently not updated since 2026-05-03. Now downloads the `.jsonl.gz`, streams it to `data/raw/scryfall_*.jsonl.gz`, and parses it line by line — peak memory is flat instead of holding a ~165 MB export as one Python structure. A missing field now raises a message naming the fields that *are* present.
- **`init_db.py` did not create `card_tags`, `card_abilities`, or `corrections`.** They existed only inside migrations, so the README's quick start (`init_db.py` → `sync.py`) crashed at the tagging step on any fresh machine. All three are now in the schema, and `sync.py`'s self-heal list runs `migrate_add_tags` + `migrate_add_corrections` as well (silently — it only prints when a migration actually changes something).
- **`correction <term>` in the TUI could not find anything.** It passed the same term as both `card` and `topic`, which `get_corrections` ANDs — so a correction whose topic slug didn't happen to repeat the card name was unreachable. `get_corrections` gained a `text=` parameter that ORs across `relates_to` / `topic` / `incorrect_claim`; the CLI keeps the precise `--card` / `--topic` AND behavior.
- **`paste` / `import` in the TUI silently dropped cards.** The TUI re-implemented the import loop without `force=True` (contradicting the documented "a pasted list loads verbatim") and swallowed every `DeckError` that wasn't "card not found" — so cards rejected by CI or singleton checks vanished with no message and no count. Both front-ends now share `decks.load_parsed_into_deck()`, which reports unresolved *and* rejected rows; `render_import_result` prints both.
- **`remove <card>` did not resolve card names.** `add fire/ice` worked but `remove fire/ice` failed with "card not in deck", as did any name typed without diacritics. `remove_card_from_deck` now runs the name through `resolve_card_name` (falling back to the raw string so a card absent from `cards` can still be deleted) and returns the canonical name, which both front-ends echo back.
- **`pow` / `tou` numeric comparisons matched non-numeric values.** The guard was `GLOB '[0-9]*'`, which only pins the first character — `'1+*'` passed it and `CAST('1+*' AS INTEGER)` is 1, so Allosaurus Rider matched `pow<=2`. Replaced with a digits-only predicate, used by both the filter and the `order:asc_power` sort key.
- `c:'blue white'` / `ci:'blue,white'` raised `unknown color token: 'l'`. Multi-word colour input is now parsed as words before falling back to letters — the form the docstring always claimed to accept.
- `sync_rules.py` reported 3,513 rules ingested when the table held 3,311: table-of-contents lines match the same patterns as real rules and are overwritten by the real body (same PK). It now reports what actually landed and writes that to `sync_state`.
- `tag_cards.py` reported rows *attempted*, not stored — `INSERT OR IGNORE` was dropping 90 of them. It now reports the stored counts plus how many collapsed.
- `mtg_cli.py deck rename <name>` without `--new-name` crashed with an `AttributeError` from inside the deck layer. Per-action required flags are declared up front and produce a usage message.
- `mtg_cli.py deck remove` always removed *all* copies and ignored `--qty`. `--qty` now defaults to None, meaning "1" for `add` and "all" for `remove`, and the echo reports the actual delta.
- Every DB connection now opens with a 15 s busy timeout. A sync holds a write transaction for tens of seconds, and with the TUI's sync moved to a background worker a query during that window used to fail instantly with `database is locked`.

### Added (2026-08-19 audit)
- `scripts/prune_stale_cards.py` — `sync_cards.py` upserts on `cards.name` and never prunes, so 89 rows whose names stopped appearing upstream still lingered, including 84 malformed double-name rows from an early sync bug (`Birds of Paradise // Birds of Paradise`, `Command Tower // Command Tower`). Their `color_identity` was NULL, which reads as colorless, so `search ci<=w` inside a mono-white commander deck returned `Blood Crypt // Blood Crypt`. Dry run by default; `--yes` deletes; rows referenced by `deck_cards` are never touched. Applied: 89 rows gone, `cards` is now 35,228 rows with zero NULL `color_identity` and zero NULL `games`.
- `scripts/migrate_fix_card_tags_pk.py` — `card_tags`' PK was `(card_name, tag)`, which cannot hold a token that is both a subtype and a keyword on the same card. Nine tokens collide (`saga`, `adventure`, `dragon`, `elemental`, `goblin`, `hero`, `licid`, `wolf`, `dungeon`), and because `tag_cards.py` builds rows from a `set`, *which* category survived varied between runs — `search kw:saga` could match a Saga on one sync and miss it on the next. Widens the key to `(card_name, tag, category)` and re-runs the tagger in the same pass. `init_db.py` ships the corrected key for fresh databases. Applied: 90 rows recovered, and `kw:saga` went from 15 hits to 101.
- `help decks` / `help search` topics. `DECK_HELP` — 58 lines documenting the whole deck/folder filesystem model — had been defined in `app.py` since phase 3 and maintained across several sessions but was never wired to any command, so no user could ever see it. The `help` overview is regrouped by task (LOOKUP / COMBOS / SEARCH / DECKS / MAINTENANCE) and points at both topics.
- In-deck `remove <card>` autofills from the cards actually in that deck instead of all 35k card names; `commander <card>` tries the deck first, then the full index.

### Changed (2026-08-19 audit)
- `sync` inside the TUI runs in a background worker thread and streams `sync.py`'s output line by line as it arrives. It used to block the whole UI with `capture_output=True` and print nothing until finished — and the message promised "~30 s" for what is now a ~650 MB, multi-minute download. The child process is given `PYTHONIOENCODING=utf-8`, since a piped stdout on Windows defaults to cp1252 and one em-dash upstream would have killed the sync. The autofill index is rebuilt when the sync finishes.
- In-deck `combos` renders the same numbered `[1]..[N]` list as the side pane, so `combo-info <N>` refers to what both panes show. It previously printed raw Spellbook ids on the right while the left pane numbered them.
- `sync_combos.py` streams the ~600 MB `variants.json` to disk and parses from the file rather than holding the whole response in memory first.
- `.gitignore` covers `data/exports/` (generated spreadsheets from `scripts/export_*.py` were showing as untracked).

### Removed (2026-08-19 audit)
- `queries.search_cards()` and its package export. Dead since `scryfall_search` landed — nothing in the CLI or TUI called it, and its `color_identity` argument filtered `type_line`, which could never have been right.
- Unused `cost` / `effect` parameters on `tag_cards.detect_flags()`; the dead "can't delete the deck you're inside" guard in `_remove_deck_in_current_folder` (unreachable — the dispatcher routes deck context elsewhere); the stale `folder new <name>` hint in `render_folder_list` (that verb was removed in the unified-syntax change).

### Added (format-aware deck behavior — commander + singleton)
- `cards.color_identity TEXT` column populated from Scryfall's `color_identity` array (e.g. `B,G` for Savra). Spans both faces and includes mana-cost / color-indicator colors — i.e. the field commander legality is checked against. Index added.
- `scripts/migrate_add_scryfall_fields.py` extended with the new column; `scripts/sync.py` now self-heals schema by invoking the migration on every run, so existing databases pick up the column without a manual step.
- `ci:` / `coloridentity:` / `id:` search operator with Scryfall semantics: `ci:WUB` and `ci<=WUB` mean *subset* (commander-legal), `ci=WUB` exactly equal, `ci>=WUB` superset.
- `mtg_oracle.decks.get_deck_color_identity()` — returns the sorted union of color identities across all `is_commander=1` rows. `None` when the deck has no commander set; `[]` for a colorless commander. Multiple commander rows supported (Partner / Background / Friends Forever — CI is the union).
- TUI: searches inside a deck with at least one `is_commander=1` row are *hard-filtered* by `ci<=<deck CI>`. The filter is announced inline so it's not silent magic; `cd ..` exits the constraint.
- TUI: pinned `-- COMMANDER --` section above the regular type-buckets in the live left-pane deck view.
- `[CI: XY]` badge in three places: full deck view header (`show`), live deck view header, and per-deck row in `ls` listings.
- Singleton enforcement on `add` for `format ∈ {commander, edh, duel commander, 1v1 commander, brawl, historic brawl, standard brawl, oathbreaker, highlander, canadian highlander}`. Basic lands (matched by type line containing `Basic` + `Land`) and cards whose oracle text contains `a deck can have any number of cards named` are exempt. Sideboard rows are validated independently from the main deck.
- Commander color-identity validation on `add`: a card whose `color_identity` is not a subset of the deck's CI is rejected with a precise error naming the offending colors.
- `add --force` flag on the TUI (and `force=True` kwarg on `add_card_to_deck`) bypasses both CI and singleton checks for one call. The commander row itself is never CI-checked (it *defines* the CI).
- `import_deck` always passes `force=True` so that pasting an existing list verbatim never fails on row-order quirks (commander row appearing after non-commander rows).

### Added (search sort)
- `order:asc_FIELD` / `order:desc_FIELD` (alias `sort:`) for explicit result ordering. Supported fields: `mv`, `cmc`, `name`, `power`, `toughness`, `rarity` (tier order: common → mythic → bonus → special), `color`, `ci` (number of colors in color identity).
- Direction prefix is *required* — bare `order:mv` is rejected so the result order is always unambiguous.
- `NULL`s sort last regardless of direction. Power/toughness with non-numeric values (`*`, `1+*`) are coerced to `NULL` for sort purposes and follow the same rule.
- A stable tiebreaker on `c.name COLLATE NOCASE` is appended to every sort, so identical primary keys produce reproducible ordering across runs.
- `count_query` strips `order:` tokens — pagination row totals are unaffected by sort.

### Changed
- TUI left navigation pane widened from 38 → 48 chars so longer card names (`Chatterfang, Squirrel General`, `Yawgmoth, Thran Physician`) fit without truncation. Both the `NAV_WIDTH` constant and the CSS rule updated together.

### Added (deck-context UX)
- `cd <deck>` now auto-renders the combos fully contained in the deck (right pane), so you no longer have to type `combos` separately. Mirrors a behaviour that existed before the split-pane refactor.
- `card <name>` inside a commander deck pre-filters the embedded "Top combos featuring this card" list by deck color identity. Combos whose CI isn't a subset of the deck CI are dropped at the SQL level, and the header notes the active filter (`Top combos featuring this card (3, filtered to deck CI BG)`). When zero combos remain, the renderer says so explicitly instead of silently hiding the section.
- `_cmd_cd` collapses runs of whitespace to a single space before lookup, so a stray double-space in pasted deck names doesn't break the `COLLATE NOCASE` match.

### Changed (unified `add` / `remove` syntax — `new` / `delete` removed)
- `add` and `remove` are now context-aware verbs that adapt to the current `cd` location:
    - **In a deck:** `add <card> [<qty>]` adds a card; `remove <card> [<qty>]` removes copies. (Existing behavior, unchanged.)
    - **In a folder:** `add <deck>` creates a new deck; `remove <deck>` deletes a deck.
    - **At root:** error message pointing to `mkdir`/`rmdir` for folder operations — folders kept on explicit Unix verbs by user preference (clear intent at top level).
- **`new` and `delete` removed entirely** — no aliases, no back-compat shims. The unified verbs cover both cases; carrying both vocabularies forward would just be noise. Removed from `_dispatch`, `COMMANDS` (autofill source), and the help texts.
- `mkdir` / `rmdir` unchanged: canonical way to create/delete folders.
- `_cmd_add` and `_cmd_remove` refactored from monolithic handlers into routers that dispatch to private helpers (`_add_card_to_current_deck`, `_add_deck_in_current_folder`, `_remove_card_from_current_deck`, `_remove_deck_in_current_folder`).
- **Suggester is cwd-aware:** `MtgSuggester` takes an `in_deck` callable. `add <card>` and `remove <card>` autofill from the 34k card-name index *only* when the user is inside a deck. At folder/root context the same verbs operate on deck/folder names and stay uncompleted (no false suggestions of card names when creating a deck).
- DECK_HELP rewritten to lead with the unified-syntax model.

### Changed (`remove` accepts a quantity)
- `remove <card> [<qty>]` now mirrors `add <card> [<qty>]`'s trailing-integer parsing. Without qty, the original "remove all copies" behavior is preserved. With qty, decrements by that amount across the matching rows. Over-removal clamps at 0 silently — `remove mountain 999` on a 9-Mountain deck takes all 9 and the echo reports the actual delta (`OK removed all 9x mountain`).
- `decks.remove_card_from_deck()` signature extended to `(deck_name, card_name, quantity=None, folder=None)`. Returns `(removed_count, remaining_count)` so the TUI can echo `OK removed 3x Mountain (6 remaining)` precisely.
- DECK_HELP updated to show the new syntax.

### Changed (`commander` verb auto-sets deck format)
- Running `commander <card>` on a deck whose `format` is currently NULL now auto-sets it to `'commander'` as part of the same transaction. This activates format-aware behavior (singleton on `add`, CI filter on `search` inside the deck) immediately — previously the user had to remember to `UPDATE decks SET format='commander'` separately, which was a quiet footgun.
- Behavior preserved: an explicitly set format (`'modern'`, `'canadian highlander'`, anything non-NULL) is left alone — the user's choice wins. Demotion via `commander --unset` never touches `format` either, since the deck might still be a commander deck with a different commander incoming.
- `decks.set_commander()` return signature extended to `(canonical_name, action, format_set)`. The TUI handler echoes a one-line note (`deck format auto-set to 'commander' — singleton checks and CI filter on \`search\` are now active`) when the auto-set fires, so it's never silent.

### Added (combo card-count honesty)
- Detect Spellbook combos whose step text references a card slot that `combo_cards` doesn't enumerate ("the affinity permanent", "your commander", "any X creature/permanent/spell", etc.). The renderer now suffixes the count with `+` for those — `(2+ cards)` instead of `(2 cards)` — so users don't get a false impression of how many distinct pieces the combo actually needs.
- New public helper `mtg_oracle.queries.flag_template_vars(rows, cur)` — vetted-whitelist regex over `combo_steps.text`, sets `has_template_vars` on each row. Applied at the end of `combos_in_deck`, `find_combos_with_card`, `find_combos_with_all`. User combos always get `has_template_vars=False` (their narrative lives in `description`, no separate steps table).
- All four combo renderers (`render_combo_list`, `render_combos_compact`, embedded combo block in `render_card`, and `_render_numbered_combo_list` in app.py) read the flag and append the `+` suffix.
- Survey: ~2.8% of Spellbook combos (2,419 of 85,495) have template-variable text in steps. Combo `542--77` is the worst-case 1-card example — Tidespout Tyrant alone, but the steps reference *the affinity permanent* twice, meaning the combo actually requires 3+ pieces.

### Added (user-curated combos)
- New tables `user_combos` (`id`, `name`, `color_identity`, `description`, `added_at`, `added_by`) and `user_combo_cards` (`combo_id`, `card_name`, `quantity`) — designed so the Spellbook wipe-and-rebuild in `sync_combos.py` never touches them. IDs use a `user-NNN` zero-padded prefix to stay visually distinct from Spellbook's numeric-pair IDs.
- `scripts/migrate_add_user_combos.py` — idempotent migration adding both tables; auto-invoked by `sync.py` along with the existing scryfall-fields migration so existing databases self-heal on next run.
- `scripts/add_user_combo.py` — backend helper that takes a list of card names + description, validates names against the `cards` table via `resolve_card_name`, auto-derives the union color identity, and inserts. Designed to back a future questionnaire-style in-app UX without rewriting the storage layer.
- `mtg_oracle.decks.combos_in_deck()`, `mtg_oracle.queries.find_combos_with_card()`, `find_combos_with_all()`, and `get_combo()` all UNION user combos with Spellbook combos. Each result row carries a `source` field (`'spellbook'` or `'user'`) so the renderer can label user combos visibly.
- Three user combos seeded for the Savra deck — Yawgmoth + Young Wolf + Blight Mound + {Blood Artist, Zulaport Cutthroat, Savra}. All three are infinite life + infinite draw via the Pest-token alternation; Spellbook hadn't curated this Yawgmoth + Blight Mound interaction. Captures the "Spellbook is not exhaustive" caveat with concrete data.

### Changed (fail-loud cleanup)
- Side-pane analytics and combos failures now surface their error to the nav pane (`(analytics error: TypeError: ...)`) instead of silently disappearing. Same fix for the per-folder `list_decks` loop in the root-view tree. The renderer used to drop sections without explanation when the underlying call raised, leaving the user with no way to see why.
- Widget-lookup `except Exception:` blocks in `_refresh_status` / `_refresh_nav` narrowed to `from textual.css.query import NoMatches`. The broad catch was masking real bugs (mistyped selector, removed widget id) behind the legitimate "called during init before compose() ran" case; the narrow catch keeps the init-safety net while letting real failures escape.
- Documented the `json.JSONDecodeError` swallow in `queries.get_corrections` — legacy / hand-edited rows may carry free-text `relates_to` instead of JSON; leaving the raw string in place is intentional rather than buggy.

### Added (deck analytics + side-pane combos)
- New `mtg_oracle/analytics.py` module: `compute_deck_analytics(deck)` returns `mana_curve` (0..6+ buckets), `mv_avg` over non-lands, `color_pips` (WUBRG count across non-land mana costs), `mana_sources` (WUBRGC count per land), and main vs land totals. Sideboard cards are excluded; commanders are included since they're cast from the command zone. DFC/split cards count only the front-face cost for color pips so back-face symbols don't double-charge the mana base.
- Land mana-source detection: basic lands resolve from the type-line subtype (handles snow basics + Wastes); non-basics scan `oracle_text` for `{W|U|B|R|G|C}` symbols (covers duals, shocks, fetches, pain/utility lands). `decks.get_deck()` now selects `oracle_text` per row to feed this.
- Renderer additions: `render_analytics_compact()` and `render_combos_compact()` produce width-aware blocks for the side pane. The mana-curve histogram uses 3-char-wide columns so two-digit counts align under their headers.
- `render_deck_compact(...)` now takes optional `analytics` and `combos` params and appends them after the type buckets, so the entire side pane refreshes atomically when a card is added / removed / promoted.

### Changed (combos move from right pane to left)
- `_refresh_nav` (the side pane) now renders `Deck → Analytics → Combos` whenever the user is inside a deck. Combos are numbered (`[  1]`, `[  2]`, ...) so `combo-info <N>` continues to expand any row to the right pane against the same `_last_combos` list.
- `_on_entered_deck` no longer writes the combos list to the right pane on `cd`. The right pane stays free for searches, card profiles, rulings, and expanded combo detail. Old behaviour was easy to lose when the deck was big — the combos got buried above the prompt; the side-pane location keeps them always visible.

### Added (commander promotion)
- `commander <card>` command in the TUI promotes a card to commander in the current deck. If the card is already in the deck (main or sideboard), the existing row is flipped to `is_commander = 1` with quantity forced to 1; otherwise a fresh commander row is inserted. Multiple commanders are allowed, so running it on a 2nd card produces a Partner / Background / Friends Forever pair (deck CI becomes the union — Savra + Tymna → `[B, G, W]`).
- `commander --unset <card>` demotes a commander row back to main-deck without removing the card.
- `mtg_oracle.decks.set_commander()` helper returns `(canonical_name, action)` where action is one of `promoted` / `added` / `unchanged` / `demoted` so the TUI can echo the right outcome message.
- Suggester wired so `commander <prefix>` autofills from the full card-name list, same as `card` / `combo`.
- `DECK_HELP` and the top-level `help` screen both list the new verb.

### Added (theme persistence)
- Last-selected Textual theme persists across launches via `data/config.json` (gitignored). `App.watch_theme` writes on user-driven theme changes; `on_mount` reapplies the saved theme. Default-theme assignments during init are gated behind a `_config_ready` flag so they don't overwrite the saved value. Falls back silently when the saved theme name no longer exists (e.g. after a Textual upgrade).

### Added
- Git repository initialized with `.gitignore` covering generated DB, bulk data sources, Python and editor artifacts.
- `CHANGELOG.md` for traceability of schema and ingestion changes going forward.
- `cards.oracle_id`, `cards.layout`, `cards.card_faces` columns (stable Scryfall identifier, layout type, raw per-face JSON).
- `rulings.oracle_id` column + index, for joining rulings to cards via a stable key rather than name.
- `sync_state` table tracking upstream `updated_at`, `last_sync`, and `row_count` per source.
- `scripts/migrate_add_oracle_id.py` — idempotent schema migration for existing databases.
- `scripts/sync_cards.py` — fetches Scryfall `oracle_cards` and `rulings` bulk files, upserts them, and skips on unchanged upstream `updated_at`. Supports `--force`.

### Changed
- Card ingestion now pulls live from Scryfall bulk API instead of the manually-reduced MTGJSON `AtomicCards.json`.
- Double-faced, modal-DFC, split, and flip cards now preserve **all** face texts (previously the back face was silently dropped because `ingest_cards.py` read `card_info[0]` only). Combined text uses a `// ` separator between faces.
- `init_db.py` schema updated to match the new columns/tables so fresh installs match migrated ones.

### Added (rules automation + orchestrator)
- `scripts/sync_rules.py` — scrapes `magic.wizards.com/en/rules`, resolves the current `MagicCompRules YYYYMMDD.txt`, parses it, and upserts the `rules` table. Release date is stored as `sync_state.updated_at` so re-runs skip unchanged releases.
- `scripts/sync.py` — orchestrator that runs cards + rules + combos in one go, with `--force` and `--only` flags. Safe to schedule daily via cron / Task Scheduler; each source no-ops when upstream is unchanged.
- ETag-based idempotency for Commander Spellbook (`HEAD` probe → compare against `sync_state`).

### Changed
- Renamed `scripts/ingest_combos.py` → `scripts/sync_combos.py` and added `sync_state` tracking (keyed on upstream ETag).
- Rules section-title regex now accepts commas/apostrophes/hyphens (fixes section 6 "Spells, Abilities, and Effects" being missed, which previously caused rules 600–616 to inherit "Turn Structure" as their section).
- `/sync` slash command, `README.md`, and `CLAUDE.md` all now direct users to `python scripts/sync.py` as the single refresh entry point.

### Removed
- `scripts/ingest_cards.py` — superseded by `sync_cards.py` / Scryfall bulk.
- `scripts/ingest_rules.py` — superseded by `sync_rules.py` / Wizards `.txt`.
- `data/source/` directory + its contents (`mtg_judge_db.json`, `MagicCompRules.docx`, ~33 MB) — no longer an input path; everything is fetched on demand by `sync.py`.
- Root-level `AtomicCards.json` (~154 MB MTGJSON dump) — only used by the original one-off cleanup script, never part of the live pipeline.
- `python-docx` dependency — the only consumer was `ingest_rules.py`. `sync_rules.py` parses the `.txt` release with stdlib only, so the project now has **zero** runtime dependencies.

### Added (card tagging — Phase 1)
- `card_tags(card_name, tag, category, source)` — flat tag table for keyword abilities (CR 702), supertypes, types, subtypes. One row per (card, tag).
- `card_abilities(id, card_name, ability_index, ability_type, cost, effect, has_target, produces_mana, is_mana_ability, raw_text)` — one row per parsed ability; abilities from each face of a DFC/split/flip get distinct indices.
- `scripts/migrate_add_tags.py` — idempotent schema migration adding both tables + indexes.
- `scripts/tag_cards.py` — regex-based tagger. Reads `cards.card_faces` JSON when present so per-face oracle_text/type_line are parsed cleanly (no face-name "abilities", no `//`/`—` subtype junk). Populates flat tags and parsed abilities. Computes `is_mana_ability` per **CR 605.1a/b** (produces mana, no target, not a loyalty ability). Full retag takes ~3 s for 34k cards.
- Integrated as the `tags` step in `scripts/sync.py`. Selectable via `--only tags`.

### Verified behavior
- Deathrite Shaman's mana-producing ability is correctly **not** flagged as a mana ability (its cost targets a land in a graveyard).
- Birds of Paradise, Llanowar Elves, Gaea's Cradle, Sol Ring, Mox Amber all correctly flagged as having a mana ability.
- Delver of Secrets / Valki DFCs produce clean per-face tags (no leaked face names or separators).

### Added (mana_cost on cards)
- `cards.mana_cost` column populated from Scryfall bulk (`{2}{W}{W}`, `{U}{U}`, `{0}` for Black Lotus, etc.). Empty string for lands. DFC / split / flip cards combine per-face costs with ` // ` (e.g., `{1}{R} // {1}{U}` for Fire//Ice; `{U} // ` for Delver of Secrets because the back face has no cost).
- `scripts/migrate_add_mana_cost.py` — idempotent migration adds the column.
- `sync_cards.py` now extracts `mana_cost` via a new `_face_mana_cost` helper; matches existing `_face_text` / `_face_type_line` pattern.
- `get_card()` returns `mana_cost`; renderer shows it right-aligned on the card header line (stacks to a second line when name+cost overflow the 70-column box). TUI verified via headless pilot.

### Added (Phase 2 — desktop TUI app)
- Pivot: desktop-first over Android-first. Android deferred to Phase 5. Desktop chosen for instant iteration loop and because Textual natively delivers the terminal / monochrome aesthetic the project targets.
- `mtg_oracle/app.py` — Textual TUI app. Imports `mtg_oracle.queries` directly (no HTTP / no IPC).
- `mtg_oracle/renderer.py` — plain-text render functions extracted from the CLI so CLI and app share all card/combo/rule formatting.
- `scripts/mtg_app.py` — launcher; reports a clear "install textual" message if the dep is missing.
- Commands (typed in bottom input box): `card`, `ruling`, `combo`, `combos` (intersection via `;`), `combo-info`, `rule`, `search-rules`, `search`, `correction`, `help`, `clear`, `quit`.
- Keybindings: `:` focus command, `Esc` unfocus, `Ctrl+L` clear output, `Ctrl+Q` quit.
- Card lookup shows attached corrections inline — Phage surface both feedback-loop rows.
- `requirements.txt` now lists `textual>=0.80` (only needed for the app; core sync/query remain stdlib-only).
- Verified via headless Textual pilot: card lookup, combo intersection, rule lookup, search with flags, correction listing, unknown-command handling all pass.

### Added (Phase 3 — Decks Lite with terminal-style navigation)
Decks live in three new tables (`deck_folders`, `decks`, `deck_cards`) with flat folders (no nesting). Folders cover the "format / theme" organization role for now — Commander, Modern, Cube, etc.

- `scripts/migrate_add_decks.py` — idempotent schema migration.
- `mtg_oracle/decks.py` — full CRUD (folders + decks + cards) with case-insensitive name resolution that handles `/` vs ` // ` and front-face-only DFC names. `Fire/Ice` resolves to `Fire // Ice`, `Jace, Vryn's Prodigy` resolves to the canonical melded form, etc.
- `mtg_oracle/deck_parser.py` — tolerant plain-text deckstring parser. Handles `4 Card`, `4x Card`, `Card x4`, set-code suffixes `(CLB) 146`, foil markers `*F*`, comment lines (`#` / `//`), and section headers (`Deck`, `Sideboard`, `Commander`, `Maybeboard`, plus aliases).
- `combos_in_deck()` — intersects a deck's cards against the combos table and returns combos whose every card is in the deck. Verified: 0 matches on a Canlander Izzet Delver list (correct — no Spellbook combos), 2 matches on a Thassa+Consultation+Tainted-Pact test deck.
- `render_deck` auto-groups by type (Lands / Creatures / Instants / Sorceries / Artifacts / Enchantments / Planeswalkers / Battles / Other) so 100-card singleton lists stay scannable. Subtotals per group; mana costs in each row; sideboard / commander as separate sections.
- CLI: full `deck` and `folder` subcommand families (`deck show|new|delete|rename|move|add|remove|import|combos`, `folder new|delete`, top-level `decks` and `folders`). `deck import --from-file path.txt` or pipe text on stdin.
- TUI: **terminal-style cwd navigation**.
  - Hierarchy: `/` (root) → `/<folder>/` → `/<folder>/<deck>/`. Status bar shows current path.
  - `cd <name>` enters folder or deck (auto-detects). `cd ..`, `cd /`. `pwd`. `ls`.
  - `mkdir <name>` / `rmdir <name>` for folders. `new <deck>` creates in current scope. `delete <deck>`, `rename <old>; <new>`, `move <deck>; <folder>`.
  - Inside a deck: `add <card> [<qty>]`, `remove <card>`, `combos` (combos-in-deck), `import <filepath>`, `show` / `ls`.
  - Old explicit forms (`deck show <name>`, `deck add <name>; <card> ...`) still work as a fallback.
- Verified end-to-end against a real 85-line / 100-card Canadian Highlander Izzet Delver list: 84 unique entries parsed, 0 unresolved (all four DFC front-face inputs auto-resolved to canonical names), grouped render produces the expected category counts (18 Creatures / 30 Instants / 13 Sorceries / 1 Planeswalker / 2 Enchantments / 36 Lands).
- `scripts/mtg_app.py` now scales the Windows console to ~1.20× its current size before starting Textual (best-effort `mode con`; no-op on other platforms).

### Added (search pagination + row expand)
- `scryfall_search.run_query` gains an `offset` parameter; new `count_query()` returns the total number of matches without fetching rows.
- `render_search` now shows a `N card(s) — showing A-B (page P of L)` header, adds mana cost as a column (name | mana_cost | type_line), and accepts an optional `nav_hint` footer.
- CLI `search` gains `--page N` flag (1-based). Default page size remains 50 (`--limit`).
- TUI gains three new commands: `next`, `prev`, `page <N>`. App stores the last search query + total count + current rows on the instance so navigation works without re-typing.
- TUI `card <N>` now expands the N-th row of the most recent search into the full card profile (same pattern `combo-info <N>` uses for combo lists). Raw `card <name>` still works; integer argument shortcuts into the search result, non-integer goes to `get_card`.
- Status bar and `help` text updated with the new navigation commands.
- Verified against "flying blue creatures" (~1,431 matches across 29 pages): header correct, next/prev/jump all work, edge cases (first/last page, out-of-range row, no prior search) all produce friendly messages.

### Added (Scryfall-style search — Level 2)
- New columns in `cards` populated from Scryfall bulk: `colors` (comma-separated sorted letters, e.g., `B,G`), `mana_value` (integer), `power` / `toughness` (TEXT — preserves `*`, `1+*`, `X`, etc.), `rarity`.
- `scripts/migrate_add_scryfall_fields.py` — idempotent migration.
- `mtg_oracle/scryfall_search.py` — self-contained tokenizer + recursive-descent parser + SQL compiler (~340 lines). Supports:
  - Operators: `o:` / `oracle:`, `t:` / `type:`, `n:` / `name:`, `kw:` / `keyword:`, `c:` / `color:` (subset-contains) and `c=` (exact), `mv:` / `cmc:`, `pow:` / `power:`, `tou:` / `toughness:`, `r:` / `rarity:`, `layout:`
  - Comparison operators for numerics: `:` (equality), `=`, `>`, `<`, `>=`, `<=`, `!=`
  - Boolean: AND (implicit via whitespace), `or`, `not`, `-` prefix, parentheses for grouping
  - Bare words and quoted strings default to oracle-text match
  - Color tokens accept letters (`u`), words (`blue`), or braces (`{U}`)
- Power/toughness comparisons guard against non-numeric values (`*`, `1+*`) via `GLOB '[0-9]*'` so they are excluded from numeric ranges instead of being silently cast to 0.
- All SQL is parameterized; read-only DB connection.
- `search` command in both CLI and TUI replaces the old flag-based interface:
  - Before: `search --name X --tag Y --type Z --mana-ability`
  - After: `search o:"enters" t:creature c:u mv<=3`
- `search help` (or `search ?`) prints an in-app syntax guide with examples.
- Verified against 15+ queries including complex booleans, colorless-creature filters, rarity, power comparisons, and error paths (unknown fields, non-integer numerics, unterminated strings, unbalanced parens).

### Added (Phase 2 polish)
Collected post-initial fixes and usability improvements to the desktop app, all committed separately on master. Documented here so the full Phase 2 delta is readable in one place.

- **Mana cost rendering.** `cards.mana_cost` column populated from Scryfall bulk; `get_card()` returns it; renderer shows it right-aligned on the card header line (e.g., `| Wrath of God                                               {2}{W}{W}`). DFC/split/flip cards show both faces joined with ` // ` (`{1}{R} // {1}{U}` for Fire // Ice, `{U} // ` for Delver because the back face has no cost). Lands render with no cost trailer.
- **Case-insensitive lookups.** `get_card`, `get_rulings`, `find_combos_with_card`, `find_combos_with_all`, `get_rule` now use `COLLATE NOCASE` on exact-match comparisons. `get_card` specifically re-resolves the canonical card name from the DB before running downstream joins (tags/abilities/rulings/combos/corrections) so lowercase input still produces a complete, properly-cased profile.
- **Autofill.** `MtgSuggester` preloads 34k card names + 3.3k rule numbers at app startup; Textual `Input.suggester` shows grayed-out ghost-text completions as the user types. Routes by command prefix: `card|ruling|combo|correction` → card names, `rule` → rule numbers, `combos X;` → card names for the last segment after `;`, empty → command names. Tab / right-arrow accepts.
- **Numbered combo selection.** `combo <card>` and `combos A; B` render results with `[  1] [  2] …` indices and a `(type combo-info <N> to expand)` footer. `combo-info <integer>` looks up the N-th result from the most recent search (stored on the app) so users never need to type opaque Spellbook IDs like `742-1295` manually. Raw IDs still work if preferred.
- **Auto-expand on single match.** `combo` or `combos` returning exactly 1 result skips the list and renders the full combo detail directly.
- **Wrap long combo rows at `+` boundaries.** Replaced the hard `[:60]` truncation in combo-list renderers with `renderer.wrap_combo_row()`; long card lists wrap onto continuation lines (indented with `    +`) so no card name is cut mid-word. Used consistently by card-view "Top combos", CLI's `combo` output, and the app's numbered list.
- **`cards` column in Top combos.** `get_card()`'s combo subquery now GROUP_CONCATs the combo card list instead of relying on `combos.name` (which is almost always empty). Card-view Top combos now show e.g. `Nexus of Fate + Spellbinder` instead of `(unnamed)`.
- **Shift+drag copy hint.** Status line at the bottom of the app now reads `Press : to enter a command  |  Ctrl+L clear  |  Ctrl+Q quit  |  Shift+drag to select/copy`. Textual captures mouse events for navigation; holding Shift in Windows Terminal / modern terminals bypasses the capture and re-enables normal text selection.
- **Oracle-combo sequencing correction (#3).** Added to the corrections table: Spellbook's canonical order for Thassa's Oracle + Demonic Consultation (and Tainted Pact) casts the instant first, but safer play is Oracle-first with the ETB trigger held on the stack, then cast the library-emptier as instant-speed interaction. Now auto-surfaces on any Oracle-related card or combo query.

### Added (Phase 1a — shared query library + CLI)
- `mtg_oracle/` Python package with pure query functions over `data/mtg.db`:
  - `get_card(name)` — card + tags + abilities + rulings + top combos + applicable corrections
  - `search_cards(name_like, tag, card_type, is_mana_ability, limit)`
  - `get_rulings(card_name)`, `find_combos_with_card(name)`, `find_combos_with_all(cards)`, `get_combo(id)`
  - `get_rule(rule_number)` (with children), `search_rules(pattern)`
  - `get_corrections(card, topic)`
- All SQL parameterized. DB opened read-only via `file:...?mode=ro`.
- `scripts/mtg_cli.py` — CLI wrapping the same queries with subcommands `card`, `ruling`, `combo`, `combos` (intersection), `combo-info`, `rule`, `search-rules`, `search`, `correction`. Supports `--json` for machine-readable output.
- CLI renders in ASCII (no unicode borders) so it works on Windows consoles.
- Card output auto-attaches any applicable corrections: looking up `Phage the Untouchable` now shows both seeded corrections inline.
- Self-review completed per CLAUDE.md rule: correctness / failure modes / SQL-injection safety / maintainability / exercised happy path + edge cases.

### Added (sync changelog — per-run diff)
- `sync.py` now snapshots keyed state before and after the run (oracle_ids + oracle_text hashes for cards, rule_numbers + text hashes for rules, combo ids) and prints a `=== changelog ===` table at the end with `added / removed / modified / total` per source.
- For wipe-and-rebuild tables without a stable business key (rulings, card_tags, card_abilities) the changelog reports net delta only; combos show added/removed on id but not modified (Spellbook wipes + rebuilds without content tracking).
- When nothing changed the table prints a "no changes" line, so idempotent reruns are visibly no-ops instead of silent.

### Added (feedback loop — persistent corrections)
- `corrections` table: `(id, topic, category, incorrect_claim, correct_claim, explanation, relates_to, source, added_at, added_by)`. Indexed on `topic`, `category`, `added_at`. Purpose: durable record of factual mistakes so they aren't re-derived every session.
- `scripts/migrate_add_corrections.py` — idempotent migration.
- `.claude/commands/correction.md` — `/correction add|show|delete` slash command for structured capture and lookup.
- CLAUDE.md updated with enforcement rules: query `corrections` before answering card/rules-interaction questions; insert a row immediately when a mistake is surfaced.
- Seeded with two corrections from the Phage the Untouchable discussion:
  - `abyssal_persecutor_vs_lose_the_game_triggers` — Persecutor prevents opponents' wins, not `you lose` effects; does NOT protect against Phage's ETB trigger.
  - `donate_phage_before_etb_trigger` — Donate / Harmless Offering change control *after* ETB; Phage's trigger has already triggered under the original controller per CR 603.2 / 113.7a.

### Planned
- GitHub remote + CI: schedule `sync.py` on a daily cron, commit diffs, and surface upstream change alerts.
- Phase 2 tagging (LLM-assisted): `is_tutor`, `is_wincon`, `is_counterspell`, `is_board_wipe`, `is_ramp` and similar intent-based tags.
- **Format-based banlists**: fetch and persist banlists per format (Commander, Modern, Legacy, Vintage, Pioneer, Standard, Pauper, Brawl). Scryfall bulk already exposes per-card `legalities` — investigate whether this covers the need before building dedicated banlist fetchers.
- **Best-practice combo sequencing**: Commander Spellbook lists a canonical combo execution, but not the interaction-resilient play order (e.g., Thassa's Oracle + Demonic Consultation is safer cast Oracle-first so the ETB trigger is already on the stack when you exile your library — a counter on Oracle alone doesn't brick the combo). Goal: a reasoning layer (heuristics + LLM) that re-orders or annotates `combo_steps` with "how to cast this safely under interaction", distinct from the upstream canonical order. Store as a separate field so Spellbook's data stays untouched.
