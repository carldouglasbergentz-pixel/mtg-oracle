"""Regressions in the TUI and the nav-pane renderers it draws with.

The renderer half is pure and runs anywhere. The App half drives the real
Textual app headlessly (`App.run_test`) against a throwaway copy of the
database and a throwaway config file, because several of these bugs only
existed in the wiring — a click taking a different path than the typed
command, a width read before layout.

Run: python -m unittest discover tests
"""
import asyncio
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).parent.parent))

from mtg_oracle import decks as d  # noqa: E402
from mtg_oracle import renderer as r  # noqa: E402
from mtg_oracle.tui import clipboard, config  # noqa: E402

DB = Path(__file__).parent.parent / "data" / "mtg.db"


# --- nav renderers (pure) -----------------------------------------------

def _deck(**over) -> dict:
    cards = [
        {"card_name": "Thassa's Oracle", "quantity": 1,
         "type_line": "Creature — Merfolk Wizard"},
        {"card_name": "Tamiyo, Inquisitive Student // Tamiyo, Seasoned Scholar",
         "quantity": 1, "type_line": "Legendary Creature — Moonfolk Wizard"},
        {"card_name": "Demonic Consultation", "quantity": 1,
         "type_line": "Instant"},
        {"card_name": "Island", "quantity": 12, "type_line": "Basic Land — Island"},
        {"card_name": "Sol Ring", "quantity": 1, "type_line": "Artifact",
         "category": "A very long user-named category for the fast mana"},
    ]
    deck = {
        "name": "A deck with a name long enough to need cutting in a narrow pane",
        "folder": "Canadian Highlander", "format": "canadianhighlander",
        "total_main": 16, "total_side": 3, "commander_ci": ["U", "B"],
        "points": {"total": 10, "budget": 10, "over": False,
                   "cards": [("Tamiyo, Inquisitive Student // Tamiyo, "
                              "Seasoned Scholar", 1, 1, 1)]},
        "cards": cards,
    }
    deck.update(over)
    return deck


ANALYTICS = {
    "mana_curve": [0, 23, 16, 11, 5, 5, 5], "nonland_count": 65,
    "land_count": 35, "mdfc_land_count": 1, "land_total": 36, "mv_avg": 2.62,
    "color_pips": {"W": 0, "U": 58, "B": 3, "R": 23, "G": 0}, "pip_total": 84,
    "mana_sources": {"W": 0, "U": 21, "B": 1, "R": 8, "G": 0, "C": 11},
}

COMBOS = [
    {"id": "111-222", "cards": "Thassa's Oracle + Demonic Consultation",
     "color_identity": "UB", "card_count": 2},
    {"id": "333-444", "cards": "Isochron Scepter + Dramatic Reversal + Sol Ring",
     "color_identity": "U", "card_count": 3, "has_template_vars": True},
]


class TestNavRenderers(unittest.TestCase):
    def render(self, width, **over):
        links: list[r.LinkSpan] = []
        body = r.render_deck_compact(_deck(**over), width=width,
                                     analytics=ANALYTICS, combos=COMBOS,
                                     links=links)
        return body.split("\n"), links

    def test_combo_links_land_on_their_row_numbers(self):
        """The points and analytics blocks used to be appended as one
        element each, so every combo link pointed ~11 rows too high."""
        for width in (20, 48, 80):
            lines, links = self.render(width)
            combo = [s for s in links if s.kind == "combo"]
            self.assertEqual([s.args for s in combo], [(1,), (2,)])
            for span in combo:
                self.assertEqual(lines[span.line][span.start:span.end],
                                 f"[{span.args[0]:>3}]", width)

    def test_forge_export_control_lands_on_its_token(self):
        """On the name row when both fit, on its own row otherwise — and the
        span covers exactly the token either way."""
        for width, own_row in ((20, True), (48, True), (80, False)):
            lines, links = self.render(width)
            spans = [s for s in links if s.kind == "forge_export"]
            self.assertEqual(len(spans), 1, width)
            span = spans[0]
            self.assertEqual(lines[span.line][span.start:span.end],
                             r.FORGE_EXPORT_TOKEN, width)
            self.assertEqual(span.line, 1 if own_row else 0, width)
            self.assertEqual(span.args, ("Canadian Highlander", _deck()["name"]))
        # A short name shares its row even in the narrowest pane.
        lines, links = self.render(20, name="Short")
        self.assertEqual(lines[0], "Short     [-> forge]")

    def test_card_links_still_cover_their_names(self):
        lines, links = self.render(48)
        for span in links:
            if span.kind == "card":
                text = lines[span.line][span.start:span.end]
                self.assertTrue(span.args[0].startswith(
                    text.split(" (")[0].rstrip(".")), text)

    def test_no_row_is_wider_than_the_pane(self):
        for width in (20, 30, 48):
            lines, _ = self.render(width)
            wide = [row for row in lines if len(row) > width]
            self.assertEqual(wide, [], width)

    def test_nothing_is_lost_at_the_narrowest_width(self):
        """Reflowed rather than clipped: the MDFC count and the points left
        are the facts most likely to fall off the end of a row."""
        text = "\n".join(self.render(20)[0])
        for fact in ("(36 with MDFC)", "(0 left)", "10 / 10", "C:11",
                     "Canadian Highlander", "+3 side"):
            self.assertIn(fact, text)

    def test_default_width_keeps_its_one_row_layout(self):
        text = self.render(80)[0]
        self.assertIn("avg MV: 2.62   non-lands: 65   lands: 35 (36 with MDFC)",
                      text)
        self.assertIn("curve   0   1   2   3   4   5  6+", text)
        self.assertIn("pips (84):    U:58 B:3 R:23", text)
        self.assertIn("Points   10 / 10   (0 left)", text)

    def test_colorless_pips_are_listed_after_green(self):
        """{C} costs count toward `pips (N)`, so the row has to name them."""
        analytics = dict(ANALYTICS, pip_total=86,
                         color_pips=dict(ANALYTICS["color_pips"], C=2))
        body = r.render_analytics_compact(analytics, width=48)
        self.assertIn("pips (86):    U:58 B:3 R:23 C:2", body)
        narrow = r.render_analytics_compact(analytics, width=20)
        self.assertIn("C:2", narrow)
        self.assertTrue(all(len(row) <= 20 for row in narrow.split("\n")))
        # No C bucket, or an empty one, prints nothing extra.
        self.assertNotIn("C:", r.render_analytics_compact(ANALYTICS).split(
            "pips")[1].split("\n")[0])

    def test_history_times_are_shown_in_local_time(self):
        """Stored UTC, printed local: 11:14Z read as 11:14 to someone at +2."""
        from datetime import datetime, timezone
        local = (datetime(2026, 9, 28, 11, 14, tzinfo=timezone.utc)
                 .astimezone().strftime("%Y-%m-%d %H:%M"))
        for stored in ("2026-09-28T11:14:03Z", "2026-09-28T11:14:03"):
            body = r.render_deck_history([{"id": 1, "at": stored,
                                           "action": "replace", "note": None,
                                           "changes": []}])
            self.assertIn(f"#1     {local}  replace", body, stored)

    def test_empty_combo_list_fits_too(self):
        body = r.render_deck_compact(_deck(), width=20, analytics=ANALYTICS,
                                     combos=[])
        self.assertTrue(all(len(row) <= 20 for row in body.split("\n")))
        self.assertIn("contained in this", body)


