Refresh the MTG Oracle database from all upstream sources.

Steps:

1. Run the orchestrator (idempotent — unchanged sources are skipped):
   ```bash
   python scripts/sync.py
   ```

   Flags:
   - `--force` re-ingests all three sources regardless of upstream state.
   - `--only cards|rules|combos` limits the run to one source.

2. Report back:
   - The `sync_state` summary printed at the end (upstream `updated_at` + row counts per source).
   - Current file size of `data/mtg.db`.
   - Which sources were actually refreshed vs. skipped, based on the script output.

Sources:
- **cards** — Scryfall `oracle_cards` + `rulings` bulk files (daily cadence upstream).
- **rules** — Wizards Comprehensive Rules `.txt`, discovered by scraping `magic.wizards.com/en/rules` (updated ~6x/year with set releases).
- **combos** — Commander Spellbook `variants.json` (weekly).

Do NOT manually call the individual `sync_cards.py` / `sync_rules.py` / `sync_combos.py` scripts unless the user explicitly asks — prefer the orchestrator so `sync_state` stays consistent and the user sees a single summary.
