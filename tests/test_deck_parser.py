"""The deck parser, against the export shapes that actually broke it.

Every case in `TestCollectorTails` and `TestTypeGroupHeadings` came out of a
real file. They are here because the failure mode is silent: the parser
returns a plausible deck, the count even looks right, and a card is simply
gone. Thirteen mtgtop8 and Moxfield lists lost 99 phantom-added cards and 17
real ones between them before these rules existed.

Mostly database-free — the parser is pure text. The one class that needs
`data/mtg.db` is the guard that no real card name is damaged.

Run: python -m unittest discover tests
"""
import sqlite3
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from mtg_oracle import deck_parser as dp  # noqa: E402
from mtg_oracle.deck_parser import parse_deckstring  # noqa: E402

DB = Path(__file__).parent.parent / "data" / "mtg.db"


def one(text: str):
    """The single row `text` parses to, or None if it parses to nothing."""
    rows = parse_deckstring(text)
    return rows[0] if rows else None


def names(text: str) -> list[str]:
    return [r["name"] for r in parse_deckstring(text)]


class TestCollectorTails(unittest.TestCase):
    """`(SET) collector` in all the shapes exporters actually emit."""

    CASES = {
        # plain
        "1 Lightning Bolt (CLB) 146": "Lightning Bolt",
        "1 Lightning Bolt (clb) 146": "Lightning Bolt",
        "1 Brainstorm (CNS) 91 *F*": "Brainstorm",
        # The List keeps its original set inside the collector number
        "1 Mana Leak (PLST) DDN-64": "Mana Leak",
        "1 Counterspell (PLST) A25-50": "Counterspell",
        "1 Path to Exile (PLST) E02-3": "Path to Exile",
        "1 Temple of the False God (PLST) C18-285": "Temple of the False God",
        # alphanumeric collectors, including a single-letter prefix
        "1 Jace, the Mind Sculptor (MED) WS3": "Jace, the Mind Sculptor",
        "1 Snapcaster Mage (PUMA) U5": "Snapcaster Mage",
        # promo numbering by year
        "1 Farewell (PWCS) 2022-5": "Farewell",
        # a star marks a promo printing
        "1 Force Spike (7ED) 76★": "Force Spike",
        "1 Triumph of Saint Katherine (40K) 17★": "Triumph of Saint Katherine",
        # etched, which the named-marker list missed
        "1 The Wandering Emperor (NEO) 418 *E*": "The Wandering Emperor",
        # set code with no collector at all
        "1 Swords to Plowshares (STA)": "Swords to Plowshares",
    }

    def test_the_tail_is_stripped_and_the_name_survives(self):
        for line, want in self.CASES.items():
            with self.subTest(line=line):
                self.assertEqual(one(line)["name"], want)

    def test_a_parenthesised_part_of_a_NAME_is_kept(self):
        """There really are cards called `Unearth (Theme)`.

        Title Case inside the parentheses is the discriminator: real set codes
        are `CLB` / `7ED` / `PLST` / `40K`, never `Theme`. Without this,
        `Unearth (Theme)` resolved to the actual Unearth — a different card.
        """
        for line in ("1 Unearth (Theme)", "1 Hazmat Suit (Used)",
                     "1 Imaginary Friends (Plane)", "1 Kang Dynasty (Theme)"):
            with self.subTest(line=line):
                self.assertEqual(one(line)["name"], line.split(" ", 1)[1])

    def test_quantities_and_sections_are_untouched_by_the_tail(self):
        row = one("4 Lightning Bolt (PLST) DDN-64 *F*")
        self.assertEqual(row, {"name": "Lightning Bolt", "quantity": 4,
                               "section": "main", "set_code": "plst",
                               "collector_number": "DDN-64"})


