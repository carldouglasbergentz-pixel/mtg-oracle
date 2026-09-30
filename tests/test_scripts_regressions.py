"""Regressions in scripts/: the sync orchestrator, upstream markers, the CLI's
deck flags, the archetype script's self-compare guard, the card and rules
parsers, self-heal migrations from the first schema, and stale-row cleanup.

Every test runs against a throwaway database built from init_db.SCHEMA, and
every network call is replaced — nothing here touches data/mtg.db, data/raw/
or the internet.

Run: python -m unittest discover tests
"""
import contextlib
import gzip
import io
import json
import sqlite3
import sys
import tempfile
import types
import unittest
import urllib.error
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).parent.parent
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "scripts"))
sys.path.insert(0, str(ROOT / "scripts" / "migrations"))

import analyse_archetype  # noqa: E402
import init_db  # noqa: E402
import load_custom_formats  # noqa: E402
import migrate_fix_card_tags_pk  # noqa: E402
import mtg_cli  # noqa: E402
import prune_stale_cards  # noqa: E402
import sync  # noqa: E402
import sync_cards  # noqa: E402
import sync_combos  # noqa: E402
import sync_oracle_tags  # noqa: E402
import sync_rules  # noqa: E402
import tag_cards  # noqa: E402
from mtg_oracle import decks as d  # noqa: E402
from mtg_oracle import queries as q  # noqa: E402


def _offline(*_args, **_kwargs):
    raise urllib.error.URLError("network disabled in tests")


class TempDBTestCase(unittest.TestCase):
    """A fresh schema per test, in a temp dir that is removed afterwards."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp = Path(self._tmp.name)
        self.db = self.tmp / "mtg.db"
        conn = sqlite3.connect(self.db)
        conn.executescript(init_db.SCHEMA)
        conn.commit()
        conn.close()

    def tearDown(self):
        self._tmp.cleanup()

    def query(self, sql, params=()):
        conn = sqlite3.connect(self.db)
        try:
            return conn.execute(sql, params).fetchall()
        finally:
            conn.close()


class TestSyncOrchestrator(TempDBTestCase):
    """#2 and #4: sync.py survives a source's sys.exit and runs in SOURCES order."""

    def run_sync(self, argv, sources):
        patches = [mock.patch.object(sync, "DB_PATH", self.db),
                   mock.patch.object(sync, "SOURCES", sources),
                   mock.patch.object(sys, "argv", ["sync.py", *argv])]
        patches += [mock.patch.object(m, "DB_PATH", self.db)
                    for m in sync.SELF_HEAL_MIGRATIONS]
        out = io.StringIO()
        with contextlib.ExitStack() as stack:
            for p in patches:
                stack.enter_context(p)
            stack.enter_context(contextlib.redirect_stdout(out))
            try:
                sync.main()
                code = 0
            except SystemExit as e:
                code = e.code
        return code, out.getvalue()

    def test_source_calling_sys_exit_does_not_stop_later_sources(self):
        ran = []

        def exits(force=False):
            print("ERR simulated upstream failure")
            sys.exit(1)

        sources = {"first": ("exits", exits),
                   "second": ("records", lambda force=False: ran.append("second"))}
        code, out = self.run_sync([], sources)
        self.assertEqual(ran, ["second"])
        self.assertEqual(code, 1, "a failed source must still fail the run")
        self.assertIn("ERR first failed (exit status 1)", out)
        self.assertIn("=== changelog ===", out)
        self.assertIn("FAIL one or more sources failed: first", out)

    def test_empty_diff_with_a_failure_does_not_claim_up_to_date(self):
        def exits(force=False):
            sys.exit(1)

        _, out = self.run_sync([], {"only": ("exits", exits)})
        self.assertNotIn("all sources already up to date", out)
        self.assertIn("no changes recorded - 1 source(s) failed: only", out)

    def test_only_follows_sources_order_and_dedupes(self):
        ran = []
        sources = {k: (k, (lambda k: lambda force=False: ran.append(k))(k))
                   for k in ("cards", "tags", "oracletags", "formats")}
        code, _ = self.run_sync(
            ["--only", "formats", "oracletags", "tags", "cards", "cards"],
            sources)
        self.assertEqual(code, 0)
        self.assertEqual(ran, ["cards", "tags", "oracletags", "formats"])


