"""The analysis renderers, `rank_cards`, and the command surfaces.

`profile` and `compare` exist in three places — the TUI, the CLI and
`scripts/analyse_archetype.py` — and the whole point of moving the tables
into `renderer` was that all three render the same bytes. What is asserted
here is the shape of that output and the wiring, not the numbers: those are
`test_compare_export.py`'s job.

Run: python -m unittest discover tests
"""
import ast
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from mtg_oracle import renderer as r  # noqa: E402
from mtg_oracle import roles as R  # noqa: E402
from mtg_oracle import services as svc  # noqa: E402
from mtg_oracle.deck_parser import parse_deckstring  # noqa: E402

DB = Path(__file__).parent.parent / "data" / "mtg.db"
SAMPLES = Path(__file__).parent.parent / "docs" / "sample decklists-uw canlander"

ORDER = [x for x in R.ROLES if x != "land"]
TURNS = list(range(1, 9))
FMT = {"role_labels": R.LABELS, "role_order": ORDER, "turns": TURNS}


def read_list(path: Path) -> dict:
    cards: dict[str, int] = {}
    for row in parse_deckstring(path.read_bytes().decode("utf-8-sig")):
        if row["section"] == "sideboard":
            continue
        cards[row["name"]] = cards.get(row["name"], 0) + row["quantity"]
    return {"name": path.stem, "cards": cards}


class TestRendererIsPure(unittest.TestCase):
    """No database, no fixtures — synthesised profiles are enough."""

    class FakeProfile:
        """The attributes the renderers actually read, and nothing else.

        Duck-typing is the contract: `renderer` must not need `services`
        imported to format its dataclasses.
        """
        def __init__(self, name, counts, size=100):
            self.name = name
            self.counts = counts
            self.size = size
            self.role_mv = {k: {2: v} for k, v in counts.items() if v}
            self.engines = {}
            self.unresolved = ()
            self.mana_sources = counts.get("land", 0)
            self.avg_mv = 2.0

        def live_curve(self, role, turns, on_play=True):
            return {t: 0.5 for t in turns}

        def ceiling(self, role, turns, on_play=True):
            return {t: 0.6 for t in turns}

    def profile(self, name="Test", **counts):
        base = {k: 0 for k in R.ROLES}
        base.update(counts)
        return self.FakeProfile(name, base)

    def test_renders_without_a_database(self):
        out = r.render_profile([self.profile(counter=19, land=39)], **FMT)
        self.assertIn("=== DENSITY", out)
        self.assertIn("=== REACH", out)
        self.assertIn("=== ON CURVE", out)
        self.assertIn("=== CEILING", out)
        self.assertIn("Counterspells", out)

    def test_a_role_no_list_plays_is_omitted_from_density(self):
        """Fourteen roles in a nine-column table is unreadable noise."""
        out = r.render_profile([self.profile(counter=19, land=39)], **FMT)
        density = out.split("=== REACH")[0]
        self.assertIn("Counterspells", density)
        self.assertNotIn("Rituals", density)
        # ...but reach reports every role, so nothing goes unmeasured.
        self.assertIn("Rituals", out.split("=== REACH")[1])

    def test_land_is_the_last_role_row(self):
        """It is the one row that is not a spell, so it sits under them —
        above the MANA SOURCES and avg-MV summary lines that close the table."""
        density = r.render_profile([self.profile(counter=19, land=39)],
                                   **FMT).split("=== REACH")[0]
        roles_block = density.split("MANA SOURCES")[0].splitlines()
        rows = [ln for ln in roles_block if ln[:1].isalpha() and "role" not in ln]
        self.assertTrue(rows[-1].startswith("Lands"), rows[-1])
        self.assertIn("MANA SOURCES", density)
        self.assertIn("avg effective MV", density)

    def test_low_confidence_cards_are_listed(self):
        out = r.render_profile([self.profile(land=39)],
                               low_confidence={"Skateboard", "Mana Short"},
                               **FMT)
        self.assertIn("2 card(s) fell through", out)
        # Sorted, so the list is stable between runs.
        self.assertLess(out.index("Mana Short"), out.index("Skateboard"))

    def test_no_low_confidence_means_no_section(self):
        out = r.render_profile([self.profile(land=39)], **FMT)
        self.assertNotIn("fell through", out)

    def test_ranking_groups_by_effective_cost(self):
        ranking = {"counter": [
            {"name": "Force of Will", "n": 9, "mv": 1, "cost": "{3}{U}{U}",
             "primary": True, "reason": "free alternative cost"},
            {"name": "Counterspell", "n": 8, "mv": 2, "cost": "{U}{U}",
             "primary": True, "reason": ""},
        ]}
        out = r.render_ranking(ranking, 9, **{k: v for k, v in FMT.items()
                                             if k != "turns"})
        self.assertIn("MV 1", out)
        self.assertIn("MV 2", out)
        self.assertLess(out.index("MV 1"), out.index("MV 2"))
        self.assertIn("[free alternative cost]", out)

    def test_ranking_marks_secondary_roles(self):
        ranking = {"burn": [
            {"name": "Lightning Bolt", "n": 9, "mv": 1, "cost": "{R}",
             "primary": False, "reason": ""}]}
        out = r.render_ranking(ranking, 9, **{k: v for k, v in FMT.items()
                                             if k != "turns"})
        self.assertIn("(secondary)", out)

    def test_empty_ranking_renders_a_header_and_nothing_else(self):
        out = r.render_ranking({}, 0, **{k: v for k, v in FMT.items()
                                        if k != "turns"})
        self.assertEqual(out.strip().count("\n"), 0)

    def test_the_tables_are_pure_ascii(self):
        """The module docstring promises it, and a legacy Windows console
        makes it load-bearing: an em-dash in a header raised
        UnicodeEncodeError on cp437 / cp850 and killed `deck profile`
        outright. Card names carrying diacritics are a separate matter —
        `Lorien Revealed` genuinely cannot be spelled in cp437 — but the
        chrome around them has no excuse.
        """
        out = r.render_profile([self.profile(counter=19, land=39)],
                               low_confidence={"Skateboard"}, **FMT)
        out += r.render_ranking(
            {"counter": [{"name": "Counterspell", "n": 1, "mv": 2,
                          "cost": "{U}{U}", "primary": True, "reason": "x"}]},
            1, **{k: v for k, v in FMT.items() if k != "turns"})
        try:
            out.encode("ascii")
        except UnicodeEncodeError as e:
            self.fail(f"non-ASCII in the analysis tables: {e}")


