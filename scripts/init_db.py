"""Initialize the SQLite database schema."""
import sqlite3
from pathlib import Path

DB_PATH = Path(__file__).parent.parent / "data" / "mtg.db"
DB_PATH.parent.mkdir(parents=True, exist_ok=True)

SCHEMA = """
CREATE TABLE IF NOT EXISTS cards (
    name TEXT PRIMARY KEY,
    oracle_id TEXT,
    oracle_text TEXT,
    type_line TEXT,
    layout TEXT,
    card_faces TEXT
);
CREATE INDEX IF NOT EXISTS idx_cards_oracle_id ON cards(oracle_id);

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
"""


def main() -> None:
    conn = sqlite3.connect(DB_PATH)
    conn.executescript(SCHEMA)
    conn.commit()
    conn.close()
    print(f"OK Database initialized at {DB_PATH}")


if __name__ == "__main__":
    main()
