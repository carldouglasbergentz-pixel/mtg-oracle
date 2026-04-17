Refresh the MTG Oracle database from external sources.

Steps:

1. Re-fetch combos from Commander Spellbook (updated weekly):
   ```bash
   python scripts/ingest_combos.py
   ```

2. Report back:
   - Total combo count now in the database
   - File size of `data/mtg.db`
   - Date of the cached `data/raw/spellbook_variants.json` (last sync)

Do NOT re-ingest cards or rules unless the user explicitly asks — those come from local source files and only change when the user replaces them.
