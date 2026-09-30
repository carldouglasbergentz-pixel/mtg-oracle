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
sys.path.insert(0, str(ROOT / "scripts" / "migrations"))

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

    def test_a_list_without_a_commander_section_keeps_the_commander(self):
        # An export without a `Commander` header put Elminster in the main
        # deck and left a Duel Commander deck without a commander (2026-09-30).
        d.set_commander(self.deck, "Elminster")
        d.add_card_to_deck(self.deck, "Counterspell")
        diff = self.replace("1 Elminster\n1 Counterspell\n10 Island\n")
        self.assertEqual(self.state(), {("Elminster", "commander"): 1,
                                        ("Counterspell", "main"): 1,
                                        ("Island", "main"): 10})
        self.assertEqual(diff["commanders_kept"], ["Elminster"])
        self.assertIn("so Elminster stayed the commander", r.render_deck_diff(diff))
        diff = self.replace("1 Counterspell\n10 Island\n")
        self.assertEqual(self.state()[("Elminster", "commander")], 1,
                         "a list that doesn't name it keeps it too")

    def test_a_list_with_a_commander_section_replaces_the_commander(self):
        d.set_commander(self.deck, "Elminster")
        diff = self.replace("Commander\n1 Tymna the Weaver\nDeck\n10 Island\n")
        self.assertEqual(self.state(), {("Tymna the Weaver", "commander"): 1,
                                        ("Island", "main"): 10})
        self.assertEqual(diff["commanders_kept"], [])

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

    def test_the_maybeboard_sets_the_considering_list_and_bad_quantities_are_reported(self):
        d.add_card_to_deck(self.deck, "Island", quantity=3)
        diff = self.replace("1 Sol Ring\n0 Island\nMaybeboard\n1 Opt\n")
        self.assertTrue(diff["considering"])
        self.assertIn(("Opt", "considering", 0, 1), [(c["card"], c["section"], c["before"], c["after"]) for c in diff["added"]])
        self.assertEqual([c["card_name"] for c in d.get_deck(self.deck)["considering"]], ["Opt"])
        self.assertEqual([n for n, _ in diff["rejected"]], ["Island"])
        # A rejected line keeps the card's current quantity.
        self.assertEqual(self.state()[("Island", "main")], 3)

    def test_a_list_without_a_maybeboard_leaves_the_considering_list_alone(self):
        d.add_card_to_deck(self.deck, "Island", quantity=3)
        d.consider_card(self.deck, "Opt")
        diff = self.replace("1 Sol Ring\n")
        self.assertFalse(diff["considering"])
        self.assertEqual([c["card_name"] for c in d.get_deck(self.deck)["considering"]], ["Opt"])

    def test_import_puts_the_maybeboard_on_the_considering_list(self):
        result = d.load_parsed_into_deck(self.deck, parse_deckstring("1 Sol Ring\nConsidering\n2 Opt\n"))
        self.assertEqual(result["considering"], 2)
        self.assertEqual([(c["card_name"], c["quantity"]) for c in d.get_deck(self.deck)["considering"]], [("Opt", 2)])

    def test_a_printing_of_its_own_is_a_revision_and_undoes(self):
        d.add_card_to_deck(self.deck, "Sol Ring")
        d.set_printing(self.deck, "sol ring", "main", "C18", "263")
        rev = d.deck_history(self.deck, limit=1)[0]
        self.assertEqual(rev["action"], "printing")
        self.assertEqual((rev["changes"][0]["set_code_after"], rev["changes"][0]["collector_number_after"]), ("c18", "263"))
        d.undo_last_change(self.deck)
        row = next(c for c in d.get_deck(self.deck)["cards"] if c["card_name"] == "Sol Ring")
        self.assertIsNone(row["set_code"])
        with self.assertRaises(d.DeckError):
            d.set_printing(self.deck, "Sol Ring", "considering", "c18")

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
        # deck_changes is widened by the printings migration after this one,
        # as self_heal runs them; init_db has the end state.
        self.run_migration()
        import migrate_add_printings_and_games as printings
        with mock.patch.object(printings, "DB_PATH", self.db), \
                contextlib.redirect_stdout(io.StringIO()):
            printings.main()
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


