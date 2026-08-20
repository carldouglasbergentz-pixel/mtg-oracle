"""Comparing a deck against a reference set, and exporting it as text.

Both work on synthetic decks where possible, because the properties being
checked are structural: a deck compared to itself deviates nowhere, and an
exported deck re-imports into the same deck.

Run: python -m unittest discover tests
"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from mtg_oracle import roles as R  # noqa: E402
from mtg_oracle import services as svc  # noqa: E402
from mtg_oracle.deck_parser import parse_deckstring  # noqa: E402

DB = Path(__file__).parent.parent / "data" / "mtg.db"
SAMPLES = Path(__file__).parent.parent / "docs" / "sample decklists-uw canlander"


def read_list(path: Path) -> dict:
    cards: dict[str, int] = {}
    for row in parse_deckstring(path.read_bytes().decode("utf-8-sig")):
        if row["section"] == "sideboard":
            continue
        cards[row["name"]] = cards.get(row["name"], 0) + row["quantity"]
    return {"name": path.stem, "cards": cards}


@unittest.skipUnless(DB.exists() and SAMPLES.is_dir(),
                     "needs data/mtg.db and the sample decklists")
class TestCompare(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        files = sorted(SAMPLES.glob("*.txt"))
        if len(files) < 3:
            raise unittest.SkipTest("need at least three sample lists")
        cls.decks = [read_list(f) for f in files]

    def test_a_deck_compared_to_itself_deviates_nowhere(self):
        """The sanity check: one deck against copies of itself is all zeroes."""
        subject = self.decks[0]
        clones = [dict(subject, name=f"clone{i}") for i in range(3)]
        cmp = svc.compare_decks(subject, clones)
        self.assertEqual(cmp.out_of_range, ())
        for r in cmp.roles:
            self.assertEqual(r.delta, 0, r.role)
            self.assertEqual(r.verdict, "in", r.role)
        self.assertEqual(cmp.missing, ())
        self.assertEqual(cmp.unique, ())
        for role, per_turn in cmp.curve_delta.items():
            for t, v in per_turn.items():
                self.assertAlmostEqual(v, 0.0, places=6, msg=f"{role} T{t}")
        self.assertEqual(cmp.nearest[0][1], 0.0)

    def test_verdict_is_against_the_range_not_the_mean(self):
        """Inside a wide range is `in`, even when far from the mean."""
        refs = [
            {"name": "a", "cards": {"Counterspell": 1, "Island": 99}},
            {"name": "b", "cards": {"Counterspell": 1, "Mana Leak": 1,
                                    "Force of Will": 1, "Island": 97}},
        ]
        subject = {"name": "s", "cards": {"Counterspell": 1, "Mana Leak": 1,
                                          "Island": 98}}
        cmp = svc.compare_decks(subject, refs)
        counter = cmp.role("counter")
        self.assertEqual((counter.ref_min, counter.ref_max), (1, 3))
        self.assertEqual(counter.subject, 2)
        self.assertEqual(counter.verdict, "in")

    def test_outside_the_range_is_flagged_in_both_directions(self):
        refs = [{"name": f"r{i}", "cards": {"Counterspell": 1, "Island": 99}}
                for i in range(2)]
        low = svc.compare_decks({"name": "s", "cards": {"Island": 100}}, refs)
        self.assertEqual(low.role("counter").verdict, "under")
        high = svc.compare_decks(
            {"name": "s", "cards": {"Counterspell": 1, "Mana Leak": 1,
                                    "Force of Will": 1, "Island": 97}}, refs)
        self.assertEqual(high.role("counter").verdict, "over")
        self.assertIn("counter", [r.role for r in high.out_of_range])

    def test_missing_and_unique_are_the_card_level_diff(self):
        refs = [{"name": "r", "cards": {"Counterspell": 1, "Brainstorm": 1,
                                        "Island": 98}}]
        subject = {"name": "s", "cards": {"Counterspell": 1, "Ponder": 1,
                                         "Island": 98}}
        cmp = svc.compare_decks(subject, refs)
        self.assertEqual([c.name for c in cmp.missing], ["Brainstorm"])
        self.assertEqual([c.name for c in cmp.unique], ["Ponder"])
        self.assertEqual(cmp.missing[0].n_lists, 1)
        self.assertEqual(cmp.missing[0].of_lists, 1)
        self.assertEqual(cmp.missing[0].share, 1.0)

    def test_min_share_filters_the_missing_list(self):
        refs = [{"name": "a", "cards": {"Brainstorm": 1, "Ponder": 1, "Island": 98}},
                {"name": "b", "cards": {"Brainstorm": 1, "Island": 99}}]
        subject = {"name": "s", "cards": {"Island": 100}}
        everything = svc.compare_decks(subject, refs)
        self.assertEqual({c.name for c in everything.missing},
                         {"Brainstorm", "Ponder"})
        half = svc.compare_decks(subject, refs, min_share=0.6)
        self.assertEqual({c.name for c in half.missing}, {"Brainstorm"})

    def test_missing_is_ordered_by_how_many_lists_play_it(self):
        cmp = svc.compare_decks(self.decks[0], self.decks[1:])
        counts = [c.n_lists for c in cmp.missing]
        self.assertEqual(counts, sorted(counts, reverse=True))

    def test_nearest_is_sorted_and_covers_every_reference(self):
        cmp = svc.compare_decks(self.decks[0], self.decks[1:])
        dists = [d for _n, d in cmp.nearest]
        self.assertEqual(dists, sorted(dists))
        self.assertEqual(len(cmp.nearest), len(self.decks) - 1)

    def test_an_empty_reference_set_is_an_error(self):
        with self.assertRaises(svc.ServiceError):
            svc.compare_decks(self.decks[0], [])

    def test_roles_covers_the_whole_taxonomy(self):
        cmp = svc.compare_decks(self.decks[0], self.decks[1:])
        self.assertEqual({r.role for r in cmp.roles}, set(R.ROLES))
        self.assertEqual(cmp.mana_sources.role, "mana_sources")


@unittest.skipUnless(DB.exists(), "needs data/mtg.db")
class TestExportRoundTrip(unittest.TestCase):
    """An exported deck must re-import into the same deck.

    This is the property that keeps the export honest: the project's own
    parser is a stand-in for every importer that takes `N Card Name` lines.
    """

    DECK = {"Counterspell": 1, "Brainstorm": 1, "Island": 12, "Plains": 8,
            "Fire // Ice": 1, "Hengegate Pathway // Mistgate Pathway": 1,
            "Jace, Vryn's Prodigy // Jace, Telepath Unbound": 1,
            "Lórien Revealed": 1}

    def _export_text(self):
        """The export format, hand-built, so this class needs no stored deck.

        `TestExportAgainstARealDeck` covers the generator itself; this checks
        that the *format* survives the parser, awkward names included.
        """
        lines = ["Deck"] + [f"{q} {n}" for n, q in sorted(self.DECK.items())]
        return "\n".join(lines) + "\n"

    def test_round_trip_through_the_parser(self):
        text = self._export_text()
        rows = parse_deckstring(text)
        back = {}
        for r in rows:
            self.assertEqual(r["section"], "main")
            back[r["name"]] = back.get(r["name"], 0) + r["quantity"]
        self.assertEqual(back, self.DECK)

    def test_the_deck_header_is_a_header_the_parser_knows(self):
        for _key, header in svc._EXPORT_ORDER:
            rows = parse_deckstring(f"{header}\n1 Counterspell\n")
            self.assertEqual(len(rows), 1, header)
            self.assertNotEqual(rows[0]["name"], header)

    def test_quantities_survive(self):
        rows = parse_deckstring("Deck\n12 Island\n")
        self.assertEqual(rows[0], {"name": "Island", "quantity": 12,
                                   "section": "main"})

    def test_comment_headers_are_skipped_by_the_parser(self):
        text = "Deck\n// Counterspells (1)\n1 Counterspell\n"
        rows = parse_deckstring(text)
        self.assertEqual([r["name"] for r in rows], ["Counterspell"])


@unittest.skipUnless(DB.exists(), "needs data/mtg.db")
class TestExportAgainstARealDeck(unittest.TestCase):
    """Uses a throwaway deck so it never touches the user's collection."""

    NAME = "__test_export__"

    @classmethod
    def setUpClass(cls):
        from mtg_oracle import decks as d
        cls.d = d
        cls._cleanup()
        d.create_deck(cls.NAME, format="canlander")
        for card, qty in (("Counterspell", 1), ("Island", 9),
                          ("Fire // Ice", 1),
                          ("Hengegate Pathway", 1), ("Solitude", 1)):
            d.add_card_to_deck(cls.NAME, card, quantity=qty, force=True)
        cls.ref = svc.DeckRef(cls.NAME)

    @classmethod
    def tearDownClass(cls):
        cls._cleanup()

    @classmethod
    def _cleanup(cls):
        from mtg_oracle import decks as d
        try:
            d.delete_deck(cls.NAME)
        except Exception:
            pass

    def test_exports_every_card_once(self):
        e = svc.export_deck_text(self.ref)
        self.assertEqual(e.cards, 13)
        self.assertEqual(e.sections, {"main": 13})
        self.assertEqual(e.rows, 5)

    def test_round_trips_back_into_the_same_card_counts(self):
        e = svc.export_deck_text(self.ref)
        back = {}
        for row in parse_deckstring(e.text):
            back[row["name"]] = back.get(row["name"], 0) + row["quantity"]
        deck = self.d.get_deck(self.NAME)
        original = {}
        for row in deck["cards"]:
            original[row["card_name"]] = (
                original.get(row["card_name"], 0) + row["quantity"])
        self.assertEqual(back, original)

    def test_basics_are_stacked_not_repeated(self):
        e = svc.export_deck_text(self.ref)
        island = [l for l in e.text.splitlines() if l.endswith(" Island")]
        self.assertEqual(island, ["9 Island"])

    def test_front_face_shortens_a_modal_dfc(self):
        e = svc.export_deck_text(self.ref, front_face=True)
        self.assertIn("1 Hengegate Pathway\n", e.text)
        self.assertNotIn("Mistgate", e.text)

    def test_front_face_does_NOT_break_a_split_card(self):
        """There is no card called `Fire`. Only `Fire // Ice`."""
        e = svc.export_deck_text(self.ref, front_face=True)
        self.assertIn("1 Fire // Ice", e.text)

    def test_full_names_are_the_default(self):
        e = svc.export_deck_text(self.ref)
        self.assertIn("Hengegate Pathway // Mistgate Pathway", e.text)

    def test_grouped_adds_role_comments_and_still_round_trips(self):
        e = svc.export_deck_text(self.ref, group_by_role=True)
        self.assertTrue(any(l.startswith("// ") for l in e.text.splitlines()))
        back = {r["name"] for r in parse_deckstring(e.text)}
        self.assertIn("Counterspell", back)
        self.assertNotIn("// Counterspells (1)", back)

    def test_no_headers_still_parses_as_main(self):
        e = svc.export_deck_text(self.ref, headers=False)
        self.assertFalse(e.text.startswith("Deck"))
        self.assertTrue(all(r["section"] == "main"
                            for r in parse_deckstring(e.text)))

    def test_a_missing_deck_is_a_service_error(self):
        with self.assertRaises(svc.ServiceError):
            svc.export_deck_text(svc.DeckRef("__no_such_deck__"))

    def test_no_deck_selected_is_a_service_error(self):
        with self.assertRaises(svc.ServiceError):
            svc.export_deck_text(svc.DeckRef())


if __name__ == "__main__":
    unittest.main()
