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

### Planned
- GitHub remote + CI: schedule `sync.py` on a daily cron, commit diffs, and surface upstream change alerts.