class TestPrintings(_HistoryTest):
    """A row's printing: carried in, changed as content, exported back."""

    def printings(self):
        return {(c["card_name"], c["is_sideboard"]):
                (c["set_code"], c["collector_number"])
                for c in d.get_deck(self.deck)["cards"]}

    def replace(self, text, **kwargs):
        return d.replace_deck_contents(self.deck, parse_deckstring(text), **kwargs)

    def test_load_carries_the_printing(self):
        d.load_parsed_into_deck(self.deck, parse_deckstring(
            "1 Sol Ring (C18) 222\n2 Island\nSideboard\n1 Duress (PLST) M20-96\n"))
        self.assertEqual(self.printings(), {
            ("Sol Ring", 0): ("c18", "222"), ("Island", 0): (None, None),
            ("Duress", 1): ("plst", "M20-96")})

    def test_import_carries_the_printing(self):
        name = f"{self.deck}-import"
        d.import_deck(name, parse_deckstring("1 Force Spike (7ED) 76*\n"))
        [row] = d.get_deck(name)["cards"]
        self.assertEqual((row["set_code"], row["collector_number"]), ("7ed", "76★"))

    def test_add_sets_a_printing_and_add_without_one_keeps_it(self):
        d.add_card_to_deck(self.deck, "Island", quantity=2,
                           set_code="M21", collector_number="264")
        d.add_card_to_deck(self.deck, "Island")
        self.assertEqual(self.printings()[("Island", 0)], ("m21", "264"))
        d.add_card_to_deck(self.deck, "Island", set_code="unf", collector_number="240")
        self.assertEqual(self.printings()[("Island", 0)], ("unf", "240"))
        self.assertEqual(self.state(), {("Island", "main"): 4})

    def test_a_printing_only_change_is_a_revision_and_undo_restores_it(self):
        self.replace("1 Sol Ring (C18) 222\n")
        diff = self.replace("1 Sol Ring (CMR) 472\n")
        [change] = diff["changed"]
        self.assertEqual((change["before"], change["after"]), (1, 1))
        self.assertEqual((change["set_code_before"], change["collector_number_before"],
                          change["set_code_after"], change["collector_number_after"]),
                         ("c18", "222", "cmr", "472"))
        latest = self.history()[0]
        self.assertEqual((latest["id"], latest["action"]),
                         (diff["revision_id"], "replace"))
        self.assertIn("Sol Ring (C18) 222 -> (CMR) 472", r.render_deck_diff(diff))
        d.undo_last_change(self.deck)
        self.assertEqual(self.printings()[("Sol Ring", 0)], ("c18", "222"))
        d.undo_last_change(self.deck)
        self.assertEqual(self.printings()[("Sol Ring", 0)], ("cmr", "472"))

    def test_same_list_with_printings_is_a_no_op(self):
        text = "1 Sol Ring (C18) 222\n2 Island (M21) 264\n"
        self.replace(text)
        count = len(self.history())
        self.assertIsNone(self.replace(text)["revision_id"])
        self.assertEqual(len(self.history()), count)

    def test_a_line_without_a_printing_keeps_the_rows(self):
        self.replace("1 Sol Ring (C18) 222\n")
        diff = self.replace("1 Sol Ring\n")
        self.assertIsNone(diff["revision_id"])
        self.assertEqual(self.printings()[("Sol Ring", 0)], ("c18", "222"))

    def test_quantity_and_printing_together_are_one_change(self):
        self.replace("1 Island (M21) 263\n")
        diff = self.replace("3 Island (M21) 265\n")
        [change] = diff["changed"]
        self.assertEqual((change["before"], change["after"],
                          change["collector_number_after"]), (1, 3, "265"))
        self.assertIn("Island 1 -> 3, (M21) 263 -> (M21) 265",
                      r.render_deck_diff(diff))

    def test_history_reports_printings(self):
        d.add_card_to_deck(self.deck, "Sol Ring", set_code="c18",
                           collector_number="222")
        [rev] = self.history()
        self.assertEqual(rev["changes"][0]["set_code_after"], "c18")
        self.assertIn("+1 Sol Ring (C18) 222", r.render_deck_history([rev]))

    def test_export_writes_the_printing_and_import_reads_it_back(self):
        self.replace("Commander\n1 Atraxa, Praetors' Voice (C16) 28\n"
                     "Deck\n1 Sol Ring (C18) 222\n1 Swords to Plowshares (STA)\n"
                     "2 Island\nSideboard\n1 Force Spike (7ED) 76★\n")
        text = svc.export_deck_text(svc.DeckRef(self.deck)).text
        for line in ("1 Atraxa, Praetors' Voice (C16) 28", "1 Sol Ring (C18) 222",
                     "1 Swords to Plowshares (STA)", "2 Island\n",
                     "1 Force Spike (7ED) 76★"):
            self.assertIn(line, text)
        name = f"{self.deck}-round-trip"
        d.import_deck(name, parse_deckstring(text))
        self.assertEqual(
            sorted((c["card_name"], c["is_commander"], c["is_sideboard"],
                    c["quantity"], c["set_code"], c["collector_number"])
                   for c in d.get_deck(name)["cards"]),
            sorted((c["card_name"], c["is_commander"], c["is_sideboard"],
                    c["quantity"], c["set_code"], c["collector_number"])
                   for c in d.get_deck(self.deck)["cards"]))


