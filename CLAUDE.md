# MTG Oracle — Project Context

A queryable SQLite knowledge base of Magic: The Gathering cards, rulings,
the Comprehensive Rules, Commander Spellbook combos, and the user's
own decks. The Textual TUI in `mtg_oracle.tui` is the primary interface;
`scripts/mtg_cli.py` mirrors it for shell use. Both are thin adapters over
`mtg_oracle.services` — see **Layers** below.

## Where things live

- **Status / phase progress / parked items** → [`docs/project-plan.md`](docs/project-plan.md). Read this first when starting a fresh chat to see where we are without reloading the conversation.
- **Per-feature history** → [`CHANGELOG.md`](CHANGELOG.md). Append-only.
- **Bootstrapping a new contributor** → [`README.md`](README.md).
- **App aesthetic intent** → [`docs/app-design.md`](docs/app-design.md).
- **Deck-analysis report style** → [`docs/report-style.md`](docs/report-style.md). Reports are written in **Swedish** (every other doc here is English, because those are code-facing), live in `docs/*-report.html` **and** as an artifact, and share one inlined stylesheet. Read it before writing a new one — the convention existed only as a single example once, and the second report was written in the wrong language because of it.
- **Stable user preferences** → `memory/` (loaded selectively).
- **This file** → durable rules: schema semantics, conventions, don'ts, plan-first gate, self-review checklist. Loaded every turn — keep it lean.

## Schema (data/mtg.db)

Tables: `cards`, `card_legalities`, `rulings`, `rules`, `combos` + `combo_cards` / `combo_results` / `combo_prerequisites` / `combo_steps`, `card_tags`, `card_abilities`, `decks` + `deck_folders` + `deck_cards` + `deck_revisions` / `deck_changes`, `corrections`, `sync_state`. Read `scripts/init_db.py` for the full column list; only the non-obvious semantics belong here:

- **`cards.color_identity`** — CSV (`B,G`), spans both faces, drives commander filtering. DFC/split/flip names combine faces with ` // `; per-face data lives in `card_faces` JSON.
- **`combos.color_identity`** — contiguous letters (`WBG`, `GU`), unlike `cards.color_identity` which is comma-separated. Match accordingly.
- **Two-faced cards are classified by their FRONT face**, in both the deck renderer and analytics — `"Land" in type_line` over the combined line made `Sorcery // Land` a pure land. Only layouts where the player *chooses* a face (`modal_dfc`) grant the back face: an MDFC with a land back is a spell that also counts as a mana source and is reported as `39 (42 with MDFC)`, while a `transform` back is unreachable by playing and grants nothing. `Land // Land` (Pathways) is a full land sourcing both colours. `analytics` needs `cards.layout` + `cards.card_faces`, which `get_deck` selects for it.
- **`card_abilities.is_mana_ability`** — follows CR 605.1a/b: produces mana, has no target, is not a loyalty ability. Deathrite Shaman's mana ability is correctly *not* flagged because its cost targets a graveyard card.
- **`card_legalities` only stores `legal` / `restricted` / `banned`.** Scryfall reports all 23 formats for every card, but ~55% are `not_legal` — so **no row means not legal**, and every query has to treat absence as illegal. `f:` matches legal *or* restricted. The search language folds format names through `queries.normalize_format()` (strict — raises on a typo); everything else goes through `queries.resolve_format()`, which is the single answer to "what is this format string" and returns None for names it doesn't know.
- **`restricted` means two different things**, per `queries.RESTRICTED_MEANS_NO_COMMANDER`. In `vintage` / `oldschool` it's a one-copy limit. In `duel` (Duel Commander) and `tlr` (Tiny Leaders: Reborn) it's **banned as commander, legal in the deck** — verified against duelcommander.org, whose "cannot be used as your commander" list is exactly Scryfall's 27 `duel` restricted rows. `get_card` re-buckets those as `no_commander`.
- **Scryfall's `duel` key *is* Duel Commander** — you already have that community format, ban list and all (250 bans, 186 of them not shared with EDH). `tlr` is Tiny Leaders: Reborn. Don't build a scraper for either.
- **`custom_formats` / `custom_format_points`** cover what Scryfall can't express: a points list. `derives_from` names the Scryfall format whose pool is inherited (`canadianhighlander` → `vintage`, because the format shares Vintage's ban list), so legality is never duplicated. Source data is curated JSON in `data/formats/`, loaded by `sync.py --only formats`; a card name that doesn't resolve is a hard failure. `queries.resolve_format()` is the single answer to "what is this format string" — key, label, inherited legality key, points budget, singleton flag — and it's cached, so call `queries.clear_format_cache()` after loading formats.
- **`cards.games`** — CSV (`arena,mtgo,paper`). This is what separates real cards from the 216 Arena-only Alchemy `A-` rebalances that otherwise head every alphabetical result. `game:paper` is the filter.
- **`card_oracle_tags` is upstream community knowledge; `card_tags` is derived locally.** Different questions: `card_tags` knows Lightning Bolt is an Instant, Scryfall Tagger knows it is removal. 227k taggings covering 99.4% of the database, rebuilt daily by Scryfall, ingested by `sync_oracle_tags.py` (source key `oracletags`). `roles.classify(card, tags=...)` asks the tags first and falls back to its text rules only where a card carries no *recognised* label — a recognised label that implies no role gives `utility` from the tags (`roles.tag_verdict`) — and **tags replace those rules rather than union with them**, because a loose regex can add a role the tags correctly withheld (Lava Spike is the case: `"3 damage to target player"` matches the spot-removal pattern). Tagger's drawback labels on removal (`land ramp`, `tutor-land-*` alongside `donate rampant growth`) are dropped, so Path to Exile is `spot` only. Tagger's `spot removal` on damage that only reaches players and planeswalkers is dropped too (Lava Spike is `burn`). This is the one place the oracle text is consulted for a tagged card, and it is only a veto: `burn planeswalker` also labels bites ("target creature or planeswalker"), so the tags alone cannot tell the two apart. Only **direct** taggings are stored; the parent graph was measured and expanding it costs more verdicts than it buys.
- **`card_tags` PK is `(card_name, tag, category)`** — a token can legitimately be two things on one card (`saga`, `adventure`, `dragon` are subtypes *and* keywords). The old two-column key silently kept whichever row `tag_cards.py`'s set happened to yield first, so `kw:` results varied between syncs. Existing DBs need `scripts/migrate_fix_card_tags_pk.py`.
- **`cards` rows are never pruned.** `sync_cards.py` upserts on `name`; a card whose name stops appearing upstream keeps its old row with NULL Scryfall columns, and a NULL `color_identity` reads as colorless — which leaks into every `ci<=` filter. `scripts/prune_stale_cards.py` is the cleanup (dry run by default).
- **Deck names are unique per folder, case-insensitively** (`idx_decks_folder_name_nocase_unique` on `(COALESCE(folder_id, 0), name COLLATE NOCASE)`). `folder=None` in `decks` means *any* folder; `decks.UNSORTED` means *only* decks outside a folder — they are different questions, and conflating them made unsorted decks unreachable. `get_deck` raises `AmbiguousDeckError` (with `.folders`) rather than returning None when a name matches in several folders.
- **`deck_cards.is_commander`** — drives format-aware behavior (see below). Multiple rows allowed for Partner / Background / Friends Forever. ON DELETE CASCADE from `decks`.
- **`deck_revisions` + `deck_changes`** — per-deck content history: **one user action = one revision** (add, remove, promote, demote, import, load, replace, undo — an import of 100 cards is one), and its `deck_changes` rows are the quantity diff per `(card_name, section)`, written in the same transaction as the change. No-ops record nothing; rename / move / format are not content and are not logged. `decks.undo_last_change` applies the latest revision's inverse and records that as an `undo` revision, so undo of undo is redo. Both cascade from `decks`.
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

Setup commands live in [`README.md`](README.md). The load-bearing detail for code work is that `scripts/sync.py` is idempotent (sources skip when upstream is unchanged), takes `--force` to re-ingest, `--only cards|rules|combos|tags` to scope, and self-heals the schema on every invocation. Each run prints `=== changelog ===` summarizing what changed. **The migration list is `scripts/self_heal.py`'s `MIGRATIONS`, and the app and CLI run it on start too**, so a new table never leaves deck edits failing until someone runs sync. A new migration goes in that list, in dependency order. `self_heal.run(db_path=...)` heals the database the caller names; without a path it leaves each migration's own `DB_PATH` alone, so a test that pointed them at a copy is never redirected to the real file.

`mtg_oracle/roles.py` answers "what does this card do and what does it really cost". Two rules there are load-bearing enough to state here: a role's **primary** is picked by `PRIMARY_ORDER`, and that order encodes modelling decisions, not taste — `ritual` beats `mana` because `profile_deck` turns a `mana` primary into a permanent mana source in the on-curve model, and a Dark Ritual counted as a rock hands the deck an extra mana every turn forever. And a **`-self` tag describes resilience, not an effect**: a creature that returns itself is a threat, a Vehicle that taps for mana is a threat, a card that loots is not attacking your hand. `TAG_SUPPRESS` exists because that veto has to see the whole tag set — Gravecrawler carries `reanimate-self` *and* `reanimate-cast` — and it silences only the ambiguous labels (`reanimate-cast`, `reanimate-face-down`), never `recursion` card-wide: The Scarab God's `reanimate-creature` is real recursion. In `services.profile_deck` **a card sits in exactly one pile**: rocks power every other role's live curve and are the category only for `mana` (`DeckProfile.on_curve_mv`) — counting a rock as both made the curves double-count it.