@unittest.skipUnless(DB.exists() and SAMPLES.is_dir(),
                     "needs data/mtg.db and the sample decklists")
class TestComparisonRendering(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        files = sorted(SAMPLES.glob("*.txt"))
        if len(files) < 3:
            raise unittest.SkipTest("need at least three sample lists")
        cls.decks = [read_list(f) for f in files]

    def test_many_references_report_ranges(self):
        cmp = svc.compare_decks(self.decks[0], self.decks[1:], turns=TURNS)
        out = r.render_comparison(cmp, **FMT)
        self.assertIn("=== IN RANGE?", out)
        self.assertIn("NEAREST REFERENCE LIST", out)
        self.assertIn("COMPARISON", out)
        self.assertNotIn("HEAD TO HEAD", out)

    def test_one_reference_reports_a_head_to_head_instead(self):
        """A range built from one list is a point, so every difference would
        read as stepping outside it. That verdict would be pure noise."""
        cmp = svc.compare_decks(self.decks[0], [self.decks[1]], turns=TURNS)
        out = r.render_comparison(cmp, **FMT)
        self.assertIn("HEAD TO HEAD", out)
        self.assertIn("=== ROLE COUNTS", out)
        self.assertNotIn("IN RANGE?", out)
        self.assertNotIn("ABOVE every list", out)
        self.assertNotIn("BELOW every list", out)
        self.assertNotIn("CAUTION", out)
        self.assertIn("theirs", out)

    def test_head_to_head_still_lists_the_card_diff(self):
        """The card-level diff is the actionable half; it must survive."""
        cmp = svc.compare_decks(self.decks[0], [self.decks[1]], turns=TURNS)
        out = r.render_comparison(cmp, **FMT)
        self.assertIn("CARDS THEY PLAY THAT THIS DECK DOESN'T", out)
        # With one reference, "played by more than one list" would hide all
        # of them, so the filter has to stand down.
        if cmp.missing:
            self.assertIn(cmp.missing[0].name, out)

    def test_the_comparison_is_pure_ascii_too(self):
        cmp = svc.compare_decks(self.decks[0], self.decks[1:], turns=TURNS)
        out = r.render_comparison(cmp, **FMT)
        # Card names may legitimately carry diacritics; the chrome may not.
        chrome = "\n".join(ln for ln in out.splitlines()
                           if ln.lstrip().startswith(("=", "-", "(")) or "===" in ln)
        try:
            chrome.encode("ascii")
        except UnicodeEncodeError as e:
            self.fail(f"non-ASCII in the comparison chrome: {e}")

    def test_comparing_to_self_deviates_nowhere(self):
        cmp = svc.compare_decks(self.decks[0], [self.decks[0]], turns=TURNS)
        out = r.render_comparison(cmp, **FMT)
        self.assertNotIn("CARDS THEY PLAY", out)
        self.assertNotIn("ONLY THIS DECK PLAYS", out)
        self.assertIn("role-density distance: 0.00", out)


@unittest.skipUnless(DB.exists() and SAMPLES.is_dir(),
                     "needs data/mtg.db and the sample decklists")
class TestRankCards(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        files = sorted(SAMPLES.glob("*.txt"))
        if not files:
            raise unittest.SkipTest("no sample lists")
        cls.decks = [read_list(f) for f in files]
        cls.ranking, cls.low = svc.rank_cards(cls.decks, ORDER)

    def test_no_deck_is_not_a_crash(self):
        ranking, low = svc.rank_cards([], ORDER)
        self.assertEqual(low, set())
        self.assertEqual(sum(len(v) for v in ranking.values()), 0)

    def test_every_requested_role_is_a_key(self):
        self.assertEqual(set(self.ranking), set(ORDER))

    def test_lands_are_never_ranked(self):
        """`1/9 Snow-Covered Swamp` is not a finding; profile_deck counts them."""
        names = {row["name"] for rows in self.ranking.values() for row in rows}
        self.assertNotIn("Island", names)
        self.assertNotIn("Flooded Strand", names)

    def test_rows_are_sorted_by_cost_then_popularity(self):
        for role, rows in self.ranking.items():
            with self.subTest(role=role):
                keys = [(x["mv"], -x["n"], x["name"]) for x in rows]
                self.assertEqual(keys, sorted(keys))

    def test_a_card_is_counted_once_per_list_that_plays_it(self):
        for rows in self.ranking.values():
            for row in rows:
                self.assertLessEqual(row["n"], len(self.decks))
                self.assertGreaterEqual(row["n"], 1)

    def test_low_confidence_names_are_real_cards_in_the_lists(self):
        played = {n for d in self.decks for n in d["cards"]}
        self.assertTrue(self.low <= played, self.low - played)


class TestCommandSurfaces(unittest.TestCase):
    """Wiring: a command with no help is a command nobody finds."""

    APP = Path(__file__).parent.parent / "mtg_oracle" / "tui" / "app.py"

    def tui_commands(self) -> set[str]:
        """The keys of the handler dict in `_dispatch`, read from source.

        Parsed rather than imported so the check needs no Textual app running.
        """
        tree = ast.parse(self.APP.read_text(encoding="utf-8"))
        found: set[str] = set()
        for node in ast.walk(tree):
            if isinstance(node, ast.Dict):
                keys = [k.value for k in node.keys
                        if isinstance(k, ast.Constant) and isinstance(k.value, str)]
                # The dispatch table is the one holding the navigation verbs.
                if {"card", "search", "cd"} <= set(keys):
                    found.update(keys)
        return found

    def test_the_dispatch_table_was_found(self):
        self.assertIn("card", self.tui_commands())

    def test_profile_and_compare_are_registered(self):
        cmds = self.tui_commands()
        self.assertIn("profile", cmds)
        self.assertIn("compare", cmds)

    def test_every_tui_command_is_offered_by_autofill(self):
        from mtg_oracle.tui.help import COMMANDS
        # Aliases deliberately absent from the completion list.
        aliases = {"rulings", "corrections", "?", "q", "exit"}
        missing = self.tui_commands() - set(COMMANDS) - aliases
        self.assertEqual(missing, set(),
                         f"commands with no autofill entry: {sorted(missing)}")

    def test_the_deck_help_topic_documents_them(self):
        from mtg_oracle.tui.help import HELP_TOPICS
        decks = HELP_TOPICS["decks"]
        self.assertIn("profile", decks)
        self.assertIn("compare", decks)

    def test_the_cli_offers_the_same_two_actions(self):
        """Three surfaces, one capability. A gap here is the bug this file
        exists to catch — analysis lived only in the script for a while."""
        src = (Path(__file__).parent.parent / "scripts" / "mtg_cli.py"
               ).read_text(encoding="utf-8")
        self.assertIn('"profile"', src)
        self.assertIn('"compare"', src)
        self.assertIn("--against", src)


if __name__ == "__main__":
    unittest.main()
