-- Schema version 1: scripts/init_db.py SCHEMA as of 2026-09-30, verbatim, which every
-- Python migration had brought the user's database to. The app owns the schema from here:
-- later versions are mtgoracle.data.Migrations, never an edit to this file.
CREATE TABLE IF NOT EXISTS cards (
    name TEXT PRIMARY KEY,
    oracle_id TEXT,
    oracle_text TEXT,
    mana_cost TEXT,
    mana_value INTEGER,
    colors TEXT,
    color_identity TEXT,
    power TEXT,
    toughness TEXT,
    rarity TEXT,
    type_line TEXT,
    layout TEXT,
    card_faces TEXT,
    games TEXT,
    reserved INTEGER,
    edhrec_rank INTEGER
);
CREATE INDEX IF NOT EXISTS idx_cards_mana_value ON cards(mana_value);
CREATE INDEX IF NOT EXISTS idx_cards_rarity ON cards(rarity);
CREATE INDEX IF NOT EXISTS idx_cards_oracle_id ON cards(oracle_id);
CREATE INDEX IF NOT EXISTS idx_cards_color_identity ON cards(color_identity);
CREATE INDEX IF NOT EXISTS idx_cards_edhrec ON cards(edhrec_rank);
-- Name comparisons in this project use COLLATE NOCASE, and SQLite cannot
-- satisfy a NOCASE comparison from a BINARY index. Without these, the
-- convention silently costs a full table scan: get_deck's join over a
-- 100-card deck took 747 ms instead of 0.8 ms. One per column any query
-- compares case-insensitively — see scripts/migrations/migrate_add_nocase_indexes.py.
CREATE INDEX IF NOT EXISTS idx_cards_name_nocase ON cards(name COLLATE NOCASE);

CREATE TABLE IF NOT EXISTS rulings (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    card_name TEXT NOT NULL,
    oracle_id TEXT,
    date TEXT,
    text TEXT NOT NULL,
    FOREIGN KEY (card_name) REFERENCES cards(name)
);
CREATE INDEX IF NOT EXISTS idx_rulings_card ON rulings(card_name);
CREATE INDEX IF NOT EXISTS idx_rulings_oracle_id ON rulings(oracle_id);
CREATE INDEX IF NOT EXISTS idx_rulings_card_nocase ON rulings(card_name COLLATE NOCASE);

CREATE TABLE IF NOT EXISTS sync_state (
    source TEXT PRIMARY KEY,
    updated_at TEXT NOT NULL,
    last_sync TEXT NOT NULL,
    row_count INTEGER
);

