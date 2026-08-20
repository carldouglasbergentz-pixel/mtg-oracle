"""Card classification and effective cost, against real cards in the database.

These tests read `data/mtg.db`. Cards are named rather than synthesised
because the point is that the derivation survives real oracle text — the two
bugs it has already had (a modal DFC's land back reading as a Mox, and a
preparation card's inset frame read as castable) were both invisible on
hand-written fixtures.

Run: python -m unittest discover tests
"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from mtg_oracle import queries as q  # noqa: E402
from mtg_oracle import roles as R  # noqa: E402

DB = Path(__file__).parent.parent / "data" / "mtg.db"


@unittest.skipUnless(DB.exists(), "needs data/mtg.db — run scripts/sync.py")
class RolesTestCase(unittest.TestCase):
    """Shared card cache so the whole module costs one query."""

    NAMES = [
        # counters
        "Counterspell", "Force of Will", "Force of Negation", "Mental Misstep",
        "Cryptic Command", "Archmage's Charm", "Daze", "Subtlety", "Reprieve",
        # removal
        "Swords to Plowshares", "Path to Exile", "Get Lost", "Solitude",
        "Council's Judgment", "Prismatic Ending", "Unexpectedly Absent",
        "Brazen Borrower", "Sink into Stupor",
        # sweepers
        "Wrath of God", "Supreme Verdict", "Terminus", "Wrath of the Skies",
        "Farewell", "Ondu Inversion",
        # selection and advantage
        "Brainstorm", "Ponder", "Opt", "Impulse", "Stock Up", "Brainsurge",
        "Ancestral Recall", "Dig Through Time", "Treasure Cruise",
        "Fact or Fiction", "Sphinx's Revelation", "Deduce",
        "Consult the Star Charts", "Sea Gate Restoration",
        # threats
        "Snapcaster Mage", "Murktide Regent", "Jace, the Mind Sculptor",
        "Teferi, Hero of Dominaria", "Teferi, Time Raveler", "Shark Typhoon",
        "Wan Shi Tong, Librarian", "Entreat the Angels", "Quantum Riddler",
        "Approach of the Second Sun", "Emeritus of Ideation",
        "Tamiyo, Inquisitive Student",
        # mana, tutors, lands
        "Mox Sapphire", "Sol Ring", "Mystical Tutor", "Waterlogged Teachings",
        "Island", "Hallowed Fountain", "Celestial Colonnade", "Hengegate Pathway",
        "Wasteland", "Flooded Strand",
        # Red removal and split cards: the UW lists kill by exiling, so these
        # shapes were all missing until a red deck was run through the module.
        "Lightning Bolt", "Flame Slash", "Pyrokinesis", "Galvanic Discharge",
        "Magmatic Sinkhole", "Fire // Ice", "Brotherhood's End",
        "Back to Basics", "Blood Moon",
    ]

    @classmethod
    def setUpClass(cls):
        cls.facts = q.get_card_facts(cls.NAMES)
        missing = [n for n in cls.NAMES if n not in cls.facts]
        if missing:
            raise unittest.SkipTest(f"cards not in this database: {missing}")

    def cl(self, name):
        return R.classify(self.facts[name])

    def mv(self, name):
        return self.cl(name).cost.effective

    def primary(self, name):
        return self.cl(name).primary


class TestLandDetection(RolesTestCase):
    def test_basics_and_duals(self):
        for n in ("Island", "Hallowed Fountain", "Celestial Colonnade",
                  "Wasteland", "Flooded Strand", "Hengegate Pathway"):
            self.assertTrue(R.is_land(self.facts[n]), n)

    def test_a_spell_with_a_land_back_is_not_a_land(self):
        """`Sorcery // Land` is a spell. `"Land" in type_line` said otherwise."""
        for n in ("Ondu Inversion", "Sea Gate Restoration",
                  "Sink into Stupor", "Waterlogged Teachings"):
            self.assertFalse(R.is_land(self.facts[n]), n)
            self.assertTrue(R.has_land_back(self.facts[n]), n)

    def test_a_pathway_is_a_land_not_a_land_back(self):
        self.assertTrue(R.is_land(self.facts["Hengegate Pathway"]))
        self.assertFalse(R.has_land_back(self.facts["Hengegate Pathway"]))

    def test_woodland_is_not_a_land_type(self):
        """`Land` must match as a card type, not as a substring."""
        self.assertFalse(R._is_land_word("Creature — Woodland Bear"))
        self.assertTrue(R._is_land_word("Land — Forest"))


class TestPrimaryRoles(RolesTestCase):
    CASES = {
        "Counterspell": "counter", "Force of Will": "counter",
        "Cryptic Command": "counter", "Archmage's Charm": "counter",
        "Swords to Plowshares": "spot", "Get Lost": "spot",
        "Solitude": "spot", "Council's Judgment": "spot",
        "Brazen Borrower": "spot", "Sink into Stupor": "spot",
        "Wrath of God": "sweeper", "Supreme Verdict": "sweeper",
        "Terminus": "sweeper", "Farewell": "sweeper",
        "Ondu Inversion": "sweeper",
        "Brainstorm": "cantrip", "Ponder": "cantrip", "Opt": "cantrip",
        "Impulse": "cantrip",
        "Ancestral Recall": "draw", "Stock Up": "draw", "Brainsurge": "draw",
        "Dig Through Time": "draw", "Fact or Fiction": "draw",
        "Sphinx's Revelation": "draw", "Deduce": "draw",
        "Sea Gate Restoration": "draw",
        "Snapcaster Mage": "threat", "Murktide Regent": "threat",
        "Jace, the Mind Sculptor": "threat",
        "Teferi, Hero of Dominaria": "threat",
        "Approach of the Second Sun": "threat",
        "Entreat the Angels": "threat", "Shark Typhoon": "threat",
        "Wan Shi Tong, Librarian": "threat",
        "Mox Sapphire": "mana", "Sol Ring": "mana",
        "Mystical Tutor": "tutor", "Waterlogged Teachings": "tutor",
        "Teferi, Time Raveler": "utility",
        "Island": "land", "Wasteland": "land",
    }

    def test_primary_roles(self):
        for name, expect in self.CASES.items():
            with self.subTest(card=name):
                self.assertEqual(self.primary(name), expect)

    def test_planeswalkers_are_threats_not_removal(self):
        """Jace's -12 exiles a library; that does not make him a sweeper."""
        jace = self.cl("Jace, the Mind Sculptor")
        self.assertEqual(jace.primary, "threat")
        self.assertIn("spot", jace.roles)

    def test_a_modal_counter_also_counts_as_card_advantage(self):
        """Four mana that counters AND draws is a two-for-one."""
        for n in ("Cryptic Command", "Archmage's Charm"):
            self.assertIn("draw", self.cl(n).roles, n)

    def test_removal_that_leaves_a_body_is_still_removal(self):
        s = self.cl("Solitude")
        self.assertEqual(s.primary, "spot")
        self.assertIn("threat", s.roles)

    def test_every_card_gets_at_least_one_role(self):
        for name in self.NAMES:
            self.assertTrue(self.cl(name).roles, name)

    def test_nothing_lands_on_utility_by_accident(self):
        """Utility is the fallback, so an unexpected one means a missed rule."""
        expected_utility = {"Teferi, Time Raveler", "Back to Basics", "Blood Moon"}
        got = {n for n in self.NAMES if self.primary(n) == "utility"}
        self.assertEqual(got, expected_utility)


