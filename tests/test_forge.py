"""Forge integration: formats, client, substitutions, sims, results.

Three kinds of test, by what they need:
- `forge_format` is pure, and is checked against sim output captured from
  real Forge 2.0.14 runs (tests/fixtures/forge/).
- `forge_client` runs on temp directories with subprocess mocked. The real
  Forge decks folder is never written by a test.
- The services run on `db_sandbox`'s copy of the database, with the Forge
  install and card index stubbed.
One integration test plays a real game, in a sandboxed Forge (its own user
and decks directories), and only when tools/forge and java are present.

Run: python -m unittest discover tests
"""
import contextlib
import hashlib
import io
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).parent.parent
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(Path(__file__).parent))

import db_sandbox  # noqa: E402
from mtg_oracle import decks as d  # noqa: E402
from mtg_oracle import forge_client as fc  # noqa: E402
from mtg_oracle import forge_data as fd  # noqa: E402
from mtg_oracle import forge_format as ff  # noqa: E402
from mtg_oracle import renderer as r  # noqa: E402
from mtg_oracle import services as svc  # noqa: E402

FIXTURES = Path(__file__).parent / "fixtures" / "forge"
REAL_DB = ROOT / "data" / "mtg.db"


def fixture(name: str) -> str:
    return (FIXTURES / name).read_text(encoding="utf-8")


def row(name, qty=1, commander=False, sideboard=False):
    return {"card_name": name, "quantity": qty,
            "is_commander": int(commander), "is_sideboard": int(sideboard)}


# --- forge_format -----------------------------------------------------------

class TestSimOutputParsing(unittest.TestCase):
    def test_quiet_constructed_output(self):
        match = ff.parse_sim_output(fixture("constructed_q_3games.txt"))
        self.assertEqual([(g.game_no, g.winner, g.turns, g.duration_ms)
                          for g in match.games],
                         [(1, 2, 9, 3926), (2, 2, 10, 2018), (3, 2, 26, 8521)])
        self.assertEqual((match.wins, match.draws), ({1: 0, 2: 3}, 0))

    def test_full_game_log_carries_the_same_result(self):
        match = ff.parse_sim_output(fixture("constructed_full_1game.txt"))
        self.assertEqual([(g.winner, g.turns, g.duration_ms) for g in match.games],
                         [(2, 9, 3075)])

    def test_commander_output(self):
        match = ff.parse_sim_output(fixture("commander_q_2games.txt"))
        self.assertEqual(match.wins, {1: 2, 2: 0})
        self.assertEqual([g.turns for g in match.games], [14, 14])

    def test_a_clock_stopped_game_is_a_draw_whatever_forge_prints(self):
        # Forge 2.0.14 logs "Stopping slow match as draw" and then reports
        # player 1 as the winner; counting that would hand deck A every slow game.
        match = ff.parse_sim_output(fixture("clock_draw_stdout_3games.txt"))
        self.assertEqual(match.draws, 3)
        self.assertEqual(match.wins, {1: 0, 2: 0})
        self.assertTrue(all(g.clock_draw for g in match.games))

    def test_real_draw_line(self):
        match = ff.parse_sim_output(
            "Game Outcome: Turn 12\nGame Result: Game 1 ended in a Draw! Took 4100 ms.\n")
        self.assertEqual([(g.winner, g.turns, g.duration_ms) for g in match.games],
                         [(None, 12, 4100)])

    def test_winner_is_read_from_the_index_not_the_name(self):
        text = ("Game Outcome: Turn 5\nGame Result: Game 1 ended in 10 ms. "
                "Ai(1)-Ai(2)-Tricky has won! has won!\n")
        self.assertEqual(ff.parse_sim_output(text).games[0].winner, 1)

    def test_failure_output_explains_itself(self):
        text = fixture("missing_deck_stdout.txt")
        self.assertEqual(ff.parse_sim_output(text).games, [])
        errors = ff.sim_errors(text)
        self.assertTrue(any(e.startswith("Could not load deck") for e in errors))
        self.assertTrue(any(e.startswith("No deck found") for e in errors))


class TestDckFiles(unittest.TestCase):
    def test_sections_front_faces_and_merging(self):
        rows = [row("Fire // Ice"), row("Island", 3), row("Island", 1),
                row("Narset, Enlightened Master", commander=True),
                row("Delver of Secrets // Insectile Aberration", 2, sideboard=True)]
        self.assertEqual(ff.dck_sections(rows), {
            "Commander": [(1, "Narset, Enlightened Master")],
            "Main": [(1, "Fire"), (4, "Island")],
            "Sideboard": [(2, "Delver of Secrets")],
        })

    def test_substitutions_rename_rows_case_insensitively(self):
        rows = ff.apply_substitutions([row("City of Traitors"), row("Island")],
                                      {"city of TRAITORS": "Wasteland"})
        self.assertEqual([x["card_name"] for x in rows], ["Wasteland", "Island"])

    def test_substitute_onto_a_card_already_there_merges(self):
        rows = ff.apply_substitutions([row("Gemstone Caverns"), row("Swamp", 5)],
                                      {"Gemstone Caverns": "Swamp"})
        self.assertEqual(ff.dck_sections(rows)["Main"], [(6, "Swamp")])

    def test_ownership_round_trip(self):
        text = ff.render_dck("My Deck", 7, {"Main": [(4, "Island")]})
        self.assertEqual(ff.read_ownership(text), ff.Ownership(True, 7, False))
        self.assertTrue(ff.read_ownership(text.replace("4 Island", "3 Island")).edited)
        # Line endings don't count as an edit.
        self.assertFalse(ff.read_ownership(text.replace("\n", "\r\n")).edited)

    def test_forge_own_deck_is_not_ours(self):
        own = "[metadata]\nName=Akroma\nDescription=Angels.\n[Main]\n4 Serra Angel\n"
        self.assertFalse(ff.read_ownership(own).ours)

    def test_metadata_value_cannot_inject_a_line(self):
        text = ff.render_dck("Evil\n[Main]\n4 Black Lotus", 1, {"Main": [(1, "Island")]})
        self.assertEqual(text.splitlines().count("[Main]"), 1)
        self.assertIn("Name=Evil [Main] 4 Black Lotus\n", text)


