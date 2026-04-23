# Changelog

All notable changes to MTG Oracle are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
