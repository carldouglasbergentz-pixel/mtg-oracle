"""Persistent user preferences for the TUI.

One JSON dict next to the database — already gitignored as part of `data/`
— so adding a setting is a one-key addition. Theme and pane split so far.
"""
from __future__ import annotations

import json
from pathlib import Path

# The repo root, stated once. Every path built from `__file__` has to count
# directories, and this module is two levels deep — moving a file that counted
# for itself is exactly how `scripts/sync.py` would go missing.
REPO_ROOT = Path(__file__).resolve().parent.parent.parent

CONFIG_PATH = REPO_ROOT / "data" / "config.json"


def load_config() -> dict:
    try:
        with open(CONFIG_PATH, encoding="utf-8") as f:
            data = json.load(f)
            return data if isinstance(data, dict) else {}
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        return {}


def save_config(config: dict) -> None:
    try:
        CONFIG_PATH.parent.mkdir(parents=True, exist_ok=True)
        with open(CONFIG_PATH, "w", encoding="utf-8") as f:
            json.dump(config, f, indent=2)
    except OSError:
        # Persistence is best-effort; never crash the app over a config write.
        pass
