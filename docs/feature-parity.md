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
| Folders and decks; unsorted decks; unique names per folder | `ls`, `cd`, `mkdir`, `rmdir` | 2 (read) / 4 (edit) | ▶ read-only library |
| Deck view grouped by type; commander pinned; CI badge; pointed cards `Name (3)` | nav pane, `show` | 2 | ▶ |
| Deck workspace: the deck beside a search that follows it, results as a card grid or lines, arrow-key selection | — (new, 3.5; Moxfield's edit mode) | 3.5 | ✓ read-only; adding from it is step 4 |
| Live nav analytics: curve, avg MV, pips, sources by colour, combos in deck | nav pane | 5 | ○ |
| Add / remove with format rules: CI, legality, singleton (incl. "up to N"), restricted, points; `--force` | `add`, `remove` | 4 | ○ |
| Commander promote / demote, auto-set format | `commander` | 4 | ○ |
| Deck format and folder default format | `format` | 4 | ○ |
| Points spend vs budget | `points` | 4 | ○ |
| Rename / move / delete deck | `rename`, `move`, `remove` | 4 | ○ |
| Import / paste (append), tolerant parser (Moxfield, Archidekt, MTGO, mtgtop8, BOM, `SB:`, headers with counts) | `import`, `paste` | 4 | ○ |
| Replace from a list with diff; abort on unknown cards | `paste --replace` | 4 | ○ |
| History and undo / redo | `history`, `undo` | 4 | ○ |
| Printings (set + collector number) kept from the paste, used for art | parser + `deck_cards` | 2 | ▶ |
| Choose a card's printing / art in the app (pick from its printings in the deck view or zoom pane; recorded in deck history, undoable) | — (new; wanted 2026-09-29 — Jace set to WWK 31 by hand) | 4 | ○ |
| Export to clipboard / file, round-trips; `--front-face`, `--grouped` | `export` | 4 | ○ |
| Combos fully contained in a deck | `combos` in a deck | 5 | ○ |

## Analysis

| Feature | Today | Step | Status |
|---|---|---|---|
| Deck profile: role densities, reach, exact castable-on-turn odds, low-confidence cards | `profile` | 5 | ○ |
| Head-to-head comparison | `compare` | 5 | ○ |
| Reference-set ranges over a folder of lists, `--json` | `scripts/analyse_archetype.py` | 5 | ○ |
| Role classifier (Tagger tags + text rules, face-burn veto, drawback vetoes) | `roles.py` | 5 | ○ |

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
