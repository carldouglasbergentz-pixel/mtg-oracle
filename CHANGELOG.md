# Changelog

All notable changes to MTG Oracle are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Git repository initialized with `.gitignore` covering generated DB, bulk data sources, Python and editor artifacts.
- `CHANGELOG.md` for traceability of schema and ingestion changes going forward.

### Planned
- Migrate card/ruling ingestion from manually-reduced MTGJSON `AtomicCards.json` to Scryfall bulk API.
- Introduce `oracle_id` as stable join key between `cards` and `rulings`.
- Add `sync_state` table to track upstream `updated_at` per source and enable idempotent sync.
- Preserve both faces of double-faced/MDFC cards (current ingestion silently drops back faces).
