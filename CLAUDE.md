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
- **This file** → durable rules: schema semantics, conventions, don'ts, plan-first gate, self-review checklist. Loaded every turn — keep it lean.

## Schema (data/mtg.db)

Tables: `cards`, `rulings`, `rules`, `combos` + `combo_cards` / `combo_results` / `combo_prerequisites` / `combo_steps`, `card_tags`, `card_abilities`, `decks` + `deck_folders` + `deck_cards`, `corrections`, `sync_state`. Read `scripts/init_db.py` for the full column list; only the non-obvious semantics belong here:

- **`cards.color_identity`** — CSV (`B,G`), spans both faces, drives commander filtering. DFC/split/flip names combine faces with ` // `; per-face data lives in `card_faces` JSON.
- **`combos.color_identity`** — contiguous letters (`WBG`, `GU`), unlike `cards.color_identity` which is comma-separated. Match accordingly.
- **`card_abilities.is_mana_ability`** — follows CR 605.1a/b: produces mana, has no target, is not a loyalty ability. Deathrite Shaman's mana ability is correctly *not* flagged because its cost targets a graveyard card.
- **`deck_cards.is_commander`** — drives format-aware behavior (see below). Multiple rows allowed for Partner / Background / Friends Forever. ON DELETE CASCADE from `decks`.
- **`sync_state`** — keyed on `source` with `updated_at` upstream marker (timestamp / ETag / release date).

Card-name lookups should go through `mtg_oracle.queries.resolve_card_name()`, which handles case, `/` vs ` // `, DFC front-face-only names, and missing diacritics / apostrophes / ligatures. Raw SQL on names should also use `COLLATE NOCASE`.

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

Setup commands live in [`README.md`](README.md). The load-bearing detail for code work is that `scripts/sync.py` is idempotent (sources skip when upstream is unchanged), takes `--force` to re-ingest, `--only cards|rules|combos|tags` to scope, and self-heals the schema by running `migrate_add_scryfall_fields.py` on every invocation. Each run prints `=== changelog ===` summarizing what changed.

## Query patterns

Most card / rule / combo / deck lookups have helpers in `mtg_oracle.queries` and `mtg_oracle.decks` — prefer those over hand-rolled SQL. For ad-hoc queries, the conventions are `COLLATE NOCASE` on names, `resolve_card_name()` for tolerant input, and `''` to escape apostrophes (`'Thassa''s Oracle'`). One emblematic shape:

```sql
-- Combos requiring ALL of several cards
SELECT combo_id FROM combo_cards
WHERE card_name COLLATE NOCASE IN (?, ?, ?)
GROUP BY combo_id HAVING COUNT(DISTINCT card_name) = ?;
```

## Format-aware deck behavior

Two triggers decide whether a deck's `add` and `search` get extra rules:

- **Commander color identity.** When a deck has any `deck_cards.is_commander = 1` row, the deck's effective CI is the sorted union of those rows' `cards.color_identity`. `mtg_oracle.decks.get_deck_color_identity()` returns it (or `None` for no commanders). `add` rejects cards whose CI isn't a subset of the deck CI; `search` inside the deck is hard-filtered with `ci<=<deck CI>`.
- **Singleton.** When `decks.format` (case-insensitive) is in `mtg_oracle.decks.SINGLETON_FORMATS` (`commander`, `edh`, `duel commander`, `1v1 commander`, `brawl`, `historic brawl`, `standard brawl`, `oathbreaker`, `highlander`, `canadian highlander`), `add` rejects a 2nd copy of the same card. Basic lands (type line contains `Basic` + `Land`) and cards whose oracle text contains `a deck can have any number of cards named` are exempt. Sideboard rows count separately from main.

Both checks accept `force=True` (kwarg) / `--force` (TUI) to bypass for one call. `import_deck` always forces — paste lists are loaded verbatim. The commander row itself is never CI-checked because it *defines* the CI.

## Plan first for big changes

Before writing code for a multi-file refactor, schema migration, change to the sync pipeline, or anything touching user data (decks, corrections), propose a plan and wait for approval. The plan should name the files, the new functions, and the rollback story. Trivial edits (typo, comment, one-line config) and contained additions (one helper, one new query) skip the gate.

This complements the post-hoc self-review checklist below: plan-first catches *what to build*; self-review catches *whether it works*.

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

- For *rules interactions*, prefer `rules` over `rulings`. Rulings clarify specific cards; rules govern the system.
- For "can X do Y?" questions, check **both** rules AND that card's rulings.
- Combo answers include: cards involved, color identity, prerequisites, result, steps.
- Phase status changes go in [`docs/project-plan.md`](docs/project-plan.md), not here. CHANGELOG records the work; the project plan records the position.

## Don't

- Don't fetch from Scryfall / Wizards / Spellbook live unless a card is genuinely missing — running `/sync` is the supported refresh.
- Don't modify `data/raw/` — overwritten on every sync.
- Don't assume Spellbook combos are exhaustive *or* that the listed cards are the complete set. ~2.8% of Spellbook combos have step text referencing a card slot that `combo_cards` doesn't enumerate ("the affinity permanent", "your commander", "any X creature/permanent/spell") — `queries.flag_template_vars()` detects these and the renderer suffixes the count with `+` (e.g. `(2+ cards)`). Many homebrew combos also exist outside Spellbook entirely; the `user_combos` table is the supplement.
- Don't run `init_db.py` against a populated database without confirming with the user.
- Don't skip the `corrections` lookup on interaction/rules questions — that's how last session's bugs reach this session unfixed.
- Don't mutate `corrections` rows in place when a correction turns out to be wrong — insert a new row that supersedes it, or `/correction delete <id>` after explicit confirmation.
- Don't add status / "phase X done" lines here. That belongs in `docs/project-plan.md`.