class TestUnknownUpstreamMarker(TempDBTestCase):
    """#3: a missing upstream marker never causes a skip and is never stored."""

    def sync_combos_with(self, payload):
        with mock.patch.object(sync_combos, "DB_PATH", self.db), \
                mock.patch.object(sync_combos, "_head_etag", _offline), \
                mock.patch.object(sync_combos, "fetch", lambda: payload), \
                contextlib.redirect_stdout(io.StringIO()):
            sync_combos.sync()

    def test_combos_reingest_every_time_head_fails(self):
        combo = {"id": "1-2", "uses": [{"card": {"name": "A"}}], "produces": []}
        self.sync_combos_with([combo])
        self.sync_combos_with([combo, {**combo, "id": "3-4"}])
        self.assertEqual(self.query("SELECT COUNT(*) FROM combos")[0][0], 2)
        marker = self.query("SELECT updated_at FROM sync_state "
                            "WHERE source = 'spellbook_variants'")[0][0]
        self.assertNotEqual(marker, "unknown")

    def test_combos_keep_previous_marker_when_head_fails(self):
        conn = sqlite3.connect(self.db)
        conn.execute("INSERT INTO sync_state VALUES "
                     "('spellbook_variants', '\"etag-1\"', 'then', 0)")
        conn.commit()
        conn.close()
        self.sync_combos_with([{"id": "1-2", "uses": [], "produces": []}])
        marker, rows = self.query("SELECT updated_at, row_count FROM sync_state "
                                  "WHERE source = 'spellbook_variants'")[0]
        self.assertEqual((marker, rows), ('"etag-1"', 1))

    def test_oracle_tags_without_updated_at_are_never_skipped(self):
        conn = sqlite3.connect(self.db)
        conn.execute("INSERT INTO cards (name, oracle_id) VALUES ('Bolt', 'oid-1')")
        conn.commit()
        conn.close()
        export = self.tmp / "tags.jsonl.gz"

        def write_export(labels):
            with gzip.open(export, "wt", encoding="utf-8") as f:
                for label in labels:
                    f.write(json.dumps({"label": label,
                                        "taggings": [{"oracle_id": "oid-1"}]}) + "\n")
            return export

        meta = {"jsonl_download_uri": "https://example.invalid/tags"}
        for labels in (["removal"], ["removal", "burn"]):
            with mock.patch.object(sync_oracle_tags, "DB_PATH", self.db), \
                    mock.patch.object(sync_oracle_tags, "bulk_meta", lambda: meta), \
                    mock.patch.object(sync_oracle_tags, "fetch",
                                      lambda _uri, labels=labels: write_export(labels)), \
                    contextlib.redirect_stdout(io.StringIO()):
                sync_oracle_tags.sync()
        tags = {t for (t,) in self.query("SELECT tag FROM card_oracle_tags")}
        self.assertEqual(tags, {"removal", "burn"})
        marker = self.query("SELECT updated_at FROM sync_state "
                            "WHERE source = 'scryfall_oracle_tags'")[0][0]
        self.assertNotEqual(marker, "unknown")


