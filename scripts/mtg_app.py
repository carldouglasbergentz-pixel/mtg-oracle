"""Launcher for the MTG Oracle Textual TUI app.

Run from the repo root:

    python scripts/mtg_app.py

Requires textual (see requirements.txt). If textual isn't installed,
the import below raises ModuleNotFoundError with a clear fix.

On Windows the launcher resizes the console window to ~1.20x the
current size before starting Textual. This is a best-effort nudge —
it's a no-op outside Windows or when the terminal doesn't support
the legacy `mode con` command.
"""
from __future__ import annotations

import os
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))


def _enlarge_console(scale: float = 1.20) -> None:
    """On Windows, scale the console size by `scale`. Silent on other OSes
    or if the resize fails."""
    if not sys.platform.startswith("win"):
        return
    try:
        size = shutil.get_terminal_size(fallback=(100, 30))
        cols = max(80, int(size.columns * scale))
        lines = max(24, int(size.lines * scale))
        # `mode con` is the legacy console resize. It silently no-ops on
        # some Windows Terminal profiles; harmless either way.
        os.system(f"mode con cols={cols} lines={lines}")
    except Exception:
        pass


_enlarge_console()


try:
    from mtg_oracle.app import run
except ModuleNotFoundError as e:
    if "textual" in str(e):
        print(
            "ERR textual is not installed. Install it with:\n"
            "  pip install -r requirements.txt",
            file=sys.stderr,
        )
        raise SystemExit(2)
    raise


if __name__ == "__main__":
    run()
