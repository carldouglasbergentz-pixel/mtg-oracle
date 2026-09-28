"""A throwaway copy of data/mtg.db for tests that write decks.

`data/mtg.db` holds the user's real decks, so no test may write to it — not
even a create-then-delete, which leaves the file modified and loses the
user's work if the test dies in between.

Use from a test module:

    def setUpModule():
        db_sandbox.enter()

    def tearDownModule():
        db_sandbox.leave()
"""
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

from mtg_oracle import decks, queries, scryfall_search

REAL_DB = Path(__file__).parent.parent / "data" / "mtg.db"

# Every module that opens the database by its own DB_PATH.
_MODULES = (queries, decks, scryfall_search)
_saved: dict = {}
_tmpdir = None


def enter() -> Path:
    """Point every data module at a fresh copy; skip the module if there is
    no database to copy."""
    global _tmpdir
    if not REAL_DB.exists():
        raise unittest.SkipTest("needs data/mtg.db")
    _tmpdir = tempfile.mkdtemp(prefix="mtg-oracle-test-")
    copy = Path(_tmpdir) / "mtg.db"
    shutil.copyfile(REAL_DB, copy)
    _self_heal(copy)
    for mod in _MODULES:
        _saved[mod] = mod.DB_PATH
        mod.DB_PATH = copy
    queries.clear_format_cache()
    return copy


def _self_heal(copy: Path) -> None:
    """Bring the copy to this build's schema, as the app and `sync.py` do.

    The copy is of the user's database, which may predate a migration this
    build adds (deck history did) — tests should see the schema the code
    expects, not whatever the last sync left.
    """
    scripts = str(REAL_DB.parent.parent / "scripts")
    if scripts not in sys.path:
        sys.path.insert(0, scripts)
    import self_heal  # noqa: E402 — scripts/ is only importable from here on

    self_heal.run(db_path=copy)


def leave() -> None:
    global _tmpdir
    for mod, path in _saved.items():
        mod.DB_PATH = path
    _saved.clear()
    queries.clear_format_cache()
    if _tmpdir:
        shutil.rmtree(_tmpdir, ignore_errors=True)
        _tmpdir = None
