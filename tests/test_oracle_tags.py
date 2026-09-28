"""Scryfall Tagger oracle tags: the mapping, and what they change.

Two halves. `TestTagMapping` is pure — it feeds label sets straight into
`roles.roles_from_tags` and needs no database, so the precedence rules stay
pinned even on a fresh checkout. The rest reads `data/mtg.db` and asserts on
the cards that made each rule necessary.

Run: python -m unittest discover tests
"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from mtg_oracle import queries as q  # noqa: E402
from mtg_oracle import roles as R  # noqa: E402

DB = Path(__file__).parent.parent / "data" / "mtg.db"


class TestTagMapping(unittest.TestCase):
    """`roles_from_tags` in isolation — no database needed."""

    def test_no_tags_means_no_roles(self):
        """An empty result is the signal to fall back to the text rules."""
        self.assertEqual(R.roles_from_tags(()), set())
        self.assertEqual(R.roles_from_tags(None), set())

    def test_unmapped_tags_mean_no_roles(self):
        """Most of the 4,500 labels are flavour, not function."""
        self.assertEqual(
            R.roles_from_tags(["single english word name", "cycle", "alliteration"]),
            set())

    def test_hate_beats_the_family_it_hates(self):
        """Pyroblast fights counterspells via `hate-blue`; it is not one.

        `hate-` is checked before `counterspell`, so the substring cannot
        promote a hate piece into the family it attacks.
        """
        self.assertEqual(R.roles_from_tags(["hate-counterspell"]), {"utility"})
        self.assertEqual(R.roles_from_tags(["hate-graveyard"]), {"utility"})

    def test_counterspell_family_matches_by_prefix(self):
        for label in ("counterspell", "counterspell-soft", "counterspell-creature"):
            self.assertEqual(R.roles_from_tags([label]), {"counter"}, label)

    def test_graveyard_sweeper_is_not_a_wrath(self):
        """Rest in Peace is tagged `sweeper-graveyard` and touches no board.

        The exact table is consulted before the `sweeper` prefix precisely so
        this one card family can opt out of its own name.
        """
        self.assertEqual(R.roles_from_tags(["sweeper-graveyard"]), {"utility"})
        self.assertEqual(R.roles_from_tags(["sweeper"]), {"sweeper"})

    def test_burn_at_a_face_is_a_clock_burn_at_a_creature_is_removal(self):
        self.assertEqual(R.roles_from_tags(["burn player"]), {"burn"})
        self.assertEqual(R.roles_from_tags(["burn player-each"]), {"burn"})
        self.assertEqual(R.roles_from_tags(["burn creature"]), {"spot", "burn"})
        self.assertEqual(R.roles_from_tags(["burn any"]), {"spot", "burn"})

    def test_roles_accumulate_across_labels(self):
        got = R.roles_from_tags(["counterspell", "spot removal", "pure draw"])
        self.assertEqual(got, {"counter", "spot", "draw"})

    def test_an_empty_exact_entry_stops_the_prefix_rules(self):
        """`deanimate self` starts with `deanimate`, which means removal."""
        self.assertEqual(R.roles_from_tags(["deanimate"]), {"spot"})
        self.assertEqual(R.roles_from_tags(["deanimate self"]), set())

    def test_returning_itself_vetoes_recursion_for_the_whole_card(self):
        """Gravecrawler carries `reanimate-self` AND `reanimate-cast`.

        The veto has to see the whole set: suppressing only its own label
        would leave `reanimate-cast` to make a recursive one-drop into a
        recursion spell.
        """
        self.assertEqual(
            R.roles_from_tags(["reanimate-self", "reanimate-cast"]), set())
        self.assertEqual(R.roles_from_tags(["reanimate-cast"]), {"recursion"})

    def test_the_veto_only_removes_recursion(self):
        """A self-recurring Aura that also makes mana is still a mana source."""
        self.assertEqual(
            R.roles_from_tags(["reanimate-self", "mana dork"]), {"mana"})

    def test_regrowth_to_hand_is_recursion_too(self):
        self.assertEqual(R.roles_from_tags(["regrowth-creature"]), {"recursion"})
        self.assertEqual(R.roles_from_tags(["regrowth-self"]), set())

    def test_every_silenced_label_is_a_recursion_label(self):
        """TAG_SUPPRESS silences labels, not roles — see its comment.

        A typo would silence nothing; a label outside the recursion family
        would silently take an unrelated role from the card.
        """
        for label, silenced in R.TAG_SUPPRESS.items():
            for other in silenced:
                self.assertEqual(R.roles_from_tags([other]), {"recursion"},
                                 f"{label} silences {other}")

    def test_every_mapped_role_is_a_real_role(self):
        """A typo in the tables would silently drop the role in `counts`."""
        for label, implied in R.TAG_EXACT_ROLES.items():
            for role in implied:
                self.assertIn(role, R.ROLES, f"{label} -> {role}")
        for prefix, implied in R.TAG_PREFIX_ROLES:
            for role in implied:
                self.assertIn(role, R.ROLES, f"{prefix} -> {role}")

    def test_prefix_order_puts_hate_first(self):
        """Order is load-bearing; the first matching prefix wins and stops."""
        self.assertEqual(R.TAG_PREFIX_ROLES[0][0], "hate-")

    def test_there_is_no_loose_burn_prefix(self):
        """A `burn ` prefix swept up flavour tags and made Lava Spike removal."""
        prefixes = [p for p, _ in R.TAG_PREFIX_ROLES]
        self.assertNotIn("burn ", prefixes)

    def test_flavour_burn_tags_imply_nothing(self):
        """`burn with set's mechanic` describes Arcane, not what the card hits.

        `burn-you` and `burn-self` are drawbacks — Fireblast damaging its own
        controller is not a role.
        """
        for label in ("burn with set's mechanic", "burn bright with set mechanic",
                      "burn-you", "burn-self"):
            self.assertEqual(R.roles_from_tags([label]), set(), label)

    def test_player_or_planeswalker_burn_is_not_creature_removal(self):
        """Lava Spike's only targets. It cannot answer a creature, so it is a clock."""
        self.assertEqual(R.roles_from_tags(["burn planeswalker"]), {"burn"})


