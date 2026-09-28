"""SQL for the Forge tables: AI substitutions and simulated games.

    forge_substitutions  per deck, a card the Forge AI can't pilot and what
                         the AI copy of the deck plays instead. The deck in
                         `deck_cards` is never changed by a substitution.
    forge_matches        one row per simulated game.

Created by scripts/migrate_add_forge.py (self-healed on start) and mirrored
in scripts/init_db.py. Validation of a substitution lives in
`decks.check_swaps`; this module only stores what the service layer decided.
"""
from __future__ import annotations

import datetime as dt
import sqlite3
from pathlib import Path
from typing import Iterable, Optional

from mtg_oracle.queries import BUSY_TIMEOUT_S

DB_PATH = Path(__file__).parent.parent / "data" / "mtg.db"


def _connect() -> sqlite3.Connection:
    if not DB_PATH.exists():
        raise FileNotFoundError(f"Database not found at {DB_PATH}.")
    conn = sqlite3.connect(DB_PATH, timeout=BUSY_TIMEOUT_S)
    conn.row_factory = sqlite3.Row
    # The deck foreign keys cascade / set null; SQLite ignores them otherwise.
    conn.execute("PRAGMA foreign_keys = ON")
    return conn


def _now() -> str:
    return (dt.datetime.now(dt.timezone.utc)
            .isoformat(timespec="seconds").replace("+00:00", "Z"))


# --- substitutions --------------------------------------------------------

def list_substitutions(deck_id: int) -> list[dict]:
    """[{card_name, substitute, added_at}], in card order."""
    conn = _connect()
    try:
        rows = conn.execute(
            "SELECT card_name, substitute, added_at FROM forge_substitutions "
            "WHERE deck_id = ? ORDER BY card_name COLLATE NOCASE",
            (deck_id,)).fetchall()
        return [dict(r) for r in rows]
    finally:
        conn.close()


def set_substitution(deck_id: int, card_name: str,
                     substitute: str) -> Optional[str]:
    """Store or replace one substitution. Returns the substitute it replaced."""
    conn = _connect()
    try:
        cur = conn.cursor()
        cur.execute(
            "SELECT id, substitute FROM forge_substitutions "
            "WHERE deck_id = ? AND card_name = ? COLLATE NOCASE",
            (deck_id, card_name))
        row = cur.fetchone()
        if row:
            cur.execute(
                "UPDATE forge_substitutions SET card_name = ?, substitute = ?, "
                "added_at = ? WHERE id = ?",
                (card_name, substitute, _now(), row["id"]))
        else:
            cur.execute(
                "INSERT INTO forge_substitutions "
                "(deck_id, card_name, substitute, added_at) VALUES (?, ?, ?, ?)",
                (deck_id, card_name, substitute, _now()))
        conn.commit()
        return row["substitute"] if row else None
    finally:
        conn.close()


def remove_substitution(deck_id: int, card_name: str) -> Optional[str]:
    """Delete one substitution. Returns the substitute removed, or None."""
    conn = _connect()
    try:
        cur = conn.cursor()
        cur.execute(
            "SELECT id, substitute FROM forge_substitutions "
            "WHERE deck_id = ? AND card_name = ? COLLATE NOCASE",
            (deck_id, card_name))
        row = cur.fetchone()
        if not row:
            return None
        cur.execute("DELETE FROM forge_substitutions WHERE id = ?", (row["id"],))
        conn.commit()
        return row["substitute"]
    finally:
        conn.close()


# --- matches --------------------------------------------------------------

def record_games(*, match_id: str, deck_a: str, deck_b: str,
                 deck_a_id: Optional[int], deck_b_id: Optional[int],
                 ai_variant_a: bool, ai_variant_b: bool, game_type: str,
                 forge_version: str, log_path: str,
                 games: Iterable[dict]) -> int:
    """Insert one row per game of one sim run. Returns rows written.

    `games` items carry `game_no`, `winner` ('a' | 'b' | 'draw'), `turns`,
    `duration_ms`.
    """
    played_at = _now()
    rows = [(match_id, played_at, deck_a, deck_b, deck_a_id, deck_b_id,
             int(ai_variant_a), int(ai_variant_b), game_type,
             g["game_no"], g["winner"], g["turns"], g["duration_ms"],
             forge_version, log_path) for g in games]
    conn = _connect()
    try:
        conn.executemany(
            "INSERT INTO forge_matches (match_id, played_at, deck_a, deck_b, "
            "deck_a_id, deck_b_id, ai_variant_a, ai_variant_b, game_type, "
            "game_no, winner, turns, duration_ms, forge_version, log_path) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", rows)
        conn.commit()
        return len(rows)
    finally:
        conn.close()


def game_rows(deck_id: Optional[int] = None) -> list[dict]:
    """Every stored game, or those involving one deck, oldest first."""
    sql = ("SELECT match_id, played_at, deck_a, deck_b, deck_a_id, deck_b_id, "
           "ai_variant_a, ai_variant_b, game_type, game_no, winner, turns, "
           "duration_ms, forge_version, log_path FROM forge_matches")
    params: tuple = ()
    if deck_id is not None:
        sql += " WHERE deck_a_id = ? OR deck_b_id = ?"
        params = (deck_id, deck_id)
    conn = _connect()
    try:
        rows = conn.execute(sql + " ORDER BY id", params).fetchall()
        return [dict(r) for r in rows]
    finally:
        conn.close()