class TestCLIDeckFlags(TempDBTestCase):
    """#9: deck flags that were parsed and then ignored or misread."""

    def setUp(self):
        super().setUp()
        conn = sqlite3.connect(self.db)
        conn.executemany(
            "INSERT INTO cards (name, oracle_id, type_line, color_identity, games) "
            "VALUES (?, ?, ?, ?, 'paper')",
            [("Sol Ring", "oid-sol", "Artifact", ""),
             ("Krenko, Mob Boss", "oid-krenko", "Legendary Creature — Goblin", "R")])
        # set_commander checks legality against the format the deck ends up
        # with, and an unset format becomes 'commander'.
        conn.executemany(
            "INSERT INTO card_legalities VALUES (?, 'commander', 'legal')",
            [("Sol Ring",), ("Krenko, Mob Boss",)])
        conn.commit()
        conn.close()
        for p in (mock.patch.object(d, "DB_PATH", self.db),
                  mock.patch.object(q, "DB_PATH", self.db)):
            p.start()
            self.addCleanup(p.stop)
        q.clear_format_cache()
        self.addCleanup(q.clear_format_cache)
        d.create_folder("Goblins")
        d.create_deck("Krenko", folder="Goblins")

    def cli(self, *argv):
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
            code = mtg_cli.main(list(argv))
        return code, out.getvalue()

    def deck_rows(self):
        return self.query("SELECT card_name, quantity, is_commander, is_sideboard "
                          "FROM deck_cards ORDER BY id")

    def test_move_without_new_folder_is_refused(self):
        code, out = self.cli("deck", "move", "Krenko")
        self.assertEqual(code, 2)
        self.assertIn("--new-folder is required", out)
        self.assertIsNotNone(self.query("SELECT folder_id FROM decks")[0][0])

    def test_move_to_unsorted_is_explicit(self):
        code, _ = self.cli("deck", "move", "Krenko", "--new-folder", "")
        self.assertEqual(code, 0)
        self.assertIsNone(self.query("SELECT folder_id FROM decks")[0][0])

    def test_remove_sideboard_is_refused_not_ignored(self):
        d.add_card_to_deck("Krenko", "Sol Ring", folder="Goblins")
        d.add_card_to_deck("Krenko", "Sol Ring", folder="Goblins", is_sideboard=True)
        code, out = self.cli("deck", "remove", "Krenko", "--folder", "Goblins",
                             "--card", "Sol Ring", "--qty", "1", "--sideboard")
        self.assertEqual(code, 2)
        self.assertIn("not supported", out)
        self.assertEqual(len(self.deck_rows()), 2, "nothing may be removed")

    def test_add_commander_promotes_through_set_commander(self):
        d.add_card_to_deck("Krenko", "Krenko, Mob Boss", folder="Goblins")
        code, out = self.cli("deck", "add", "Krenko", "--folder", "Goblins",
                             "--card", "Krenko, Mob Boss", "--commander")
        self.assertEqual(code, 0, out)
        self.assertIn("promoted to commander", out)
        # One promoted row, not a second commander row next to the first.
        self.assertEqual(self.deck_rows(), [("Krenko, Mob Boss", 1, 1, 0)])
        self.assertEqual(self.query("SELECT format FROM decks")[0][0], "commander")

    def test_add_commander_rejects_sideboard_and_qty(self):
        for extra in (["--sideboard"], ["--qty", "2"]):
            code, _ = self.cli("deck", "add", "Krenko", "--folder", "Goblins",
                               "--card", "Krenko, Mob Boss", "--commander", *extra)
            self.assertEqual(code, 2, extra)
        self.assertEqual(self.deck_rows(), [])

    def test_compare_accepts_against_folder(self):
        args = mtg_cli.build_parser().parse_args(
            ["deck", "compare", "A", "--against", "B", "--against-folder", "F"])
        self.assertEqual((args.against, args.against_folder), ("B", "F"))

    def test_import_from_file_strips_bom(self):
        src = self.tmp / "list.txt"
        src.write_bytes("Commander\n1 Krenko, Mob Boss\n".encode("utf-8-sig"))
        args = mtg_cli.build_parser().parse_args(
            ["deck", "import", "X", "--from-file", str(src)])
        self.assertTrue(mtg_cli._read_deck_source(args).startswith("Commander"))


