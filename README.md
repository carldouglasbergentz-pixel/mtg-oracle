# MTG Oracle

Local Magic: The Gathering knowledge base — cards, rulings, comprehensive rules, and combos — queryable through Claude Code.

## Setup

1. Install dependencies and create the database schema:
   ```bash
   pip install -r requirements.txt
   python scripts/init_db.py
   ```

2. Populate it from upstream sources (Scryfall, Wizards, Commander Spellbook):
   ```bash
   python scripts/sync.py
   ```

3. Open the project in Claude Code:
   ```bash
   cd mtg-oracle
   claude
   ```

### Upgrading an existing database

If you have a `mtg.db` built before the Scryfall migration, run the
idempotent schema migration once, then sync:

```bash
python scripts/migrate_add_oracle_id.py
python scripts/sync.py --force
```

## Usage

In Claude Code:

- `/sync` — refresh all upstream sources (cards, rulings, rules, combos)
- `/ruling Thassa's Oracle` — look up rulings for a card
- `/combo Thassa's Oracle, Demonic Consultation` — find combos
- Natural language works too:
  - *"What does the layer system actually do?"*
  - *"How does indestructible interact with -X/-X effects?"*
  - *"Show me all 2-card combos in Izzet colors that win the game."*

## Project layout

```
mtg-oracle/
├── CLAUDE.md                ← Project context for Claude Code
├── .claude/
│   ├── settings.json        ← Tool permissions
│   └── commands/            ← Custom slash commands
├── data/
│   ├── raw/                       ← Cached upstream payloads (gitignored)
│   └── mtg.db                     ← The database (gitignored)
├── scripts/
│   ├── init_db.py                 ← create schema on a fresh DB
│   ├── migrate_add_oracle_id.py   ← one-time upgrade for pre-Scryfall DBs
│   ├── sync.py                    ← orchestrator (cards + rules + combos)
│   ├── sync_cards.py              ← Scryfall bulk (oracle_cards + rulings)
│   ├── sync_rules.py              ← Wizards Comprehensive Rules (.txt)
│   └── sync_combos.py             ← Commander Spellbook
└── requirements.txt
```

## Notes

- `sync.py` is idempotent: it skips any source whose upstream `updated_at` / ETag / release date has not changed. Use `--force` to re-ingest anyway.
- Run cadence suggestion: cards daily, rules after set releases (~6x/year), combos weekly. A single daily `sync.py` covers all three with near-zero cost when unchanged.
- Spellbook does not contain every possible combo. Treat it as comprehensive-for-known-combos, not exhaustive.
