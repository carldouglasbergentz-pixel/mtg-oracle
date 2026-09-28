"""Deck change history: every content change is one revision, replace
applies a whole list as one, and undo reverts the latest (undo of undo is
redo).

Runs on `db_sandbox`'s copy of data/mtg.db — nothing here touches the real
decks. Each test works on its own deck.

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
import migrate_add_deck_history as migration  # noqa: E402
import mtg_cli  # noqa: E402
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


def _sql(query, params=()):
    conn = sqlite3.connect(str(_copy))
    try:
        rows = conn.execute(query, params).fetchall()
        conn.commit()
        return rows
    finally:
        conn.close()


class _HistoryTest(unittest.TestCase):
    FORMAT = None

    def setUp(self):
        self.deck = f"__{self.id().rsplit('.', 1)[-1]}__"
        d.create_deck(self.deck, format=self.FORMAT)

    def history(self):
        return d.deck_history(self.deck, limit=500)

    def state(self):
        out = {}
        for c in d.get_deck(self.deck)["cards"]:
            section = ("sideboard" if c["is_sideboard"] else
                       "commander" if c["is_commander"] else "main")
            out[(c["card_name"], section)] = (
                out.get((c["card_name"], section), 0) + c["quantity"])
        return out

    def changes(self, rev):
        return [(c["card"], c["section"], c["before"], c["after"])
                for c in rev["changes"]]


class TestEveryContentChangeIsLogged(_HistoryTest):
    FORMAT = "commander"

    def test_add(self):
        d.add_card_to_deck(self.deck, "Sol Ring")
        [rev] = self.history()
        self.assertEqual(rev["action"], "add")
        self.assertEqual(self.changes(rev), [("Sol Ring", "main", 0, 1)])

    def test_refused_add_records_nothing(self):
        d.add_card_to_deck(self.deck, "Sol Ring")
        with self.assertRaises(d.DeckError):
            d.add_card_to_deck(self.deck, "Sol Ring")
        self.assertEqual(len(self.history()), 1)

    def test_remove(self):
        d.add_card_to_deck(self.deck, "Island", quantity=5)
        d.remove_card_from_deck(self.deck, "Island", quantity=2)
        rev = self.history()[0]
        self.assertEqual(rev["action"], "remove")
        self.assertEqual(self.changes(rev), [("Island", "main", 5, 3)])

    def test_promote_and_demote(self):
        d.add_card_to_deck(self.deck, "Atraxa, Praetors' Voice")
        d.set_commander(self.deck, "Atraxa, Praetors' Voice")
        promote = self.history()[0]
        self.assertEqual(promote["action"], "promote")
        self.assertEqual(self.changes(promote), [
            ("Atraxa, Praetors' Voice", "commander", 0, 1),
            ("Atraxa, Praetors' Voice", "main", 1, 0)])
        d.set_commander(self.deck, "Atraxa, Praetors' Voice", unset=True)
        self.assertEqual(self.history()[0]["action"], "demote")

    def test_unchanged_commander_records_nothing(self):
        d.set_commander(self.deck, "Atraxa, Praetors' Voice")
        self.assertEqual(d.set_commander(self.deck, "Atraxa, Praetors' Voice")[1],
                         "unchanged")
        self.assertEqual(len(self.history()), 1)

    def test_load_into_an_existing_deck_is_one_revision(self):
        result = d.load_parsed_into_deck(
            self.deck, parse_deckstring("1 Sol Ring\n10 Island\n"))
        [rev] = self.history()
        self.assertEqual(rev["action"], "load")
        self.assertEqual(rev["id"], result["revision_id"])
        self.assertEqual(len(rev["changes"]), 2)


class TestImport(unittest.TestCase):
    def test_import_is_one_revision(self):
        result = d.import_deck(
            "__history_import__",
            parse_deckstring("Commander\n1 Atraxa, Praetors' Voice\n"
                             "Deck\n1 Sol Ring\n30 Island\n"))
        [rev] = d.deck_history("__history_import__")
        self.assertEqual(rev["action"], "import")
        self.assertEqual(rev["id"], result["revision_id"])
        self.assertEqual(len(rev["changes"]), 3)

    def test_import_of_nothing_resolvable_records_nothing(self):
        result = d.import_deck("__history_empty__",
                               parse_deckstring("1 Not A Real Card\n"))
        self.assertIsNone(result["revision_id"])
        self.assertEqual(d.deck_history("__history_empty__"), [])


class TestReplace(_HistoryTest):
    LIST = "Deck\n2 Counterspell\n1 Brainstorm\n10 Island\nSideboard\n1 Duress\n"

    def replace(self, text, **kwargs):
        return d.replace_deck_contents(self.deck, parse_deckstring(text),
                                       **kwargs)

    def test_the_deck_becomes_the_list_in_one_revision(self):
        d.add_card_to_deck(self.deck, "Counterspell")
        d.add_card_to_deck(self.deck, "Opt")
        d.add_card_to_deck(self.deck, "Island", quantity=10)
        diff = self.replace(self.LIST)
        self.assertEqual(self.state(), {
            ("Counterspell", "main"): 2, ("Brainstorm", "main"): 1,
            ("Island", "main"): 10, ("Duress", "sideboard"): 1})
        revisions = self.history()
        self.assertEqual([x["action"] for x in revisions[:1]], ["replace"])
        self.assertEqual(len(revisions), 4)  # three adds + one replace
        self.assertEqual(diff["revision_id"], revisions[0]["id"])
        pick = lambda key: sorted((c["card"], c["before"], c["after"])  # noqa: E731
                                  for c in diff[key])
        self.assertEqual(pick("added"), [("Brainstorm", 0, 1), ("Duress", 0, 1)])
        self.assertEqual(pick("removed"), [("Opt", 1, 0)])
        self.assertEqual(pick("changed"), [("Counterspell", 1, 2)])

    def test_identical_list_is_a_no_op(self):
        self.replace(self.LIST)
        count = len(self.history())
        diff = self.replace(self.LIST)
        self.assertIsNone(diff["revision_id"])
        self.assertEqual(diff["added"] + diff["removed"] + diff["changed"], [])
        self.assertEqual(len(self.history()), count)

    def test_unchanged_rows_keep_category_and_added_at(self):
        d.add_card_to_deck(self.deck, "Counterspell", quantity=2,
                           category="Counters")
        _sql("UPDATE deck_cards SET added_at = '2020-01-01T00:00:00Z' "
             "WHERE card_name = 'Counterspell' AND deck_id = "
             "(SELECT id FROM decks WHERE name = ?)", (self.deck,))
        self.replace(self.LIST)
        [(category, added_at)] = _sql(
            "SELECT category, added_at FROM deck_cards WHERE card_name = "
            "'Counterspell' AND deck_id = (SELECT id FROM decks WHERE name = ?)",
            (self.deck,))
        self.assertEqual((category, added_at),
                         ("Counters", "2020-01-01T00:00:00Z"))

    def test_unresolved_name_aborts_and_changes_nothing(self):
        d.add_card_to_deck(self.deck, "Opt")
        with self.assertRaises(d.UnresolvedCardsError) as ctx:
            self.replace(self.LIST + "1 Not A Real Card\n")
        self.assertEqual(ctx.exception.names, ["Not A Real Card"])
        self.assertEqual(self.state(), {("Opt", "main"): 1})
        self.assertEqual(len(self.history()), 1)

    def test_force_replaces_without_the_unresolved_names(self):
        d.add_card_to_deck(self.deck, "Opt")
        diff = self.replace(self.LIST + "1 Not A Real Card\n", force=True)
        self.assertEqual(diff["unresolved"], ["Not A Real Card"])
        self.assertNotIn(("Opt", "main"), self.state())
        self.assertIn(("Duress", "sideboard"), self.state())

    def test_commander_in_the_list_and_format_auto_set(self):
        diff = self.replace("Commander\n1 Atraxa, Praetors' Voice\n"
                            "Deck\n1 Sol Ring\n")
        self.assertEqual(diff["format_set"], "commander")
        self.assertEqual(d.get_deck(self.deck)["format"], "commander")
        self.assertEqual(d.get_deck(self.deck)["commander_ci"],
                         ["B", "G", "U", "W"])

    def test_moving_a_card_between_sections(self):
        d.add_card_to_deck(self.deck, "Duress")
        diff = self.replace("Sideboard\n1 Duress\n")
        self.assertEqual(self.state(), {("Duress", "sideboard"): 1})
        self.assertEqual(len(diff["added"]) + len(diff["removed"]), 2)

    def test_maybeboard_and_bad_quantities_are_reported(self):
        d.add_card_to_deck(self.deck, "Island", quantity=3)
        diff = self.replace("1 Sol Ring\n0 Island\nMaybeboard\n1 Opt\n")
        self.assertEqual(diff["maybeboard"], 1)
        self.assertEqual([n for n, _ in diff["rejected"]], ["Island"])
        # A rejected line keeps the card's current quantity.
        self.assertEqual(self.state()[("Island", "main")], 3)

    def test_service_maps_the_abort_to_a_service_error(self):
        ref = svc.DeckRef(self.deck)
        with self.assertRaisesRegex(svc.ServiceError, "Not A Real Card"):
            svc.replace_deck_from_text(ref, "1 Not A Real Card\n")
        diff = svc.replace_deck_from_text(ref, "1 Sol Ring\n1 Nope Nope\n",
                                          force=True)
        self.assertEqual([c["card"] for c in diff["added"]], ["Sol Ring"])


class TestUndo(_HistoryTest):
    def test_nothing_to_undo(self):
        with self.assertRaisesRegex(d.DeckError, "nothing to undo"):
            d.undo_last_change(self.deck)

    def test_undo_reverts_the_latest_revision_and_is_recorded(self):
        d.add_card_to_deck(self.deck, "Opt")
        d.add_card_to_deck(self.deck, "Island", quantity=4)
        d.replace_deck_contents(self.deck, parse_deckstring(
            "2 Counterspell\nSideboard\n1 Duress\n"))
        diff = d.undo_last_change(self.deck)
        self.assertEqual(self.state(), {("Opt", "main"): 1,
                                        ("Island", "main"): 4})
        self.assertEqual(diff["undone"]["action"], "replace")
        latest = self.history()[0]
        self.assertEqual((latest["action"], latest["id"]),
                         ("undo", diff["revision_id"]))

    def test_undo_of_undo_is_redo(self):
        d.add_card_to_deck(self.deck, "Opt")
        d.replace_deck_contents(self.deck, parse_deckstring("1 Brainstorm\n"))
        replaced = self.state()
        d.undo_last_change(self.deck)
        d.undo_last_change(self.deck)
        self.assertEqual(self.state(), replaced)
        self.assertEqual([x["action"] for x in self.history()[:3]],
                         ["undo", "undo", "replace"])

    def test_undo_of_a_promote_restores_the_main_deck_copy(self):
        d.add_card_to_deck(self.deck, "Atraxa, Praetors' Voice")
        d.set_commander(self.deck, "Atraxa, Praetors' Voice")
        d.undo_last_change(self.deck)
        self.assertEqual(self.state(), {("Atraxa, Praetors' Voice", "main"): 1})

    def test_undo_does_not_restore_an_auto_set_format(self):
        d.replace_deck_contents(self.deck, parse_deckstring(
            "Commander\n1 Atraxa, Praetors' Voice\n"))
        d.undo_last_change(self.deck)
        self.assertEqual(self.state(), {})
        self.assertEqual(d.get_deck(self.deck)["format"], "commander")

    def test_undo_refuses_when_the_deck_drifted_outside_history(self):
        d.add_card_to_deck(self.deck, "Island", quantity=2)
        _sql("UPDATE deck_cards SET quantity = 7 WHERE deck_id = "
             "(SELECT id FROM decks WHERE name = ?)", (self.deck,))
        with self.assertRaisesRegex(d.DeckError, "no longer matches"):
            d.undo_last_change(self.deck)
        self.assertEqual(self.state(), {("Island", "main"): 7})


class TestHistory(_HistoryTest):
    def test_newest_first_and_limit(self):
        for card in ("Opt", "Brainstorm", "Ponder"):
            d.add_card_to_deck(self.deck, card)
        revisions = self.history()
        self.assertEqual([x["note"] for x in revisions],
                         ["Ponder", "Brainstorm", "Opt"])
        self.assertEqual(len(d.deck_history(self.deck, limit=2)), 2)

    def test_delete_deck_cascades_history(self):
        d.add_card_to_deck(self.deck, "Opt")
        d.add_card_to_deck(self.deck, "Ponder")
        [(deck_id,)] = _sql("SELECT id FROM decks WHERE name = ?", (self.deck,))
        d.delete_deck(self.deck)
        self.assertEqual(_sql("SELECT COUNT(*) FROM deck_revisions "
                              "WHERE deck_id = ?", (deck_id,)), [(0,)])
        self.assertEqual(_sql(
            "SELECT COUNT(*) FROM deck_changes WHERE revision_id NOT IN "
            "(SELECT id FROM deck_revisions)"), [(0,)])


class TestRendering(unittest.TestCase):
    DIFF = {
        "deck": "Test", "action": "replace", "revision_id": 7,
        "added": [{"card": "Brazen Borrower", "section": "main",
                   "before": 0, "after": 2},
                  {"card": "Duress", "section": "sideboard",
                   "before": 0, "after": 1}],
        "removed": [{"card": "Opt", "section": "main", "before": 1, "after": 0}],
        "changed": [{"card": "Counterspell", "section": "main",
                     "before": 1, "after": 2}],
        "unresolved": ["Nope"], "rejected": [], "maybeboard": 0,
        "format_set": None,
    }

    def test_diff_lines(self):
        out = r.render_deck_diff(self.DIFF)
        for line in ("+2 Brazen Borrower", "-1 Opt", "Counterspell 1 -> 2",
                     "+1 Duress [sideboard]", "revision #7", "Nope"):
            self.assertIn(line, out)

    def test_no_op_diff(self):
        out = r.render_deck_diff({**self.DIFF, "added": [], "removed": [],
                                  "changed": [], "revision_id": None})
        self.assertIn("no changes", out)

    def test_history(self):
        out = r.render_deck_history([{
            "id": 3, "at": "2026-09-28T12:00:00Z", "action": "promote",
            "note": "Atraxa", "changes": [
                {"card": "Atraxa", "section": "commander", "before": 0,
                 "after": 1}]}])
        self.assertIn("#3", out)
        self.assertIn("+1 Atraxa [commander]", out)
        self.assertEqual(r.render_deck_history([]), "(no recorded changes)")


class TestCLI(unittest.TestCase):
    def run_cli(self, *argv):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = mtg_cli.main(list(argv))
        return code, out.getvalue()

    def test_replace_history_undo(self):
        d.create_deck("__cli_history__")
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "list.txt"
            path.write_text("2 Counterspell\n", encoding="utf-8")
            code, out = self.run_cli("deck", "import", "__cli_history__",
                                     "--replace", "--from-file", str(path))
            self.assertEqual(code, 0, out)
            self.assertIn("+2 Counterspell", out)
            path.write_text("1 Not A Real Card\n", encoding="utf-8")
            code, out = self.run_cli("deck", "import", "__cli_history__",
                                     "--replace", "--from-file", str(path))
            self.assertEqual(code, 1)
            self.assertIn("Not A Real Card", out)
        code, out = self.run_cli("deck", "history", "__cli_history__",
                                 "--limit", "5")
        self.assertIn("replace", out)
        code, out = self.run_cli("deck", "undo", "__cli_history__")
        self.assertEqual(code, 0, out)
        self.assertIn("-2 Counterspell", out)

    def test_replace_needs_an_existing_deck(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "list.txt"
            path.write_text("1 Opt\n", encoding="utf-8")
            code, out = self.run_cli("deck", "import", "__no_such_deck__",
                                     "--replace", "--from-file", str(path))
        self.assertEqual(code, 1)
        self.assertIn("deck not found", out)


class TestMigration(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.db = Path(tmp.name) / "old.db"
        conn = sqlite3.connect(self.db)
        conn.execute("CREATE TABLE decks (id INTEGER PRIMARY KEY)")
        conn.commit()
        conn.close()
        patcher = mock.patch.object(migration, "DB_PATH", self.db)
        patcher.start()
        self.addCleanup(patcher.stop)

    def run_migration(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            migration.main()
        return out.getvalue()

    def test_idempotent(self):
        self.assertIn("Migration applied", self.run_migration())
        self.assertIn("already up to date", self.run_migration())

    def test_matches_init_db(self):
        self.run_migration()
        fresh = sqlite3.connect(":memory:")
        fresh.executescript(init_db.SCHEMA)
        migrated = sqlite3.connect(self.db)
        try:
            for table in ("deck_revisions", "deck_changes"):
                self.assertEqual(
                    fresh.execute(f"PRAGMA table_info({table})").fetchall(),
                    migrated.execute(f"PRAGMA table_info({table})").fetchall(),
                    table)
                self.assertEqual(
                    fresh.execute(f"PRAGMA foreign_key_list({table})").fetchall(),
                    migrated.execute(f"PRAGMA foreign_key_list({table})").fetchall(),
                    table)
            query = ("SELECT name FROM sqlite_master WHERE type = 'index' AND "
                     "tbl_name IN ('deck_revisions', 'deck_changes') ORDER BY 1")
            self.assertEqual(fresh.execute(query).fetchall(),
                             migrated.execute(query).fetchall())
        finally:
            fresh.close()
            migrated.close()

    def test_registered_for_self_heal_after_decks(self):
        import sync
        order = [m.__name__ for m in sync.SELF_HEAL_MIGRATIONS]
        self.assertGreater(order.index("migrate_add_deck_history"),
                           order.index("migrate_add_decks"))


if __name__ == "__main__":
    unittest.main()
