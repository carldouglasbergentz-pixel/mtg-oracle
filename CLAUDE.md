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
- `oracle_id` — Scryfall stable id (shared across prints of the same card)
- `oracle_text` — official rules text (both faces joined with `// ` for DFC/split/flip)
- `type_line` — full type line (e.g., "Legendary Creature — Elf Noble")
- `layout` — e.g., `normal`, `transform`, `modal_dfc`, `split`, `flip`
- `card_faces` — raw Scryfall per-face JSON (NULL for single-face cards)

### rulings
- `id`, `card_name` (FK), `oracle_id`, `date` (YYYY-MM-DD), `text`

### card_tags
- `card_name` (FK), `tag` (e.g., `flying`, `legendary`, `elf`), `category` (`keyword` / `supertype` / `type` / `subtype`), `source` (`regex` / `type_line`)
- PK `(card_name, tag)`; indexed on `tag` and `category`

### card_abilities
- `id`, `card_name`, `ability_index` — one row per parsed ability on a card (per-face abilities on DFCs get distinct indices)
- `ability_type` — `keyword` / `activated` / `triggered` / `static` / `loyalty`
- `cost` (for activated/loyalty only), `effect`, `raw_text`
- `has_target`, `produces_mana`, `is_mana_ability` — booleans (0/1). `is_mana_ability` follows CR 605.1a/b.

### sync_state
- `source` (PK) — e.g., `scryfall_oracle_cards`, `wizards_cr`, `spellbook_variants`, `local_tags`
- `updated_at` — upstream version marker (timestamp, ETag, or release date)
- `last_sync`, `row_count`

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
python scripts/sync.py      # pulls cards, rulings, rules, combos from upstream
```

### Refreshing
- `/sync` or `python scripts/sync.py` — skips sources whose upstream is unchanged.
- `--force` re-ingests everything; `--only cards|rules|combos` scopes the run.
- Sources of truth: Scryfall (cards + rulings), Wizards `magic.wizards.com/en/rules` (CR `.txt`), Commander Spellbook (combos).

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

**Cards with a specific keyword (e.g., flying):**
```sql
SELECT card_name FROM card_tags
WHERE tag = 'flying' AND category = 'keyword';
```

**Cards whose mana ability is a mana ability per CR 605.1a/b
(i.e., excludes things like Deathrite Shaman where the cost has a target):**
```sql
SELECT DISTINCT card_name FROM card_abilities
WHERE is_mana_ability = 1;
```

**Does Deathrite Shaman's Add-mana ability count as a mana ability?**
```sql
SELECT raw_text, has_target, produces_mana, is_mana_ability
FROM card_abilities
WHERE card_name = 'Deathrite Shaman' AND produces_mana = 1;
-- → has_target=1, is_mana_ability=0 (correctly excluded per CR 605.1a)
```

## Conventions

- Card names are **case-sensitive** and match canonical Oracle naming. For fuzzy matches, use `LIKE '%name%'`.
- For *rules interactions*, prefer the `rules` table over `rulings`. Rulings clarify specific cards; rules govern the system.
- For "can X do Y?" questions, check **both** rules AND that card's rulings.
- Combo answers should always include: cards involved, color identity, prerequisites, the result, and the steps.
- When a card name in a query has an apostrophe, escape it with `''` in SQL (`'Thassa''s Oracle'`).

## Don't

- Don't fetch from Scryfall live unless a card is genuinely missing from `cards`. Running `/sync` is the supported refresh path.
- Don't modify files in `data/raw/` or `data/source/` — those are cached upstream payloads / legacy inputs, overwritten on sync.
- Don't assume Spellbook combos are exhaustive — many homebrew combos exist outside their database. State this caveat when relevant.
- Don't run `init_db.py` against an existing populated database without confirming with the user — it doesn't drop data, but the user should know.
