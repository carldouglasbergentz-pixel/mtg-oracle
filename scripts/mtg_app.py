"""Launcher for the MTG Oracle Textual TUI app.

Run from the repo root:

    python scripts/mtg_app.py

Requires textual (see requirements.txt). If textual isn't installed,
the import below raises ModuleNotFoundError with a clear fix.

Window-size note: Textual adopts whatever size the host terminal has.
We do NOT resize the console at launch — on Windows Terminal the
legacy `mode con` command only sets the buffer width, not the window,
which leaves Textual rendering against a wider canvas than is visible
and produces broken borders / clipped text. Resize the terminal
yourself if you want a bigger window.
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

sys.path.insert(0, str(Path(__file__).parent))
import self_heal  # noqa: E402 — scripts/ sibling

try:
    from mtg_oracle.tui import run
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
    # Bring the database to this build's schema before the app opens it, so
    # a build that adds a table doesn't leave deck edits failing until the
    # user thinks to run `sync`. Printed before Textual takes the screen, so
    # it is still in the scrollback after the app exits.
    from mtg_oracle import queries
    reports, _failures = self_heal.run(db_path=queries.DB_PATH)
    for report in reports:
        print(report, file=sys.stderr)
    run()