class TestSafeFilename(unittest.TestCase):
    CASES = {
        "UW Draw Go - Control": "UW Draw Go - Control.dck",
        "../../evil": ".._.._evil.dck",
        "..\\..\\evil": ".._.._evil.dck",
        "C:\\Windows\\x": "C__Windows_x.dck",
        'a:b*c?"d<e>f|g': "a_b_c__d_e_f_g.dck",
        "trailing. . ": "trailing.dck",
        "CON": "_CON.dck",
        "nul.txt": "_nul.txt.dck",
        "COM1": "_COM1.dck",
        "-n": "_n.dck",
        "": "_.dck",
        "...": "_.dck",
        "Fire // Ice": "Fire __ Ice.dck",
        "tab\there": "tab_here.dck",
        # cmd.exe metacharacters: the name is also a command-line argument.
        "x&calc&y": "x_calc_y.dck",
        "100% ^burn!": "100_ _burn_.dck",
    }

    def test_cases(self):
        for name, expected in self.CASES.items():
            with self.subTest(name=name):
                self.assertEqual(ff.safe_filename(name), expected)

    def test_length_is_capped(self):
        self.assertLessEqual(len(ff.safe_filename("x" * 500)), ff.MAX_STEM + 4)


class TestCardScripts(unittest.TestCase):
    def test_front_face_and_ai_flag(self):
        fire_ice = (b"Name:Fire\r\nManaCost:1 R\r\nAlternateMode:Split\r\n\r\n"
                    b"ALTERNATE\r\n\r\nName:Ice\r\nManaCost:1 U\r\n")
        city = b"Name:City of Traitors\nTypes:Land\nAI:RemoveDeck:All\n"
        self.assertEqual(ff.parse_card_script(fire_ice), ("Fire", None))
        self.assertEqual(ff.parse_card_script(city), ("City of Traitors", "All"))
        self.assertIsNone(ff.parse_card_script(b"no name here"))


# --- forge_client -------------------------------------------------------------

def make_fake_install(root: Path, cards: dict) -> Path:
    """A directory shaped like a Forge release, with `cards` as scripts."""
    install = root / "forge"
    (install / "res" / "cardsfolder").mkdir(parents=True)
    (install / "forge-gui-desktop-9.9.9-jar-with-dependencies.jar").write_bytes(b"")
    (install / "build.txt").write_text("2099-01-01 00:00:00", encoding="utf-8")
    with zipfile.ZipFile(install / fc.CARDS_ZIP, "w") as z:
        z.writestr("a/", "")
        for i, (name, flag) in enumerate(cards.items()):
            script = f"Name:{name}\nTypes:Card\n"
            if flag:
                script += f"AI:RemoveDeck:{flag}\n"
            z.writestr(f"x/card_{i}.txt", script)
    return install