# --- config / clipboard (no database) -----------------------------------

class TestConfig(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name) / "config.json"
        patcher = mock.patch.object(config, "CONFIG_PATH", self.path)
        patcher.start()
        self.addCleanup(patcher.stop)
        self.addCleanup(self.tmp.cleanup)

    def test_a_bom_is_read_not_treated_as_empty(self):
        """PowerShell 5.1 writes a BOM; reading that as {} made the next
        save wipe the theme."""
        self.path.write_bytes(b'\xef\xbb\xbf{"theme": "rose-pine", "nav_width": 58}')
        self.assertEqual(config.load_config(),
                         {"theme": "rose-pine", "nav_width": 58})

    def test_a_file_that_is_not_utf8_does_not_crash(self):
        self.path.write_bytes('{"note": "é"}'.encode("cp1252"))
        self.assertEqual(config.load_config(), {})


class TestClipboard(unittest.TestCase):
    @unittest.skipUnless(sys.platform.startswith("win"), "Windows clipboard path")
    def test_powershell_output_is_decoded_as_utf8(self):
        """`Lim-Dûl's Vault` came back as `Lim-D–l's Vault`: PowerShell
        wrote the OEM codepage and Python decoded cp1252. Run the command
        read_clipboard builds, with the clipboard read swapped for a
        literal so the test never touches the user's clipboard."""
        calls = []

        def fake_run(cmd, **kw):
            calls.append((cmd, kw))
            return subprocess.CompletedProcess(cmd, 0, "", "")

        with mock.patch.object(clipboard.subprocess, "run", fake_run):
            clipboard.read_clipboard()
        cmd, kw = calls[0]
        script = cmd[-1].replace(
            "Get-Clipboard -Raw",
            "Write-Output ([string][char]0x00FB + [char]0x00C6 + [char]0x2014)")
        out = subprocess.run(cmd[:-1] + [script], **kw)
        self.assertEqual(out.stdout.strip(), "ûÆ—")


# --- the App, headless, on a sandboxed database ---------------------------

def _nav_lines(app):
    from textual.widgets import RichLog
    return [s.text for s in app.query_one("#nav", RichLog).lines]


def _out_lines(app):
    from textual.widgets import RichLog
    return [s.text for s in app.query_one("#output", RichLog).lines]


