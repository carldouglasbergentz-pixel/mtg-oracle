"""The considering list (a deck's maybeboard), moves between sections, and
removing from one section: all in the history, all undoable, and nothing on
the list counted, exported or held to the deck's rules.

Runs on `db_sandbox`'s copy of data/mtg.db. Each test works on its own deck.

Run: python -m unittest discover tests
"""
import sqlite3
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).parent.parent
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "scripts"))
sys.path.insert(0, str(ROOT / "scripts" / "migrations"))

import db_sandbox  # noqa: E402
import migrate_add_considering  # noqa: E402
from mtg_oracle import decks as d  # noqa: E402

_copy = None


def setUpModule():
    global _copy
    _copy = db_sandbox.enter()


def tearDownModule():
    db_sandbox.leave()


class _DeckTest(unittest.TestCase):
    FORMAT = "commander"

    def setUp(self):
        self.deck = f"__{self.id().rsplit('.', 1)[-1]}__"
        d.create_deck(self.deck, format=self.FORMAT)

    def considering(self):
        return {c["card_name"]: c["quantity"] for c in d.get_deck(self.deck)["considering"]}

    def main(self):
        return {c["card_name"]: c["quantity"] for c in d.get_deck(self.deck)["cards"] if not c["is_sideboard"]}

    def latest(self):
        rev = d.deck_history(self.deck, limit=1)[0]
        return rev["action"], [(c["card"], c["section"], c["before"], c["after"]) for c in rev["changes"]]


class TestConsidering(_DeckTest):

    def test_a_card_goes_on_the_list_without_the_deck_rules(self):
        d.set_commander(self.deck, "Savra, Queen of the Golgari")
        # Outside the BG identity: `add` refuses it, the list takes it.
        with self.assertRaises(d.DeckError):
            d.add_card_to_deck(self.deck, "Counterspell")
        self.assertEqual(d.consider_card(self.deck, "counterspell"), "Counterspell")
        self.assertEqual(self.considering(), {"Counterspell": 1})
        self.assertEqual(self.latest(), ("consider", [("Counterspell", "considering", 0, 1)]))
        self.assertNotIn("Counterspell", self.main(), "the list is not the deck")
        self.assertEqual(d.get_deck(self.deck)["total_main"], 1)

    def test_moving_in_applies_the_rules_and_moving_out_takes_anything(self):
        d.set_commander(self.deck, "Savra, Queen of the Golgari")
        d.consider_card(self.deck, "Counterspell")
        with self.assertRaises(d.DeckError):
            d.move_card(self.deck, "Counterspell", to_section="main", from_section="considering")
        self.assertEqual(self.considering(), {"Counterspell": 1}, "a refused move changes nothing")
        d.consider_card(self.deck, "Sol Ring")
        d.move_card(self.deck, "sol ring", to_section="main", from_section="considering")
        self.assertEqual(self.main().get("Sol Ring"), 1)
        self.assertEqual(self.latest(), ("move", [("Sol Ring", "main", 0, 1), ("Sol Ring", "considering", 1, 0)]))
        d.move_card(self.deck, "Sol Ring", to_section="considering", from_section="main")
        self.assertEqual(self.considering().get("Sol Ring"), 1)
        self.assertNotIn("Sol Ring", self.main())

    def test_undo_reverts_a_move_and_undo_of_undo_redoes_it(self):
        d.add_card_to_deck(self.deck, "Sol Ring")
        d.move_card(self.deck, "Sol Ring", to_section="considering", from_section="main")
        d.undo_last_change(self.deck)
        self.assertEqual((self.main().get("Sol Ring"), self.considering()), (1, {}))
        d.undo_last_change(self.deck)
        self.assertEqual((self.main().get("Sol Ring"), self.considering()), (None, {"Sol Ring": 1}))

    def test_removing_from_one_section_leaves_the_others(self):
        d.add_card_to_deck(self.deck, "Sol Ring")
        d.consider_card(self.deck, "Sol Ring")
        self.assertEqual(d.remove_card_from_deck(self.deck, "sol ring", section="considering"), ("Sol Ring", 1, 0))
        self.assertEqual((self.main().get("Sol Ring"), self.considering()), (1, {}))
        with self.assertRaises(d.DeckError):
            d.remove_card_from_deck(self.deck, "Sol Ring", section="sideboard")
        # Without a section, as before: every section but the list.
        d.consider_card(self.deck, "Sol Ring")
        d.remove_card_from_deck(self.deck, "Sol Ring")
        self.assertEqual((self.main().get("Sol Ring"), self.considering()), (None, {"Sol Ring": 1}))

    def test_the_list_is_not_exported(self):
        d.add_card_to_deck(self.deck, "Sol Ring")
        d.consider_card(self.deck, "Arcane Signet")
        from mtg_oracle import services as svc
        text = svc.export_deck_text(svc.DeckRef(self.deck)).text
        self.assertIn("Sol Ring", text)
        self.assertNotIn("Arcane Signet", text)

    def test_both_deck_views_show_the_list_last(self):
        from mtg_oracle import renderer as r
        d.add_card_to_deck(self.deck, "Sol Ring")
        d.consider_card(self.deck, "Arcane Signet")
        deck = d.get_deck(self.deck)
        full = r.render_deck(deck)
        self.assertIn("Considering (1):", full)
        self.assertGreater(full.index("Arcane Signet"), full.index("Sol Ring"))
        self.assertIn("1 cards", full, "the list is in no total")
        self.assertIn("Considering (1)", r.render_deck_compact(deck, width=40))

    def test_a_deleted_deck_takes_its_list_along(self):
        d.consider_card(self.deck, "Sol Ring")
        d.delete_deck(self.deck)
        conn = sqlite3.connect(str(_copy))
        try:
            self.assertEqual(conn.execute(
                "SELECT COUNT(*) FROM deck_considering dc LEFT JOIN decks k ON k.id = dc.deck_id WHERE k.id IS NULL").fetchone()[0], 0)
        finally:
            conn.close()


class TestMigration(unittest.TestCase):

    def test_it_is_idempotent_and_keeps_every_change(self):
        conn = sqlite3.connect(str(_copy))
        try:
            before = conn.execute("SELECT COUNT(*) FROM deck_changes").fetchone()[0]
        finally:
            conn.close()
        saved = migrate_add_considering.DB_PATH
        migrate_add_considering.DB_PATH = _copy
        try:
            migrate_add_considering.main()
        finally:
            migrate_add_considering.DB_PATH = saved
        conn = sqlite3.connect(str(_copy))
        try:
            self.assertEqual(conn.execute("SELECT COUNT(*) FROM deck_changes").fetchone()[0], before)
            sql = conn.execute("SELECT sql FROM sqlite_master WHERE name = 'deck_changes'").fetchone()[0]
            self.assertIn("'considering'", sql)
        finally:
            conn.close()


if __name__ == "__main__":
    unittest.main()