class ClientTestCase(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp = Path(self._tmp.name)
        self.addCleanup(self._tmp.cleanup)
        self.install_dir = make_fake_install(
            self.tmp, {"Island": None, "City of Traitors": "All", "Fire": None})
        self.config = fc.ForgeConfig(
            install_dir=self.install_dir,
            decks_dir=self.tmp / "decks" / "constructed",
            commander_decks_dir=self.tmp / "decks" / "commander",
            java=sys.executable)  # any real executable passes the java check

    def install(self):
        return fc.validate(self.config)


class TestConfig(ClientTestCase):
    def test_defaults_when_absent(self):
        cfg = fc.load_config(self.tmp / "missing.json")
        self.assertEqual(cfg.install_dir, fc.REPO_ROOT / "tools" / "forge")
        self.assertTrue(str(cfg.decks_dir).replace("\\", "/")
                        .endswith("Forge/decks/constructed"))

    def test_overrides_and_expansion(self):
        path = self.tmp / "config.json"
        path.write_text('{"theme": "x", "forge": {"install_dir": "elsewhere", '
                        '"decks_dir": "%MTG_TEST_DIR%/decks", "java": "myjava"}}',
                        encoding="utf-8-sig")
        with mock.patch.dict(os.environ, {"MTG_TEST_DIR": str(self.tmp)}):
            cfg = fc.load_config(path)
        self.assertEqual(cfg.install_dir, fc.REPO_ROOT / "elsewhere")
        self.assertEqual(cfg.decks_dir, self.tmp / "decks")
        self.assertEqual(cfg.java, "myjava")

    def test_validate_names_what_is_missing(self):
        self.assertEqual(self.install().version, "9.9.9 (2099-01-01 00:00:00)")
        cases = {
            "not installed": {"install_dir": self.tmp / "nowhere"},
            "java not found": {"java": "definitely-not-java-xyz"},
        }
        for message, change in cases.items():
            with self.subTest(case=message):
                cfg = fc.ForgeConfig(**{**self.config.__dict__, **change})
                with self.assertRaisesRegex(fc.ForgeError, message):
                    fc.validate(cfg)
        (self.install_dir / fc.CARDS_ZIP).unlink()
        with self.assertRaisesRegex(fc.ForgeError, "incomplete"):
            fc.validate(self.config)
        for jar in self.install_dir.glob("*.jar"):
            jar.unlink()
        with self.assertRaisesRegex(fc.ForgeError, "jar"):
            fc.validate(self.config)

    def test_a_batch_file_java_is_refused(self):
        # cmd.exe would re-parse the arguments of a .bat/.cmd launcher.
        for suffix in (".bat", ".CMD"):
            with self.subTest(suffix=suffix):
                shim = self.tmp / f"java{suffix}"
                shim.write_text("@echo off\n", encoding="utf-8")
                cfg = fc.ForgeConfig(**{**self.config.__dict__, "java": str(shim)})
                with self.assertRaisesRegex(fc.ForgeError, "batch script"):
                    fc.validate(cfg)


class TestCardIndex(ClientTestCase):
    def test_index_and_cache(self):
        cache = self.tmp / "index.json"
        index = fc.load_card_index(self.install(), cache_path=cache)
        self.assertEqual(index["city of traitors"], {"name": "City of Traitors", "ai": "All"})
        self.assertTrue(cache.exists())
        with mock.patch.object(zipfile, "ZipFile", side_effect=AssertionError("rescanned")):
            self.assertEqual(fc.load_card_index(self.install(), cache_path=cache), index)

    def test_a_changed_cardsfolder_rebuilds(self):
        cache = self.tmp / "index.json"
        fc.load_card_index(self.install(), cache_path=cache)
        with zipfile.ZipFile(self.install_dir / fc.CARDS_ZIP, "a") as z:
            z.writestr("x/new.txt", "Name:Brand New\n")
        os.utime(self.install_dir / fc.CARDS_ZIP, (1, 1))  # a different mtime
        self.assertIn("brand new", fc.load_card_index(self.install(), cache_path=cache))


class TestWriteDeck(ClientTestCase):
    SECTIONS = {"Main": [(4, "Island")]}

    def write(self, name="My Deck", deck_id=1, sections=None, game_type="constructed",
              **kw):
        return fc.write_deck(self.config, name, deck_id, sections or self.SECTIONS,
                             game_type, **kw)

    def test_writes_into_the_decks_folder_for_its_type(self):
        self.assertEqual(self.write().parent, self.config.decks_dir)
        self.assertEqual(self.write(game_type="commander").parent,
                         self.config.commander_decks_dir)

    def test_rewrites_its_own_untouched_export(self):
        self.write()
        path = self.write(sections={"Main": [(3, "Island")]})
        self.assertIn("3 Island", path.read_text(encoding="utf-8"))

    def test_refuses_a_file_it_did_not_write(self):
        self.config.decks_dir.mkdir(parents=True)
        user_deck = self.config.decks_dir / "My Deck.dck"
        user_deck.write_text("[metadata]\nName=My Deck\n[Main]\n60 Island\n",
                             encoding="utf-8")
        with self.assertRaisesRegex(fc.ForgeError, "not written by mtg-oracle"):
            self.write()
        self.assertIn("60 Island", user_deck.read_text(encoding="utf-8"))
        self.write(overwrite=True)
        self.assertIn("4 Island", user_deck.read_text(encoding="utf-8"))

    def test_refuses_another_decks_export(self):
        self.write(name="A:B", deck_id=1)
        with self.assertRaisesRegex(fc.ForgeError, "another deck"):
            self.write(name="A*B", deck_id=2)   # sanitises to the same file

    def test_refuses_an_export_edited_in_forge(self):
        path = self.write()
        path.write_text(path.read_text(encoding="utf-8").replace("4 Island", "5 Island"),
                        encoding="utf-8")
        with self.assertRaisesRegex(fc.ForgeError, "edited in Forge"):
            self.write()

    def test_path_traversal_stays_inside(self):
        for name in ("../../escape", "..\\..\\escape", "/abs/escape", "C:\\x"):
            with self.subTest(name=name):
                path = self.write(name=name)
                self.assertEqual(path.parent.resolve(), self.config.decks_dir.resolve())
        self.assertFalse(any(self.tmp.glob("escape*")))
        self.assertFalse((self.tmp / "decks" / "escape.dck").exists())


class TestRunSim(ClientTestCase):
    def fake_run(self, stdout="", stderr="", code=0):
        return mock.patch.object(fc.subprocess, "run", return_value=subprocess.CompletedProcess(
            args=[], returncode=code, stdout=stdout, stderr=stderr))

    def test_argument_list_cwd_timeout_and_log(self):
        logs = self.tmp / "logs"
        with self.fake_run(stdout=fixture("constructed_q_3games.txt"), stderr="trace") as run:
            out = fc.run_sim(self.install(), Path("A deck.dck"), Path("B.dck"),
                             games=3, game_type="commander", logs_dir=logs)
        args, kwargs = run.call_args
        cmd = args[0]
        self.assertIsInstance(cmd, list)
        self.assertNotIn("shell", kwargs)
        self.assertEqual(cmd[cmd.index("-d") + 1:cmd.index("-d") + 3], ["A deck.dck", "B.dck"])
        self.assertEqual(cmd[cmd.index("-n") + 1], "3")
        self.assertEqual(cmd[-2:], ["-f", "Commander"])
        self.assertNotIn("-q", cmd, "the full log is kept for per-card stats")
        self.assertEqual(kwargs["cwd"], self.install_dir)
        self.assertEqual(kwargs["timeout"],
                         fc.STARTUP_TIMEOUT_S + 3 * fc.PER_GAME_TIMEOUT_S)
        self.assertTrue(kwargs["capture_output"])
        log = out.log_path.read_text(encoding="utf-8")
        self.assertEqual(out.log_path.parent, logs)
        self.assertIn("Game Result: Game 3", log)
        self.assertIn("# ---- stderr ----\ntrace", log)

    def test_constructed_passes_no_format(self):
        with self.fake_run() as run:
            fc.run_sim(self.install(), Path("A.dck"), Path("B.dck"), games=1,
                       game_type="constructed", logs_dir=self.tmp / "logs")
        self.assertNotIn("-f", run.call_args[0][0])

    def test_timeout_keeps_the_output_and_fails_loud(self):
        err = subprocess.TimeoutExpired(cmd=[], timeout=1, output=b"partial", stderr=None)
        with mock.patch.object(fc.subprocess, "run", side_effect=err):
            with self.assertRaisesRegex(fc.ForgeError, "timed out"):
                fc.run_sim(self.install(), Path("A.dck"), Path("B.dck"), games=1,
                           game_type="constructed", logs_dir=self.tmp / "logs")
        logs = list((self.tmp / "logs").glob("*.log"))
        self.assertEqual(len(logs), 1)
        self.assertIn("partial", logs[0].read_text(encoding="utf-8"))

    def test_game_count_is_bounded(self):
        for games in (0, fc.MAX_GAMES + 1):
            with self.assertRaises(fc.ForgeError):
                fc.run_sim(self.install(), Path("A.dck"), Path("B.dck"), games=games,
                           game_type="constructed", logs_dir=self.tmp / "logs")

    def test_gui_launch_is_detached_and_unshelled(self):
        with mock.patch.object(fc.subprocess, "Popen") as popen:
            popen.return_value.pid = 4242
            self.assertEqual(fc.launch_gui(self.install()), 4242)
        args, kwargs = popen.call_args
        self.assertIsInstance(args[0], list)
        self.assertNotIn("shell", kwargs)
        self.assertEqual(kwargs["cwd"], self.install_dir)
        self.assertEqual(kwargs["stdout"], subprocess.DEVNULL)


# --- services (sandboxed database) ----------------------------------------------

def setUpModule():
    db_sandbox.enter()


def tearDownModule():
    db_sandbox.leave()


class ServiceTestCase(unittest.TestCase):
    """Two decks in the sandbox, a temp Forge decks folder, a stub index."""

    FORGE_CARDS = {
        "Island": None, "Wasteland": None, "Dark Confidant": None,
        "City of Traitors": "All", "Ancient Tomb": None, "Sol Ring": None,
        "Mana Crypt": None, "Mystical Tutor": "Random", "Complaints Clerk": None,
        "Narset, Enlightened Master": None, "Llanowar Elves": None,
        "Brainstorm": None, "Lightning Bolt": None, "Strip Mine": None,
        "Gemstone Caverns": "All", "Mountain": None,
    }

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp = Path(self._tmp.name)
        self.addCleanup(self._tmp.cleanup)
        self.config = fc.ForgeConfig(
            install_dir=self.tmp / "forge",
            decks_dir=self.tmp / "decks" / "constructed",
            commander_decks_dir=self.tmp / "decks" / "commander")
        self.install = fc.ForgeInstall(config=self.config, jar=self.tmp / "f.jar",
                                       java="java", version="test")
        self.index = {n.casefold(): {"name": n, "ai": f}
                      for n, f in self.FORGE_CARDS.items()}
        for p in (mock.patch.object(fc, "validate", return_value=self.install),
                  mock.patch.object(fc, "load_card_index",
                                    side_effect=lambda *_a, **_k: self.index)):
            p.start()
            self.addCleanup(p.stop)
        self.folder = f"forge-test-{self.id().rsplit('.', 1)[-1]}"
        d.create_folder(self.folder)
        self.addCleanup(self._drop_folder)
        # Canadian Highlander at exactly 10/10 points: Sol Ring 3, Mana Crypt 5,
        # Mystical Tutor 2.
        d.create_deck("Canlander", folder=self.folder, format="canadianhighlander")
        for card in ("Sol Ring", "Mana Crypt", "Mystical Tutor", "City of Traitors",
                     "Dark Confidant", "Gemstone Caverns"):
            d.add_card_to_deck("Canlander", card, folder=self.folder, force=True)
        d.add_card_to_deck("Canlander", "Island", quantity=5, folder=self.folder)
        d.create_deck("Narset", folder=self.folder, format="duel")
        d.add_card_to_deck("Narset", "Narset, Enlightened Master", folder=self.folder,
                           is_commander=True, force=True)
        for card in ("Island", "Brainstorm", "Lightning Bolt"):
            d.add_card_to_deck("Narset", card, folder=self.folder, force=True)
        self.canlander = svc.DeckRef(deck="Canlander", folder=self.folder)
        self.narset = svc.DeckRef(deck="Narset", folder=self.folder)

    def _drop_folder(self):
        for deck in d.list_decks(folder=self.folder):
            d.delete_deck(deck["name"], folder=self.folder)
        d.delete_folder(self.folder)

    def sub(self, card, substitute, ref=None):
        return svc.forge_add_substitution(ref or self.canlander, card, substitute,
                                          config=self.config)


class TestSubstitutionValidation(ServiceTestCase):
    def test_accepted_swap_is_stored_and_the_deck_untouched(self):
        before = d.get_deck("Canlander", folder=self.folder)["cards"]
        out = self.sub("City of Traitors", "Wasteland")
        self.assertEqual((out["card"], out["substitute"], out["replaced"]),
                         ("City of Traitors", "Wasteland", None))
        self.assertEqual(d.get_deck("Canlander", folder=self.folder)["cards"], before)
        self.assertEqual([(s["card_name"], s["substitute"])
                          for s in svc.forge_substitutions(self.canlander)],
                         [("City of Traitors", "Wasteland")])

    def test_replacing_a_substitution_reports_the_old_one(self):
        self.sub("City of Traitors", "Wasteland")
        self.assertEqual(self.sub("City of Traitors", "Island")["replaced"], "Wasteland")

    def test_rejections(self):
        cases = {
            # 1 point on a deck at 10/10 — the Ancient Tomb case.
            ("City of Traitors", "Ancient Tomb"): "point",
            # Already in a singleton deck — the Dark Confidant case.
            ("City of Traitors", "Dark Confidant"): "singleton",
            ("City of Traitors", "Complaints Clerk"): "banned",
            ("Not A Card In The Deck", "Island"): "not in deck",
            ("City of Traitors", "Zzyzx Not A Real Card"): "not found",
            ("City of Traitors", "City of Traitors"): "itself",
        }
        for (card, substitute), reason in cases.items():
            with self.subTest(substitute=substitute):
                with self.assertRaisesRegex(svc.ServiceError, reason):
                    self.sub(card, substitute)
        self.assertEqual(svc.forge_substitutions(self.canlander), [])

    def test_basic_lands_are_exempt_from_singleton(self):
        self.assertEqual(self.sub("City of Traitors", "Island")["substitute"], "Island")

    def test_two_swaps_break_singleton_together(self):
        self.sub("City of Traitors", "Wasteland")
        with self.assertRaisesRegex(svc.ServiceError, "singleton"):
            self.sub("Gemstone Caverns", "Wasteland")

    def test_colour_identity(self):
        with self.assertRaisesRegex(svc.ServiceError, "color identity"):
            self.sub("Island", "Llanowar Elves", ref=self.narset)

    def test_forge_must_know_the_substitute_and_its_ai_play_it(self):
        del self.index["wasteland"]
        with self.assertRaisesRegex(svc.ServiceError, "unknown to Forge"):
            self.sub("City of Traitors", "Wasteland")
        self.index["wasteland"] = {"name": "Wasteland", "ai": "All"}
        with self.assertRaisesRegex(svc.ServiceError, "can't play"):
            self.sub("City of Traitors", "Wasteland")

    def test_remove(self):
        self.sub("City of Traitors", "Wasteland")
        self.assertEqual(svc.forge_remove_substitution(
            self.canlander, "city of traitors")["substitute"], "Wasteland")
        with self.assertRaisesRegex(svc.ServiceError, "no substitution"):
            svc.forge_remove_substitution(self.canlander, "City of Traitors")

    def test_deleting_the_deck_drops_its_substitutions(self):
        self.sub("City of Traitors", "Wasteland")
        deck_id = d.get_deck("Canlander", folder=self.folder)["id"]
        d.delete_deck("Canlander", folder=self.folder)
        self.assertEqual(fd.list_substitutions(deck_id), [])


class TestExport(ServiceTestCase):
    def test_export_with_ai_copy_and_warnings(self):
        self.sub("City of Traitors", "Wasteland")
        out = svc.forge_export(self.canlander, config=self.config)
        self.assertEqual(out.path, self.config.decks_dir / "Canlander.dck")
        self.assertEqual(out.ai_path, self.config.decks_dir / "Canlander (AI).dck")
        self.assertEqual(out.ai_unplayable, ["Gemstone Caverns"])
        self.assertEqual(out.ai_situational, [("Mystical Tutor", "Random")])
        ai_text = out.ai_path.read_text(encoding="utf-8")
        self.assertIn("1 Wasteland", ai_text)
        self.assertNotIn("City of Traitors", ai_text)
        self.assertIn("1 City of Traitors", out.path.read_text(encoding="utf-8"))
        self.assertIn("Gemstone Caverns", r.render_forge_export(out))

    def test_no_substitutions_no_ai_copy(self):
        out = svc.forge_export(self.canlander, config=self.config)
        self.assertIsNone(out.ai_path)
        self.assertFalse((self.config.decks_dir / "Canlander (AI).dck").exists())

    def test_unknown_cards_refuse_unless_forced(self):
        del self.index["dark confidant"]
        with self.assertRaises(svc.ForgeUnknownCardsError) as caught:
            svc.forge_export(self.canlander, config=self.config)
        self.assertEqual(caught.exception.unknown, ["Dark Confidant"])
        self.assertFalse(self.config.decks_dir.exists())
        out = svc.forge_export(self.canlander, force=True, config=self.config)
        self.assertNotIn("Dark Confidant", out.path.read_text(encoding="utf-8"))
        self.assertEqual(out.unknown, ["Dark Confidant"])

    def test_commander_deck_goes_to_the_commander_folder_with_the_life_note(self):
        out = svc.forge_export(self.narset, config=self.config)
        self.assertEqual(out.game_type, "commander")
        self.assertEqual(out.path.parent, self.config.commander_decks_dir)
        self.assertIn("[Commander]\n1 Narset, Enlightened Master",
                      out.path.read_text(encoding="utf-8"))
        self.assertIn(svc.COMMANDER_NOTE, out.notes)


class TestSimAndResults(ServiceTestCase):
    def setUp(self):
        super().setUp()
        d.create_deck("Burn", folder=self.folder)
        d.add_card_to_deck("Burn", "Lightning Bolt", quantity=4, folder=self.folder)
        d.add_card_to_deck("Burn", "Mountain", quantity=20, folder=self.folder)
        self.burn = svc.DeckRef(deck="Burn", folder=self.folder)
        # Results are keyed by deck name, and every test here names its decks
        # alike: start each from no stored games.
        conn = fd._connect()
        conn.execute("DELETE FROM forge_matches")
        conn.commit()
        conn.close()

    def run_with(self, stdout, games=3, **kw):
        fake = fc.SimRun(match_id=fc.new_match_id(), stdout=stdout, stderr="",
                         returncode=0, log_path=self.tmp / "sim.log")
        with mock.patch.object(fc, "run_sim", return_value=fake) as run:
            out = svc.forge_sim(self.canlander, self.burn, games=games,
                                config=self.config, **kw)
        return out, run

    def test_sim_stores_every_game_and_the_ai_copy_is_used(self):
        self.sub("City of Traitors", "Wasteland")
        out, run = self.run_with(fixture("constructed_q_3games.txt"))
        file_a, file_b = run.call_args[0][1:3]
        self.assertEqual(file_a.name, "Canlander (AI).dck")
        self.assertEqual(file_b.name, "Burn.dck")
        self.assertEqual((out.wins_a, out.wins_b, out.draws), (0, 3, 0))
        self.assertTrue(out.ai_variant_a)
        self.assertFalse(out.ai_variant_b)
        rows = fd.game_rows(d.get_deck("Burn", folder=self.folder)["id"])
        self.assertEqual([x["winner"] for x in rows], ["b", "b", "b"])
        self.assertIn("0 - 3", r.render_forge_sim(out))

    def test_no_ai_variant_plays_the_deck_as_built(self):
        self.sub("City of Traitors", "Wasteland")
        out, run = self.run_with(fixture("constructed_q_3games.txt"),
                                 use_ai_variant=False)
        self.assertEqual(run.call_args[0][1].name, "Canlander.dck")
        self.assertFalse(out.ai_variant_a)

    def test_clock_draws_are_draws_with_a_note(self):
        out, _ = self.run_with(fixture("clock_draw_stdout_3games.txt"))
        self.assertEqual((out.wins_a, out.draws), (0, 3))
        self.assertTrue(any("120 s clock" in n for n in out.notes))

    def test_no_games_is_an_error_with_forges_reason(self):
        with self.assertRaisesRegex(svc.ServiceError, "Could not load deck"):
            self.run_with(fixture("missing_deck_stdout.txt"))

    def test_commander_cannot_play_constructed(self):
        with mock.patch.object(fc, "run_sim") as run:
            with self.assertRaisesRegex(svc.ServiceError, "same kind"):
                svc.forge_sim(self.narset, self.burn, config=self.config)
        run.assert_not_called()

    def test_results_per_deck_and_matrix(self):
        self.run_with(fixture("constructed_q_3games.txt"))   # Burn wins 3
        results = svc.forge_results(self.burn)
        self.assertEqual([(x.opponent, x.wins, x.losses) for x in results.records],
                         [("Canlander", 3, 0)])
        matrix = svc.forge_results()
        mine = [x for x in matrix.records if {x.deck, x.opponent} == {"Burn", "Canlander"}]
        self.assertEqual(sorted((x.deck, x.wins, x.losses) for x in mine),
                         [("Burn", 3, 0), ("Canlander", 0, 3)])
        self.assertIn("3-0", r.render_forge_results(matrix))

    def test_deleting_a_deck_keeps_its_games(self):
        self.run_with(fixture("constructed_q_3games.txt"))
        d.delete_deck("Burn", folder=self.folder)
        canlander_id = d.get_deck("Canlander", folder=self.folder)["id"]
        rows = fd.game_rows(canlander_id)
        self.assertEqual(len(rows), 3)
        self.assertEqual({(x["deck_b"], x["deck_b_id"]) for x in rows}, {("Burn", None)})


# --- a real game -----------------------------------------------------------------

INSTALL = ROOT / "tools" / "forge"


def _forge_available() -> bool:
    return (INSTALL.is_dir() and any(INSTALL.glob(fc.JAR_GLOB))
            and shutil.which("java") is not None)


def _link_dir(target: Path, link: Path) -> None:
    """A directory link without admin rights: a junction on Windows."""
    if sys.platform == "win32":
        import _winapi
        _winapi.CreateJunction(str(target), str(link))
    else:
        link.symlink_to(target, target_is_directory=True)


def _file_digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest() if path.exists() else ""


@unittest.skipUnless(_forge_available(), "needs tools/forge and java")
class TestRealForgeGame(unittest.TestCase):
    """One real game, with Forge's user and decks directories sandboxed.

    Forge reads `res/` and `forge.profile.properties` from its working
    directory, so a temp run directory with `res/` linked in and a profile
    pointing userDir/decksDir into the temp dir keeps Forge off the real
    %APPDATA%\\Forge — and nothing here touches data/mtg.db, which
    db_sandbox has already swapped for a copy.
    """

    def test_one_game(self):
        real_db_before = _file_digest(REAL_DB)
        tmp = Path(tempfile.mkdtemp(prefix="mtg-oracle-forge-"))
        self.addCleanup(shutil.rmtree, tmp, ignore_errors=True)
        run_dir = tmp / "run"
        run_dir.mkdir()
        _link_dir(INSTALL / "res", run_dir / "res")
        # rmtree must not follow the junction into the real install.
        self.addCleanup(lambda: os.rmdir(run_dir / "res")
                        if (run_dir / "res").exists() else None)
        root = tmp.as_posix()
        (run_dir / "forge.profile.properties").write_text(
            f"userDir={root}/user/\ncacheDir={root}/cache/\ndecksDir={root}/decks/\n",
            encoding="utf-8")
        config = fc.ForgeConfig(
            install_dir=INSTALL, decks_dir=tmp / "decks" / "constructed",
            commander_decks_dir=tmp / "decks" / "commander",
            java=shutil.which("java"), run_dir=run_dir)

        folder = "forge-integration-test"
        d.create_folder(folder)
        self.addCleanup(lambda: [d.delete_deck(x["name"], folder=folder)
                                 for x in d.list_decks(folder=folder)]
                        and d.delete_folder(folder))
        d.create_deck("Red", folder=folder)
        # Printings, so the game also proves Forge loads `Name|CODE|[number]`.
        d.add_card_to_deck("Red", "Mountain", quantity=24, folder=folder,
                           set_code="m21", collector_number="270")
        d.add_card_to_deck("Red", "Raging Goblin", quantity=36, folder=folder,
                           set_code="ath", collector_number="49")
        d.create_deck("Green", folder=folder)
        d.add_card_to_deck("Green", "Forest", quantity=24, folder=folder)
        d.add_card_to_deck("Green", "Grizzly Bears", quantity=36, folder=folder)

        logs = tmp / "logs"
        with mock.patch.object(fc, "LOGS_DIR", logs), \
                mock.patch.object(fc, "INDEX_CACHE_PATH", tmp / "index.json"):
            out = svc.forge_sim(svc.DeckRef("Red", folder), svc.DeckRef("Green", folder),
                                games=1, config=config)
        self.assertEqual(len(out.games), 1)
        self.assertIn(out.games[0]["winner"], ("a", "b", "draw"))
        self.assertEqual(out.log_path.parent, logs)
        red = (tmp / "decks" / "constructed" / "Red.dck").read_text(encoding="utf-8")
        self.assertIn("24 Mountain|M21|[270]", red)
        self.assertIn("36 Raging Goblin|ATH|[49]", red)
        self.assertEqual(_file_digest(REAL_DB), real_db_before)


# --- printings: Scryfall -> Forge editions ---------------------------------

FORGE_EDITIONS = ROOT / "tools" / "forge" / "res" / "editions"

# A miniature edition file, in Forge's real shape: two arts of one card out
# of file order, a split card, sections that aren't printings, and an
# artist-less line.
MINI_EDITION = """[metadata]
Code=TST
Date=2020-01-01
Name=Test Set
ScryfallCode=tst

[cards]
265 L Island @Andreas Rocha
263 L Island @Cliff Childs
12 U Fire // Ice @Franz Vohwinkel
7 R Sol Ring

[borderless]
300 R Sol Ring @Someone Else

[Common]
Island

[tokens]
1 b_1_1_bird_flying
"""


def mini_index(*texts):
    return ff.edition_index(ff.parse_edition(t) for t in texts)


class TestSortableCollectorNumber(unittest.TestCase):
    """Forge's CardEdition.getSortableCollectorNumber, case by case."""

    CASES = {"9": "00009", "10": "00010", "76★": "00076★", "418a": "00418a",
             "DDN-64": "DDN-00064", "WS3": "WS00003", "U5": "U00005",
             "2022-5": "-20225", None: "50000", "": "50000"}

    def test_cases(self):
        for number, want in self.CASES.items():
            with self.subTest(number=number):
                self.assertEqual(ff.sortable_collector_number(number), want)


class TestParseEdition(unittest.TestCase):
    def test_codes_and_printings(self):
        edition = ff.parse_edition(MINI_EDITION)
        self.assertEqual((edition.code, edition.scryfall_code), ("TST", "tst"))
        self.assertEqual(edition.cards["island"], ("263", "265"))
        self.assertEqual(edition.cards["sol ring"], ("7", "300"))
        self.assertEqual(edition.cards["fire // ice"], ("12",))
        self.assertEqual(edition.cards["fire"], ("12",))
        # Tokens and booster sheets are not printings.
        self.assertNotIn("b_1_1_bird_flying", edition.cards)

    def test_scryfall_code_defaults_to_code(self):
        edition = ff.parse_edition(MINI_EDITION.replace("ScryfallCode=tst\n", ""))
        self.assertEqual(edition.scryfall_code, "tst")

    def test_no_code_is_no_edition(self):
        self.assertIsNone(ff.parse_edition("[cards]\n1 C Island\n"))


class TestForgePrinting(unittest.TestCase):
    def setUp(self):
        self.index = mini_index(MINI_EDITION)

    def printing(self, name, set_code, number):
        return ff.forge_printing(self.index, name, set_code, number)

    def test_exact_number_and_art_index(self):
        self.assertEqual(self.printing("Island", "tst", "265"),
                         ff.ForgePrinting("TST", "265", 2))
        self.assertEqual(self.printing("Island", "TST", "263").suffix, "|TST|[263]")

    def test_split_card_by_full_or_front_name(self):
        self.assertEqual(self.printing("Fire // Ice", "tst", "12").suffix, "|TST|[12]")
        self.assertEqual(self.printing("Fire", "tst", "12").suffix, "|TST|[12]")

    def test_unknown_number_falls_back_to_the_edition(self):
        self.assertEqual(self.printing("Sol Ring", "tst", "7★").suffix, "|TST")
        self.assertEqual(self.printing("Sol Ring", "tst", None).suffix, "|TST")

    def test_no_forge_printing_is_none(self):
        self.assertIsNone(self.printing("Sol Ring", "zzz", "7"))
        self.assertIsNone(self.printing("Counterspell", "tst", "1"))
        self.assertIsNone(self.printing("Sol Ring", None, None))

    def test_one_scryfall_code_several_forge_editions(self):
        other = MINI_EDITION.replace("Code=TST", "Code=TST_AA").replace(
            "7 R Sol Ring\n", "7 R Sol Ring\n99 R Counterspell\n")
        index = mini_index(MINI_EDITION, other)
        # The number decides, then the edition whose Code is the Scryfall code.
        self.assertEqual(ff.forge_printing(index, "Counterspell", "tst", "99").code,
                         "TST_AA")
        self.assertEqual(ff.forge_printing(index, "Sol Ring", "tst", "7").code, "TST")


class TestDckPrintings(unittest.TestCase):
    def test_lines_carry_the_forge_printing(self):
        index = mini_index(MINI_EDITION)
        rows = [{**row("Island", 3), "set_code": "tst", "collector_number": "265"},
                {**row("Island", 2), "set_code": None, "collector_number": None},
                {**row("Sol Ring"), "set_code": "zzz", "collector_number": "1"}]
        self.assertEqual(ff.dck_sections(rows, index)["Main"],
                         [(2, "Island"), (3, "Island|TST|[265]"), (1, "Sol Ring")])

    def test_without_an_index_names_only(self):
        rows = [{**row("Island", 3), "set_code": "tst", "collector_number": "265"}]
        self.assertEqual(ff.dck_sections(rows)["Main"], [(3, "Island")])

    def test_a_substitute_drops_the_originals_printing(self):
        rows = [{**row("City of Traitors"), "set_code": "tst",
                 "collector_number": "7"}]
        out = ff.apply_substitutions(rows, {"City of Traitors": "Sol Ring"})
        self.assertIsNone(out[0]["set_code"])
        self.assertEqual(ff.dck_sections(out, mini_index(MINI_EDITION))["Main"],
                         [(1, "Sol Ring")])


@unittest.skipUnless(FORGE_EDITIONS.is_dir(), "needs tools/forge/res/editions")
class TestRealForgeEditions(unittest.TestCase):
    """The mapping over Forge 2.0.14's own edition files."""

    @classmethod
    def setUpClass(cls):
        install = fc.ForgeInstall(
            config=fc.ForgeConfig(install_dir=FORGE_EDITIONS.parent.parent,
                                  decks_dir=Path("unused"),
                                  commander_decks_dir=Path("unused")),
            jar=Path("unused.jar"), java="java", version="real")
        cls.index = fc.load_edition_index(install)

    CASES = [
        # (card, Scryfall set, number) -> .dck suffix
        (("Sol Ring", "c18", "222"), "|C18|[222]"),
        (("Island", "m21", "264"), "|M21|[264]"),
        # The List: Scryfall `plst`, Forge Code PLST (Code2 PLIST); the
        # original set lives in the collector number.
        (("Mana Leak", "plst", "DDN-64"), "|PLST|[DDN-64]"),
        (("Temple of the False God", "plst", "C18-285"), "|PLST|[C18-285]"),
        # `med` is three Forge editions; the number finds the right one.
        (("Jace, the Mind Sculptor", "med", "WS3"), "|MPS_WAR|[WS3]"),
        # Scryfall's star variant; Forge lists only the plain number.
        (("Force Spike", "7ed", "76★"), "|7ED"),
        (("Fire // Ice", "mh2", "290"), "|MH2|[290]"),
        (("Brazen Borrower // Petty Theft", "eld", "281"), "|ELD|[281]"),
    ]

    def test_cases(self):
        for (card, set_code, number), suffix in self.CASES:
            with self.subTest(card=card, set_code=set_code):
                self.assertEqual(
                    ff.forge_printing(self.index, card, set_code, number).suffix,
                    suffix)

    def test_art_index_follows_forges_order(self):
        # M21's three full-art Islands are 263, 264, 265: arts 1, 2, 3.
        self.assertEqual(
            [ff.forge_printing(self.index, "Island", "m21", n).art_index
             for n in ("263", "264", "265")], [1, 2, 3])

    def test_no_forge_printing(self):
        self.assertIsNone(ff.forge_printing(self.index, "Lightning Bolt", "c18", "1"))
        self.assertIsNone(ff.forge_printing(self.index, "Sol Ring", "zzzz", "1"))

    def test_every_edition_parses(self):
        files = list(FORGE_EDITIONS.glob("*.txt"))
        self.assertEqual(sum(len(group) for group in self.index.values()),
                         len(files))


class TestExportPrintings(ServiceTestCase):
    def test_the_dck_carries_the_printing(self):
        d.remove_card_from_deck("Canlander", "Island", folder=self.folder)
        d.add_card_to_deck("Canlander", "Island", quantity=5, folder=self.folder,
                           set_code="TST", collector_number="265")
        with mock.patch.object(fc, "load_edition_index",
                               return_value=mini_index(MINI_EDITION)):
            out = svc.forge_export(self.canlander, config=self.config)
        text = out.path.read_text(encoding="utf-8")
        self.assertIn("5 Island|TST|[265]\n", text)
        self.assertIn("1 Sol Ring\n", text)

    def test_no_printings_reads_no_editions(self):
        with mock.patch.object(fc, "load_edition_index") as load:
            svc.forge_export(self.canlander, config=self.config)
        load.assert_not_called()


if __name__ == "__main__":
    unittest.main()