@unittest.skipUnless(DB.exists(), "needs data/mtg.db")
class TestApp(unittest.IsolatedAsyncioTestCase):
    @classmethod
    def setUpClass(cls):
        import db_sandbox
        from mtg_oracle import decks as d
        cls.sandbox = db_sandbox
        db_sandbox.enter()
        cls.tmp = tempfile.TemporaryDirectory()
        cls.config_patch = mock.patch.object(
            config, "CONFIG_PATH", Path(cls.tmp.name) / "config.json")
        cls.config_patch.start()
        d.create_folder("TUI Tests")
        d.create_deck("Oracle Pile", folder="TUI Tests")
        for card in ("Thassa's Oracle", "Demonic Consultation", "Island"):
            d.add_card_to_deck("Oracle Pile", card, folder="TUI Tests",
                               force=True)
        cls.deck_combos = d.combos_in_deck("Oracle Pile", folder="TUI Tests")

    @classmethod
    def tearDownClass(cls):
        cls.config_patch.stop()
        cls.tmp.cleanup()
        cls.sandbox.leave()

    async def asyncSetUp(self):
        # IsolatedAsyncioTestCase runs the loop in debug mode, which prints a
        # warning for every Textual callback over 100 ms — pure noise here.
        asyncio.get_running_loop().set_debug(False)

    def app(self):
        from mtg_oracle.tui.app import MtgOracleApp
        return MtgOracleApp()

    async def submit(self, pilot, app, command):
        from textual.widgets import Input
        field = app.query_one("#cmd", Input)
        field.value = command
        await field.action_submit()
        await pilot.pause()

    def ticket_for(self, app, kind, args):
        return next(t for t, target in app._click_targets.items()
                    if target == (kind, args))

    async def test_nav_combo_click_expands_that_combo(self):
        self.assertTrue(self.deck_combos, "fixture deck should contain a combo")
        first_id = self.deck_combos[0]["id"]
        async with self.app().run_test(size=(160, 60)) as pilot:
            app = pilot.app
            await self.submit(pilot, app, "cd TUI Tests/Oracle Pile")
            ticket = self.ticket_for(app, "combo", (first_id,))
            # The clickable segment in the pane itself is the row number.
            from textual.widgets import RichLog
            segments = [seg.text for strip in app.query_one("#nav", RichLog).lines
                        for seg in strip
                        if seg.style and seg.style.meta.get("@click")
                        == f"app.click_target({ticket})"]
            self.assertEqual(segments, ["[  1]"])
            # Another list shown since must not change what that row means.
            await self.submit(pilot, app, "combo Sol Ring")
            n = len(_out_lines(app))
            await app.run_action(f"click_target({ticket})")
            await pilot.pause()
            self.assertIn(f"Combo {first_id}", "\n".join(_out_lines(app)[n:]))

    async def test_typed_combo_info_keeps_the_explicit_list(self):
        """`add` refreshes the side pane, which used to replace the list a
        `combo` command had just shown."""
        async with self.app().run_test(size=(160, 60)) as pilot:
            app = pilot.app
            await self.submit(pilot, app, "cd TUI Tests/Oracle Pile")
            await self.submit(pilot, app, "combo Sol Ring")
            wanted = app._last_combos[1]["id"]
            await self.submit(pilot, app, "add Swamp")
            n = len(_out_lines(app))
            await self.submit(pilot, app, "combo-info 2")
            self.assertIn(f"Combo {wanted}", "\n".join(_out_lines(app)[n:]))
            # A folder shows no list, so the explicit one still stands ...
            await self.submit(pilot, app, "cd ..")
            self.assertEqual(app._last_combos[1]["id"], wanted)
            # ... but entering a deck hands `combo-info <N>` back to its pane.
            await self.submit(pilot, app, "cd Oracle Pile")
            self.assertEqual([c["id"] for c in app._last_combos],
                             [c["id"] for c in self.deck_combos])

    async def test_a_failing_click_reports_instead_of_crashing(self):
        from mtg_oracle import services as svc
        async with self.app().run_test(size=(160, 60)) as pilot:
            app = pilot.app
            await self.submit(pilot, app, "search t:creature c:u mv=1")
            ticket = next(t for t, (kind, _) in app._click_targets.items()
                          if kind == "card")
            locked = sqlite3.OperationalError("database is locked")
            with mock.patch.object(svc, "card_profile", side_effect=locked):
                await app.run_action(f"click_target({ticket})")
                await pilot.pause()
            self.assertTrue(app.is_running)
            self.assertEqual(_out_lines(app)[-1],
                             "ERR database: database is locked")

    async def test_a_failing_sync_does_not_kill_the_app(self):
        import mtg_oracle.tui.app as app_module
        spawn_fails = mock.patch.object(
            app_module.subprocess, "Popen",
            side_effect=FileNotFoundError("no python"))
        async with self.app().run_test(size=(160, 60)) as pilot:
            app = pilot.app
            with spawn_fails:
                await self.submit(pilot, app, "sync")
                await app.workers.wait_for_complete()
                await pilot.pause()
            self.assertTrue(app.is_running)
            self.assertFalse(app._sync_running)
            self.assertIn("sync aborted: FileNotFoundError: no python",
                          _out_lines(app))

    async def test_card_names_ending_in_a_number(self):
        async with self.app().run_test(size=(160, 60)) as pilot:
            app = pilot.app
            await self.submit(pilot, app, "cd TUI Tests")
            await self.submit(pilot, app, "add Numbers")
            await self.submit(pilot, app, "cd Numbers")
            for command, echo in (
                ("add Pip-Boy 3000", "OK 1x Pip-Boy 3000"),
                ("add Pain 101 2", "OK 2x Pain 101"),
                ("add Sol Ring 3", "OK 3x Sol Ring"),
                ("remove Pain 101 1", "OK removed 1x Pain 101 (1 remaining)"),
            ):
                await self.submit(pilot, app, command)
                self.assertEqual(_out_lines(app)[-1], echo, command)

    async def test_ctrl_arrows_resize_while_typing_and_rows_fit(self):
        from textual.widgets import Input, RichLog
        async with self.app().run_test(size=(160, 60)) as pilot:
            app = pilot.app
            await self.submit(pilot, app, "cd TUI Tests/Oracle Pile")
            nav = app.query_one("#nav", RichLog)
            self.assertIs(app.focused, app.query_one("#cmd", Input))
            before = nav.content_region.width
            def widest():
                return max(len(row.rstrip()) for row in _nav_lines(app))

            for _ in range(3):
                await pilot.press("ctrl+left")
                # The re-render is deferred past a layout pass; give a loaded
                # machine a few passes. The old bug never converged at all —
                # it stayed at the previous width until something else redrew.
                for _ in range(10):
                    await pilot.pause()
                    if widest() <= nav.content_region.width:
                        break
                self.assertLessEqual(widest(), nav.content_region.width)
            self.assertEqual(nav.content_region.width, before - 6)

    async def test_saved_width_is_clamped_to_the_terminal(self):
        config.save_config({"nav_width": 150})
        try:
            async with self.app().run_test(size=(100, 40)) as pilot:
                await pilot.pause()
                self.assertGreaterEqual(
                    pilot.app.query_one("#output").size.width, 20)
                # A terminal that shrinks mid-session is re-clamped too.
                await pilot.resize_terminal(70, 40)
                await pilot.pause()
                self.assertGreaterEqual(
                    pilot.app.query_one("#output").size.width, 20)
        finally:
            config.save_config({})

    async def test_cwd_follows_rename_move_and_rmdir(self):
        from mtg_oracle import decks as d
        d.create_folder("TUI Elsewhere")
        d.create_deck("Wanderer", folder="TUI Tests")
        async with self.app().run_test(size=(160, 60)) as pilot:
            app = pilot.app
            await self.submit(pilot, app, "cd TUI Tests/Wanderer")
            await self.submit(pilot, app, "rename wanderer; Rover")
            self.assertEqual(app._cwd_deck, "Rover")
            await self.submit(pilot, app, "move rover; tui elsewhere")
            self.assertEqual(app._cwd_folder, "TUI Elsewhere")
            self.assertNotIn("disappeared", "\n".join(_nav_lines(app)))
            await self.submit(pilot, app, "add Island")
            self.assertEqual(_out_lines(app)[-1], "OK 1x Island")

            await self.submit(pilot, app, "cd /")
            await self.submit(pilot, app, "mkdir TUI Ghost")
            await self.submit(pilot, app, "cd TUI Ghost")
            await self.submit(pilot, app, "rmdir tui ghost")
            self.assertEqual(app._ref().path, "/")

    # --- #10: unsorted decks, paths, clicks, '/' in names ---------------

    @classmethod
    def _twins(cls):
        """An unsorted `Twin` and a `TUI Tests/Twin`, plus an unsorted
        `Loose` — the shapes that used to be unreachable."""
        from mtg_oracle import decks as d
        if getattr(cls, "_twins_made", False):
            return
        d.create_deck("Twin", folder=d.UNSORTED)
        d.create_deck("Twin", folder="TUI Tests")
        d.create_deck("Loose", folder=d.UNSORTED)
        cls._twins_made = True

    async def test_clicking_an_unsorted_deck_from_inside_a_folder(self):
        from mtg_oracle import decks as d
        self._twins()
        async with self.app().run_test(size=(160, 60)) as pilot:
            app = pilot.app
            await self.submit(pilot, app, "cd TUI Tests")
            ticket = self.ticket_for(app, "deck", (None, "Loose"))
            await app.run_action(f"click_target({ticket})")
            await pilot.pause()
            self.assertEqual((app._cwd_folder, app._cwd_deck),
                             (d.UNSORTED, "Loose"))
            # The breadcrumb's deck segment re-enters it without complaint.
            ticket = self.ticket_for(app, "deck", (d.UNSORTED, "Loose"))
            await app.run_action(f"click_target({ticket})")
            await pilot.pause()
            self.assertNotIn("already inside", "\n".join(_out_lines(app)[-3:]))
            self.assertEqual(app._cwd_deck, "Loose")
            # `cd ..` from an unsorted deck is root, not a "(unsorted)" folder.
            await self.submit(pilot, app, "cd ..")
            self.assertEqual(app._ref().path, "/")

    async def test_ambiguous_cd_hints_paths_that_work(self):
        from mtg_oracle import decks as d
        self._twins()
        async with self.app().run_test(size=(160, 60)) as pilot:
            app = pilot.app
            await self.submit(pilot, app, "cd Twin")
            hints = [row.strip() for row in _out_lines(app)
                     if row.strip().startswith("cd ") and "Twin" in row]
            self.assertEqual(sorted(hints),
                             ["cd (unsorted)/Twin", "cd TUI Tests/Twin"])
            for hint, folder in (("cd (unsorted)/Twin", d.UNSORTED),
                                 ("cd TUI Tests/Twin", "TUI Tests")):
                await self.submit(pilot, app, "cd /")
                await self.submit(pilot, app, hint)
                self.assertEqual((app._cwd_folder, app._cwd_deck),
                                 (folder, "Twin"), hint)
            # Inside the unsorted twin, deck commands are not ambiguous.
            await self.submit(pilot, app, "cd /(unsorted)/Twin")
            await self.submit(pilot, app, "add Island")
            self.assertEqual(_out_lines(app)[-1], "OK 1x Island")

    async def test_show_profile_compare_scope_and_ambiguity(self):
        self._twins()
        async with self.app().run_test(size=(160, 60)) as pilot:
            app = pilot.app
            await self.submit(pilot, app, "show Twin")
            self.assertIn("show: 2 decks named 'Twin' — use a folder path:",
                          _out_lines(app))
            n = len(_out_lines(app))
            await self.submit(pilot, app, "show (unsorted)/Twin")
            self.assertIn("Twin", "\n".join(_out_lines(app)[n:]))
            self.assertNotIn("not found", "\n".join(_out_lines(app)[n:]))
            # Inside a folder, a bare name means that folder's deck.
            await self.submit(pilot, app, "cd TUI Tests")
            n = len(_out_lines(app))
            await self.submit(pilot, app, "profile Twin")
            self.assertIn("=== DENSITY", "\n".join(_out_lines(app)[n:]))
            await self.submit(pilot, app, "cd Oracle Pile")
            n = len(_out_lines(app))
            await self.submit(pilot, app, "compare (unsorted)/Twin")
            self.assertIn("HEAD TO HEAD", "\n".join(_out_lines(app)[n:]))
            await self.submit(pilot, app, "compare oracle pile")
            self.assertIn("compared to itself", _out_lines(app)[-1])

    async def test_slash_is_refused_in_new_names(self):
        async with self.app().run_test(size=(160, 60)) as pilot:
            app = pilot.app
            # The deck layer refuses it, so the CLI and imports are covered too;
            # the TUI only prefixes its verb.
            for command, verb, name in (
                ("mkdir Modern/Legacy", "mkdir", "Modern/Legacy"),
                ("cd TUI Tests", None, None),
                ("add UR/Delver", "add", "UR/Delver"),
                ("rename Oracle Pile; Oracle/Pile", "rename", "Oracle/Pile"),
            ):
                await self.submit(pilot, app, command)
                if verb:
                    last = _out_lines(app)[-1]
                    self.assertTrue(last.startswith(f"{verb}: "), last)
                    self.assertIn(f"{name!r} contains '/'", last)
            self.assertIsNone(d.get_deck("UR/Delver"))

    # --- replace / history / undo ----------------------------------------

    def _contents(self, deck, folder="TUI Tests"):
        from mtg_oracle import decks as d
        return {c["card_name"]: c["quantity"]
                for c in d.get_deck(deck, folder=folder)["cards"]}

    def _fresh_deck(self, name):
        from mtg_oracle import decks as d
        d.create_deck(name, folder="TUI Tests")
        for card, qty in (("Island", 3), ("Counterspell", 1)):
            d.add_card_to_deck(name, card, quantity=qty, folder="TUI Tests",
                               force=True)

    def _clipboard(self, text):
        import mtg_oracle.tui.app as app_module
        return mock.patch.object(app_module, "read_clipboard", return_value=text)

    async def test_paste_replace_applies_and_shows_the_diff(self):
        self._fresh_deck("Replace Me")
        async with self.app().run_test(size=(160, 60)) as pilot:
            app = pilot.app
            await self.submit(pilot, app, "cd TUI Tests/Replace Me")
            n = len(_out_lines(app))
            with self._clipboard("2 Island\n1 Brainstorm\n"):
                await self.submit(pilot, app, "paste --replace")
            out = "\n".join(_out_lines(app)[n:])
            self.assertIn("Replaced 'Replace Me'", out)
            self.assertIn("Brainstorm", out)
            self.assertEqual(self._contents("Replace Me"),
                             {"Island": 2, "Brainstorm": 1})
            # The side pane shows the new list.
            self.assertIn("Brainstorm", "\n".join(_nav_lines(app)))

    async def test_replace_stops_on_an_unknown_card_unless_forced(self):
        self._fresh_deck("Replace Strict")
        listing = "2 Island\n1 Notacard Xyzzy\n"
        async with self.app().run_test(size=(160, 60)) as pilot:
            app = pilot.app
            await self.submit(pilot, app, "cd TUI Tests/Replace Strict")
            with self._clipboard(listing):
                await self.submit(pilot, app, "paste --replace")
            self.assertTrue(_out_lines(app)[-1].startswith("replace: "))
            self.assertIn("Notacard Xyzzy", _out_lines(app)[-1])
            self.assertEqual(self._contents("Replace Strict"),
                             {"Island": 3, "Counterspell": 1})

            # The same list from a file, forced: replaced without the card.
            with tempfile.TemporaryDirectory() as tmp:
                path = Path(tmp) / "list with  two spaces.txt"
                path.write_text(listing, encoding="utf-8")
                n = len(_out_lines(app))
                await self.submit(pilot, app,
                                  f"import {path} --replace --force")
            out = "\n".join(_out_lines(app)[n:])
            self.assertIn("Replaced 'Replace Strict'", out)
            self.assertIn("Notacard Xyzzy", out)  # listed as left out
            self.assertEqual(self._contents("Replace Strict"), {"Island": 2})

    async def test_history_and_undo_then_redo(self):
        self._fresh_deck("Undo Me")
        async with self.app().run_test(size=(160, 60)) as pilot:
            app = pilot.app
            await self.submit(pilot, app, "cd TUI Tests/Undo Me")
            with self._clipboard("1 Brainstorm\n"):
                await self.submit(pilot, app, "paste --replace")
            replaced = self._contents("Undo Me")

            n = len(_out_lines(app))
            await self.submit(pilot, app, "history")
            out = "\n".join(_out_lines(app)[n:])
            self.assertIn("revision(s), newest first:", out)
            self.assertIn("replace", out)
            await self.submit(pilot, app, "history 1")
            self.assertIn("1 revision(s), newest first:", _out_lines(app))

            n = len(_out_lines(app))
            await self.submit(pilot, app, "undo")
            self.assertIn("Undid #", "\n".join(_out_lines(app)[n:]))
            self.assertEqual(self._contents("Undo Me"),
                             {"Island": 3, "Counterspell": 1})
            await self.submit(pilot, app, "undo")  # again: redo
            self.assertEqual(self._contents("Undo Me"), replaced)

            # Another deck's history, by path, from root.
            await self.submit(pilot, app, "cd /")
            n = len(_out_lines(app))
            await self.submit(pilot, app, "history TUI Tests/Undo Me 2")
            out = _out_lines(app)[n:]
            self.assertIn("/TUI Tests/Undo Me", out)
            self.assertIn("2 revision(s), newest first:", out)

    async def test_replace_history_undo_usage(self):
        async with self.app().run_test(size=(160, 60)) as pilot:
            app = pilot.app
            for command, expected in (
                ("paste --replace",
                 "(use `cd <deck>` to enter a deck before `paste`)"),
                ("import some.txt --replace",
                 "(use `cd <deck>` to enter a deck before `import`)"),
                ("undo", "(use `cd <deck>` to enter a deck before `undo`)"),
            ):
                await self.submit(pilot, app, command)
                self.assertEqual(_out_lines(app)[-1], expected, command)
            await self.submit(pilot, app, "history")
            self.assertTrue(_out_lines(app)[-1].startswith("usage: history"))
            await self.submit(pilot, app, "cd TUI Tests/Oracle Pile")
            await self.submit(pilot, app, "paste --force")
            self.assertEqual(_out_lines(app)[-1],
                             "paste: --force only applies with --replace")

    async def test_a_database_without_history_asks_for_sync(self):
        """Until `sync` migrates it, the user's database has no revision
        table; that must read as the usual hint, not a traceback."""
        from mtg_oracle import decks as d
        missing = sqlite3.OperationalError("no such table: deck_revisions")
        async with self.app().run_test(size=(160, 60)) as pilot:
            app = pilot.app
            await self.submit(pilot, app, "cd TUI Tests/Oracle Pile")
            for command, target in (("history", "deck_history"),
                                    ("undo", "undo_last_change")):
                with mock.patch.object(d, target, side_effect=missing):
                    await self.submit(pilot, app, command)
                out = "\n".join(_out_lines(app)[-2:])
                self.assertIn("ERR database: no such table: deck_revisions", out)
                self.assertIn("Run `sync` to migrate it.", out)
            self.assertTrue(app.is_running)

    # --- Forge ----------------------------------------------------------
    #
    # No Forge runs: the install check, card index and config are stubbed,
    # `.dck` files go to a temp decks folder, and `run_sim` returns a
    # recorded Forge output. The services themselves run for real.

    FORGE_CARDS = {"Thassa's Oracle": None, "Demonic Consultation": "All",
                   "Tainted Pact": None, "Island": None, "Brainstorm": None,
                   "Swamp": None}

    def _fake_forge(self, run_sim=None):
        """Patch forge_client for one test; returns the temp decks folder."""
        from mtg_oracle import forge_client as fc
        tmp = Path(tempfile.mkdtemp(prefix="mtg-oracle-forge-"))
        self.addCleanup(lambda: __import__("shutil").rmtree(tmp, ignore_errors=True))
        config = fc.ForgeConfig(
            install_dir=tmp / "forge", decks_dir=tmp / "decks" / "constructed",
            commander_decks_dir=tmp / "decks" / "commander")
        install = fc.ForgeInstall(config=config, jar=tmp / "forge.jar",
                                  java="java", version="test")
        index = {n.casefold(): {"name": n, "ai": f}
                 for n, f in self.FORGE_CARDS.items()}
        patches = [mock.patch.object(fc, "validate", return_value=install),
                   mock.patch.object(fc, "load_config", return_value=config),
                   mock.patch.object(fc, "load_card_index",
                                     side_effect=lambda *_a, **_k: index)]
        if run_sim is not None:
            patches.append(mock.patch.object(fc, "run_sim", **run_sim))
        for p in patches:
            p.start()
            self.addCleanup(p.stop)
        return tmp

    @classmethod
    def _forge_decks(cls):
        from mtg_oracle import decks as d
        if getattr(cls, "_forge_made", False):
            return
        d.create_deck("Forge Pile", folder="TUI Tests")
        for card, qty in (("Thassa's Oracle", 1), ("Demonic Consultation", 1),
                          ("Island", 5)):
            d.add_card_to_deck("Forge Pile", card, quantity=qty,
                               folder="TUI Tests", force=True)
        d.create_deck("Forge Foe", folder="TUI Tests")
        for card, qty in (("Brainstorm", 1), ("Island", 6)):
            d.add_card_to_deck("Forge Foe", card, quantity=qty,
                               folder="TUI Tests", force=True)
        cls._forge_made = True

    def _sim_run(self, tmp, fixture="constructed_q_3games.txt"):
        from mtg_oracle import forge_client as fc
        stdout = (Path(__file__).parent / "fixtures" / "forge" / fixture
                  ).read_text(encoding="utf-8")
        return fc.SimRun(match_id=f"tui-{fixture}", stdout=stdout, stderr="",
                         returncode=0, log_path=tmp / "sim.log")

    async def test_forge_export_and_substitutions(self):
        self._forge_decks()
        tmp = self._fake_forge()
        async with self.app().run_test(size=(160, 60)) as pilot:
            app = pilot.app
            await self.submit(pilot, app, "cd TUI Tests/Forge Pile")
            n = len(_out_lines(app))
            await self.submit(pilot, app, "forge export")
            out = "\n".join(_out_lines(app)[n:])
            self.assertIn("Exported 'Forge Pile' for Forge (constructed):", out)
            self.assertIn("Forge's AI can't play 1 card(s)", out)
            self.assertTrue((tmp / "decks" / "constructed" / "Forge Pile.dck").exists())

            await self.submit(pilot, app,
                              "forge sub add Demonic Consultation -> Tainted Pact")
            self.assertEqual(_out_lines(app)[-1],
                             "OK Forge Pile: the AI copy plays Tainted Pact "
                             "for Demonic Consultation")
            n = len(_out_lines(app))
            await self.submit(pilot, app, "forge sub list")
            out = "\n".join(_out_lines(app)[n:])
            self.assertIn("Forge AI substitutions in 'Forge Pile' (1):", out)
            self.assertIn("Demonic Consultation  ->  Tainted Pact", out)
            # Export now writes the AI copy too.
            await self.submit(pilot, app, "forge export")
            self.assertTrue(
                (tmp / "decks" / "constructed" / "Forge Pile (AI).dck").exists())

            await self.submit(pilot, app, "forge sub remove Demonic Consultation")
            self.assertEqual(_out_lines(app)[-1],
                             "OK Forge Pile: Demonic Consultation no longer "
                             "substituted (was Tainted Pact)")
            # A missing separator is a usage error, not a guess.
            await self.submit(pilot, app, "forge sub add Island Swamp")
            self.assertEqual(_out_lines(app)[-1],
                             "usage: forge sub add <card> -> <substitute>")

    async def test_forge_export_refuses_a_hand_made_file_until_overwrite(self):
        """The user's spike-era .dck files carry no ownership marker, so the
        first export trips on them; the message has to say the way out."""
        self._forge_decks()
        tmp = self._fake_forge()
        target = tmp / "decks" / "constructed" / "Forge Foe.dck"
        target.parent.mkdir(parents=True)
        target.write_text("[metadata]\nName=Forge Foe\n[Main]\n1 Island\n",
                          encoding="utf-8")
        async with self.app().run_test(size=(160, 60)) as pilot:
            app = pilot.app
            await self.submit(pilot, app, "forge export TUI Tests/Forge Foe")
            self.assertTrue(_out_lines(app)[-1].startswith("forge export: "))
            self.assertIn("--overwrite", _out_lines(app)[-1])
            n = len(_out_lines(app))
            await self.submit(pilot, app,
                              "forge export TUI Tests/Forge Foe --overwrite")
            self.assertIn("Exported 'Forge Foe' for Forge",
                          "\n".join(_out_lines(app)[n:]))

    async def test_nav_forge_click_exports_that_deck(self):
        from textual.widgets import RichLog
        self._forge_decks()
        tmp = self._fake_forge()
        target = tmp / "decks" / "constructed" / "Forge Pile.dck"
        target.parent.mkdir(parents=True)
        hand_made = "[metadata]\nName=Forge Pile\n[Main]\n1 Island\n"
        target.write_text(hand_made, encoding="utf-8")
        async with self.app().run_test(size=(160, 60)) as pilot:
            app = pilot.app
            await self.submit(pilot, app, "cd TUI Tests/Forge Pile")
            ticket = self.ticket_for(app, "forge_export",
                                     ("TUI Tests", "Forge Pile"))
            segments = [seg.text for strip in app.query_one("#nav", RichLog).lines
                        for seg in strip
                        if seg.style and seg.style.meta.get("@click")
                        == f"app.click_target({ticket})"]
            self.assertEqual(segments, ["[-> forge]"])

            # A hand-made file is refused with the hint, never overwritten.
            n = len(_out_lines(app))
            await app.run_action(f"click_target({ticket})")
            await pilot.pause()
            out = _out_lines(app)[n:]
            self.assertEqual(out[0], "> forge export TUI Tests/Forge Pile")
            self.assertTrue(out[-1].startswith("forge export: "))
            self.assertIn("--overwrite", out[-1])
            self.assertEqual(target.read_text(encoding="utf-8"), hand_made)

            target.unlink()
            n = len(_out_lines(app))
            await app.run_action(f"click_target({ticket})")
            await pilot.pause()
            self.assertIn("Exported 'Forge Pile' for Forge (constructed):",
                          "\n".join(_out_lines(app)[n:]))
            self.assertTrue(target.exists())

    async def test_forge_sim_runs_in_a_worker_and_records_results(self):
        import sqlite3
        from mtg_oracle import queries as _q
        with sqlite3.connect(str(_q.DB_PATH)) as _c:
            if not _c.execute("SELECT 1 FROM sqlite_master WHERE name = 'forge_matches'").fetchone():
                self.skipTest("forge_matches was dropped by the app's schema version 2: the app simulates now")
        self._forge_decks()
        tmp = self._fake_forge()
        from mtg_oracle import forge_client as fc
        started = []

        def fake_run(*_a, **kw):
            started.append(kw)
            return self._sim_run(tmp)

        with mock.patch.object(fc, "run_sim", side_effect=fake_run):
            async with self.app().run_test(size=(160, 60)) as pilot:
                app = pilot.app
                await self.submit(pilot, app, "cd TUI Tests/Forge Pile")
                n = len(_out_lines(app))
                await self.submit(pilot, app, "forge sim Forge Foe 3")
                self.assertTrue(_out_lines(app)[n + 1].startswith(
                    "Simulating 'Forge Pile' vs 'Forge Foe', 3 game(s)"))
                await app.workers.wait_for_complete()
                await pilot.pause()
                out = "\n".join(_out_lines(app)[n:])
                self.assertIn("3 game(s), constructed, Forge test", out)
                self.assertFalse(app._forge_sim_running)
                self.assertEqual(started[0]["games"], 3)

                n = len(_out_lines(app))
                await self.submit(pilot, app, "forge results")
                out = "\n".join(_out_lines(app)[n:])
                self.assertIn("Forge record for 'Forge Pile':", out)
                self.assertIn("vs Forge Foe", out)
                # At root: the matrix.
                await self.submit(pilot, app, "cd /")
                n = len(_out_lines(app))
                await self.submit(pilot, app, "forge results")
                self.assertIn("Forge win matrix", "\n".join(_out_lines(app)[n:]))
                # From anywhere, with `;`.
                n = len(_out_lines(app))
                await self.submit(pilot, app,
                                  "forge sim TUI Tests/Forge Foe; Forge Pile 1")
                await app.workers.wait_for_complete()
                await pilot.pause()
                self.assertIn("Simulating 'Forge Foe' vs 'Forge Pile', 1 game(s)",
                              "\n".join(_out_lines(app)[n:]))

    async def test_a_failing_forge_sim_is_reported_and_the_app_survives(self):
        from mtg_oracle import forge_client as fc
        self._forge_decks()
        self._fake_forge()
        async with self.app().run_test(size=(160, 60)) as pilot:
            app = pilot.app
            await self.submit(pilot, app, "cd TUI Tests/Forge Pile")
            for failure, expected in (
                (fc.ForgeError("java was not found"),
                 "forge sim: java was not found"),
                (RuntimeError("boom"), "forge sim: RuntimeError: boom"),
            ):
                with mock.patch.object(fc, "run_sim", side_effect=failure):
                    await self.submit(pilot, app, "forge sim Forge Foe")
                    await app.workers.wait_for_complete()
                    await pilot.pause()
                self.assertTrue(app.is_running)
                self.assertFalse(app._forge_sim_running)
                self.assertEqual(_out_lines(app)[-1], expected)

    async def test_forge_usage_outside_a_deck(self):
        async with self.app().run_test(size=(160, 60)) as pilot:
            app = pilot.app
            for command, expected in (
                ("forge export",
                 "(forge export: name a deck, or `cd <deck>` first)"),
                ("forge sub add Island -> Swamp",
                 "(use `cd <deck>` to enter a deck before `forge sub add`)"),
                ("forge sub remove Island",
                 "(use `cd <deck>` to enter a deck before `forge sub remove`)"),
                ("forge sub list",
                 "(forge sub list: name a deck, or `cd <deck>` first)"),
                ("forge sim Forge Foe",
                 "(forge sim: `cd <deck>` first, or "
                 "`forge sim <deck>; <opponent> [N]`)"),
                ("forge export No Such Deck",
                 "forge export: no deck named 'No Such Deck'"),
            ):
                await self.submit(pilot, app, command)
                self.assertEqual(_out_lines(app)[-1], expected, command)
            await self.submit(pilot, app, "forge")
            self.assertIn("usage: forge export [<deck>] [--force] [--overwrite]",
                          _out_lines(app))
            n = len(_out_lines(app))
            await self.submit(pilot, app, "help forge")
            text = "\n".join(_out_lines(app)[n:])
            self.assertIn("(AI).dck", text)
            self.assertIn("40 life", text)

    async def test_clear_drops_the_output_panes_tickets(self):
        async with self.app().run_test(size=(160, 60)) as pilot:
            app = pilot.app
            await self.submit(pilot, app, "search t:creature c:u mv=1")
            self.assertGreater(len(app._click_targets), len(app._nav_click_ids))
            await self.submit(pilot, app, "clear")
            self.assertEqual(set(app._click_targets), set(app._nav_click_ids))


if __name__ == "__main__":
    unittest.main()
