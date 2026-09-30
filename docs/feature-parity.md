# Feature parity: Python TUI/CLI → Kotlin app

The Kotlin app (ADR 0001) replaces the Textual TUI and the CLI piece by piece. This list records everything the Python build does today, so nothing is lost in the move. Each feature is ticked here when the new app has it. The TUI keeps working until every row it owns is ticked.

Steps refer to Phase 6 in `project-plan.md`:

- **2** covers foundation and play.
- **3** covers lookup and search (done 2026-09-30).
- **4** covers deck editing, the next step.
- **5** covers analysis.
- **6** covers sync and packaging.

Status legend: ✓ in the new app · ▶ in progress · ○ planned.

## App shell and interaction

| Feature | Today (TUI) | Step | Status |
|---|---|---|---|
| Theme switching, persisted between runs (the user runs `rose-pine`) | Ctrl+P palette → theme, saved to `data/config.json` | 2 | ✓ F8 cycles five two-tone themes; the first run takes the TUI's |
| Command line / palette: type any command, `:` to focus | command input | 3 | ✓ `:` or Ctrl+K; Esc leaves it |
| Autofill: card names, rule numbers, commands, `;` segments | Suggester | 3 | ✓ plus deck names for `cd`, help topics |
| Command history with Up/Down, restoring a pending draft | input history | 3 | ✓ |
| Clickable output: card names → profile, folders/decks → open, combo rows → detail | LinkSpan + tickets | 3 | ✓ also rule numbers and next/prev; hover shows the card in the zoom pane. Decks open from the list (the output has no deck rows until step 4) |
| Resizable nav pane, width persisted; Ctrl+←/→ | PaneDivider | 2 | ✓ zone and right columns: drag the border, Ctrl(+Shift)+←/→ |
| Copy pane contents to clipboard (`copy last/all/nav`) | OSC 52 | 3 | ✓ `last`, `all`; `nav` is `export` in step 4 |
| Clear output (`clear`, Ctrl+L), quit (Ctrl+Q) | bindings | 2 | ✓ `clear` / Ctrl+L; `quit` or Q in the library (Ctrl+Q concedes on the board) |
| Help per topic (`help`, `help decks/search/forge`) | help.py | 3 | ✓ `help`, `help search`; decks and forge get theirs with steps 4 and 5 |
| Errors shown in the app, never a crash; "database predates this version" hint | `_run_guarded` | 2 | ▶ (schema check) |
| Schema self-heal on start | `self_heal.py` | 2 | ✓ (Python owns it; app checks) |

## Card, rules, combos, corrections (lookup)

| Feature | Today | Step | Status |
|---|---|---|---|
| Card profile: text, cost, type, tags, parsed abilities, rulings, legalities (incl. `no_commander`), combos, corrections | `card` | 3 | ✓ |
| Tolerant name resolution: case, `/` vs ` // `, front face, diacritics, quotes | `resolve_card_name` | 3 | ✓ `CardNames`, checked against Python (`SearchParityTest`) |
| Rulings | `ruling` | 3 | ✓ |
| Comprehensive Rules: rule + children (natural order), text search | `rule`, `search-rules` | 3 | ✓ |
| Combos with a card; with all of several cards; detail with steps, prerequisites, results; `+` for template slots; user combos | `combo`, `combos`, `combo-info` | 3 | ✓ |
| Corrections list (the feedback loop) | `correction` | 3 | ✓ |
| Scryfall-style search language (`o: t: n: kw: c: ci: mv: pow: tou: r: layout: f: banned: restricted: game: is:`, or/not/parens, `order:`), paging, `card <N>` | `search`, `next/prev/page` | 3 | ✓ 74 queries checked against Python (`SearchParityTest`); since 3.5 both also have free text over name/type/text, `m:`, `c:m`, `otag:` and more `is:` |
| Inside a deck, search is restricted to the deck's CI and format | `search` in a deck | 3 | ✓ after `cd <deck>`; `order:` works there (it broke in Python, fixed) |

## Decks