class TestArchetypeSelfCompare(unittest.TestCase):
    """#12: the --compare subject is excluded by path/content, not by name."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self._tmp.name)
        (self.dir / "a.txt").write_text("1 Sol Ring\n1 Island\n", encoding="utf-8")
        (self.dir / "b.txt").write_text("1 Sol Ring\n1 Island\n", encoding="utf-8")
        (self.dir / "c.txt").write_text("1 Sol Ring\n1 Plains\n", encoding="utf-8")

    def tearDown(self):
        self._tmp.cleanup()

    def excluded(self, subject_file, keep_duplicates):
        refs = analyse_archetype.read_dir(self.dir, keep_duplicates)
        subject = analyse_archetype.read_subject(str(self.dir / subject_file), None)
        return [r["name"] for r in refs
                if analyse_archetype.is_same_deck(r, subject, keep_duplicates)]

    def test_duplicate_of_an_earlier_file_is_excluded(self):
        # a and b collapse into one entry named "a"; name matching missed it.
        self.assertEqual(self.excluded("b.txt", keep_duplicates=False), ["a"])

    def test_keep_duplicates_excludes_only_the_file_itself(self):
        self.assertEqual(self.excluded("b.txt", keep_duplicates=True), ["b"])

    def test_collection_deck_never_matches_a_file_by_stem(self):
        refs = analyse_archetype.read_dir(self.dir, False)
        subject = {"name": "c", "cards": {}, "db_deck": "c"}
        self.assertFalse(any(analyse_archetype.is_same_deck(r, subject, False)
                             for r in refs))
        self.assertTrue(analyse_archetype.is_same_deck(
            {"name": "C", "db_deck": "c"}, subject, False))


def _card(name, oracle_id, layout="normal", set_type="expansion",
          legal=None, type_line="Sorcery", ci=()):
    """One oracle_cards export entry, trimmed to what ingest_cards reads."""
    return {"name": name, "oracle_id": oracle_id, "layout": layout,
            "set_type": set_type, "type_line": type_line,
            "color_identity": list(ci), "games": ["paper"],
            "legalities": {"commander": "not_legal", **(legal or {})}}


class TestCardIngestCollisions(TempDBTestCase):
    """#1: a non-card printing no longer overwrites the real card."""

    def ingest(self, entries):
        export = self.tmp / "oracle_cards.jsonl.gz"
        with gzip.open(export, "wt", encoding="utf-8") as f:
            for entry in entries:
                f.write(json.dumps(entry) + "\n")
        conn = sqlite3.connect(self.db)
        try:
            with contextlib.redirect_stdout(io.StringIO()):
                return sync_cards.ingest_cards(conn, export)
        finally:
            conn.close()

    def card(self, name):
        return self.query("SELECT oracle_id, layout, type_line, color_identity "
                          "FROM cards WHERE name = ?", (name,))

    def legalities(self, name):
        return self.query("SELECT format, status FROM card_legalities "
                          "WHERE card_name = ? ORDER BY format", (name,))

    def test_real_card_wins_whichever_order_it_comes_in(self):
        count, collisions = self.ingest([
            # Real card first, novelty second (the order that used to lose).
            _card("No Way Out", "real-nwo", legal={"commander": "legal"}, ci="B"),
            _card("No Way Out", "plane-nwo", layout="planar", set_type="funny",
                  type_line="Plane — Duskmourn"),
            # Memorabilia front face first, real card second.
            _card("Blink", "front-blink", layout="front_card",
                  set_type="memorabilia", type_line="Card"),
            _card("Blink", "real-blink", layout="saga",
                  legal={"commander": "legal"}, type_line="Enchantment — Saga",
                  ci="BU"),
            _card("Counters", "front-counters", layout="front_card",
                  set_type="memorabilia", type_line="Card"),
        ])
        self.assertEqual(self.card("No Way Out"),
                         [("real-nwo", "normal", "Sorcery", "B")])
        self.assertEqual(self.card("Blink"),
                         [("real-blink", "saga", "Enchantment — Saga", "B,U")])
        self.assertEqual(self.card("Counters"), [], "front_card is not a card")
        # front_card entries are skipped before the name check, so only the
        # No Way Out pair counts as a collision.
        self.assertEqual((count, collisions), (2, 1))

    def test_losing_printing_leaves_no_legality_rows_behind(self):
        self.ingest([
            _card("Red Herring", "funny-rh", set_type="funny",
                  legal={"vintage": "banned"}),
            _card("Red Herring", "real-rh", legal={"commander": "legal"}),
        ])
        self.assertEqual(self.card("Red Herring")[0][0], "real-rh")
        self.assertEqual(self.legalities("Red Herring"), [("commander", "legal")])

    def test_tie_keeps_the_first_entry(self):
        self.ingest([
            _card("Everythingamajig", "variant-a", set_type="funny"),
            _card("Everythingamajig", "variant-b", set_type="funny"),
        ])
        self.assertEqual(self.card("Everythingamajig")[0][0], "variant-a")

    def test_sync_py_prints_source_notes_in_the_changelog(self):
        out = io.StringIO()
        with mock.patch.object(sync, "DB_PATH", self.db), \
                mock.patch.object(sync, "SOURCES", {"cards": (
                    "fake", lambda force=False: ["3 duplicate-name entries"])}), \
                mock.patch.object(sync, "SELF_HEAL_MIGRATIONS", ()), \
                mock.patch.object(sys, "argv", ["sync.py"]), \
                contextlib.redirect_stdout(out):
            sync.main()
        changelog = out.getvalue().split("=== changelog ===")[1]
        self.assertIn("cards: 3 duplicate-name entries", changelog)


