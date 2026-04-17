# MTG Oracle

Local Magic: The Gathering knowledge base — cards, rulings, comprehensive rules, and combos — queryable through Claude Code.

## Setup

1. Drop your source files in `data/source/`:
   - `mtg_judge_db.json` — your card + rulings file
   - `MagicCompRules.docx` — WotC comprehensive rules

2. Install dependencies and build the database:
   ```bash
   pip install -r requirements.txt
   python scripts/init_db.py
   python scripts/ingest_cards.py data/source/mtg_judge_db.json
   python scripts/ingest_rules.py data/source/MagicCompRules.docx
   python scripts/ingest_combos.py
   ```

3. Open the project in Claude Code:
   ```bash
   cd mtg-oracle
   claude
   ```

## Usage

In Claude Code:

- `/sync` — refresh combos from Commander Spellbook
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
│   ├── source/              ← Your input files (gitignored)
│   ├── raw/                 ← Cached Spellbook download (gitignored)
│   └── mtg.db               ← The database (gitignored)
├── scripts/
│   ├── init_db.py
│   ├── ingest_cards.py
│   ├── ingest_rules.py
│   └── ingest_combos.py
└── requirements.txt
```

## Notes

- Commander Spellbook's bulk endpoint is updated weekly. Re-run `/sync` when you want fresh combos.
- The card and rules ingest scripts are idempotent — re-running them with the same source file is safe.
- Spellbook does not contain every possible combo. Treat it as comprehensive-for-known-combos, not exhaustive.
