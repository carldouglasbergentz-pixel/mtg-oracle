"""Regressions in the service layer and the draw maths it drives.

Each test names the bug it pins: a deck modelled at the wrong size, a card
counted twice because two lists spelled it differently, and a deck-layer
error that reached the interfaces as something other than ServiceError.

Run: python -m unittest discover tests
"""
import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).parent.parent))

from mtg_oracle import decks as D  # noqa: E402
from mtg_oracle import probability as P  # noqa: E402
from mtg_oracle import services as svc  # noqa: E402

DB = Path(__file__).parent.parent / "data" / "mtg.db"
TURNS = range(1, 9)


def synthetic_profile(size, role_mv, lands, rocks=0):
    """A DeckProfile built by hand, so no database is needed.

    None of the categories here contain a rock, so the live-curve category
    is `role_mv` itself.
    """
    return svc.DeckProfile("synthetic", size, {}, role_mv, {}, lands, rocks, 0,
                           on_curve_mv=role_mv)


class TestSeenBeyondTheDeck(unittest.TestCase):
    """Asking for more cards than the deck has made `comb(deck, seen)` zero."""

    def test_hold_at_least_with_the_whole_deck_seen(self):
        self.assertEqual(P.hold_at_least(5, 1, 120, deck_size=100), 1.0)
        self.assertEqual(P.hold_at_least(5, 6, 120, deck_size=100), 0.0)

    def test_hold_any_with_the_whole_deck_seen(self):
        self.assertEqual(P.hold_any(5, 120, deck_size=100), 1.0)

    def test_category_live_with_the_whole_deck_seen(self):
        # Every card seen: 24 lands on turn 8 is 8 mana, so a 1-drop is live.
        got = P.category_live({1: 4}, 24, 0, turn=8, seen=70, deck_size=60)
        self.assertAlmostEqual(got, 1.0, places=12)

    def test_a_tiny_deck_on_a_late_turn(self):
        curve = P.curve({1: 2}, 3, 0, TURNS, deck_size=10)
        self.assertEqual(set(curve), set(TURNS))
        self.assertAlmostEqual(curve[8], 1.0, places=12)


class TestProfileUsesItsOwnSize(unittest.TestCase):
    """`live_curve` / `ceiling` used probability's 100-card default."""

    def test_sixty_card_deck_live_curve(self):
        prof = synthetic_profile(60, {"spot": {1: 4}}, lands=24)
        want = P.curve({1: 4}, 24, 0, TURNS, deck_size=60)
        self.assertEqual(prof.live_curve("spot", TURNS), want)
        # The bug read turn one at 0.211 instead of 0.387.
        self.assertAlmostEqual(prof.live_curve("spot", TURNS)[1], 0.387, places=3)

    def test_sixty_card_deck_ceiling(self):
        prof = synthetic_profile(60, {"spot": {1: 4}}, lands=24)
        self.assertEqual(prof.ceiling("spot", TURNS),
                         P.ceiling(4, TURNS, deck_size=60))

    def test_deck_over_one_hundred_cards_does_not_raise(self):
        # 110 cards, 60 lands, 5 rocks: 45 threats fit the 45-card spell pile.
        # At the old fixed size of 100 the pile was 35 and this raised.
        prof = synthetic_profile(110, {"threat": {3: 45}}, lands=60, rocks=5)
        curve = prof.live_curve("threat", TURNS)
        self.assertTrue(all(0.0 <= p <= 1.0 for p in curve.values()), curve)

    def test_empty_profile_is_all_zeros(self):
        prof = synthetic_profile(0, {}, lands=0)
        self.assertEqual(set(prof.live_curve("spot", TURNS).values()), {0.0})
        self.assertEqual(set(prof.ceiling("spot", TURNS).values()), {0.0})


@unittest.skipUnless(DB.exists(), "needs data/mtg.db")
class TestRankCardsCanonicalNames(unittest.TestCase):
    """`rank_cards` keyed on the raw string, so one card became two rows."""

    DECKS = [
        {"name": "a", "cards": {"Fire // Ice": 1, "Brazen Borrower": 1}},
        {"name": "b", "cards": {"Fire/Ice": 1,
                                "Brazen Borrower // Petty Theft": 1}},
    ]

    def rows_named(self, ranking, fragment):
        return [row for rows in ranking.values() for row in rows
                if fragment in row["name"]]

    def test_two_spellings_are_one_card_in_two_lists(self):
        ranking, _ = svc.rank_cards(self.DECKS)
        for fragment in ("Fire", "Brazen Borrower"):
            with self.subTest(card=fragment):
                rows = self.rows_named(ranking, fragment)
                self.assertTrue(rows)
                self.assertEqual({row["name"] for row in rows}, {rows[0]["name"]})
                for row in rows:
                    self.assertEqual((row["n"], row["pct"]), (2, 100))

    def test_one_list_with_two_spellings_is_still_one_list(self):
        ranking, _ = svc.rank_cards(
            [{"name": "a", "cards": {"Fire // Ice": 1, "Fire/Ice": 1}}])
        rows = self.rows_named(ranking, "Fire")
        self.assertTrue(rows)
        self.assertTrue(all(row["n"] == 1 for row in rows), rows)


class TestDeckErrorsBecomeServiceErrors(unittest.TestCase):
    """The TUI catches ServiceError; a DeckError printed `ERR DeckError`.

    The deck layer is patched, so nothing here touches the database.
    """

    REF = svc.DeckRef(deck="Some Deck")

    def test_import_into_deck(self):
        boom = D.DeckError("ambiguous deck name 'Some Deck'")
        with mock.patch.object(D, "load_parsed_into_deck", side_effect=boom):
            with self.assertRaises(svc.ServiceError) as caught:
                svc.import_text_into_deck(self.REF, "1 Lightning Bolt")
        self.assertIn("ambiguous", str(caught.exception))
        self.assertIs(caught.exception.__cause__, boom)

    def test_create_deck(self):
        boom = D.DeckError("deck 'Some Deck' already exists")
        with mock.patch.object(D, "import_deck", side_effect=boom):
            with self.assertRaises(svc.ServiceError) as caught:
                svc.create_deck_from_text(self.REF, "1 Lightning Bolt")
        self.assertIn("already exists", str(caught.exception))

    def test_unparseable_text_is_still_a_service_error(self):
        with mock.patch.object(D, "load_parsed_into_deck") as load:
            with self.assertRaises(svc.ServiceError):
                svc.import_text_into_deck(self.REF, "")
        load.assert_not_called()


if __name__ == "__main__":
    unittest.main()
