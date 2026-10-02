Refresh the MTG Oracle database from all upstream sources.

Steps:

1. Run the app's sync from the newest snapshot (idempotent: unchanged sources are skipped). In PowerShell:
   ```powershell
   app\mtg.cmd sync
   ```
   From Git Bash: `cmd //c "app\\mtg.cmd sync"`. Without a snapshot, build one first with `app\gradlew -p app :app:installLocal`, or run the same sync from the build with `app\gradlew -p app :app:sync`.

   Arguments:
   - `--force` re-ingests every source regardless of upstream state.
   - One or more source keys limit the run: `cards`, `rules`, `combos`, `tags`, `oracletags`, `formats`, `printings`.
   - `--json` prints the report as JSON.

   In the app itself, the [ Sync ] button and the `sync` command do the same, and reload the lookup afterwards. The app keeps a backup before the first sync of a database (`data/backups/`).

2. Report back:
   - The `=== sync: ... ===` changelog printed at the end: what each keyed table gained, lost or changed.
   - Which sources were refreshed and which skipped as unchanged, and any that failed (exit code 1).
   - The current size of `data/mtg.db`.

Sources:
- **cards**: Scryfall `oracle_cards` + `rulings` bulk files (daily upstream).
- **rules**: the Wizards Comprehensive Rules `.txt`, found by reading `magic.wizards.com/en/rules` (about six times a year, with set releases).
- **combos**: Commander Spellbook `variants.json` (weekly, ~600 MB).
- **tags**: the local tagging pass (CR 702 keywords, type-line types, per-ability `has_target` / `produces_mana` / `is_mana_ability` per CR 605.1a/b), rebuilt from the `cards` table.
- **oracletags**: Scryfall Tagger's oracle tags (daily).
- **printings**: every paper printing Scryfall has, for card art (the bulk file once, then only the sets whose card count moved).
- **formats**: the curated points lists in `data/formats/*.json`.

The sync never touches a deck. Stale card rows (names no export writes any more) are a separate step: `app\mtg.cmd prune` is a dry run, `prune --yes` deletes.