class TestGamesTable(unittest.TestCase):
    """The app's `games` rows outlive their decks, as forge_matches do."""

    def test_deleting_a_deck_keeps_its_games(self):
        d.create_deck("__games_me__")
        d.create_deck("__games_them__")
        [(me,)] = _sql("SELECT id FROM decks WHERE name = '__games_me__'")
        [(them,)] = _sql("SELECT id FROM decks WHERE name = '__games_them__'")
        _sql("INSERT INTO games (played_at, mode, deck_id, deck_name, "
             "opponent_deck_id, opponent_name, winner) VALUES "
             "('2026-09-29T12:00:00Z', 'human_vs_ai', ?, '__games_me__', ?, "
             "'__games_them__', 'me')", (me, them))
        d.delete_deck("__games_me__")
        self.assertEqual(
            _sql("SELECT deck_id, deck_name, opponent_deck_id FROM games "
                 "WHERE deck_name = '__games_me__'"),
            [(None, "__games_me__", them)])

    def test_mode_and_winner_are_checked(self):
        with self.assertRaises(sqlite3.IntegrityError):
            _sql("INSERT INTO games (played_at, mode, deck_name, opponent_name) "
                 "VALUES ('x', 'hotseat', 'a', 'b')")
        with self.assertRaises(sqlite3.IntegrityError):
            _sql("INSERT INTO games (played_at, mode, deck_name, opponent_name, "
                 "winner) VALUES ('x', 'ai_vs_ai', 'a', 'b', 'a')")


