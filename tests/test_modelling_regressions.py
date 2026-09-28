"""Regressions in how cards are priced, classified and counted.

Each class names the defect it pins: alternative costs assumed free, split
cards priced by a half you cannot cast, one-shot draws counted as engines,
the `-self` veto taking genuine recursion, drawback tags leaking into
secondary roles, pips and mana sources misread, and rocks counted twice in
the draw maths.

Run: python -m unittest discover tests
"""
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from mtg_oracle import analytics as A  # noqa: E402
from mtg_oracle import queries as q  # noqa: E402
from mtg_oracle import roles as R  # noqa: E402
from mtg_oracle import services as svc  # noqa: E402

DB = Path(__file__).parent.parent / "data" / "mtg.db"
TURNS = range(1, 9)


# --- no database ----------------------------------------------------------

class TestTagVerdict(unittest.TestCase):
    def test_the_self_label_silences_only_the_ambiguous_labels(self):
        """The Scarab God is `reanimate-self` AND `reanimate-creature`."""
        self.assertEqual(
            R.roles_from_tags(["reanimate-self", "reanimate-creature"]), {"recursion"})
        self.assertEqual(R.roles_from_tags(["reanimate-self", "reanimate-cast"]), set())
        self.assertEqual(R.roles_from_tags(["regrowth-self", "regrowth-land"]),
                         {"recursion"})

    def test_a_recognised_label_with_no_role_is_still_a_verdict(self):
        self.assertEqual(R.tag_verdict(["regrowth-self"]), (set(), True))
        self.assertEqual(R.tag_verdict(["cycle", "alliteration"]), (set(), False))
        self.assertEqual(R.tag_verdict(()), (set(), False))

    def test_the_opponents_ramp_is_not_a_role_of_removal(self):
        path = ["spot removal", "land ramp", "tutor-land-basic",
                "tutor-land-to-battlefield", "donate rampant growth"]
        self.assertEqual(R.roles_from_tags(path), {"spot"})

    def test_removal_that_ramps_its_caster_keeps_the_ramp(self):
        """Deathsprout fetches YOUR basic; there is no donate label."""
        self.assertEqual(
            R.roles_from_tags(["spot removal", "land ramp", "tutor-land-basic"]),
            {"spot", "mana", "tutor"})

    def test_a_mana_egg_is_one_shot_mana(self):
        self.assertEqual(R.roles_from_tags(["mana egg", "ramp"]), {"ritual", "mana"})


def card(name, cost, mv, type_line="Instant", text="", layout="normal",
         faces=None, ci="", **extra):
    return dict(name=name, mana_cost=cost, mana_value=mv, type_line=type_line,
                oracle_text=text, layout=layout, color_identity=ci,
                card_faces=json.dumps(faces) if faces else None,
                quantity=1, is_sideboard=0, is_commander=0, **extra)


CONSIGN = card(
    "Consign // Oblivion", "{1}{U} // {4}{B}", 7, "Instant // Sorcery",
    layout="split", faces=[
        {"name": "Consign", "mana_cost": "{1}{U}", "type_line": "Instant",
         "oracle_text": "Return target nonland permanent to its owner's hand."},
        {"name": "Oblivion", "mana_cost": "{4}{B}", "type_line": "Sorcery",
         "oracle_text": "Aftermath (Cast this spell only from your graveyard.)\n"
                        "Target opponent discards two cards."},
    ])


class TestPips(unittest.TestCase):
    def test_hybrid_counts_toward_each_colour(self):
        self.assertEqual({k: v for k, v in A.cost_pips("{1}{G/W}{G/W}").items() if v},
                         {"G": 2, "W": 2})

    def test_twobrid_and_phyrexian_count_their_colour(self):
        self.assertEqual(A.cost_pips("{2/W}{2/W}{2/W}")["W"], 3)
        self.assertEqual(A.cost_pips("{1}{B/P}{B/P}")["B"], 2)

    def test_colourless_has_its_own_bucket(self):
        pips = A.cost_pips("{6}{C}{C}")
        self.assertEqual(pips["C"], 2)
        self.assertEqual(sum(pips.values()), 2)

    def test_generic_x_and_snow_ask_for_no_colour(self):
        self.assertEqual(sum(A.cost_pips("{X}{2}{S}").values()), 0)