class TestEffectiveMana(RolesTestCase):
    def test_pitch_costs_are_free(self):
        for n in ("Force of Will", "Force of Negation", "Daze", "Solitude",
                  "Subtlety"):
            self.assertEqual(self.mv(n), 0, n)

    def test_phyrexian_mana_is_free(self):
        self.assertEqual(self.mv("Mental Misstep"), 0)

    def test_delve_is_priced_at_its_coloured_pips(self):
        self.assertEqual(self.mv("Dig Through Time"), 2)   # {6}{U}{U}
        self.assertEqual(self.mv("Treasure Cruise"), 2)    # {7}{U}, yard of 6

    def test_x_spells_are_priced_at_the_convention(self):
        self.assertEqual(self.mv("Sphinx's Revelation"), 5)   # {X}{W}{U}{U}
        self.assertEqual(self.mv("Wrath of the Skies"), 4)    # {X}{W}{W}

    def test_double_x_is_counted_twice(self):
        """Entreat the Angels is {X}{X}{W}{W}{W}: X=2 costs seven, not five."""
        self.assertEqual(self.mv("Entreat the Angels"), 7)

    def test_an_x_creature_with_a_printed_body_is_castable_at_x_zero(self):
        """Wan Shi Tong is a 1/1 for {U}{U}; X only buys counters on top."""
        self.assertEqual(self.mv("Wan Shi Tong, Librarian"), 2)

    def test_warp_is_castable_from_hand(self):
        """CR 702.185a — warp is an alternative cost paid from hand."""
        self.assertEqual(self.mv("Quantum Riddler"), 2)   # warp {1}{U}

    def test_a_cheaper_adventure_half_counts(self):
        self.assertEqual(self.mv("Brazen Borrower"), 2)   # Petty Theft {1}{U}

    def test_miracle_is_NOT_applied_but_is_reported(self):
        """A pitch cost is reliable; miracle depends on draw order.

        Costing Terminus at {W} would claim a UW deck can wrath on turn one.
        """
        c = self.cl("Terminus").cost
        self.assertEqual(c.effective, 6)
        self.assertEqual(c.alternative, 1)
        self.assertIn("miracle", c.alternative_reason)

    def test_prepare_is_never_cast_for_its_inset_frame(self):
        """CR 722.3. Emeritus of Ideation is always at least a five-drop.

        The inset frame is literally `Ancestral Recall` for {U}, which is why
        assuming adventure-like behaviour turned it into a one-mana draw-three.
        """
        self.assertNotIn("prepare", R.CASTABLE_SECOND_FACE)
        self.assertEqual(self.mv("Emeritus of Ideation"), 5)

    def test_transform_backs_are_not_castable(self):
        self.assertNotIn("transform", R.CASTABLE_SECOND_FACE)

    def test_unadjusted_cards_keep_their_printed_cost(self):
        for n in ("Counterspell", "Wrath of God", "Snapcaster Mage",
                  "Swords to Plowshares", "Brainstorm"):
            c = self.cl(n).cost
            self.assertEqual(c.effective, c.printed, n)
            self.assertFalse(c.adjusted, n)


