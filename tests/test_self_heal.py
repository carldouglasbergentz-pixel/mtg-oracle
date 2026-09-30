"""Schema self-heal on start: `scripts/self_heal.py`, run by sync, the CLI and the app.

A build that adds a table (deck history did) left every deck edit failing
with `no such table` until the user thought to run `sync`. These pin the
fix, and the two ways it could go wrong: healing a database other than the
one the caller is using, and touching the user's real database from a test.

Database-free: every case builds its own schema in a temp directory.

Run: python -m unittest discover tests
"""
import contextlib
import io
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).parent.parent
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "scripts"))
sys.path.insert(0, str(ROOT / "scripts" / "migrations"))

import init_db  # noqa: E402
import migrate_add_deck_history  # noqa: E402
import mtg_cli  # noqa: E402
import self_heal  # noqa: E402
from mtg_oracle import decks as d  # noqa: E402
from mtg_oracle import queries as q  # noqa: E402
from mtg_oracle import scryfall_search as ss  # noqa: E402


def tables(path: Path) -> set[str]:
    conn = sqlite3.connect(path)
    try:
        return {r[0] for r in conn.execute(
            "SELECT name FROM sqlite_master WHERE type = 'table'")}
    finally:
        conn.close()


class HealTestCase(unittest.TestCase):
    """A current schema minus the deck-history tables: the state the user's
    database was in when this build first shipped."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.db = Path(self._tmp.name) / "mtg.db"
        conn = sqlite3.connect(self.db)
        conn.executescript(init_db.SCHEMA)
        conn.executescript("DROP TABLE deck_changes; DROP TABLE deck_revisions;")
        conn.commit()
        conn.close()

    def tearDown(self):
        self._tmp.cleanup()


class TestRun(HealTestCase):

    def test_heals_the_named_database_once(self):
        reports, failures = self_heal.run(db_path=self.db)
        self.assertEqual(failures, [])
        self.assertTrue(any("deck_revisions" in r for r in reports))
        self.assertIn("deck_revisions", tables(self.db))
        self.assertEqual(self_heal.run(db_path=self.db), ([], []))

    def test_restores_each_migrations_own_path(self):
        before = migrate_add_deck_history.DB_PATH
        self_heal.run(db_path=self.db)
        self.assertEqual(migrate_add_deck_history.DB_PATH, before)

    def test_without_a_path_each_migration_keeps_where_it_points(self):
        # A caller that pointed a migration at a copy must never be
        # redirected to the real database behind its back.
        with mock.patch.object(migrate_add_deck_history, "DB_PATH", self.db), \
                mock.patch.object(self_heal, "DB_PATH", self.db):
            self_heal.run(migrations=(migrate_add_deck_history,))
        self.assertIn("deck_revisions", tables(self.db))

    def test_a_missing_database_is_left_alone(self):
        missing = Path(self._tmp.name) / "nope.db"
        self.assertEqual(self_heal.run(db_path=missing), ([], []))
        self.assertFalse(missing.exists())


class TestCliHealsBeforeTheFirstWrite(HealTestCase):

    def test_a_deck_edit_works_on_an_unmigrated_database(self):
        with mock.patch.object(q, "DB_PATH", self.db), \
                mock.patch.object(d, "DB_PATH", self.db), \
                mock.patch.object(ss, "DB_PATH", self.db), \
                contextlib.redirect_stdout(io.StringIO()), \
                contextlib.redirect_stderr(io.StringIO()) as err:
            code = mtg_cli.main(["deck", "new", "Healed"])
        self.assertEqual(code, 0)
        self.assertIn("deck_revisions", tables(self.db))
        # Reported on stderr, so a --json consumer's stdout stays clean.
        self.assertIn("deck_revisions", err.getvalue())


if __name__ == "__main__":
    unittest.main()