class TestSplitCardsInAnalytics(unittest.TestCase):
    def test_placed_and_coloured_by_the_half_you_cast_from_hand(self):
        a = A.compute_deck_analytics({"cards": [CONSIGN]})
        self.assertEqual(a["mana_curve"][2], 1)       # Consign, not 7
        self.assertEqual(a["color_pips"]["U"], 1)
        self.assertEqual(a["color_pips"]["B"], 0)     # Oblivion is aftermath


def land(name, text, ci="", type_line="Land"):
    return card(name, "", 0, type_line, text, ci=ci)


class TestManaSources(unittest.TestCase):
    def sources(self, *cards):
        got = A.compute_deck_analytics({"cards": list(cards)})["mana_sources"]
        return {c for c, n in got.items() if n}

    def test_an_activation_cost_is_not_a_colour(self):
        kor_haven = land("Kor Haven", "{T}: Add {C}.\n{1}{W}, {T}: Prevent all "
                         "combat damage that would be dealt by target attacking "
                         "creature this turn.")
        self.assertEqual(self.sources(kor_haven), {"C"})

    def test_any_colour_is_all_five(self):
        brass = land("City of Brass", "Whenever City of Brass becomes tapped, it "
                     "deals 1 damage to you.\n{T}: Add one mana of any color.")
        self.assertEqual(self.sources(brass), set("WUBRG"))

    def test_command_tower_makes_the_commanders_colours(self):
        tower = land("Command Tower", "{T}: Add one mana of any color in your "
                     "commander's color identity.")
        judith = card("Judith, the Scourge Diva", "{1}{B}{R}", 3,
                      "Legendary Creature — Human Shaman", ci="B,R")
        judith["is_commander"] = 1
        self.assertEqual(self.sources(tower, judith), {"B", "R"})

    def test_a_fetchland_makes_the_types_it_names(self):
        delta = land("Polluted Delta", "{T}, Pay 1 life, Sacrifice this land: "
                     "Search your library for an Island or Swamp card, put it onto "
                     "the battlefield, then shuffle.")
        self.assertEqual(self.sources(delta), {"U", "B"})

    JUDITH = dict(card("Judith, the Scourge Diva", "{1}{B}{R}", 3,
                       "Legendary Creature — Human Shaman", ci="B,R"),
                  is_commander=1)
    STARTING_TOWN = land("Starting Town", "This land enters tapped unless it's "
                         "your first, second, or third turn of the game.\n"
                         "{T}: Add {C}.\n{T}, Pay 1 life: Add one mana of any color.")
    ARID_MESA = land("Arid Mesa", "{T}, Pay 1 life, Sacrifice this land: Search "
                     "your library for a Mountain or Plains card, put it onto the "
                     "battlefield, then shuffle.")

    def test_flexible_colours_are_clipped_to_the_deck(self):
        """A Rakdos deck reported W/U/G sources from Starting Town and Arid Mesa."""
        self.assertEqual(self.sources(self.STARTING_TOWN, self.JUDITH), {"B", "R", "C"})
        self.assertEqual(self.sources(self.ARID_MESA, self.JUDITH), {"R"})

    def test_printed_colours_are_never_clipped(self):
        tundra = land("Tundra", "({T}: Add {W} or {U}.)", ci="W,U",
                      type_line="Land — Plains Island")
        self.assertEqual(self.sources(tundra, self.JUDITH), {"W", "U"})

    def test_a_fetch_for_none_of_the_decks_colours_is_no_source(self):
        mono_u = card("Counterspell", "{U}{U}", 2, ci="U")
        got = A.compute_deck_analytics({"cards": [self.ARID_MESA, mono_u]})
        self.assertEqual(got["land_count"], 1)
        self.assertEqual(sum(got["mana_sources"].values()), 0)

    def test_without_a_commander_an_off_colour_land_widens_the_deck(self):
        """Canadian Highlander runs off-colour lands to power converge.

        A W/U list with a B/G surveil land wants black and green mana, so
        they stay sources and "any color" counts toward them — only red,
        which nothing in the deck touches, is clipped.
        """
        mortuary = land("Underground Mortuary", "({T}: Add {B} or {G}.)\nThis "
                        "land enters tapped.\nWhen this land enters, surveil 1.",
                        ci="B,G", type_line="Land — Swamp Forest")
        swords = card("Swords to Plowshares", "{W}", 1, ci="W")
        counter = card("Counterspell", "{U}{U}", 2, ci="U")
        deck = [mortuary, swords, counter]
        self.assertEqual(A.deck_colors(deck), frozenset("WUBG"))
        self.assertEqual(self.sources(mortuary, swords, counter), {"B", "G"})
        self.assertEqual(self.sources(self.STARTING_TOWN, *deck),
                         {"W", "U", "B", "G", "C"})

    def test_unknown_deck_colours_keep_every_colour(self):
        """No commander and no coloured card: nothing to clip against."""
        self.assertEqual(self.sources(self.STARTING_TOWN), set("WUBRGC"))
        self.assertEqual(self.sources(self.ARID_MESA), {"R", "W"})

    def test_a_basic_fetch_makes_the_decks_colours(self):
        wilds = land("Evolving Wilds", "{T}, Sacrifice Evolving Wilds: Search "
                     "your library for a basic land card, put it onto the "
                     "battlefield tapped, then shuffle.")
        spell = card("Lightning Helix", "{R}{W}", 2, ci="R,W")
        self.assertEqual(self.sources(wilds, spell), {"R", "W"})


