"""Deck-name uniqueness, the UNSORTED address, and all-or-nothing imports.

Every test writes decks, so the module runs against `db_sandbox`'s copy of
data/mtg.db. Folder and deck names are unique per test because the copy is
shared by the whole module.

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

import db_sandbox  # noqa: E402
import init_db  # noqa: E402
import migrate_unique_deck_names as migration  # noqa: E402
from mtg_oracle import decks as d  # noqa: E402
from mtg_oracle import renderer as r  # noqa: E402
from mtg_oracle import services as svc  # noqa: E402
from mtg_oracle.deck_parser import parse_deckstring  # noqa: E402

_copy = None


def setUpModule():
    global _copy
    _copy = db_sandbox.enter()


def tearDownModule():
    db_sandbox.leave()


def _deck_ids(name):
    conn = sqlite3.connect(str(_copy))
    try:
        return [r[0] for r in conn.execute(
            "SELECT id FROM decks WHERE name = ? COLLATE NOCASE", (name,))]
    finally:
        conn.close()


class TestUnsortedIsAddressable(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        d.create_folder("U-Folder")
        d.create_deck("Same", folder="U-Folder", format="modern")
        d.create_deck("Same", format="legacy")

    def test_name_alone_is_ambiguous_and_says_where(self):
        with self.assertRaises(d.AmbiguousDeckError) as ctx:
            d.get_deck("Same")
        self.assertEqual(ctx.exception.folders, ["U-Folder", d.UNSORTED])
        self.assertIsInstance(ctx.exception, d.DeckError)

    def test_unsorted_reaches_only_the_deck_outside_folders(self):
        deck = d.get_deck("same", folder=d.UNSORTED)
        self.assertIsNone(deck["folder"])
        self.assertEqual(deck["format"], "legacy")

    def test_folder_reaches_only_that_folder(self):
        self.assertEqual(d.get_deck("Same", folder="u-folder")["format"],
                         "modern")

    def test_missing_is_none_not_an_error(self):
        self.assertIsNone(d.get_deck("No Such Deck"))
        self.assertIsNone(d.get_deck("Same", folder="No Such Folder"))

    def test_list_decks_unsorted_filters(self):
        names = {(x["name"], x["folder"]) for x in d.list_decks(d.UNSORTED)}
        self.assertIn(("Same", None), names)
        self.assertNotIn(("Same", "U-Folder"), names)

    def test_mutations_accept_unsorted(self):
        d.add_card_to_deck("Same", "Island", folder=d.UNSORTED)
        self.assertEqual(
            d.get_deck("Same", folder=d.UNSORTED)["total_main"], 1)
        self.assertEqual(
            d.get_deck("Same", folder="U-Folder")["total_main"], 0)

    def test_services_turn_ambiguity_into_a_service_error(self):
        with self.assertRaisesRegex(svc.ServiceError, "ambiguous"):
            svc.export_deck_text(svc.DeckRef("Same"))
        with self.assertRaisesRegex(svc.ServiceError, "ambiguous"):
            svc.deck_cards_for_analysis(svc.DeckRef("Same"))

    def test_unsorted_is_reserved_as_a_folder_name(self):
        with self.assertRaisesRegex(d.DeckError, "reserved"):
            d.create_folder("(Unsorted)")

    def test_unsorted_has_no_folder_format(self):
        with self.assertRaisesRegex(d.DeckError, "no folder default"):
            d.set_folder_format(d.UNSORTED, "commander")


class TestNameClashes(unittest.TestCase):
    def test_duplicate_unsorted_name_is_refused(self):
        d.create_deck("Clash A")
        with self.assertRaisesRegex(d.DeckError, r"already exists in \(unsorted\)"):
            d.create_deck("clash a")
        self.assertEqual(len(_deck_ids("Clash A")), 1)

    def test_case_variant_in_one_folder_is_refused(self):
        d.create_folder("C-Folder")
        d.create_deck("Case", folder="C-Folder")
        with self.assertRaisesRegex(d.DeckError, "folder 'C-Folder'"):
            d.create_deck("CASE", folder="C-Folder")

    def test_same_name_in_different_folders_is_fine(self):
        d.create_folder("D-Folder")
        d.create_deck("Shared", folder="D-Folder")
        d.create_deck("Shared")

    def test_rename_onto_another_deck_is_refused(self):
        d.create_deck("Rename One")
        d.create_deck("Rename Two")
        with self.assertRaisesRegex(d.DeckError, "already exists"):
            d.rename_deck("Rename One", "rename two", folder=d.UNSORTED)

    def test_rename_to_a_case_variant_of_itself_is_allowed(self):
        d.create_deck("case change")
        d.rename_deck("case change", "Case Change")
        self.assertEqual(d.get_deck("Case Change")["name"], "Case Change")

    def test_move_into_a_clash_is_refused(self):
        d.create_folder("M-Folder")
        d.create_deck("Mover", folder="M-Folder")
        d.create_deck("Mover")
        with self.assertRaisesRegex(d.DeckError, "already exists"):
            d.move_deck("Mover", None, folder="M-Folder")
        with self.assertRaisesRegex(d.DeckError, "already exists"):
            d.move_deck("Mover", "M-Folder", folder=d.UNSORTED)

    def test_move_to_unsorted_by_name(self):
        d.create_folder("N-Folder")
        d.create_deck("Leaver", folder="N-Folder")
        d.move_deck("Leaver", d.UNSORTED, folder="N-Folder")
        self.assertIsNone(d.get_deck("Leaver")["folder"])

    def test_delete_folder_refuses_to_create_a_clash(self):
        d.create_folder("X-Folder")
        d.create_deck("Orphan", folder="X-Folder")
        d.create_deck("orphan")
        with self.assertRaisesRegex(d.DeckError, "'Orphan'"):
            d.delete_folder("X-Folder", force=True)
        self.assertEqual(d.get_deck("Orphan", folder="X-Folder")["folder"],
                         "X-Folder")


class TestMigration(unittest.TestCase):
    """migrate_unique_deck_names.py on a scratch DB with the old schema."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db = Path(self.tmp.name) / "old.db"
        conn = sqlite3.connect(self.db)
        conn.executescript("""
            CREATE TABLE deck_folders (id INTEGER PRIMARY KEY, name TEXT);
            CREATE TABLE decks (id INTEGER PRIMARY KEY, folder_id INTEGER,
                                name TEXT NOT NULL,
                                UNIQUE (folder_id, name));
            INSERT INTO deck_folders VALUES (1, 'F');
            INSERT INTO decks (folder_id, name) VALUES
                (NULL, 'Dup'), (NULL, 'dup'), (1, 'Ok'), (NULL, 'Ok');
        """)
        conn.commit()
        conn.close()
        patcher = mock.patch.object(migration, "DB_PATH", self.db)
        patcher.start()
        self.addCleanup(patcher.stop)
        self.addCleanup(self.tmp.cleanup)

    def _run(self):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            migration.main()
        return out.getvalue(), err.getvalue()

    def _has_index(self):
        conn = sqlite3.connect(self.db)
        try:
            return conn.execute(
                "SELECT 1 FROM sqlite_master WHERE name = ?",
                (migration.INDEX_NAME,)).fetchone() is not None
        finally:
            conn.close()

    def test_duplicates_fail_loudly_and_change_nothing(self):
        with self.assertRaises(SystemExit) as ctx:
            self._run()
        self.assertEqual(ctx.exception.code, 1)
        self.assertFalse(self._has_index())

    def test_duplicates_are_listed(self):
        err = io.StringIO()
        with contextlib.redirect_stderr(err), self.assertRaises(SystemExit):
            migration.main()
        self.assertIn("(unsorted)/Dup", err.getvalue())
        self.assertNotIn("Ok", err.getvalue())

    def test_applies_once_then_is_idempotent_and_enforces(self):
        conn = sqlite3.connect(self.db)
        conn.execute("DELETE FROM decks WHERE name = 'dup'")
        conn.commit()
        conn.close()
        out, _ = self._run()
        self.assertIn("Migration applied", out)
        out, _ = self._run()
        self.assertIn("already up to date", out)
        conn = sqlite3.connect(self.db)
        try:
            with self.assertRaises(sqlite3.IntegrityError):
                conn.execute("INSERT INTO decks (folder_id, name) "
                             "VALUES (NULL, 'DUP')")
        finally:
            conn.close()

    def test_runs_clean_on_the_real_database_copy(self):
        # The live database may already carry the index (sync self-heals it),
        # so remove it from the copy to exercise the migration itself.
        conn = sqlite3.connect(_copy)
        try:
            conn.execute("DROP INDEX IF EXISTS idx_decks_folder_name_nocase_unique")
            conn.commit()
        finally:
            conn.close()
        with mock.patch.object(migration, "DB_PATH", _copy):
            out, _ = self._run()
        self.assertIn("Migration applied", out)

    def test_init_db_schema_has_the_index(self):
        conn = sqlite3.connect(":memory:")
        try:
            conn.executescript(init_db.SCHEMA)
            conn.execute("INSERT INTO decks (folder_id, name, created_at, "
                         "updated_at) VALUES (NULL, 'A', '', '')")
            with self.assertRaises(sqlite3.IntegrityError):
                conn.execute("INSERT INTO decks (folder_id, name, created_at, "
                             "updated_at) VALUES (NULL, 'a', '', '')")
        finally:
            conn.close()


