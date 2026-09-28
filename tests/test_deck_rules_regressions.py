"""Regression tests for deck-rule bugs found in review.

Every test here writes decks, so the module runs against a throwaway copy of
data/mtg.db and never touches the user's real decks. The copy is made once
per run; each test uses its own deck name.

Run: python -m unittest discover tests
"""
import sqlite3
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

import db_sandbox  # noqa: E402
from mtg_oracle import decks as d  # noqa: E402
from mtg_oracle import queries as q  # noqa: E402


def setUpModule():
    db_sandbox.enter()


def tearDownModule():
    db_sandbox.leave()


class _DeckTest(unittest.TestCase):
    FORMAT = None

    def setUp(self):
        self.deck = f"__{self.id().rsplit('.', 1)[-1]}__"
        d.create_deck(self.deck, format=self.FORMAT)

    def rows(self):
        return sorted(
            (c["card_name"], c["quantity"], c["is_commander"], c["is_sideboard"])
            for c in d.get_deck(self.deck)["cards"]
        )


class TestSetCommanderChecksTheFormatItWillSet(_DeckTest):
    """#3: an unset format becomes 'commander', so check against that."""

    def test_banned_commander_is_refused_on_an_unformatted_deck(self):
        with self.assertRaisesRegex(d.DeckError, "banned in"):
            d.set_commander(self.deck, "Leovold, Emissary of Trest")
        deck = d.get_deck(self.deck)
        self.assertEqual(deck["cards"], [])
        self.assertIsNone(deck["format"])

    def test_legal_commander_still_auto_sets_the_format(self):
        result = d.set_commander(self.deck, "Atraxa, Praetors' Voice")
        self.assertEqual(result[1:], ("added", "commander"))

    def test_force_still_bypasses(self):
        d.set_commander(self.deck, "Leovold, Emissary of Trest", force=True)
        self.assertEqual(self.rows(),
                         [("Leovold, Emissary of Trest", 1, 1, 0)])


class TestUpToNCopies(_DeckTest):
    """#4: 'a deck can have up to N cards named' caps singleton at N."""

    FORMAT = "commander"

    def test_nazgul_allows_nine(self):
        d.add_card_to_deck(self.deck, "Nazgûl", quantity=8)
        d.add_card_to_deck(self.deck, "Nazgûl")
        with self.assertRaisesRegex(d.DeckError, "limit is 9"):
            d.add_card_to_deck(self.deck, "Nazgûl")

    def test_seven_dwarves_allows_seven(self):
        d.add_card_to_deck(self.deck, "Seven Dwarves", quantity=7)
        with self.assertRaisesRegex(d.DeckError, "limit is 7"):
            d.add_card_to_deck(self.deck, "Seven Dwarves")

    def test_any_number_and_basics_stay_unlimited(self):
        d.add_card_to_deck(self.deck, "Relentless Rats", quantity=30)
        d.add_card_to_deck(self.deck, "Swamp", quantity=30)

    def test_ordinary_cards_stay_singleton(self):
        d.add_card_to_deck(self.deck, "Sol Ring")
        with self.assertRaisesRegex(d.DeckError, "limit is 1"):
            d.add_card_to_deck(self.deck, "Sol Ring")


class TestRestrictedCountsMainAndSideboard(_DeckTest):
    """#5: Vintage's one copy spans main deck and sideboard combined."""

    FORMAT = "vintage"

    def test_sideboard_copy_after_main_copy_is_refused(self):
        d.add_card_to_deck(self.deck, "Ancestral Recall")
        with self.assertRaisesRegex(d.DeckError, "restricted"):
            d.add_card_to_deck(self.deck, "Ancestral Recall", is_sideboard=True)

    def test_main_copy_after_sideboard_copy_is_refused(self):
        d.add_card_to_deck(self.deck, "Ancestral Recall", is_sideboard=True)
        with self.assertRaisesRegex(d.DeckError, "restricted"):
            d.add_card_to_deck(self.deck, "Ancestral Recall")

    def test_unrestricted_cards_are_unaffected(self):
        d.add_card_to_deck(self.deck, "Counterspell", quantity=4)
        d.add_card_to_deck(self.deck, "Counterspell", quantity=2,
                           is_sideboard=True)


class TestSingletonStaysPerSection(_DeckTest):
    """#5's fix must not spill into the singleton rule."""

    FORMAT = "canadianhighlander"

    def test_one_main_and_one_sideboard_copy_is_allowed(self):
        d.add_card_to_deck(self.deck, "Counterspell")
        d.add_card_to_deck(self.deck, "Counterspell", is_sideboard=True)


