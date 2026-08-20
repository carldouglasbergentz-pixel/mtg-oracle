"""Exact draw probabilities, checked against simulation and against algebra.

Run: python -m unittest discover tests
"""
import random
import sys
import unittest
from math import comb
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from mtg_oracle import probability as P  # noqa: E402


class TestCardsSeen(unittest.TestCase):
    def test_on_the_play_skips_a_draw(self):
        # Turn one on the play is the opening hand and nothing else.
        self.assertEqual(P.cards_seen(1, on_play=True), 7)
        self.assertEqual(P.cards_seen(1, on_play=False), 8)

    def test_one_more_card_per_turn(self):
        self.assertEqual([P.cards_seen(t) for t in range(1, 6)],
                         [7, 8, 9, 10, 11])

    def test_before_the_game_is_the_opening_hand(self):
        self.assertEqual(P.cards_seen(0), 7)


class TestHoldAny(unittest.TestCase):
    def test_matches_the_closed_form(self):
        for hits, seen in [(9, 7), (17, 12), (1, 7), (40, 9)]:
            self.assertAlmostEqual(
                P.hold_any(hits, seen),
                1 - comb(100 - hits, seen) / comb(100, seen), places=12)

    def test_none_in_the_deck_is_impossible(self):
        self.assertEqual(P.hold_any(0, 7), 0.0)

    def test_no_cards_drawn_is_impossible(self):
        self.assertEqual(P.hold_any(9, 0), 0.0)

    def test_more_hits_than_misses_is_certain(self):
        self.assertEqual(P.hold_any(95, 7), 1.0)

    def test_monotone_in_both_arguments(self):
        for seen in (7, 10, 13):
            vals = [P.hold_any(k, seen) for k in range(0, 30)]
            self.assertEqual(vals, sorted(vals))
        for hits in (4, 17):
            vals = [P.hold_any(hits, s) for s in range(1, 20)]
            self.assertEqual(vals, sorted(vals))


class TestHoldAtLeast(unittest.TestCase):
    def test_k_of_one_equals_hold_any(self):
        self.assertAlmostEqual(P.hold_at_least(9, 1, 9), P.hold_any(9, 9), places=12)

    def test_k_zero_is_certain(self):
        self.assertEqual(P.hold_at_least(0, 0, 7), 1.0)

    def test_needing_more_than_exists_is_impossible(self):
        self.assertEqual(P.hold_at_least(3, 4, 20), 0.0)
        self.assertEqual(P.hold_at_least(40, 8, 7), 0.0)

    def test_decreasing_in_k(self):
        vals = [P.hold_at_least(40, k, 10) for k in range(1, 9)]
        self.assertEqual(vals, sorted(vals, reverse=True))


class TestCategoryLive(unittest.TestCase):
    """The on-curve model, which is the one that had to be got right twice."""

    def test_agrees_with_monte_carlo(self):
        random.seed(20260820)
        trials = 120_000
        cases = [
            ({1: 4, 2: 9, 3: 3, 5: 1}, 39, 2, 2),
            ({1: 6, 2: 3, 3: 1}, 37, 2, 3),
            ({4: 2, 6: 1, 1: 1}, 40, 2, 4),
            ({0: 2, 1: 5}, 34, 0, 1),
            ({2: 1, 3: 2, 4: 2, 5: 2}, 44, 2, 6),
        ]
        for mv_counts, lands, rocks, turn in cases:
            with self.subTest(turn=turn, cat=sum(mv_counts.values())):
                seen = P.cards_seen(turn)
                exact = P.category_live(mv_counts, lands, rocks, turn, seen)
                pool = (["L"] * lands + ["R"] * rocks
                        + [("A", mv) for mv, c in mv_counts.items() for _ in range(c)])
                pool += ["C"] * (100 - len(pool))
                hits = 0
                for _ in range(trials):
                    s = random.sample(pool, seen)
                    mana = min(turn, s.count("L")) + s.count("R")
                    if any(isinstance(x, tuple) and x[1] <= mana for x in s):
                        hits += 1
                self.assertAlmostEqual(exact, hits / trials, delta=0.008)

    def test_monotone_in_turn(self):
        """The whole point of the model: it must not fall off after turn three."""
        mv = {1: 4, 2: 9, 3: 3, 5: 1}
        vals = [P.category_live(mv, 39, 2, t, P.cards_seen(t)) for t in range(1, 13)]
        self.assertEqual([round(v, 12) for v in vals],
                         sorted(round(v, 12) for v in vals))

    def test_never_exceeds_the_ceiling(self):
        mv = {1: 4, 2: 9, 3: 3, 5: 1}
        total = sum(mv.values())
        for t in range(1, 13):
            seen = P.cards_seen(t)
            self.assertLessEqual(P.category_live(mv, 39, 2, t, seen),
                                 P.hold_any(total, seen) + 1e-12)

    def test_converges_to_the_ceiling(self):
        mv = {1: 4, 2: 9}
        seen = P.cards_seen(14)
        self.assertAlmostEqual(P.category_live(mv, 39, 2, 14, seen),
                               P.hold_any(13, seen), delta=0.01)

    def test_free_spells_are_live_on_turn_one_with_no_land(self):
        self.assertGreater(P.category_live({0: 3}, 39, 0, 1, 7), 0.19)

    def test_a_six_drop_is_dead_on_turn_one(self):
        self.assertEqual(P.category_live({6: 5}, 39, 0, 1, 7), 0.0)

    def test_rocks_accelerate(self):
        without = P.category_live({3: 5}, 40, 0, 2, 8)
        with_two = P.category_live({3: 5}, 38, 2, 2, 8)
        self.assertGreater(with_two, without)

    def test_empty_category_is_impossible(self):
        self.assertEqual(P.category_live({}, 39, 2, 3, 9), 0.0)

    def test_more_copies_is_never_worse(self):
        vals = [P.category_live({2: k}, 39, 2, 3, 9) for k in range(1, 16)]
        self.assertEqual(vals, sorted(vals))

    def test_on_the_draw_beats_on_the_play(self):
        mv = {1: 4, 2: 9, 3: 3}
        for t in range(1, 8):
            self.assertGreaterEqual(
                P.category_live(mv, 39, 2, t, P.cards_seen(t, False)),
                P.category_live(mv, 39, 2, t, P.cards_seen(t, True)))

    def test_a_category_bigger_than_the_spell_pile_is_an_error(self):
        with self.assertRaises(ValueError):
            P.category_live({1: 70}, 40, 2, 3, 9)


class TestCurveHelpers(unittest.TestCase):
    def test_curve_matches_category_live(self):
        mv = {1: 2, 3: 4}
        got = P.curve(mv, 38, 2, range(1, 5))
        for t in range(1, 5):
            self.assertAlmostEqual(
                got[t], P.category_live(mv, 38, 2, t, P.cards_seen(t)), places=12)

    def test_ceiling_matches_hold_any(self):
        got = P.ceiling(9, range(1, 5))
        for t in range(1, 5):
            self.assertAlmostEqual(got[t], P.hold_any(9, P.cards_seen(t)), places=12)


if __name__ == "__main__":
    unittest.main()