@unittest.skipUnless(DB.exists(), "needs data/mtg.db — run scripts/sync.py")
class OracleTagTestCase(unittest.TestCase):
    """Shared cache: one facts query and one tags query for the module."""

    NAMES = [
        # Hand attack — the whole role was missing before tags existed.
        "Thoughtseize", "Hymn to Tourach", "Duress", "Grief",
        # Burn at a face vs. burn that happens to be able to go there.
        "Lava Spike", "Flame Rift", "Lightning Bolt", "Fireblast",
        "Eidolon of the Great Revel", "Flame Jet", "Boros Charm",
        # Bites: `burn planeswalker` + `spot removal`, and real removal.
        "Bite Down", "Flourishing Grapple", "Kabira Takedown // Kabira Plateau",
        # Rituals and mana that no text rule caught.
        "High Tide", "Turnabout", "Dark Ritual",
        "Utopia Sprawl", "Wild Growth", "Sapphire Medallion",
        # Reanimation, and the self-recurring creatures it must not swallow.
        "Animate Dead", "Unearth", "Eternal Witness",
        "Gravecrawler", "Bloodsoaked Champion", "Scrapheap Scrounger",
        # Hate pieces that are correctly nothing but utility.
        "Rest in Peace", "Pithing Needle", "Tangle Wire",
        # Vehicles: creatures that need crewing, and the looting bug.
        "Smuggler's Copter", "Flywheel Racer", "High-Speed Hoverbike",
        # Removal that leaves the card on the battlefield.
        "Swift Reconfiguration",
        # Sanity anchors that must not move.
        "Counterspell", "Swords to Plowshares", "Brainstorm", "Wrath of God",
        "Sol Ring", "Island",
        # Removal and planeswalkers that Tagger also calls ramp.
        "Path to Exile", "Erode", "Teferi, Hero of Dominaria",
        "Birds of Paradise",
    ]

    @classmethod
    def setUpClass(cls):
        cls.facts = q.get_card_facts(cls.NAMES)
        missing = [n for n in cls.NAMES if n not in cls.facts]
        if missing:
            raise unittest.SkipTest(f"cards not in this database: {missing}")
        cls.tags = q.get_oracle_tags(cls.NAMES)
        if not cls.tags:
            raise unittest.SkipTest(
                "card_oracle_tags is empty — run scripts/sync_oracle_tags.py")

    def cl(self, name):
        return R.classify(self.facts[name], tags=self.tags.get(name, ()))

    def primary(self, name):
        return self.cl(name).primary

    def roles(self, name):
        return set(self.cl(name).roles)


