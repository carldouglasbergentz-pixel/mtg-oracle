# Changelog

All notable changes to MTG Oracle are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
