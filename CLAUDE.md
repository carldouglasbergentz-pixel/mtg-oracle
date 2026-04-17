# MTG Oracle — Project Context

This project compiles a comprehensive Magic: The Gathering knowledge base — cards, rulings, comprehensive rules, and combos — into a queryable SQLite database. Claude Code is the primary interface.

## Quick reference

**Database:** `data/mtg.db` (SQLite, ~50–100 MB once populated)

**Most common queries to handle:**
- Card rulings lookup → `rulings` table joined with `cards`
- Rules interactions → `rules` table (rule_number or text search)
- Combo discovery → `combos` table joined with `combo_cards`

## Schema

### cards
- `name` (PK) — canonical Oracle name
- `oracle_text` — official rules text
- `type_line` — full type line (e.g., "Legendary Creature — Elf Noble")

### rulings
- `id`, `card_name` (FK), `date` (YYYY-MM-DD), `text`

### rules
- `rule_number` (PK) — e.g., "100.1a"
- `parent_rule` — e.g., "100.1" (NULL for top-level)
- `section_title` — e.g., "Game Concepts"
- `text`

### combos
- `id` (PK, Spellbook ID)
- `name`, `color_identity`, `description`

### combo_cards
- `combo_id`, `card_name`, `quantity`

### combo_results, combo_prerequisites, combo_steps
- All have `combo_id` + `text` (steps also have `step_order`)

## Workflow

### First-time setup
```bash
pip install -r requirements.txt
python scripts/init_db.py
python scripts/ingest_cards.py data/source/mtg_judge_db.json
python scripts/ingest_rules.py data/source/MagicCompRules.docx
python scripts/ingest_combos.py
```

### Refreshing
- Combos change weekly → `/sync` or `python scripts/ingest_combos.py`
- Cards/rules only change when source files are replaced

## Query patterns

**Card rulings:**
```sql
SELECT date, text FROM rulings
WHERE card_name = 'Thassa''s Oracle'
ORDER BY date;
```

**Combos containing a card:**
```sql
SELECT c.id, c.color_identity, c.description
FROM combos c
JOIN combo_cards cc ON c.id = cc.combo_id
WHERE cc.card_name = 'Thassa''s Oracle';
```

**Combos requiring ALL of several cards:**
```sql
SELECT combo_id FROM combo_cards
WHERE card_name IN ('Card A', 'Card B')
GROUP BY combo_id
HAVING COUNT(DISTINCT card_name) = 2;
```

**Rules by keyword in a section:**
```sql
SELECT rule_number, text FROM rules
WHERE text LIKE '%layer%' AND rule_number LIKE '613%';
```

## Conventions

- Card names are **case-sensitive** and match canonical Oracle naming. For fuzzy matches, use `LIKE '%name%'`.
- For *rules interactions*, prefer the `rules` table over `rulings`. Rulings clarify specific cards; rules govern the system.
- For "can X do Y?" questions, check **both** rules AND that card's rulings.
- Combo answers should always include: cards involved, color identity, prerequisites, the result, and the steps.
- When a card name in a query has an apostrophe, escape it with `''` in SQL (`'Thassa''s Oracle'`).

## Don't

- Don't fetch from Scryfall live unless a card is genuinely missing from `cards`.
- Don't modify files in `data/source/` — those are immutable inputs.
- Don't assume Spellbook combos are exhaustive — many homebrew combos exist outside their database. State this caveat when relevant.
- Don't run `init_db.py` against an existing populated database without confirming with the user — it doesn't drop data, but the user should know.
