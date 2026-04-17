Refresh the MTG Oracle database from all upstream sources.

Steps:

1. Run the orchestrator (idempotent — unchanged sources are skipped):
   ```bash
   python scripts/sync.py
   ```

   Flags:
   - `--force` re-ingests all sources regardless of upstream state.
   - `--only cards|rules|combos|tags` limits the run to one source.

2. Report back:
   - The `sync_state` summary printed at the end (upstream `updated_at` + row counts per source).
   - Current file size of `data/mtg.db`.
   - Which sources were actually refreshed vs. skipped, based on the script output.

Sources:
- **cards** — Scryfall `oracle_cards` + `rulings` bulk files (daily cadence upstream).
- **rules** — Wizards Comprehensive Rules `.txt`, discovered by scraping `magic.wizards.com/en/rules` (updated ~6x/year with set releases).
- **combos** — Commander Spellbook `variants.json` (weekly).
- **tags** — local regex-based tagging pass (CR 702 keywords, type-line supertypes/types/subtypes, per-ability `has_target` / `produces_mana` / `is_mana_ability` per CR 605.1a/b). Rebuilds from the current `cards` table; ~3s for 34k cards.

Do NOT manually call the individual `sync_cards.py` / `sync_rules.py` / `sync_combos.py` / `tag_cards.py` scripts unless the user explicitly asks — prefer the orchestrator so `sync_state` stays consistent and the user sees a single summary.