class TestPrintingsMigration(unittest.TestCase):
    """migrate_add_printings_and_games on a database from before it."""

    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.db = Path(tmp.name) / "old.db"
        conn = sqlite3.connect(self.db)
        conn.executescript(init_db.SCHEMA)
        conn.executescript("""
            DROP TABLE games;
            ALTER TABLE deck_cards DROP COLUMN set_code;
            ALTER TABLE deck_cards DROP COLUMN collector_number;
            ALTER TABLE deck_changes DROP COLUMN set_code_before;
            ALTER TABLE deck_changes DROP COLUMN collector_number_before;
            ALTER TABLE deck_changes DROP COLUMN set_code_after;
            ALTER TABLE deck_changes DROP COLUMN collector_number_after;
        """)
        conn.commit()
        conn.close()
        import migrate_add_printings_and_games as printings
        self.migration = printings
        patcher = mock.patch.object(printings, "DB_PATH", self.db)
        patcher.start()
        self.addCleanup(patcher.stop)

    def run_migration(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.migration.main()
        return out.getvalue()

    def test_idempotent(self):
        self.assertIn("Migration applied", self.run_migration())
        self.assertIn("already up to date", self.run_migration())

    def test_matches_init_db(self):
        self.run_migration()
        # games is widened by the match migration after this one, as
        # self_heal runs them; init_db has the end state.
        import migrate_add_game_matches as matches
        with mock.patch.object(matches, "DB_PATH", self.db), \
                contextlib.redirect_stdout(io.StringIO()):
            matches.main()
        fresh = sqlite3.connect(":memory:")
        fresh.executescript(init_db.SCHEMA)
        migrated = sqlite3.connect(self.db)
        try:
            for table in ("deck_cards", "deck_changes", "games"):
                for pragma in ("table_info", "foreign_key_list"):
                    self.assertEqual(
                        fresh.execute(f"PRAGMA {pragma}({table})").fetchall(),
                        migrated.execute(f"PRAGMA {pragma}({table})").fetchall(),
                        f"{pragma}({table})")
            query = ("SELECT name, sql FROM sqlite_master WHERE type = 'index' "
                     "AND tbl_name = 'games' ORDER BY 1")
            self.assertEqual([n for n, _ in fresh.execute(query)],
                             [n for n, _ in migrated.execute(query)])
        finally:
            fresh.close()
            migrated.close()

    def test_registered_for_self_heal_after_history_and_forge(self):
        import self_heal
        order = [m.__name__ for m in self_heal.MIGRATIONS]
        at = order.index("migrate_add_printings_and_games")
        self.assertGreater(at, order.index("migrate_add_deck_history"))
        self.assertGreater(at, order.index("migrate_add_forge"))


class TestGameMatchesMigration(unittest.TestCase):
    """migrate_add_game_matches on a `games` table from before matches."""

    OLD_GAMES = """
        CREATE TABLE games (
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
            log_path TEXT
        );
        CREATE INDEX idx_games_deck ON games(deck_id);
        CREATE INDEX idx_games_opponent_deck ON games(opponent_deck_id);
        INSERT INTO games (played_at, mode, deck_name, opponent_name, winner)
        VALUES ('2026-09-29T10:00:00Z', 'human_vs_ai', 'Mine', 'Theirs', 'me');
    """

    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.db = Path(tmp.name) / "old.db"
        conn = sqlite3.connect(self.db)
        conn.executescript(init_db.SCHEMA)
        conn.executescript("DROP TABLE games;" + self.OLD_GAMES)
        conn.commit()
        conn.close()
        import migrate_add_game_matches as matches
        self.migration = matches
        patcher = mock.patch.object(matches, "DB_PATH", self.db)
        patcher.start()
        self.addCleanup(patcher.stop)

    def run_migration(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.migration.main()
        return out.getvalue()

    def query(self, sql, params=()):
        conn = sqlite3.connect(self.db)
        try:
            rows = conn.execute(sql, params).fetchall()
            conn.commit()
            return rows
        finally:
            conn.close()

    def test_idempotent(self):
        self.assertIn("Migration applied", self.run_migration())
        self.assertIn("already up to date", self.run_migration())

    def test_exact_column_names(self):
        self.run_migration()
        columns = [r[1] for r in self.query("PRAGMA table_info(games)")]
        self.assertEqual(columns[-4:],
                         ["match_id", "game_no", "match_format", "conceded"])

    def test_existing_rows_survive_as_single_games(self):
        self.run_migration()
        self.assertEqual(
            self.query("SELECT deck_name, winner, match_id, game_no, "
                       "match_format, conceded FROM games"),
            [("Mine", "me", None, None, None, 0)])

    def test_match_format_is_checked(self):
        self.run_migration()
        insert = ("INSERT INTO games (played_at, mode, deck_name, opponent_name, "
                  "match_id, game_no, match_format, conceded) "
                  "VALUES ('x', 'ai_vs_ai', 'a', 'b', 'm1', ?, ?, ?)")
        for game_no, fmt in ((1, "bo1"), (2, "bo3"), (3, "bo5")):
            self.query(insert, (game_no, fmt, 1))
        with self.assertRaises(sqlite3.IntegrityError):
            self.query(insert, (1, "bo7", 0))
        with self.assertRaises(sqlite3.IntegrityError):
            self.query(insert.replace(", ?)", ", NULL)"), (1, "bo3"))

    def test_matches_init_db(self):
        self.run_migration()
        fresh = sqlite3.connect(":memory:")
        fresh.executescript(init_db.SCHEMA)
        migrated = sqlite3.connect(self.db)
        try:
            for pragma in ("table_info", "foreign_key_list", "index_list"):
                self.assertEqual(
                    sorted(fresh.execute(f"PRAGMA {pragma}(games)").fetchall()),
                    sorted(migrated.execute(f"PRAGMA {pragma}(games)").fetchall()),
                    pragma)
        finally:
            fresh.close()
            migrated.close()

    def test_registered_for_self_heal_after_printings(self):
        import self_heal
        order = [m.__name__ for m in self_heal.MIGRATIONS]
        self.assertGreater(order.index("migrate_add_game_matches"),
                           order.index("migrate_add_printings_and_games"))

    def test_no_games_table_is_left_alone(self):
        self.query("DROP TABLE games")
        self.assertIn("already up to date", self.run_migration())


if __name__ == "__main__":
    unittest.main()