class TestPrintingsAreKept(unittest.TestCase):
    """The tail is the printing the user chose; the art follows it."""

    CASES = {
        "1 Lightning Bolt (CLB) 146": ("clb", "146"),
        "1 Lightning Bolt (clb) 146": ("clb", "146"),
        "1 Brainstorm (CNS) 91 *F*": ("cns", "91"),
        "1 Mana Leak (PLST) DDN-64": ("plst", "DDN-64"),
        "1 Counterspell (PLST) A25-50": ("plst", "A25-50"),
        "1 Jace, the Mind Sculptor (MED) WS3": ("med", "WS3"),
        "1 Snapcaster Mage (PUMA) U5": ("puma", "U5"),
        "1 Farewell (PWCS) 2022-5": ("pwcs", "2022-5"),
        "1 Force Spike (7ED) 76★": ("7ed", "76★"),
        # Scryfall spells the star `★`; some exporters type `*`.
        "1 Force Spike (7ED) 76*": ("7ed", "76★"),
        "1 The Wandering Emperor (NEO) 418 *E*": ("neo", "418"),
        "1 Swords to Plowshares (STA)": ("sta", None),
        "1x Sol Ring (c21) 263 [Ramp] ^Have,#37d67a^": ("c21", "263"),
        "SB: 1 Pyroblast (PLST) A25-141": ("plst", "A25-141"),
    }

    def test_set_code_and_collector_number(self):
        for line, (set_code, number) in self.CASES.items():
            with self.subTest(line=line):
                row = one(line)
                self.assertEqual((row["set_code"], row["collector_number"]),
                                 (set_code, number))

    def test_no_printing_means_no_keys(self):
        for line in ("1 Sol Ring", "1 Unearth (Theme)", "Sol Ring x2",
                     "1 Hazmat Suit (Used)"):
            with self.subTest(line=line):
                self.assertNotIn("set_code", one(line))

    def test_an_all_digit_group_is_a_count_not_a_set(self):
        self.assertEqual(one("1 Island (15)"),
                         {"name": "Island", "quantity": 1, "section": "main"})


class TestTypeGroupHeadings(unittest.TestCase):
    """mtgtop8 groups the main deck by type, and its headings carry a count."""

    HEADINGS = ("40 LANDS (42)", "39 LANDS", "3 CREATURES", "7 OTHER SPELLS",
                "28 INSTANTS and SORC.", "12 ARTIFACTS", "2 PLANESWALKERS")

    def test_headings_are_not_cards(self):
        for line in self.HEADINGS:
            with self.subTest(line=line):
                self.assertEqual(parse_deckstring(line), [])

    def test_a_shouted_heading_and_a_title_case_card_are_told_apart(self):
        """There are cards named `Lands`, `Spells` and `Artifacts`.

        A closed vocabulary alone would have eaten them, so the heading must
        also be upper-case — which is how mtgtop8 writes it.
        """
        self.assertEqual(parse_deckstring("40 LANDS"), [])
        self.assertEqual(one("40 Lands")["name"], "Lands")
        self.assertEqual(parse_deckstring("2 SPELLS"), [])
        self.assertEqual(one("2 Spells")["name"], "Spells")
        self.assertEqual(one("1 Artifacts")["name"], "Artifacts")

    def test_a_card_whose_name_starts_with_a_group_word_is_safe(self):
        self.assertEqual(one("1 Creatures of the Deep")["name"],
                         "Creatures of the Deep")

    def test_ordinary_land_lines_still_parse(self):
        self.assertEqual(one("12 Island")["name"], "Island")
        self.assertEqual(one("40 Islands")["quantity"], 40)

    def test_the_phantom_cards_are_gone(self):
        """Four headings added 99 cards to a 100-card deck."""
        text = ("COMMANDER\n1 Elminster\n40 LANDS (42)\n1 Arid Mesa\n"
                "4 CREATURES\n1 Solitude\n5 OTHER SPELLS\n1 Stock Up\n")
        rows = parse_deckstring(text)
        self.assertEqual(sum(r["quantity"] for r in rows), 4)
        self.assertEqual(names(text),
                         ["Elminster", "Arid Mesa", "Solitude", "Stock Up"])

    def test_a_heading_ends_the_commander_block(self):
        """mtgtop8 has nothing else to say the commander is over.

        Preserving the section across the heading filed all 100 cards as
        commanders — a deck the app would refuse to reason about.
        """
        text = ("COMMANDER\n1 Elminster\n40 LANDS (42)\n1 Arid Mesa\n"
                "1 Island\n")
        rows = parse_deckstring(text)
        self.assertEqual([(r["name"], r["section"]) for r in rows],
                         [("Elminster", "commander"), ("Arid Mesa", "main"),
                          ("Island", "main")])

    def test_a_heading_inside_a_sideboard_keeps_its_section(self):
        """Only `commander` is ended by a heading — it is the one section a
        type group cannot belong to."""
        text = "Sideboard\n12 CREATURES\n1 Solitude\n"
        rows = parse_deckstring(text)
        self.assertEqual(rows, [{"name": "Solitude", "quantity": 1,
                                 "section": "sideboard"}])

    def test_is_type_group_is_exact_about_the_vocabulary(self):
        self.assertTrue(dp._is_type_group("40 LANDS (42)"))
        self.assertFalse(dp._is_type_group("40 GOBLINS"))
        self.assertFalse(dp._is_type_group("LANDS"))         # no count
        self.assertFalse(dp._is_type_group("1 Island"))


