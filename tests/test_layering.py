"""The layering contract, enforced instead of merely documented.

CLAUDE.md states the dependency direction and the reason for it. Until now
that was a convention a single careless import could break silently — and the
temptation is real: `render_profile` would be shorter if it could just read
`roles.LABELS` itself, and `rank_cards` would look at home in `analytics`.

Imports are read with `ast`, not by importing the modules: a module that
imports something forbidden would still import *successfully*, which is
exactly why the check has to look at the source.

Run: python -m unittest discover tests
"""
import ast
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

PKG = Path(__file__).parent.parent / "mtg_oracle"

# Presentation and pure computation. These take primitives and return
# primitives; a renderer that could reach for the database would start
# answering questions instead of formatting answers, and `roles` deciding what
# a card does from a DB lookup would make its verdicts untestable offline.
PURE = ("renderer", "analytics", "probability", "roles", "deck_parser",
        "forge_format")

# The data modules, which may import each other but not the layers above.
DATA = ("queries", "decks", "scryfall_search", "forge_data")

# External integrations: they drive a tool outside the project (Forge),
# and know its formats through a pure module, never the database or the
# layers above. What they may import, exactly.
INTEGRATIONS = {"forge_client": {"forge_format"}}


def project_imports(path: Path) -> set[str]:
    """Every `mtg_oracle.X` this file imports, as bare module names."""
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    found: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for alias in node.names:
                if alias.name.startswith("mtg_oracle"):
                    found.add(alias.name.split(".")[-1])
        elif isinstance(node, ast.ImportFrom):
            mod = node.module or ""
            # `from mtg_oracle import queries as q`
            if mod == "mtg_oracle":
                found.update(a.name for a in node.names)
            # `from mtg_oracle.deck_parser import parse_deckstring`
            elif mod.startswith("mtg_oracle."):
                found.add(mod.split(".")[1])
    return found - {"mtg_oracle"}


class TestPureLayerImportsNothing(unittest.TestCase):
    def test_no_project_imports(self):
        for name in PURE:
            path = PKG / f"{name}.py"
            if not path.exists():
                continue
            with self.subTest(module=name):
                self.assertEqual(
                    project_imports(path), set(),
                    f"{name}.py must import nothing from the project — that is "
                    f"what makes it testable without a database")

    def test_the_check_can_actually_fail(self):
        """A guard that cannot fail is decoration. Prove this one detects."""
        tmp = Path(__file__).parent / "_layering_probe.py"
        tmp.write_text("from mtg_oracle import queries as q\n", encoding="utf-8")
        try:
            self.assertEqual(project_imports(tmp), {"queries"})
        finally:
            tmp.unlink()

    def test_all_four_import_styles_are_seen(self):
        tmp = Path(__file__).parent / "_layering_probe.py"
        tmp.write_text(
            "import mtg_oracle.roles\n"
            "from mtg_oracle import decks\n"
            "from mtg_oracle.deck_parser import parse_deckstring\n"
            "from mtg_oracle import queries as q, services as s\n",
            encoding="utf-8")
        try:
            self.assertEqual(
                project_imports(tmp),
                {"roles", "decks", "deck_parser", "queries", "services"})
        finally:
            tmp.unlink()


class TestDataLayerStaysBelowServices(unittest.TestCase):
    def test_data_modules_do_not_import_services_or_tui(self):
        """The direction is one-way: services composes them, not the reverse."""
        for name in DATA:
            path = PKG / f"{name}.py"
            if not path.exists():
                continue
            with self.subTest(module=name):
                forbidden = project_imports(path) & {"services", "tui", "renderer"}
                self.assertEqual(forbidden, set(),
                                 f"{name}.py imports {forbidden} from above it")


class TestIntegrationsStayNarrow(unittest.TestCase):
    def test_integrations_import_only_their_format_module(self):
        """forge_client runs Java and writes files; if it could reach decks or
        queries, "export" would grow a second copy of the deck rules."""
        for name, allowed in INTEGRATIONS.items():
            with self.subTest(module=name):
                found = project_imports(PKG / f"{name}.py")
                self.assertLessEqual(found, allowed,
                                     f"{name}.py imports {found - allowed}")


class TestServicesMayComposeDataButNotPresent(unittest.TestCase):
    def test_services_does_not_import_the_renderer(self):
        """Use cases return data. The adapter decides how it looks.

        Without this, `profile_deck` would eventually return a formatted
        string and the TUI would be parsing it back apart.
        """
        found = project_imports(PKG / "services.py")
        self.assertNotIn("renderer", found)
        self.assertNotIn("tui", found)

    def test_services_reaches_the_data_layer(self):
        """The inverse guard: a services module importing nothing is a sign
        the use case leaked back into an adapter."""
        found = project_imports(PKG / "services.py")
        self.assertTrue(found & set(DATA), f"services imports only {found}")


if __name__ == "__main__":
    unittest.main()