CR_SAMPLE = """﻿Magic: The Gathering Comprehensive Rules

Contents

1. Game Concepts
100. General
119. Life
Glossary

1. Game Concepts

100. General

100.1. These Magic rules apply to any Magic game.

119.1d. In a two-player Brawl game, each player's starting life total is 25.

205.4c Any land with the supertype "basic" is a basic land.
     Cards printed before Eighth Edition didn't use the word "basic".

606.5 If the total cost contains multiple loyalty costs, they are combined.
Example: A player activates an ability that costs [-4].

702.19b The controller of an attacking creature with trample assigns damage.
Example: A 2/2 blocks a 3/3 with trample.
Example: A 6/6 with trample is blocked by a creature with protection.

704.5aa If a player controls a permanent with start your engines!, speed is 1.

Glossary

Ability
1. Text on an object that explains what that object does or can do.
2. An activated or triggered ability on the stack.
Example: This glossary example belongs to no rule.

Credits
"""


class TestRulesParser(TempDBTestCase):
    """#5: glossary senses are not rules, and examples stay with their rule."""

    def parsed(self):
        return {r[0]: r for r in sync_rules.parse_rules_text(CR_SAMPLE)}

    def test_glossary_senses_are_not_rules(self):
        self.assertFalse([n for n in self.parsed() if "." not in n and int(n) < 100])

    def test_irregular_upstream_numbers_are_parsed(self):
        rules = self.parsed()
        for number in ("119.1d", "606.5", "704.5aa"):
            self.assertIn(number, rules)
        self.assertEqual(rules["704.5aa"][1], "704.5")
        self.assertEqual(rules["606.5"][1], "606")

    def test_examples_and_continuations_attach_to_their_rule(self):
        rules = self.parsed()
        self.assertEqual(rules["702.19b"][3].count("\nExample:"), 2)
        self.assertIn("\nExample: A player activates", rules["606.5"][3])
        self.assertIn("\nCards printed before Eighth Edition", rules["205.4c"][3])
        self.assertFalse(any("glossary example" in r[3] for r in rules.values()))

    def test_ingest_wipes_bogus_rows(self):
        conn = sqlite3.connect(self.db)
        conn.execute("INSERT INTO rules VALUES ('1', NULL, 'Casual Variants', 'x')")
        conn.commit()
        conn.close()
        raw = self.tmp / "raw"
        with mock.patch.object(sync_rules, "DB_PATH", self.db), \
                mock.patch.object(sync_rules, "RAW_DIR", raw), \
                mock.patch.object(sync_rules, "discover_cr_url",
                                  lambda: ("https://example.invalid/cr.txt", "20990101")), \
                mock.patch.object(sync_rules, "_http_get",
                                  lambda *_a, **_k: CR_SAMPLE.encode("utf-8")), \
                contextlib.redirect_stdout(io.StringIO()):
            sync_rules.sync()
        numbers = {n for (n,) in self.query("SELECT rule_number FROM rules")}
        self.assertNotIn("1", numbers)
        self.assertIn("702.19b", numbers)