# --- with the database ----------------------------------------------------

@unittest.skipUnless(DB.exists(), "needs data/mtg.db")
class DbCase(unittest.TestCase):
    NAMES = [
        "Baleful Mastery", "Bringer of the Blue Dawn", "Fieldmist Borderpost",
        "Force of Will", "Bolas's Citadel",
        "Consign // Oblivion", "Commit // Memory", "Dusk // Dawn", "Fire // Ice",
        "Mishra's Bauble", "Chromatic Sphere", "Brainstone", "Mind Stone",
        "Conjurer's Bauble", "Phyrexian Arena",
        "The Scarab God", "Sue, Everlasting Dinosaur", "Chainer, Nightmare Adept",
        "Gravecrawler", "Life from the Loam", "Angelic Destiny",
        "Path to Exile", "Lotus Petal", "Lion's Eye Diamond", "Ruby Medallion",
        "Dismember", "Birthing Pod", "Gitaxian Probe",
    ]

    @classmethod
    def setUpClass(cls):
        cls.facts = q.get_card_facts(cls.NAMES)
        cls.tags = q.get_oracle_tags(cls.NAMES)
        missing = [n for n in cls.NAMES if n not in cls.facts]
        if missing:
            raise unittest.SkipTest(f"cards not in this database: {missing}")

    def cl(self, name):
        return R.classify(self.facts[name], tags=self.tags.get(name, ()))


class TestAlternativeCostsArePriced(DbCase):
    def test_an_alternative_that_costs_mana_costs_that_mana(self):
        for name, want in (("Baleful Mastery", 2), ("Bringer of the Blue Dawn", 5),
                           ("Fieldmist Borderpost", 1)):
            with self.subTest(card=name):
                self.assertEqual(self.cl(name).cost.effective, want)

    def test_a_pitch_cost_is_still_free(self):
        self.assertEqual(self.cl("Force of Will").cost.effective, 0)

    def test_a_cost_granted_to_other_spells_is_not_this_cards(self):
        self.assertEqual(self.cl("Bolas's Citadel").cost.effective, 6)


class TestSplitCardsInRoles(DbCase):
    def test_the_cheapest_half_castable_from_hand(self):
        for name, want in (("Consign // Oblivion", 2), ("Commit // Memory", 4),
                           ("Dusk // Dawn", 4), ("Fire // Ice", 2)):
            with self.subTest(card=name):
                self.assertEqual(self.cl(name).cost.effective, want)

    def test_an_aftermath_half_does_not_feed_the_text_rules(self):
        """Oblivion's "target opponent discards" is cast from the graveyard."""
        self.assertNotIn("discard", R.derive_roles(self.facts["Consign // Oblivion"]))


class TestOneShotDrawsAreNotEngines(DbCase):
    def test_sacrificing_itself_or_a_delayed_draw_happens_once(self):
        for name in ("Mishra's Bauble", "Chromatic Sphere", "Brainstone",
                     "Mind Stone", "Conjurer's Bauble"):
            with self.subTest(card=name):
                self.assertFalse(self.cl(name).engine)

    def test_mind_stones_draw_is_a_secondary_role(self):
        cl = self.cl("Mind Stone")
        self.assertEqual(cl.primary, "mana")
        self.assertIn("draw", cl.roles)

    def test_a_real_upkeep_engine_still_is_one(self):
        self.assertTrue(self.cl("Phyrexian Arena").engine)


