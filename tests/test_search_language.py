"""The search language and name-tolerant lookups, against inputs that broke them.

Each case is a query that used to return a plausible but wrong answer — a
count that looked fine, a sort that looked sorted — so the failure only
showed when someone knew what the right number was.

Needs `data/mtg.db`; skips itself when it is absent.

Run: python -m unittest discover tests
"""
import sqlite3
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from mtg_oracle import queries as q  # noqa: E402
from mtg_oracle import scryfall_search as ss  # noqa: E402

DB = Path(__file__).parent.parent / "data" / "mtg.db"


class TestTokenizer(unittest.TestCase):
    """Database-free: these fail before any SQL runs."""

    def test_bang_is_not_an_operator_on_power(self):
        # `pow!3` used to escape as a raw KeyError, not a SearchError.
        with self.assertRaises(ss.SearchError):
            ss.compile_ast(ss.parse("pow!3"))

    def test_dangling_minus_is_an_error(self):
        # It used to become a bareword and search oracle text for '-'.
        with self.assertRaises(ss.SearchError):
            ss.parse("t:goblin -")

    def test_order_inside_quotes_is_text(self):
        cleaned, orders = ss._extract_order('o:"x order:asc_mv y"')
        self.assertEqual(cleaned, 'o:"x order:asc_mv y"')
        self.assertEqual(orders, [])

    def test_order_token_outside_quotes_still_extracted(self):
        self.assertEqual(ss._extract_order("t:goblin order:desc_ci"),
                         ("t:goblin", [("ci", "desc")]))


@unittest.skipUnless(DB.exists(), "needs data/mtg.db")
class TestSearchCounts(unittest.TestCase):

    def test_negation_keeps_rows_where_the_column_is_null(self):
        # NOT (NULL) is NULL, so `-pow>=4` used to drop every non-creature.
        total = ss.count_query("")
        self.assertEqual(ss.count_query("pow>=4") + ss.count_query("-pow>=4"),
                         total)

    def test_explicit_and_is_juxtaposition(self):
        self.assertEqual(ss.count_query("t:creature and c:u"),
                         ss.count_query("t:creature c:u"))

    def test_like_wildcards_in_input_are_literal(self):
        # `n:_____` matched every name of five or more letters.
        self.assertLess(ss.count_query("n:_____"), 100)

    def test_negative_power(self):
        names = {r["name"] for r in ss.run_query("pow<0")}
        self.assertIn("Spinal Parasite", names)

    def test_oversized_limit_clamps(self):
        # A limit over the cap used to reset to 50, stranding rows past it.
        self.assertGreater(len(ss.run_query("t:goblin", limit=2000)), 50)

    def test_ci_sort_counts_colours_not_commas(self):
        # The old expression counted commas: colourless and mono-coloured
        # both scored 0 and interleaved.
        rows = ss.run_query("n:bolt order:asc_ci", limit=200)
        conn = sqlite3.connect(DB)
        try:
            widths = []
            for r in rows:
                (ci,) = conn.execute(
                    "SELECT COALESCE(color_identity, '') FROM cards WHERE name = ?",
                    (r["name"],)).fetchone()
                widths.append(len(ci.split(",")) if ci else 0)
        finally:
            conn.close()
        self.assertIn(0, widths)
        self.assertEqual(widths, sorted(widths))


@unittest.skipUnless(DB.exists(), "needs data/mtg.db")
class TestNameTolerance(unittest.TestCase):

    def test_fold_finds_names_starting_with_a_diacritic(self):
        # SQLite's LOWER() is ASCII-only, so a first-letter prefilter never
        # offered 'Éomer' to the fold comparison.
        self.assertEqual(q.resolve_card_name("eomer, marshal of rohan"),
                         "Éomer, Marshal of Rohan")

    def test_double_quotes_are_optional(self):
        self.assertEqual(q.resolve_card_name("Kongming, Sleeping Dragon"),
                         'Kongming, "Sleeping Dragon"')

    def test_percent_is_not_a_wildcard(self):
        self.assertIsNone(q.resolve_card_name("%"))

    def test_rulings_and_combos_resolve_front_face(self):
        self.assertTrue(q.get_rulings("Delver of Secrets"))
        self.assertTrue(q.find_combos_with_card("Birgi, God of Storytelling"))

    def test_combos_with_all_ignores_duplicate_names(self):
        one = q.find_combos_with_all(["Thassa's Oracle"])
        dup = q.find_combos_with_all(["Thassa's Oracle", "thassa's oracle"])
        self.assertEqual([c["id"] for c in one], [c["id"] for c in dup])


@unittest.skipUnless(DB.exists(), "needs data/mtg.db")
class TestCardProfile(unittest.TestCase):

    def test_corrections_match_whole_names(self):
        # 'Strangle' used to pick up Strangleroot Geist's correction.
        strangle = {c["id"] for c in q.get_card("Strangle")["corrections"]}
        geist = {c["id"] for c in q.get_card("Strangleroot Geist")["corrections"]}
        self.assertTrue(geist)
        self.assertFalse(strangle & geist)

    def test_embedded_combos_carry_the_template_flag(self):
        combos = q.get_card("Ashnod's Altar")["combos"]
        self.assertTrue(combos)
        self.assertTrue(all("has_template_vars" in c for c in combos))


@unittest.skipUnless(DB.exists(), "needs data/mtg.db")
class TestRuleOrder(unittest.TestCase):

    def test_children_sort_naturally(self):
        numbers = [r["rule_number"] for r in q.get_rule("702")["children"]]
        self.assertLess(numbers.index("702.2"), numbers.index("702.10"))

    def test_sort_key(self):
        self.assertLess(q.rule_sort_key("702.9"), q.rule_sort_key("702.10"))
        self.assertLess(q.rule_sort_key("702.10"), q.rule_sort_key("702.10a"))


if __name__ == "__main__":
    unittest.main()