# The schema of the very first commit (1060bbc): no oracle_id, no sync_state,
# no decks, no tags. Every later table and column must come from migrations.
INITIAL_SCHEMA = """
CREATE TABLE cards (name TEXT PRIMARY KEY, oracle_text TEXT, type_line TEXT);
CREATE TABLE rulings (id INTEGER PRIMARY KEY AUTOINCREMENT,
    card_name TEXT NOT NULL, date TEXT, text TEXT NOT NULL,
    FOREIGN KEY (card_name) REFERENCES cards(name));
CREATE INDEX idx_rulings_card ON rulings(card_name);
CREATE TABLE rules (rule_number TEXT PRIMARY KEY, parent_rule TEXT,
    section_title TEXT, text TEXT NOT NULL);
CREATE INDEX idx_rules_parent ON rules(parent_rule);
CREATE TABLE combos (id TEXT PRIMARY KEY, name TEXT, color_identity TEXT,
    description TEXT);
CREATE TABLE combo_cards (combo_id TEXT NOT NULL, card_name TEXT NOT NULL,
    quantity INTEGER DEFAULT 1, PRIMARY KEY (combo_id, card_name),
    FOREIGN KEY (combo_id) REFERENCES combos(id));
CREATE INDEX idx_combo_cards_card ON combo_cards(card_name);
CREATE TABLE combo_results (id INTEGER PRIMARY KEY AUTOINCREMENT,
    combo_id TEXT NOT NULL, text TEXT NOT NULL);
CREATE TABLE combo_prerequisites (id INTEGER PRIMARY KEY AUTOINCREMENT,
    combo_id TEXT NOT NULL, text TEXT NOT NULL);
CREATE TABLE combo_steps (id INTEGER PRIMARY KEY AUTOINCREMENT,
    combo_id TEXT NOT NULL, step_order INTEGER NOT NULL, text TEXT NOT NULL);
"""


def _shape(db):
    """{table: sorted columns} plus the set of named indexes."""
    conn = sqlite3.connect(db)
    try:
        tables = [t for (t,) in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table' "
            "AND name NOT LIKE 'sqlite_%'")]
        columns = {t: sorted(r[1] for r in conn.execute(f"PRAGMA table_info({t})"))
                   for t in tables}
        indexes = {i for (i,) in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='index' "
            "AND name NOT LIKE 'sqlite_autoindex%'")}
        return columns, indexes
    finally:
        conn.close()