class TestTagsFillTheGaps(OracleTagTestCase):
    """Roles the text rules had no pattern for at all."""

    CASES = {
        "Thoughtseize": "discard", "Hymn to Tourach": "discard",
        "Duress": "discard", "Grief": "discard",
        "Lava Spike": "burn", "Flame Rift": "burn",
        "High Tide": "ritual", "Turnabout": "ritual", "Dark Ritual": "ritual",
        "Utopia Sprawl": "mana", "Wild Growth": "mana",
        "Sapphire Medallion": "mana",
        "Animate Dead": "recursion", "Unearth": "recursion",
        "Eternal Witness": "recursion",
    }

    def test_a_creature_that_recurs_itself_stays_a_threat(self):
        """These are the aggro one-drops. Ten of them read as recursion spells."""
        for name in ("Gravecrawler", "Bloodsoaked Champion", "Scrapheap Scrounger"):
            with self.subTest(card=name):
                self.assertEqual(self.primary(name), "threat")
                self.assertNotIn("recursion", self.roles(name))

    def test_removal_that_ramps_the_OPPONENT_is_still_removal(self):
        """Tagger tags the drawback too, and `land ramp` fires on Path to
        Exile because the opponent gets the basic. With `mana` above `spot`,
        the format's premier white removal spell was a mana source."""
        for name in ("Path to Exile", "Erode"):
            with self.subTest(card=name):
                self.assertEqual(self.primary(name), "spot")
                # The ramp is the opponent's (`donate rampant growth`), so it
                # is not a secondary role either: it put Path in the tutor and
                # mana-source reach counts.
                self.assertEqual(self.roles(name), {"spot"})

    def test_a_planeswalker_that_untaps_lands_is_still_a_threat(self):
        """Teferi, Hero of Dominaria's `+1` untaps two lands. It read as a Mox."""
        self.assertEqual(self.primary("Teferi, Hero of Dominaria"), "threat")

    def test_a_real_mana_source_still_wins_its_primary(self):
        """The inverse guard: moving `mana` down must not demote the Moxen."""
        for name in ("Sol Ring", "Birds of Paradise", "Utopia Sprawl",
                     "Sapphire Medallion"):
            with self.subTest(card=name):
                self.assertEqual(self.primary(name), "mana")

    def test_a_ritual_is_never_counted_as_a_mana_source(self):
        """Dark Ritual is tagged `adds multiple mana` as well as `ritual`.

        If `mana` won the precedence it would become a rock, and the on-curve
        model would hand the deck a permanent extra mana from turn one.
        """
        for name in ("Dark Ritual", "High Tide", "Turnabout"):
            self.assertEqual(self.primary(name), "ritual", name)

    def test_primaries(self):
        for name, want in self.CASES.items():
            with self.subTest(card=name):
                self.assertEqual(self.primary(name), want)


class TestFaceBurnIsNotRemoval(OracleTagTestCase):
    """Tagger's `spot removal` also labels damage that only reaches players
    and planeswalkers (2026-09-28 export). Those cards are a clock."""

    def test_face_only_burn_is_burn(self):
        for name in ("Lava Spike", "Flame Jet", "Boros Charm"):
            with self.subTest(card=name):
                self.assertIn("spot removal", self.tags[name])
                self.assertNotIn("spot", self.roles(name))
                self.assertEqual(self.primary(name), "burn")

    def test_a_bite_is_still_removal(self):
        # `burn planeswalker` labels "target creature or planeswalker" too,
        # which is why the text has the last word here.
        for name in ("Bite Down", "Flourishing Grapple",
                     "Kabira Takedown // Kabira Plateau"):
            with self.subTest(card=name):
                self.assertIn("spot", self.roles(name))

    def test_burn_that_can_hit_a_creature_is_removal(self):
        self.assertEqual(self.primary("Lightning Bolt"), "spot")

    def test_the_veto_needs_the_spot_removal_label_to_be_alone(self):
        tags = frozenset({"spot removal", "burn player", "burn creature"})
        self.assertIn("spot", R.classify(self.facts["Lava Spike"], tags=tags).roles)


class TestTagsReplaceRatherThanUnion(OracleTagTestCase):
    def test_lava_spike_is_not_removal(self):
        """"3 damage to target player" matched the spot-removal pattern.

        This is the case that settled replacement over union: unioning the
        text roles back in would re-add `spot`, which is the wrong answer for
        a card that cannot point at a creature.
        """
        # Frozen tags, not the live ones: this tests the replace-not-union
        # rule, and Tagger's own labels for Lava Spike changed upstream on
        # 2026-09-28 (it gained `spot removal`), which is a data question.
        tags = frozenset({"burn player", "burn planeswalker",
                          "burn with set's mechanic", "lightning bolt redux",
                          "single target instant/sorcery"})
        verdict = R.classify(self.facts["Lava Spike"], tags=tags)
        self.assertNotIn("spot", verdict.roles)
        self.assertEqual(verdict.primary, "burn")
        self.assertEqual(verdict.source, "tagged")

    def test_a_bolt_is_removal_that_can_also_go_upstairs(self):
        self.assertEqual(self.roles("Lightning Bolt"), {"spot", "burn"})
        self.assertEqual(self.primary("Lightning Bolt"), "spot")

    def test_a_burning_creature_stays_a_creature(self):
        """Structural roles survive replacement — there is no `is a creature` tag."""
        self.assertIn("threat", self.roles("Eidolon of the Great Revel"))
        self.assertEqual(self.primary("Eidolon of the Great Revel"), "threat")

    def test_evoked_hand_attack_beats_its_own_body(self):
        """Grief is a 3/2 nobody casts; it is pitched to strip a card."""
        self.assertEqual(self.roles("Grief"), {"discard", "threat"})
        self.assertEqual(self.primary("Grief"), "discard")

    def test_a_land_stays_a_land(self):
        self.assertEqual(self.primary("Island"), "land")


