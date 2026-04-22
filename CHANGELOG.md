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