class TestSelfHealFromInitialSchema(TempDBTestCase):
    """#6: sync.py's self-heal list takes the first schema to the current one."""

    def self_heal(self, db):
        """Run the list exactly as sync.main does; return what it printed."""
        out = io.StringIO()
        with contextlib.ExitStack() as stack:
            for m in sync.SELF_HEAL_MIGRATIONS:
                stack.enter_context(mock.patch.object(m, "DB_PATH", db))
            stack.enter_context(mock.patch.object(tag_cards, "DB_PATH", db))
            stack.enter_context(contextlib.redirect_stdout(out))
            for migration in sync.SELF_HEAL_MIGRATIONS:
                migration.main()
        return out.getvalue()

    def test_initial_schema_reaches_init_db_schema(self):
        legacy = self.tmp / "legacy.db"
        conn = sqlite3.connect(legacy)
        conn.executescript(INITIAL_SCHEMA)
        conn.commit()
        conn.close()
        self.self_heal(legacy)
        self.assertEqual(_shape(legacy), _shape(self.db))

    def test_second_pass_changes_nothing(self):
        legacy = self.tmp / "legacy.db"
        conn = sqlite3.connect(legacy)
        conn.executescript(INITIAL_SCHEMA)
        conn.commit()
        conn.close()
        self.self_heal(legacy)
        self.assertNotIn("Migration applied", self.self_heal(legacy))
        self.assertNotIn("Migration applied", self.self_heal(self.db))

    def test_a_refusing_migration_does_not_block_the_sync(self):
        def refuse():
            sys.exit(1)

        Refuses = types.SimpleNamespace(__name__="migrate_refuses", main=refuse)
        ran = []
        out = io.StringIO()
        with mock.patch.object(sync, "DB_PATH", self.db), \
                mock.patch.object(sync, "SELF_HEAL_MIGRATIONS", (Refuses,)), \
                mock.patch.object(sync, "SOURCES", {"cards": (
                    "fake", lambda force=False: ran.append("cards"))}), \
                mock.patch.object(sys, "argv", ["sync.py"]), \
                contextlib.redirect_stdout(out):
            with self.assertRaises(SystemExit) as caught:
                sync.main()
        self.assertEqual(ran, ["cards"])
        self.assertEqual(caught.exception.code, 1)
        self.assertIn("ERR migration migrate_refuses failed", out.getvalue())