class TestVehiclesAreThreats(OracleTagTestCase):
    """A Vehicle is a creature with an extra step, not a utility artifact."""

    def test_looting_is_not_hand_disruption(self):
        """Smuggler's Copter read as `discard` — its own loot trigger matched.

        Hand attack needs an opponent doing the discarding. Without that
        subject, every looter in every aggro deck counted as disruption.
        """
        self.assertEqual(self.primary("Smuggler's Copter"), "threat")
        self.assertNotIn("discard", self.roles("Smuggler's Copter"))

    def test_crewing_for_mana_is_not_a_mox(self):
        self.assertEqual(self.primary("Flywheel Racer"), "threat")

    def test_a_vehicle_with_no_other_text_is_still_a_threat(self):
        self.assertEqual(self.primary("High-Speed Hoverbike"), "threat")

    def test_the_discard_rule_still_catches_real_hand_attack(self):
        for name in ("Thoughtseize", "Hymn to Tourach", "Duress"):
            self.assertIn("discard", self.roles(name), name)


class TestUtilityIsNotAlwaysIgnorance(OracleTagTestCase):
    def test_a_tagged_hate_piece_is_utility_with_confidence(self):
        """`low_confidence` is a work queue for a human. A curated tag saying
        `hate-graveyard` is an answer, not a gap, so it must not land there."""
        for name in ("Rest in Peace", "Pithing Needle", "Tangle Wire"):
            with self.subTest(card=name):
                cl = self.cl(name)
                self.assertEqual(cl.primary, "utility")
                self.assertEqual(cl.source, "tagged")
                self.assertFalse(cl.low_confidence)

    def test_an_untagged_fall_through_is_still_flagged(self):
        """Synthetic card: no tags, no matching text. This must stay loud."""
        card = {"name": "Nonesuch", "type_line": "Artifact",
                "oracle_text": "Nonesuch enters tapped.", "mana_cost": "{2}",
                "mana_value": 2, "layout": "normal"}
        cl = R.classify(card)
        self.assertEqual(cl.primary, "utility")
        self.assertEqual(cl.source, "derived")
        self.assertTrue(cl.low_confidence)

    def test_deanimation_is_removal(self):
        """Swift Reconfiguration never says "destroy"; the tag does the work."""
        self.assertEqual(self.primary("Swift Reconfiguration"), "spot")


class TestAnchorsDidNotMove(OracleTagTestCase):
    """Cards the text rules already got right must not regress."""

    CASES = {"Counterspell": "counter", "Swords to Plowshares": "spot",
             "Brainstorm": "cantrip", "Wrath of God": "sweeper",
             "Sol Ring": "mana", "Fireblast": "spot"}

    def test_primaries(self):
        for name, want in self.CASES.items():
            with self.subTest(card=name):
                self.assertEqual(self.primary(name), want)


@unittest.skipUnless(DB.exists(), "needs data/mtg.db — run scripts/sync.py")
class TestGetOracleTags(unittest.TestCase):
    def test_empty_input(self):
        self.assertEqual(q.get_oracle_tags([]), {})

    def test_keyed_by_input_name_not_canonical(self):
        got = q.get_oracle_tags(["swords to plowshares"])
        self.assertIn("swords to plowshares", got)
        self.assertIn("removal-creature", got["swords to plowshares"])

    def test_front_face_name_resolves_to_the_whole_card(self):
        """`Fire` is half of `Fire // Ice`; tags live under the full name."""
        got = q.get_oracle_tags(["Fire // Ice", "Fire"])
        if "Fire // Ice" not in got:
            self.skipTest("Fire // Ice not in this database")
        self.assertEqual(got.get("Fire"), got["Fire // Ice"])

    def test_unknown_names_are_absent_not_empty(self):
        got = q.get_oracle_tags(["Definitely Not A Card", "Counterspell"])
        self.assertNotIn("Definitely Not A Card", got)
        self.assertIn("Counterspell", got)

    def test_returns_frozensets(self):
        got = q.get_oracle_tags(["Counterspell"])
        self.assertIsInstance(got["Counterspell"], frozenset)


if __name__ == "__main__":
    unittest.main()
