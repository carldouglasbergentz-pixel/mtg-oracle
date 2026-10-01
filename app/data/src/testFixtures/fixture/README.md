# The frozen test fixture

Cut from the upstream exports on 2026-10-01 and never refreshed, so the tests
compare against data that does not move:

- `raw/`: 3,000 Scryfall export lines (every line of an included name, so name
  collisions stay), their rulings, their Tagger oracle tags, the Spellbook
  variants whose cards are all included, and the full Comprehensive Rules.
  The cards are those of the reference lists, the Canadian Highlander points
  list, the parity tests' own cards and search pages, and a seeded sample.
- `raw/scryfall_default_cards.jsonl.gz` and `raw/scryfall_sets.json` (added with the printings source):
  every printing of twelve test cards in Scryfall's `default_cards`, plus three edge cases (an MTGO-only
  printing, a token, a set printed in French only), and the sets they belong to.
- `formats/`, `decklists/`: the points list and the reference lists as they were.
- `python-v0-schema.sql`: the schema the Python migrations left, version 0.
- `expected/`: what the Python original answered on this data, recorded once
  before it was retired (git tag `python-final`).

`FixtureDb` (in `../kotlin/`) builds the database from these files with the
app's own migrate and sync. A deliberate change in behaviour edits the
expected lines in the same commit.