class TestDamageRemovalAndSplitCards(RolesTestCase):
    """The shapes a mono-white sample never contains.

    Every one of these landed in `utility` the first time a red deck was
    analysed, which is what the low-confidence flag is for.
    """

    def test_damage_to_a_target_is_removal(self):
        for n in ("Lightning Bolt", "Flame Slash", "Magmatic Sinkhole"):
            self.assertEqual(self.primary(n), "spot", n)

    def test_damage_divided_among_targets_is_removal(self):
        """Pyrokinesis never says "damage to target"."""
        self.assertEqual(self.primary("Pyrokinesis"), "spot")

    def test_damage_paid_with_energy_is_removal(self):
        """Galvanic Discharge puts three sentences between target and damage."""
        self.assertEqual(self.primary("Galvanic Discharge"), "spot")

    def test_pyrokinesis_is_free(self):
        self.assertEqual(self.mv("Pyrokinesis"), 0)

    def test_delve_makes_magmatic_sinkhole_cheap(self):
        self.assertEqual(self.mv("Magmatic Sinkhole"), 1)   # {4}{B/R}... delve

    def test_a_split_card_costs_its_cheaper_half(self):
        """Scryfall's mana value for a split card is the SUM of both halves.

        Fire // Ice reads as a four-drop until the cheaper half is taken.
        """
        self.assertIn("split", R.CASTABLE_SECOND_FACE)
        c = self.cl("Fire // Ice").cost
        self.assertEqual(c.printed, 4)
        self.assertEqual(c.effective, 2)

    def test_a_split_card_gets_roles_from_both_halves(self):
        cl = self.cl("Fire // Ice")
        self.assertEqual(cl.primary, "spot")      # Fire
        self.assertIn("cantrip", cl.roles)        # Ice draws a card

    def test_damage_to_each_creature_is_a_sweeper_not_spot(self):
        self.assertEqual(self.primary("Brotherhood's End"), "sweeper")

    def test_lock_pieces_stay_utility(self):
        """Back to Basics and Blood Moon really are utility, not a miss."""
        for n in ("Back to Basics", "Blood Moon"):
            self.assertEqual(self.primary(n), "utility", n)


class TestOverrides(RolesTestCase):
    def test_overrides_resolve_for_two_faced_cards(self):
        """The table is keyed on the front face; the database stores `A // B`."""
        for n in ("Brazen Borrower", "Emeritus of Ideation"):
            self.assertEqual(self.cl(n).source, "override", n)

    def test_every_override_names_a_real_card(self):
        facts = q.get_card_facts(list(R.OVERRIDES))
        missing = sorted(set(R.OVERRIDES) - set(facts))
        self.assertEqual(missing, [], f"overrides for unknown cards: {missing}")

    def test_every_override_is_well_formed(self):
        for name, entry in R.OVERRIDES.items():
            with self.subTest(card=name):
                primary, extra, mv, reason = entry
                self.assertIn(primary, R.ROLES)
                self.assertTrue(all(r in R.ROLES for r in extra), extra)
                self.assertTrue(mv is None or isinstance(mv, int))
                self.assertIsInstance(reason, str)

    def test_every_role_has_a_label(self):
        for role in R.ROLES:
            self.assertIn(role, R.LABELS)

    def test_primary_order_covers_every_role(self):
        covered = set(R.PRIMARY_ORDER) - {"planeswalker"}
        self.assertEqual(covered, set(R.ROLES))


if __name__ == "__main__":
    unittest.main()