class TestAddAsCommander(_DeckTest):
    """#6: only the CI check is skipped for a commander."""

    FORMAT = "commander"
    ATRAXA = "Atraxa, Praetors' Voice"

    def test_second_commander_copy_is_refused(self):
        d.add_card_to_deck(self.deck, self.ATRAXA, is_commander=True)
        with self.assertRaisesRegex(d.DeckError, "already a commander"):
            d.add_card_to_deck(self.deck, self.ATRAXA, is_commander=True)
        self.assertEqual(self.rows(), [(self.ATRAXA, 1, 1, 0)])

    def test_card_in_the_99_cannot_also_be_added_as_commander(self):
        d.add_card_to_deck(self.deck, self.ATRAXA)
        with self.assertRaisesRegex(d.DeckError, "already in the main deck"):
            d.add_card_to_deck(self.deck, self.ATRAXA, is_commander=True)

    def test_commander_quantity_must_be_one(self):
        with self.assertRaisesRegex(d.DeckError, "single card"):
            d.add_card_to_deck(self.deck, self.ATRAXA, quantity=2,
                               is_commander=True)

    def test_rule_applies_without_a_format(self):
        d.set_deck_format(self.deck, None)
        d.add_card_to_deck(self.deck, self.ATRAXA, is_commander=True)
        with self.assertRaises(d.DeckError):
            d.add_card_to_deck(self.deck, self.ATRAXA, is_commander=True)

    def test_ci_is_still_skipped_for_a_second_commander(self):
        d.add_card_to_deck(self.deck, "Thrasios, Triton Hero", is_commander=True)
        d.add_card_to_deck(self.deck, "Tymna the Weaver", is_commander=True)
        self.assertEqual(d.get_deck(self.deck)["commander_ci"],
                         ["B", "G", "U", "W"])

    def test_force_bypasses(self):
        d.add_card_to_deck(self.deck, self.ATRAXA, is_commander=True)
        d.add_card_to_deck(self.deck, self.ATRAXA, is_commander=True,
                           force=True)


class TestSetCommanderPointsFromSideboard(_DeckTest):
    """#7: a sideboard copy was never charged, so promoting it must be."""

    FORMAT = "canadianhighlander"

    def test_promoting_a_sideboard_card_over_budget_is_refused(self):
        d.add_card_to_deck(self.deck, "Ancestral Recall")        # 8 points
        d.add_card_to_deck(self.deck, "Black Lotus", is_sideboard=True)
        with self.assertRaisesRegex(d.DeckError, "point"):
            d.set_commander(self.deck, "Black Lotus")
        self.assertEqual(d.deck_points(self.deck)["total"], 8)
        self.assertIn(("Black Lotus", 1, 0, 1), self.rows())

    def test_promoting_a_main_deck_card_is_already_paid_for(self):
        d.add_card_to_deck(self.deck, "Ancestral Recall")
        d.add_card_to_deck(self.deck, "Time Vault", force=True)  # 15/10
        self.assertEqual(d.set_commander(self.deck, "Ancestral Recall")[1],
                         "promoted")


class TestSetCommanderKeepsExtraCopies(_DeckTest):
    """#8: one copy becomes the commander; the rest stay where they were."""

    def test_main_deck_copies_are_kept(self):
        d.add_card_to_deck(self.deck, "Llanowar Elves", quantity=4)
        self.assertEqual(d.set_commander(self.deck, "Llanowar Elves")[1],
                         "promoted")
        self.assertEqual(self.rows(), [("Llanowar Elves", 1, 1, 0),
                                       ("Llanowar Elves", 3, 0, 0)])

    def test_sideboard_copies_are_kept(self):
        d.add_card_to_deck(self.deck, "Duress", quantity=3, is_sideboard=True)
        d.set_commander(self.deck, "Duress")
        self.assertEqual(self.rows(), [("Duress", 1, 1, 0),
                                       ("Duress", 2, 0, 1)])

    def test_single_copy_is_flipped_in_place(self):
        d.add_card_to_deck(self.deck, "Duress")
        d.set_commander(self.deck, "Duress")
        self.assertEqual(self.rows(), [("Duress", 1, 1, 0)])

    def test_demote_after_promote_round_trips(self):
        d.add_card_to_deck(self.deck, "Duress")
        d.set_commander(self.deck, "Duress")
        self.assertEqual(d.set_commander(self.deck, "Duress", unset=True)[1],
                         "demoted")
        self.assertEqual(self.rows(), [("Duress", 1, 0, 0)])


class TestMinor(unittest.TestCase):
    def test_blank_format_is_stored_as_null(self):
        for i, blank in enumerate(("", "   ")):
            name = f"__blank_format_{i}__"
            d.create_deck(name, format=blank)
            self.assertIsNone(d.get_deck(name)["format"])

    def test_combos_limit_is_clamped_not_reset(self):
        name = "__combo_limit__"
        d.create_deck(name)
        conn = sqlite3.connect(str(q.DB_PATH))
        try:
            combo_ids = [r[0] for r in conn.execute(
                "SELECT combo_id FROM combo_cards GROUP BY combo_id "
                "HAVING COUNT(*) = 2 ORDER BY combo_id LIMIT 60")]
            placeholders = ",".join("?" * len(combo_ids))
            cards = sorted({r[0] for r in conn.execute(
                f"SELECT card_name FROM combo_cards "
                f"WHERE combo_id IN ({placeholders})", combo_ids)})
        finally:
            conn.close()
        for card in cards:
            d.add_card_to_deck(name, card, force=True)
        found = d.combos_in_deck(name, limit=1000)
        self.assertGreaterEqual(len(found), 60)
        self.assertEqual(len(found), len(d.combos_in_deck(name, limit=500)))


if __name__ == "__main__":
    unittest.main()
