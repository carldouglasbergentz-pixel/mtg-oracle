# MTG Oracle — Project Context

A queryable SQLite knowledge base of Magic: The Gathering cards, rulings,
the Comprehensive Rules, Commander Spellbook combos, and the user's
own decks. The Textual TUI in `mtg_oracle.app` is the primary interface;
`scripts/mtg_cli.py` mirrors it for shell use.

## Where things live

- **Status / phase progress / parked items** → [`docs/project-plan.md`](docs/project-plan.md). Read this first when starting a fresh chat to see where we are without reloading the conversation.
- **Per-feature history** → [`CHANGELOG.md`](CHANGELOG.md). Append-only.
- **Bootstrapping a new contributor** → [`README.md`](README.md).
- **App aesthetic intent** → [`docs/app-design.md`](docs/app-design.md).
- **Stable user preferences** → `memory/` (loaded selectively).
- **This file** → durable rules: schema, conventions, don'ts, self-review checklist. Loaded every turn — keep it lean.

## Schema (data/mtg.db)

- **`cards`** (`name` PK, `oracle_id`, `oracle_text`, `mana_cost`, `mana_value`, `colors` (CSV), `color_identity` (CSV — Scryfall's, spans both faces, drives commander filtering), `power`, `toughness`, `rarity`, `type_line`, `layout`, `card_faces` JSON). DFC/split/flip cards combine faces with ` // `.
- **`rulings`** — `id`, `card_name` (FK), `oracle_id`, `date`, `text`.
- **`rules`** — `rule_number` PK (`100.1a`), `parent_rule`, `section_title`, `text`.
- **`combos`** + `combo_cards` / `combo_results` / `combo_prerequisites` / `combo_steps` — Spellbook IDs as PK, sub-tables share `combo_id` + `text`.
- **`card_tags`** — flat `(card_name, tag)` PK with `category` (`keyword` / `supertype` / `type` / `subtype`) and `source`.
- **`card_abilities`** — one row per parsed ability with `ability_type` (`keyword`/`activated`/`triggered`/`static`/`loyalty`), `cost`, `effect`, `has_target`, `produces_mana`, `is_mana_ability` (CR 605.1a/b).
- **`deck_folders`** (flat) + **`decks`** (`folder_id` FK, NULL = unsorted; UNIQUE on `(folder_id, name)`) + **`deck_cards`** (`is_commander`, `is_sideboard` flags; ON DELETE CASCADE).
- **`corrections`** — `topic`, `category`, `incorrect_claim`, `correct_claim`, `explanation`, `relates_to` JSON, `source`, `added_at`, `added_by`.
- **`sync_state`** — `source` PK with `updated_at` upstream marker.

## Feedback loop — corrections table

Persistent memory of past factual mistakes. Use it.

**Before answering any card-interaction or rules-interaction question:**
```sql
SELECT id, topic, correct_claim, explanation, source FROM corrections
WHERE relates_to LIKE '%<card>%' OR topic LIKE '%<topic>%'
ORDER BY added_at DESC;
```
If a relevant row exists, honor `correct_claim` and cite the correction in your answer. Never restate `incorrect_claim` as fact.

**When a mistake is surfaced (by user or self-caught):** insert a new row immediately via `/correction add` (see `.claude/commands/correction.md`). Required: `topic`, `category`, `incorrect_claim`, `correct_claim`, `explanation`, `relates_to`, `source`. Do it in the same turn — context is freshest now.

Corrections are for **specific factual mistakes** about cards/rules/combos. They are **not** for planned features (→ CHANGELOG), preferences (→ memory/), or session work (→ conversation context).

## Workflow

```bash
pip install -r requirements.txt
python scripts/init_db.py
python scripts/sync.py        # cards + rulings + rules + combos + tags
python scripts/mtg_app.py     # launch the TUI
```

`sync.py` is idempotent — sources skip when upstream is unchanged. `--force` re-ingests; `--only cards|rules|combos|tags` scopes the run. End of every run prints a `=== changelog ===` diff.

## Query patterns

Most-used SQL shapes. Card names + rule numbers use `COLLATE NOCASE`; the `mtg_oracle.queries.resolve_card_name()` helper additionally handles diacritics, ligatures, apostrophes, and DFC front-face-only names.

**Card rulings:**
```sql
SELECT date, text FROM rulings WHERE card_name = ? ORDER BY date;
```

**Combos requiring ALL of several cards:**
```sql
SELECT combo_id FROM combo_cards
WHERE card_name COLLATE NOCASE IN (?, ?, ?)
GROUP BY combo_id HAVING COUNT(DISTINCT card_name) = ?;
```

**Cards with a keyword:**
```sql
SELECT card_name FROM card_tags WHERE tag = ? AND category = 'keyword';
```

**Mana abilities per CR 605.1a/b** (excludes Deathrite Shaman because its cost targets a graveyard card):
```sql
SELECT DISTINCT card_name FROM card_abilities WHERE is_mana_ability = 1;
```

For richer card lookups (search syntax, pagination, structured tags), use `mtg_oracle.queries` rather than hand-rolling SQL.

## Format-aware deck behavior

Two triggers decide whether a deck's `add` and `search` get extra rules:

- **Commander color identity.** When a deck has any `deck_cards.is_commander = 1` row, the deck's effective CI is the sorted union of those rows' `cards.color_identity`. `mtg_oracle.decks.get_deck_color_identity()` returns it (or `None` for no commanders). `add` rejects cards whose CI isn't a subset of the deck CI; `search` inside the deck is hard-filtered with `ci<=<deck CI>`.
- **Singleton.** When `decks.format` (case-insensitive) is in `mtg_oracle.decks.SINGLETON_FORMATS` (`commander`, `edh`, `duel commander`, `1v1 commander`, `brawl`, `historic brawl`, `standard brawl`, `oathbreaker`, `highlander`, `canadian highlander`), `add` rejects a 2nd copy of the same card. Basic lands (type line contains `Basic` + `Land`) and cards whose oracle text contains `a deck can have any number of cards named` are exempt. Sideboard rows count separately from main.

Both checks accept `force=True` (kwarg) / `--force` (TUI) to bypass for one call. `import_deck` always forces — paste lists are loaded verbatim. The commander row itself is never CI-checked because it *defines* the CI.

## Self-review before commit

Best-practice discipline for every non-trivial change (new file/function, schema migration, multi-file edit, anything touching user input / network / subprocess / file I/O). Surface the review in the same response as the change.

1. **Correctness.** Trace a concrete input through. Does it return what the conversation said it should?
2. **Failure modes.** Missing DB / row, malformed input, network timeout, empty result set, NULL columns, duplicate key — loud or silent?
3. **Security.** SQL parameterized? Shell/subprocess user-input flow? Path traversal? Secrets in output? For network/subprocess/file-I/O/auth changes, also invoke `/security-review`.
4. **Maintainability.** Fits existing patterns (`scripts/sync_*.py` shape, idempotent migrations, etc.)? Any premature abstraction or unused flexibility to drop?
5. **Redundancy / scope hygiene.** After a redesign: code paths, output, commands, helpers, kwargs, columns that became redundant — remove them. CHANGELOG + git preserve the trail; the live code shouldn't.
6. **Verify.** Run it. Show the output. Don't claim it works without exercising at least one edge case.

Trivial edits (typo, comment tweak, one-line config) may skip — call out that it was skipped and why.

## Conventions

- Card-name lookups go through `mtg_oracle.queries.resolve_card_name()` (or `COLLATE NOCASE` for raw SQL). It's tolerant of case, `/` vs ` // `, DFC front-face-only names, and missing diacritics / apostrophes.
- For *rules interactions*, prefer `rules` over `rulings`. Rulings clarify specific cards; rules govern the system.
- For "can X do Y?" questions, check **both** rules AND that card's rulings.
- Combo answers include: cards involved, color identity, prerequisites, result, steps.
- Apostrophes in raw SQL escape with `''` (`'Thassa''s Oracle'`).
- Phase status changes go in [`docs/project-plan.md`](docs/project-plan.md), not here. CHANGELOG records the work; the project plan records the position.

## Don't

- Don't fetch from Scryfall / Wizards / Spellbook live unless a card is genuinely missing — running `/sync` is the supported refresh.
- Don't modify `data/raw/` — overwritten on every sync.
- Don't assume Spellbook combos are exhaustive. Many homebrew combos exist outside their database; flag the caveat.
- Don't run `init_db.py` against a populated database without confirming with the user.
- Don't skip the `corrections` lookup on interaction/rules questions — that's how last session's bugs reach this session unfixed.
- Don't mutate `corrections` rows in place when a correction turns out to be wrong — insert a new row that supersedes it, or `/correction delete <id>` after explicit confirmation.
- Don't add status / "phase X done" lines here. That belongs in `docs/project-plan.md`.
