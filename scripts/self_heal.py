"""Bring an existing database up to this build's schema.

Every migration here is additive and idempotent, and the whole list costs a
few milliseconds when there is nothing to do. So `sync.py`, the app and the
CLI all run it on start: a build that adds a table (deck history did) must
not leave the user unable to edit a deck until they remember to run sync.

Each migration announces itself only when it actually changed something.
"""
import contextlib
import io
import sys
from pathlib import Path
from typing import Optional

sys.path.insert(0, str(Path(__file__).parent))
import migrate_add_corrections
import migrate_add_custom_formats
import migrate_add_deck_history
import migrate_add_decks
import migrate_add_folder_format
import migrate_add_legalities
import migrate_add_mana_cost
import migrate_add_nocase_indexes
import migrate_add_oracle_id
import migrate_add_oracle_tags
import migrate_add_scryfall_fields
import migrate_add_tags
import migrate_add_user_combos
import migrate_fix_card_tags_pk
import migrate_unique_deck_names

DB_PATH = Path(__file__).parent.parent / "data" / "mtg.db"

# Every migration, in dependency order, so any database back to the very
# first schema reaches the current one: tests/test_scripts_regressions.py
# replays the initial commit's schema through this list.
MIGRATIONS = (
    # sync_state and the columns every card ingest writes.
    migrate_add_oracle_id,
    migrate_add_mana_cost,
    migrate_add_scryfall_fields,
    migrate_add_legalities,
    migrate_add_custom_formats,
    # decks before the ones that widen it.
    migrate_add_decks,
    migrate_add_folder_format,
    migrate_unique_deck_names,
    migrate_add_deck_history,
    # card_tags must exist before its key can be fixed; the fix re-tags
    # from cards, so it also needs the card columns above.
    migrate_add_tags,
    migrate_fix_card_tags_pk,
    migrate_add_corrections,
    migrate_add_user_combos,
    migrate_add_oracle_tags,
    # Last: it indexes columns the migrations above may have just created.
    migrate_add_nocase_indexes,
)


def run(migrations=MIGRATIONS, db_path: Optional[Path] = None) -> tuple[list[str], list[str]]:
    """Run each migration, against `db_path` when one is given.

    Without `db_path` each migration uses its own DB_PATH — so a caller (or a
    test) that has pointed them at a copy is never silently redirected to the
    real database.

    Returns `(reports, failures)`: `reports` is the printable output of every
    migration that applied or failed, `failures` the names of those that
    failed. A missing database is left alone — creating one is init_db's job.
    One migration refusing (migrate_unique_deck_names exits on duplicate deck
    names and leaves the renaming to the user) does not stop the rest.
    """
    if not Path(db_path or DB_PATH).exists():
        return [], []
    reports: list[str] = []
    failures: list[str] = []
    for migration in migrations:
        saved = getattr(migration, "DB_PATH", None)
        if db_path is not None:
            migration.DB_PATH = db_path
        buf = io.StringIO()
        try:
            with contextlib.redirect_stdout(buf):
                migration.main()
        except (Exception, SystemExit) as e:
            reason = e if isinstance(e, Exception) else f"exit status {e.code}"
            reports.append(buf.getvalue().rstrip())
            reports.append(f"ERR migration {migration.__name__} failed: {reason}")
            failures.append(migration.__name__)
            continue
        finally:
            if db_path is not None:
                migration.DB_PATH = saved
        if "Migration applied" in buf.getvalue():
            reports.append(buf.getvalue().rstrip())
    return [r for r in reports if r], failures


def main() -> None:
    reports, failures = run()
    print("\n".join(reports) if reports else "OK Schema already up to date.")
    if failures:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