| Feature | Today | Step | Status |
|---|---|---|---|
| Folders and decks; unsorted decks; unique names per folder | `ls`, `cd`, `mkdir`, `rmdir` | 2 (read) / 4 (edit) | ✓ 4b: [ New deck ] [ New folder ], a folder's and a deck's right-click menu; every folder shown, the empty ones too |
| Deck view grouped by type; commander pinned; CI badge; pointed cards `Name (3)` | nav pane, `show` | 2 | ▶ |
| Deck workspace: the deck beside a search that follows it, results as a card grid or lines, arrow-key selection | — (new, 3.5; Moxfield's edit mode) | 3.5 | ✓ editing since 4a |
| Considering list (Moxfield's maybeboard): not counted, exported or played; `!` where a rule would stop a card | — (new, 4a) | 4a | ✓ app: the Considering tab; TUI: `consider` and both deck views |
| Live nav analytics: curve, avg MV, pips, sources by colour, combos in deck | nav pane | 5 | ✓ the analysis block, fixed at the top of the middle column above the deck (library) and above the search (workspace), recomputed on every edit; also the primary roles and their T2/T4 odds |
| Add / remove with format rules: CI, legality, singleton (incl. "up to N"), restricted, points; `--force` | `add`, `remove` | 4 | ✓ 4a: the workspace's buttons, keys, menu and `add`/`remove`; `add anyway` is `--force`; DeckParityTest checks every rule against Python |
| Commander promote / demote, auto-set format | `commander` | 4 | ✓ 4a: the menu and `commander [--unset]` |
| Deck format and folder default format | `format` | 4 | ✓ 4b: format... in the menus and [ Format ] in the workspace, chosen from every known format; a folder default can be stamped on its decks without one |
| Points spend vs budget | `points` | 4 | ✓ 4a: `9/10 pts` in the deck pane's title, pointed cards `(3)` in the deck and in search results |
| Rename / move / delete deck | `rename`, `move`, `remove` | 4 | ✓ 4b: the deck's menu; delete asks first and says the history goes with it |
| Import / paste (append), tolerant parser (Moxfield, Archidekt, MTGO, mtgtop8, BOM, `SB:`, headers with counts) | `import`, `paste` | 4 | ✓ 4b: [ Import ] from the clipboard, as a new deck or into the open one; DeckParser checked against Python on every reference list; the maybeboard fills the considering list |
| Replace from a list with diff; abort on unknown cards | `paste --replace` | 4 | ✓ 4b: Import > Replace shows the changes first (a dry run), then asks; unknown names are named and left out only on yes |
| History and undo / redo | `history`, `undo` | 4 | ✓ 4a: the History tab (every change, both apps' alike) and `undo` (undo of undo is redo) |
| Printings (set + collector number) kept from the paste, used for art | parser + `deck_cards` | 2 | ✓ kept on import and replace, written back by export |
| Choose a card's printing / art in the app (pick from its printings in the deck view or zoom pane; recorded in deck history, undoable) | — (new; wanted 2026-09-29 — Jace set to WWK 31 by hand) | 4 | ✓ 4b: a row's menu > choose printing..., every printing Forge knows, newest first, the art in the zoom pane on hover; a `printing` revision, undoable |
| Export to clipboard / file, round-trips; `--front-face`, `--grouped` | `export` | 4 | ▶ to the clipboard: full names, front faces (split cards whole), or grouped by role (5); to a file stays the TUI's |
| Combos fully contained in a deck | `combos` in a deck | 5 | ✓ `combos` with no argument in or on a deck, and the block's `[ N combos in the deck ]` |

## Analysis

| Feature | Today | Step | Status |
|---|---|---|---|
| Deck profile: role densities, reach, exact castable-on-turn odds, low-confidence cards | `profile` | 5 | ✓ `profile [<deck>]` and the block's `[ full profile ]`; the tables are Python's byte for byte (AnalysisRenderParityTest) |
| Head-to-head comparison | `compare` | 5 | ✓ `compare <deck>` |
| Reference-set ranges over a folder of lists, `--json` | `scripts/analyse_archetype.py` | 5 | ▶ in the app over a library folder: `profile <folder>` (side by side, most played per role) and `compare <folder>` (ranges); import the lists into a folder first. A folder of files and `--json` are the Kotlin CLI's (6c) |
| Role classifier (Tagger tags + text rules, face-burn veto, drawback vetoes) | `roles.py` | 5 | ✓ `core/analysis`; AnalysisParityTest classifies all 35k cards against Python, and checks every deck's analytics and every profile, ranking and comparison at 12 decimals |

## Forge and playtesting

| Feature | Today | Step | Status |
|---|---|---|---|
| Play vs AI on our own board, recorded | — (new) | 2 | ▶ |
| AI copies from substitutions | `forge sub`, export | 2 (use) / 4 (edit) | ▶ |
| Matches best of 1 / 3 / 5: sideboarding between games, the result and score, the loser chooses play or draw; each game a `games` row sharing `match_id` | — (new) | 2 | ✓ |
| The mana pool on the board, live, spendable by clicking during a payment; mana from resolving abilities on the trail | — (new) | 2 | ✓ |
| Concede a game or the whole match (Ctrl+Q, Esc, `[ concede ]`), back to the library cleanly; closing the window records the game on as conceded | — (new) | 2 | ✓ |
| Export `.dck` to Forge's folder, ownership marker; nav `[-> forge]` | `forge export` | superseded by in-process play; keep while Forge's own GUI is used | ○ decide |
| AI vs AI sims with stored results, win matrix | `forge sim`, `forge results` | 5 | ○ (watch mode exists) |
| Launch Forge's own GUI | `forge play` | dropped (our board replaces it) | — |

## Data and maintenance

| Feature | Today | Step | Status |
|---|---|---|---|
| Sync: cards, rulings, rules, combos, tags, oracle tags, formats; skip-unchanged, `--force`, `--only`, changelog | `sync.py`, `sync` in the TUI | 6 | ○ (Python keeps it) |
| Prune stale cards | `prune_stale_cards.py` | 6 | ○ |
| User combos | `add_user_combo.py` | 3 (show) / 4 (add) | ▶ shown in every combo lookup; adding one moves to step 4 with the other writes |
| Export scripts (land fetchers, typal matters) | `scripts/export_*.py` | 6 | ○ decide |
| CLI for scripting and `--json` | `mtg_cli.py` | 6 | ○ decide: keep a Kotlin CLI or keep Python's |