Scryfall serves bulk data as **gzipped JSON Lines** via `jsonl_download_uri` — one object per line, not a JSON array. The old uncompressed `download_uri` field is gone. `sync_cards.py` streams it; don't reintroduce `json.load` over the whole export.

## Layers

Four of them, and the direction of dependency is one-way:

| module | owns | may import |
|---|---|---|
| `queries` / `decks` / `scryfall_search` / `forge_data` | SQL, one concept each (reads / deck writes / the search language / Forge substitutions and games) | each other |
| `forge_client` | the external Forge install: config, card index, `.dck` files, the Java subprocess | `forge_format` only |
| `renderer` / `analytics` / `probability` / `roles` / `forge_format` | plain-text presentation and pure computation over dicts (and Forge's file / output formats) | nothing from this project |
| `services` | **use cases** — one function per user intent | the data modules |
| `tui/` + `scripts/*.py` | argument syntax, widgets, printing | services + renderer |

`services.py` is the seam, and the rule for what belongs there is *composition*: an intent that needs more than one data call, or a decision made before the call. A one-line pass-through to `queries` or `decks` does **not** belong there — call those directly, or the layer becomes a second copy of the API with nothing added.

It exists because the interfaces kept growing their own copies of the same use case and only one copy was right: `paste` and `deck import` each parsed a deckstring their own way, and `commander <card>` skipped the legality check that `add` applied. Anything two interfaces both need lives in `services` once. It is also the surface the LLM layer will call — plain values in, plain data out, `ServiceError` for anything the user should see.

`renderer` never imports `queries` or `decks`: presentation takes primitives, so a caller wanting the singleton flag passes it in rather than the renderer reaching for a DB. Same for `probability` (exact hypergeometric draw maths) and `roles` (what a card does and what it really costs) — both are pure functions over dicts, both are unit-tested without a database where possible. **`tests/test_layering.py` enforces this by reading imports with `ast`**, because a module that imports something forbidden still imports *successfully* — the contract was a convention one careless line could break silently, and the temptation is real (`render_profile` would be shorter if it could read `roles.LABELS` itself).

Data-layer dataclasses cross into `renderer` **duck-typed**, never imported: `render_profile` reads `.counts` / `.role_mv` / `.live_curve()` off a `services.DeckProfile` without knowing the type exists.

The test for what belongs in `services` versus the pure layer is *does it need a data call*. `rank_cards` looks at home in `analytics` and cannot live there: it needs `get_card_facts` **and** `get_oracle_tags`.

Tests live in `tests/`, stdlib `unittest`, no new dependency: `python -m unittest discover tests`. The suites that need `data/mtg.db` skip themselves when it is absent. **A test that writes decks runs on `tests/db_sandbox.py`'s copy** — `data/mtg.db` holds the user's real decks, and even a create-then-delete on it left the file modified.

**Deck analysis** lives on all three surfaces, and which one you want depends on the reference set. `profile` / `compare <deck>` in the TUI and `deck profile` / `deck compare --against` in the CLI work **deck to deck against the database** — no filesystem paths in a command language that otherwise only knows decks and folders. `scripts/analyse_archetype.py --dir <folder> --compare <deck>` is the only surface that takes a folder of `.txt` reference lists, and the only one that reports **ranges**; `--json` is the shape the LLM layer consumes. The tables themselves are `renderer.render_profile` / `render_ranking` / `render_comparison`, so all three surfaces emit the same bytes. They take `role_labels` and `role_order` as arguments because `renderer` imports nothing from the project — see **Layers**.

A comparison against **one** reference deck renders as a head-to-head, not a range report: the range verdicts exist to say "nobody in the reference set went there", and with one list the range is a point, so every difference would read as stepping outside it.

**Export** is `export` in the TUI (clipboard) or `deck export` in the CLI; it emits the section headers `deck_parser` reads, so export→import round-trips.

**Forge** (the playtest engine, `tools/forge/`, git-ignored) is `forge export | sub | play | sim | results` in the CLI, over `services.forge_*`. `forge_format` is pure (`.dck` text, sim-output parsing, filename sanitising), `forge_client` owns the install and the subprocess, `forge_data` the `forge_substitutions` / `forge_matches` tables. What isn't obvious from the code: **Forge names every multi-face card by its front face** (`Fire // Ice` is `Fire` in a `.dck`), so names go through `forge_format.forge_card_name`. **`sim` only reads decks from Forge's own decks folder** — `%APPDATA%\Forge\decks\constructed`, or `...\commander` with `-f Commander`; `-D` and absolute `-d` paths are ignored — so export writes there (`data/config.json` key `forge`), and a test that must not touch it runs Forge in a temp directory with `res/` linked and its own `forge.profile.properties` (`tests/test_forge.py`). **Every `.dck` we write carries an ownership marker** in `Description=` (deck id + a digest of the card sections); `forge_client.write_deck` refuses a file without it, one for another deck, or one edited in Forge since. And **a substitution changes only the `<deck> (AI).dck` copy**, validated by `decks.check_swaps` — a rolled-back dry run through `add`'s own rules, never a second copy of them. Forge's clock-stopped games print a win for player 1; `parse_sim_output` counts them as draws.

## Query patterns

Most card / rule / combo / deck lookups have helpers in `mtg_oracle.queries` and `mtg_oracle.decks` — prefer those over hand-rolled SQL. For ad-hoc queries, the conventions are `COLLATE NOCASE` on names, `resolve_card_name()` for tolerant input, and `''` to escape apostrophes (`'Thassa''s Oracle'`). One emblematic shape:

```sql
-- Combos requiring ALL of several cards
SELECT combo_id FROM combo_cards
WHERE card_name COLLATE NOCASE IN (?, ?, ?)
GROUP BY combo_id HAVING COUNT(DISTINCT card_name) = ?;
```

## Format-aware deck behavior

Three triggers decide whether a deck's `add` and `search` get extra rules:

- **Commander color identity.** When a deck has any `deck_cards.is_commander = 1` row, the deck's effective CI is the sorted union of those rows' `cards.color_identity`. `mtg_oracle.decks.get_deck_color_identity()` returns it (or `None` for no commanders). `add` rejects cards whose CI isn't a subset of the deck CI; `search` inside the deck is hard-filtered with `ci<=<deck CI>`.
- **Format legality.** When `decks.format` resolves to a legality key (`mtg_oracle.decks.get_deck_format_info()`, which follows `derives_from` for custom formats), `search` inside the deck is also hard-filtered with `f:<format>`, and `add` rejects banned cards and cards outside the format's pool with distinct messages naming both the format and its inherited pool. Unlike the CI check, this one *does* apply to commanders — an illegal commander is still illegal. Formats with no definition and no Scryfall key are unchecked.
- **Points budget.** When the resolved format has a `points_budget` (Canadian Highlander: 10), `add` rejects a card whose points wouldn't fit, and `decks.deck_points()` / the `points` command report spend vs. budget. Going over via `--force` is reported (`[11/10 pts!]`), never hidden.
- **Singleton.** `queries.is_singleton_format()` is the one answer, and it takes the raw string the user typed: it folds through `queries.fold_format()` (`EDH` → `commander`, `1v1 commander` → `duel`, `tiny leaders` → `tlr`), then asks `resolve_format()`. A format with no definition and no Scryfall key still gets the rule if it's in `queries.SINGLETON_COMMUNITY_FORMATS` (`highlander`, `ozhighlander`, `leviathan` — no upstream list), which is why the singleton question can't be answered from `resolve_format()` alone: that returns None for those. Basic lands (type line contains `Basic` + `Land`) and cards whose oracle text contains `a deck can have any number of cards named` are exempt. Sideboard rows count separately from main.

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

- **Clickable output.** Renderers report clickable regions as `renderer.LinkSpan(line, start, end, kind, args)` via an optional `links` list — they own the column widths and truncation rules, so the TUI must never re-derive positions by pattern-matching rendered text. The TUI turns each span into a Rich `@click` meta whose payload is an **integer ticket** into `app._click_targets`; never interpolate a card name into an action string (apostrophes, commas and `//` all break the action parser). Nav-pane tickets are recycled on re-render, output-pane tickets persist with the scrollback.
- **Pane widths are measured, not assumed.** The nav pane is resizable (drag the `PaneDivider`, or `Ctrl+Left`/`Ctrl+Right`), so renderers get `app._nav_content_width()` — the widget's live `content_region.width` — not a constant. `NAV_WIDTH - 4` is only the fallback before layout: the border and the padding each take a column per side. Getting it wrong overflows silently, so assert against `widget.content_region.width`.
- **A deck's format is the switch for every rule** — legality, singleton and points all hang off `decks.format`, and a deck with none gets none of them. When a rule seems not to fire, check the format first. `deck_folders.format` is a *default* that new decks in that folder inherit; a deck's own value always wins.
- **`COLLATE NOCASE` needs a NOCASE index.** SQLite cannot use a BINARY index to satisfy a NOCASE comparison, so every name column this project compares case-insensitively has a matching `... COLLATE NOCASE` index (see `scripts/migrate_add_nocase_indexes.py`). Adding a new NOCASE query on an unindexed column silently costs a full table scan — that gap made `get_deck` take 747 ms instead of 0.8 ms.
- **Nothing expensive in a mouse-move handler.** A drag emits one event per column crossed; re-rendering a pane or writing a file per event reads as a hang. Move that work to the drag-completed message.
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