class TestSelfVeto(DbCase):
    def test_recursion_engines_that_are_also_resilient_keep_recursion(self):
        for name in ("The Scarab God", "Sue, Everlasting Dinosaur",
                     "Chainer, Nightmare Adept", "Life from the Loam"):
            with self.subTest(card=name):
                cl = self.cl(name)
                self.assertIn("recursion", cl.roles)
                self.assertEqual(cl.source, "tagged")

    def test_a_creature_that_only_returns_itself_is_a_threat(self):
        cl = self.cl("Gravecrawler")
        self.assertNotIn("recursion", cl.roles)
        self.assertEqual((cl.primary, cl.source), ("threat", "tagged"))

    def test_tagged_with_no_role_is_confident_utility(self):
        cl = self.cl("Angelic Destiny")
        self.assertEqual((cl.primary, cl.source), ("utility", "tagged"))
        self.assertFalse(cl.low_confidence)


class TestManaRoles(DbCase):
    def test_path_to_exile_is_removal_only(self):
        self.assertEqual(set(self.cl("Path to Exile").roles), {"spot"})

    def test_mana_eggs_are_rituals_not_rocks(self):
        for name in ("Lotus Petal", "Lion's Eye Diamond"):
            with self.subTest(card=name):
                self.assertEqual(self.cl(name).primary, "ritual")

    def test_a_cost_reducer_stays_a_permanent_mana_source(self):
        """Decided: a Medallion discounts every spell of its colour, every
        turn — a permanent source for the on-curve model, unlike an egg."""
        self.assertEqual(self.cl("Ruby Medallion").primary, "mana")


class TestPhyrexianMana(DbCase):
    def test_each_phyrexian_symbol_is_paid_with_life(self):
        for name, want in (("Dismember", 1), ("Birthing Pod", 3),
                           ("Gitaxian Probe", 0)):
            with self.subTest(card=name):
                self.assertEqual(self.cl(name).cost.effective, want)


@unittest.skipUnless(DB.exists(), "needs data/mtg.db")
class TestRocksSitInOnePile(unittest.TestCase):
    """A rock passed as a rock AND as a category card was two cards."""

    def test_a_rock_heavy_deck_does_not_raise(self):
        # At the old accounting `mana` was 40 category cards against a
        # 20-card spell pile, and category_live raised ValueError.
        p = svc.profile_deck("rocks", {"Island": 40, "Sol Ring": 40,
                                       "Counterspell": 20})
        self.assertEqual(p.rocks, 40)
        for role in ("mana", "counter"):
            with self.subTest(role=role):
                curve = p.live_curve(role, TURNS)
                self.assertTrue(all(0.0 <= v <= 1.0 for v in curve.values()))

    def test_a_rocks_secondary_role_is_reach_not_curve(self):
        p = svc.profile_deck("stone", {"Island": 40, "Mind Stone": 1,
                                       "Counterspell": 59})
        self.assertEqual(sum(p.role_mv["draw"].values()), 1)
        self.assertEqual(sum(p.on_curve_mv["draw"].values()), 0)
        self.assertEqual(sum(p.on_curve_mv["mana"].values()), 1)

    def test_every_curve_category_fits_its_pile_in_the_sample_lists(self):
        root = Path(__file__).parent.parent / "docs"
        from mtg_oracle.deck_parser import parse_deckstring
        files = sorted(root.glob("sample decklists-*/*.txt"))
        if not files:
            self.skipTest("no sample lists")
        for path in files:
            cards = {}
            for row in parse_deckstring(path.read_bytes().decode("utf-8-sig")):
                if row["section"] not in ("sideboard", "maybeboard"):
                    cards[row["name"]] = cards.get(row["name"], 0) + row["quantity"]
            p = svc.profile_deck(path.stem, cards)
            for role in R.ROLES:
                if role == "land":
                    continue
                pile = p.size - p.lands - (0 if role == "mana" else p.rocks)
                with self.subTest(deck=path.stem, role=role):
                    self.assertLessEqual(sum(p.on_curve_mv[role].values()), pile)


if __name__ == "__main__":
    unittest.main()