class TestAtomicImport(unittest.TestCase):
    TEXT = "1 Island\n1 Counterspell\n1 Brainstorm\n"

    def test_structural_failure_leaves_no_deck(self):
        real = d._add_card
        calls = []

        def fail_on_third(*args, **kwargs):
            calls.append(1)
            if len(calls) == 3:
                raise sqlite3.OperationalError("disk I/O error")
            return real(*args, **kwargs)

        with mock.patch.object(d, "_add_card", fail_on_third):
            with self.assertRaises(sqlite3.OperationalError):
                d.import_deck("Atomic New", parse_deckstring(self.TEXT))
        self.assertEqual(_deck_ids("Atomic New"), [])

    def test_failure_loading_into_an_existing_deck_rolls_back_every_row(self):
        d.create_deck("Atomic Existing")
        d.add_card_to_deck("Atomic Existing", "Plains")

        def boom(*_args, **_kwargs):
            raise d.DeckError("structural")

        real = d._add_card
        calls = []

        def fail_on_second(*args, **kwargs):
            calls.append(1)
            if len(calls) == 2:
                boom()
            return real(*args, **kwargs)

        with mock.patch.object(d, "_add_card", fail_on_second):
            with self.assertRaises(d.DeckError):
                d.load_parsed_into_deck("Atomic Existing",
                                        parse_deckstring(self.TEXT))
        cards = d.get_deck("Atomic Existing")["cards"]
        self.assertEqual([c["card_name"] for c in cards], ["Plains"])

    def test_name_clash_creates_nothing(self):
        d.create_deck("Atomic Taken")
        with self.assertRaisesRegex(d.DeckError, "already exists"):
            d.import_deck("atomic taken", parse_deckstring(self.TEXT))
        self.assertEqual(len(_deck_ids("Atomic Taken")), 1)

    def test_unsorted_import_next_to_a_folder_deck_of_that_name(self):
        """Used to create the deck, then fail the load as ambiguous."""
        d.create_folder("I-Folder")
        d.create_deck("Imported", folder="I-Folder")
        result = d.import_deck("Imported", parse_deckstring(self.TEXT))
        self.assertEqual(result["added"], 3)
        self.assertEqual(
            d.get_deck("Imported", folder=d.UNSORTED)["total_main"], 3)

    def test_bad_quantities_are_rows_not_aborts(self):
        result = d.import_deck(
            "Atomic Qty",
            parse_deckstring("1 Island\n0 Mountain\n600 Forest\n600 Forest\n"))
        self.assertEqual(result["added"], 2)
        self.assertEqual([n for n, _ in result["rejected"]],
                         ["Mountain", "Forest"])
        self.assertEqual(d.get_deck("Atomic Qty")["total_main"], 601)

    def test_unresolved_names_are_reported_not_fatal(self):
        result = d.import_deck(
            "Atomic Unresolved", parse_deckstring("1 Island\n1 Not A Card\n"))
        self.assertEqual(result["unresolved"], ["Not A Card"])
        self.assertEqual(result["added"], 1)

    def test_unforced_rejections_are_collected_inside_the_transaction(self):
        d.create_deck("Atomic Unforced", format="commander")
        result = d.load_parsed_into_deck(
            "Atomic Unforced", parse_deckstring("1 Sol Ring\n1 Sol Ring\n"),
            force=False)
        self.assertEqual(result["added"], 1)
        self.assertEqual(len(result["rejected"]), 1)
        self.assertEqual(d.get_deck("Atomic Unforced")["total_main"], 1)