class TestCardTagsKeyMigration(TempDBTestCase):
    """#7: the key fix keeps the NOCASE index and is all-or-nothing."""

    def setUp(self):
        super().setUp()
        conn = sqlite3.connect(self.db)
        conn.executescript("""
            DROP TABLE card_tags;
            CREATE TABLE card_tags (card_name TEXT NOT NULL, tag TEXT NOT NULL,
                category TEXT NOT NULL, source TEXT NOT NULL,
                PRIMARY KEY (card_name, tag));
            CREATE INDEX idx_card_tags_card_nocase
                ON card_tags(card_name COLLATE NOCASE);
            INSERT INTO card_tags VALUES ('Urza''s Saga', 'saga', 'keyword', 'regex');
            INSERT INTO cards (name, oracle_text, type_line) VALUES
                ('Urza''s Saga', 'Saga', 'Enchantment Land — Urza''s Saga');
        """)
        conn.commit()
        conn.close()
        p = mock.patch.object(migrate_fix_card_tags_pk, "DB_PATH", self.db)
        p.start()
        self.addCleanup(p.stop)

    def migrate(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            migrate_fix_card_tags_pk.main()
        return out.getvalue()

    def primary_key(self):
        conn = sqlite3.connect(self.db)
        try:
            return migrate_fix_card_tags_pk._pk_columns(conn.cursor())
        finally:
            conn.close()

    def test_rebuild_keeps_every_index_and_both_saga_rows(self):
        self.assertIn("Migration applied", self.migrate())
        pk = self.primary_key()
        self.assertEqual(pk, ["card_name", "tag", "category"])
        indexes = {i for (i,) in self.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='card_tags'")}
        self.assertLessEqual({"idx_card_tags_tag", "idx_card_tags_category",
                              "idx_card_tags_card_nocase"}, indexes)
        categories = {c for (c,) in self.query(
            "SELECT category FROM card_tags WHERE tag = 'saga'")}
        self.assertEqual(categories, {"keyword", "subtype"})
        self.assertIn("already up to date", self.migrate())

    def test_failed_retag_rolls_the_rebuild_back(self):
        with mock.patch.object(tag_cards, "retag", side_effect=RuntimeError("boom")):
            with self.assertRaises(RuntimeError):
                self.migrate()
        pk = self.primary_key()
        self.assertEqual(pk, ["card_name", "tag"], "old table must survive")
        self.assertEqual(self.query("SELECT COUNT(*) FROM card_tags")[0][0], 1)


class TestStaleRows(TempDBTestCase):
    """#10: retired formats and pruned cards leave nothing behind."""

    def test_format_whose_file_is_gone_is_removed(self):
        formats_dir = self.tmp / "formats"
        formats_dir.mkdir()
        (formats_dir / "kept.json").write_text(json.dumps(
            {"format": "kept", "name": "Kept", "points": {"Sol Ring": 1}}),
            encoding="utf-8")
        conn = sqlite3.connect(self.db)
        conn.execute("INSERT INTO cards (name) VALUES ('Sol Ring')")
        conn.execute("INSERT INTO custom_formats (format, name, aliases, updated_at) "
                     "VALUES ('retired', 'Retired', '[\"oldname\"]', 'then')")
        conn.execute("INSERT INTO custom_format_points VALUES ('retired', 'Sol Ring', 3)")
        conn.execute("INSERT INTO decks (name, format, created_at, updated_at) "
                     "VALUES ('D', 'OldName', 'then', 'then')")
        conn.commit()
        conn.close()
        out = io.StringIO()
        with mock.patch.object(load_custom_formats, "DB_PATH", self.db), \
                mock.patch.object(load_custom_formats, "FORMATS_DIR", formats_dir), \
                mock.patch.object(load_custom_formats, "resolve_card_name",
                                  lambda name: name), \
                contextlib.redirect_stdout(out):
            load_custom_formats.sync()
        self.assertEqual(self.query("SELECT format FROM custom_formats"), [("kept",)])
        self.assertEqual(self.query("SELECT format, points FROM custom_format_points"),
                         [("kept", 1)])
        self.assertIn("removed format 'retired'", out.getvalue())
        self.assertIn("1 deck(s) still name it", out.getvalue())

    def test_prune_cleans_every_table_keyed_on_the_card(self):
        conn = sqlite3.connect(self.db)
        conn.executemany(
            "INSERT INTO cards (name, oracle_id, color_identity, games, layout) "
            "VALUES (?, ?, '', 'paper', ?)",
            [("Counters", "o1", "front_card"), ("Cats", "o2", "front_card"),
             ("Sol Ring", "o3", "normal")])
        conn.execute("INSERT INTO custom_formats (format, name, updated_at) "
                     "VALUES ('f', 'F', 'then')")
        for name in ("Counters", "Cats", "Sol Ring"):
            conn.execute("INSERT INTO card_oracle_tags VALUES (?, 'tag', '')", (name,))
            conn.execute("INSERT INTO custom_format_points VALUES ('f', ?, 1)", (name,))
        conn.execute("INSERT INTO decks (name, created_at, updated_at) "
                     "VALUES ('D', 'then', 'then')")
        conn.execute("INSERT INTO deck_cards (deck_id, card_name, added_at) "
                     "VALUES (1, 'Cats', 'then')")
        conn.commit()
        conn.close()
        with mock.patch.object(prune_stale_cards, "DB_PATH", self.db), \
                mock.patch.object(sys, "argv", ["prune", "--yes"]), \
                contextlib.redirect_stdout(io.StringIO()):
            prune_stale_cards.main()
        # Counters is gone everywhere; Cats is kept because a deck uses it.
        for table, column in (("cards", "name"), ("card_oracle_tags", "card_name"),
                              ("custom_format_points", "card_name")):
            names = {n for (n,) in self.query(f"SELECT {column} FROM {table}")}
            self.assertEqual(names, {"Cats", "Sol Ring"}, table)


if __name__ == "__main__":
    unittest.main()