class TestRealExportsRoundTrip(unittest.TestCase):
    """Whole-file shapes, in miniature."""

    def test_moxfield_shape(self):
        text = ("1 Elminster (CLB) 274\n1 Brainstorm (MH2) 43 *F*\n"
                "\nSIDEBOARD:\n1 Pyroblast (PLST) A25-141\n")
        rows = parse_deckstring(text)
        self.assertEqual([(r["name"], r["section"]) for r in rows],
                         [("Elminster", "main"), ("Brainstorm", "main"),
                          ("Pyroblast", "sideboard")])

    def test_mtgtop8_shape(self):
        text = ("COMMANDER\n1 Elminster\n39 LANDS\n1 Tundra\n"
                "28 INSTANTS and SORC.\n1 Counterspell\n"
                "3 CREATURES\n1 Snapcaster Mage\n")
        self.assertEqual(names(text),
                         ["Elminster", "Tundra", "Counterspell",
                          "Snapcaster Mage"])

    def test_comments_and_blanks_are_still_ignored(self):
        text = "// notes\n# more notes\n\n1 Elminster (CLB) 274\n"
        self.assertEqual(names(text), ["Elminster"])


@unittest.skipUnless(DB.exists(), "needs data/mtg.db")
class TestNoRealNameIsDamaged(unittest.TestCase):
    def test_every_card_name_survives_the_tail_strippers(self):
        """The strippers run on every imported line, so a false positive is a
        card silently renamed into something else — or into nothing."""
        conn = sqlite3.connect(DB)
        try:
            names_ = [r[0] for r in conn.execute("SELECT name FROM cards")]
        finally:
            conn.close()
        damaged = []
        for name in names_:
            after = dp._SET_TAIL_RE.sub("", dp._FOIL_TAIL_RE.sub(
                "", dp._CATEGORY_TAIL_RE.sub("", name))).strip()
            if after != name:
                damaged.append((name, after))
        self.assertEqual(damaged, [], f"{len(damaged)} names altered")

    def test_no_card_name_is_read_as_a_type_group_heading(self):
        conn = sqlite3.connect(DB)
        try:
            names_ = [r[0] for r in conn.execute("SELECT name FROM cards")]
        finally:
            conn.close()
        eaten = [n for n in names_ if dp._is_type_group(f"1 {n}")]
        self.assertEqual(eaten, [])


class TestExporterShapes(unittest.TestCase):
    """Headers and prefixes that used to be parsed as cards, or lost a section."""

    def test_utf8_bom_is_ignored(self):
        # PowerShell 5.1's Out-File writes one; str.strip() keeps it.
        rows = parse_deckstring("﻿Commander\n1 Sol Ring")
        self.assertEqual(rows, [{"name": "Sol Ring", "quantity": 1,
                                 "section": "commander"}])

    def test_header_with_count(self):
        rows = parse_deckstring("Commander (1)\n1 Atraxa\nSideboard (2)\n2 Duress")
        self.assertEqual([(r["name"], r["section"]) for r in rows],
                         [("Atraxa", "commander"), ("Duress", "sideboard")])

    def test_tokens_section_is_dropped(self):
        self.assertEqual(names("Deck\n1 Island\nTokens\n1 Treasure"), ["Island"])

    def test_inline_sideboard_prefix(self):
        self.assertEqual(one("SB: 2 Duress"),
                         {"name": "Duress", "quantity": 2, "section": "sideboard"})

    def test_archidekt_category_suffix(self):
        self.assertEqual(one("1x Sol Ring (c21) 263 [Ramp] ^Have,#37d67a^")["name"],
                         "Sol Ring")


if __name__ == "__main__":
    unittest.main()