class TestImportAutoFormat(unittest.TestCase):
    LIST = "Commander\n1 Atraxa, Praetors' Voice\nDeck\n1 Sol Ring\n"

    def test_commander_section_sets_a_missing_format(self):
        result = d.import_deck("Auto Cmd", parse_deckstring(self.LIST))
        self.assertEqual(result["format_set"], "commander")
        self.assertEqual(d.get_deck("Auto Cmd")["format"], "commander")
        with self.assertRaisesRegex(d.DeckError, "singleton"):
            d.add_card_to_deck("Auto Cmd", "Sol Ring")
        self.assertIn("auto-set to 'commander'",
                      r.render_import_result("Auto Cmd", result))

    def test_an_existing_format_is_kept(self):
        result = d.import_deck("Auto Duel", parse_deckstring(self.LIST),
                               format="duel")
        self.assertIsNone(result["format_set"])
        self.assertEqual(d.get_deck("Auto Duel")["format"], "duel")

    def test_folder_default_counts_as_a_format(self):
        d.create_folder("Auto-Folder")
        d.set_folder_format("Auto-Folder", "duel")
        result = d.import_deck("Auto Inherit", parse_deckstring(self.LIST),
                               folder="Auto-Folder")
        self.assertIsNone(result["format_set"])

    def test_no_commander_no_format(self):
        result = d.import_deck("Auto None", parse_deckstring("1 Sol Ring\n"))
        self.assertIsNone(result["format_set"])
        self.assertIsNone(d.get_deck("Auto None")["format"])
        self.assertNotIn("auto-set",
                         r.render_import_result("Auto None", result))

    def test_loading_into_an_existing_unformatted_deck(self):
        d.create_deck("Auto Existing")
        result = d.load_parsed_into_deck("Auto Existing",
                                         parse_deckstring(self.LIST))
        self.assertEqual(result["format_set"], "commander")


if __name__ == "__main__":
    unittest.main()