CREATE TABLE IF NOT EXISTS rules (
    rule_number TEXT PRIMARY KEY,
    parent_rule TEXT,
    section_title TEXT,
    text TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_rules_parent ON rules(parent_rule);

CREATE TABLE IF NOT EXISTS combos (
    id TEXT PRIMARY KEY,
    name TEXT,
    color_identity TEXT,
    description TEXT
);

CREATE TABLE IF NOT EXISTS combo_cards (
    combo_id TEXT NOT NULL,
    card_name TEXT NOT NULL,
    quantity INTEGER DEFAULT 1,
    PRIMARY KEY (combo_id, card_name),
    FOREIGN KEY (combo_id) REFERENCES combos(id)
);
CREATE INDEX IF NOT EXISTS idx_combo_cards_card ON combo_cards(card_name);
CREATE INDEX IF NOT EXISTS idx_combo_cards_card_nocase ON combo_cards(card_name COLLATE NOCASE);

CREATE TABLE IF NOT EXISTS combo_results (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    combo_id TEXT NOT NULL,
    text TEXT NOT NULL,
    FOREIGN KEY (combo_id) REFERENCES combos(id)
);

CREATE TABLE IF NOT EXISTS combo_prerequisites (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    combo_id TEXT NOT NULL,
    text TEXT NOT NULL,
    FOREIGN KEY (combo_id) REFERENCES combos(id)
);

CREATE TABLE IF NOT EXISTS combo_steps (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    combo_id TEXT NOT NULL,
    step_order INTEGER NOT NULL,
    text TEXT NOT NULL,
    FOREIGN KEY (combo_id) REFERENCES combos(id)
);

CREATE TABLE IF NOT EXISTS user_combos (
    id TEXT PRIMARY KEY,
    name TEXT,
    color_identity TEXT,
    description TEXT,
    added_at TEXT NOT NULL,
    added_by TEXT
);

CREATE TABLE IF NOT EXISTS user_combo_cards (
    combo_id TEXT NOT NULL,
    card_name TEXT NOT NULL,
    quantity INTEGER DEFAULT 1,
    PRIMARY KEY (combo_id, card_name),
    FOREIGN KEY (combo_id) REFERENCES user_combos(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_user_combo_cards_card ON user_combo_cards(card_name);
CREATE INDEX IF NOT EXISTS idx_user_combo_cards_card_nocase ON user_combo_cards(card_name COLLATE NOCASE);

-- Per-format legality. Only `legal` / `restricted` / `banned` rows exist:
-- Scryfall reports all 23 formats for every card and ~55% are `not_legal`,
-- so absence of a row IS "not legal". Note `restricted` means one-copy in
-- vintage/oldschool but banned-as-commander in duel/tlr — see
-- queries.RESTRICTED_MEANS_NO_COMMANDER.
CREATE TABLE IF NOT EXISTS card_legalities (
    card_name TEXT NOT NULL,
    format TEXT NOT NULL,
    status TEXT NOT NULL,
    PRIMARY KEY (card_name, format),
    FOREIGN KEY (card_name) REFERENCES cards(name)
);
CREATE INDEX IF NOT EXISTS idx_card_legalities_format
    ON card_legalities(format, status);
CREATE INDEX IF NOT EXISTS idx_card_legalities_card_nocase
    ON card_legalities(card_name COLLATE NOCASE);

-- Community formats Scryfall can't express, i.e. points lists. `derives_from`
-- names the Scryfall format whose card pool is inherited (Canadian Highlander
-- shares Vintage's ban list). Source data: data/formats/*.json.
CREATE TABLE IF NOT EXISTS custom_formats (
    format TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    aliases TEXT,
    derives_from TEXT,
    points_budget INTEGER,
    singleton INTEGER NOT NULL DEFAULT 0,
    source_url TEXT,
    list_current_as_of TEXT,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS custom_format_points (
    format TEXT NOT NULL,
    card_name TEXT NOT NULL,
    points INTEGER NOT NULL CHECK(points > 0),
    PRIMARY KEY (format, card_name),
    FOREIGN KEY (format) REFERENCES custom_formats(format) ON DELETE CASCADE,
    FOREIGN KEY (card_name) REFERENCES cards(name)
);
CREATE INDEX IF NOT EXISTS idx_custom_points_card
    ON custom_format_points(card_name);
CREATE INDEX IF NOT EXISTS idx_custom_points_card_nocase
    ON custom_format_points(card_name COLLATE NOCASE);

-- PK includes `category`: a token can be both a subtype and a keyword on
-- the same card ('saga', 'adventure', 'dragon'). A (card_name, tag) key
-- kept only one of them, and which one won depended on set iteration order
-- in tag_cards.py. Existing databases: scripts/migrations/migrate_fix_card_tags_pk.py.
CREATE TABLE IF NOT EXISTS card_tags (
    card_name TEXT NOT NULL,
    tag TEXT NOT NULL,
    category TEXT NOT NULL,
    source TEXT NOT NULL,
    PRIMARY KEY (card_name, tag, category),
    FOREIGN KEY (card_name) REFERENCES cards(name)
);
CREATE INDEX IF NOT EXISTS idx_card_tags_tag ON card_tags(tag);
CREATE INDEX IF NOT EXISTS idx_card_tags_category ON card_tags(category);
CREATE INDEX IF NOT EXISTS idx_card_tags_card_nocase ON card_tags(card_name COLLATE NOCASE);

-- Scryfall Tagger's community oracle tags: "what does this card do".
-- Distinct from card_tags above, which this project derives locally from
-- keywords and subtypes. Populated by scripts/sync_oracle_tags.py.
CREATE TABLE IF NOT EXISTS card_oracle_tags (
    card_name TEXT NOT NULL,
    tag TEXT NOT NULL,
    weight TEXT,
    PRIMARY KEY (card_name, tag)
);
CREATE INDEX IF NOT EXISTS idx_card_oracle_tags_tag ON card_oracle_tags(tag);
CREATE INDEX IF NOT EXISTS idx_card_oracle_tags_card_nocase
    ON card_oracle_tags(card_name COLLATE NOCASE);

CREATE TABLE IF NOT EXISTS card_abilities (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    card_name TEXT NOT NULL,
    ability_index INTEGER NOT NULL,
    ability_type TEXT NOT NULL,
    cost TEXT,
    effect TEXT,
    has_target INTEGER NOT NULL DEFAULT 0,
    produces_mana INTEGER NOT NULL DEFAULT 0,
    is_mana_ability INTEGER NOT NULL DEFAULT 0,
    raw_text TEXT NOT NULL,
    FOREIGN KEY (card_name) REFERENCES cards(name)
);
CREATE INDEX IF NOT EXISTS idx_card_abilities_card ON card_abilities(card_name);
CREATE INDEX IF NOT EXISTS idx_card_abilities_type ON card_abilities(ability_type);
CREATE INDEX IF NOT EXISTS idx_card_abilities_mana ON card_abilities(is_mana_ability);

CREATE TABLE IF NOT EXISTS corrections (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    topic TEXT NOT NULL,
    category TEXT NOT NULL,
    incorrect_claim TEXT NOT NULL,
    correct_claim TEXT NOT NULL,
    explanation TEXT,
    relates_to TEXT,
    source TEXT NOT NULL,
    added_at TEXT NOT NULL,
    added_by TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_corrections_topic ON corrections(topic);
CREATE INDEX IF NOT EXISTS idx_corrections_category ON corrections(category);
CREATE INDEX IF NOT EXISTS idx_corrections_added_at ON corrections(added_at);

CREATE TABLE IF NOT EXISTS deck_folders (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE COLLATE NOCASE,
    created_at TEXT NOT NULL,
    -- Default format for decks created in this folder. A default, not an
    -- override: a deck's own `format` always wins, the folder only fills it
    -- in at creation time.
    format TEXT
);

CREATE TABLE IF NOT EXISTS decks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    folder_id INTEGER,
    name TEXT NOT NULL,
    format TEXT,
    description TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (folder_id) REFERENCES deck_folders(id),
    UNIQUE (folder_id, name) ON CONFLICT ABORT
);
CREATE INDEX IF NOT EXISTS idx_decks_folder ON decks(folder_id);
-- The table's UNIQUE misses NULL folders and case variants; this doesn't.
-- See scripts/migrations/migrate_unique_deck_names.py.
CREATE UNIQUE INDEX IF NOT EXISTS idx_decks_folder_name_nocase_unique
    ON decks(COALESCE(folder_id, 0), name COLLATE NOCASE);

CREATE TABLE IF NOT EXISTS deck_cards (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    deck_id INTEGER NOT NULL,
    card_name TEXT NOT NULL,
    quantity INTEGER NOT NULL DEFAULT 1 CHECK(quantity > 0),
    category TEXT,
    is_commander INTEGER NOT NULL DEFAULT 0,
    is_sideboard INTEGER NOT NULL DEFAULT 0,
    added_at TEXT NOT NULL,
    -- The chosen printing, Scryfall's spelling (`c18`, `263`); NULL = none.
    -- See scripts/migrations/migrate_add_printings_and_games.py.
    set_code TEXT,
    collector_number TEXT,
    FOREIGN KEY (deck_id) REFERENCES decks(id) ON DELETE CASCADE,
    FOREIGN KEY (card_name) REFERENCES cards(name)
);
CREATE INDEX IF NOT EXISTS idx_deck_cards_deck ON deck_cards(deck_id);
CREATE INDEX IF NOT EXISTS idx_deck_cards_card ON deck_cards(card_name);
CREATE INDEX IF NOT EXISTS idx_deck_cards_card_nocase ON deck_cards(card_name COLLATE NOCASE);

-- One user action that changed a deck's contents = one revision; its
-- deck_changes rows are the per-(card, section) quantity diff. See
-- scripts/migrations/migrate_add_deck_history.py.
CREATE TABLE IF NOT EXISTS deck_revisions (
    id INTEGER PRIMARY KEY,
    deck_id INTEGER NOT NULL
        REFERENCES decks(id) ON DELETE CASCADE,
    at TEXT NOT NULL,
    action TEXT NOT NULL,
    note TEXT
);
CREATE INDEX IF NOT EXISTS idx_deck_revisions_deck ON deck_revisions(deck_id);

CREATE TABLE IF NOT EXISTS deck_changes (
    id INTEGER PRIMARY KEY,
    revision_id INTEGER NOT NULL
        REFERENCES deck_revisions(id) ON DELETE CASCADE,
    card_name TEXT NOT NULL,
    section TEXT NOT NULL
        CHECK (section IN ('main', 'sideboard', 'commander', 'considering')),
    qty_before INTEGER NOT NULL,
    qty_after INTEGER NOT NULL,
    -- A printing change is a change too (qty_before = qty_after then).
    set_code_before TEXT,
    collector_number_before TEXT,
    set_code_after TEXT,
    collector_number_after TEXT
);
CREATE INDEX IF NOT EXISTS idx_deck_changes_revision ON deck_changes(revision_id);

-- Cards being considered for a deck (Moxfield's maybeboard): not in it,
-- not counted, exported or played. See scripts/migrations/migrate_add_considering.py.
CREATE TABLE IF NOT EXISTS deck_considering (
    id INTEGER PRIMARY KEY,
    deck_id INTEGER NOT NULL
        REFERENCES decks(id) ON DELETE CASCADE,
    card_name TEXT NOT NULL REFERENCES cards(name),
    quantity INTEGER NOT NULL DEFAULT 1 CHECK (quantity > 0),
    added_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_deck_considering_deck ON deck_considering(deck_id);
CREATE INDEX IF NOT EXISTS idx_deck_considering_card_nocase ON deck_considering(card_name COLLATE NOCASE);

-- Forge: per-deck AI substitutions, and one row per simulated game. See
-- scripts/migrations/migrate_add_forge.py.
CREATE TABLE IF NOT EXISTS forge_substitutions (
    id INTEGER PRIMARY KEY,
    deck_id INTEGER NOT NULL
        REFERENCES decks(id) ON DELETE CASCADE,
    card_name TEXT NOT NULL,
    substitute TEXT NOT NULL,
    added_at TEXT NOT NULL,
    UNIQUE (deck_id, card_name COLLATE NOCASE)
);

CREATE TABLE IF NOT EXISTS forge_matches (
    id INTEGER PRIMARY KEY,
    match_id TEXT NOT NULL,
    played_at TEXT NOT NULL,
    deck_a TEXT NOT NULL,
    deck_b TEXT NOT NULL,
    deck_a_id INTEGER REFERENCES decks(id) ON DELETE SET NULL,
    deck_b_id INTEGER REFERENCES decks(id) ON DELETE SET NULL,
    ai_variant_a INTEGER NOT NULL DEFAULT 0 CHECK (ai_variant_a IN (0, 1)),
    ai_variant_b INTEGER NOT NULL DEFAULT 0 CHECK (ai_variant_b IN (0, 1)),
    game_type TEXT NOT NULL
        CHECK (game_type IN ('constructed', 'commander')),
    game_no INTEGER NOT NULL,
    winner TEXT NOT NULL CHECK (winner IN ('a', 'b', 'draw')),
    turns INTEGER,
    duration_ms INTEGER,
    forge_version TEXT,
    log_path TEXT
);
CREATE INDEX IF NOT EXISTS idx_forge_matches_match ON forge_matches(match_id);
CREATE INDEX IF NOT EXISTS idx_forge_matches_deck_a ON forge_matches(deck_a_id);
CREATE INDEX IF NOT EXISTS idx_forge_matches_deck_b ON forge_matches(deck_b_id);

-- One row per game the Kotlin app plays; the app writes it. See
-- scripts/migrations/migrate_add_printings_and_games.py.
CREATE TABLE IF NOT EXISTS games (
    id INTEGER PRIMARY KEY,
    played_at TEXT NOT NULL,
    mode TEXT NOT NULL CHECK (mode IN ('human_vs_ai', 'ai_vs_ai')),
    deck_id INTEGER REFERENCES decks(id) ON DELETE SET NULL,
    deck_name TEXT NOT NULL,
    opponent_deck_id INTEGER REFERENCES decks(id) ON DELETE SET NULL,
    opponent_name TEXT NOT NULL,
    opponent_ai_variant INTEGER NOT NULL DEFAULT 0,
    seed INTEGER,
    winner TEXT CHECK (winner IN ('me', 'opponent', 'draw')),
    turns INTEGER,
    duration_ms INTEGER,
    forge_version TEXT,
    log_path TEXT,
    -- Match grouping; see scripts/migrations/migrate_add_game_matches.py. A NULL
    -- match_id is a single-game match.
    match_id TEXT,
    game_no INTEGER,
    match_format TEXT CHECK (match_format IN ('bo1', 'bo3', 'bo5')),
    conceded INTEGER NOT NULL DEFAULT 0,
    -- 1 when `deck` played its AI copy; see migrate_add_game_deck_ai_variant.py.
    deck_ai_variant INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_games_deck ON games(deck_id);
CREATE INDEX IF NOT EXISTS idx_games_opponent_deck ON games(opponent_deck_id);
CREATE INDEX IF NOT EXISTS idx_games_match ON games(match_id);
