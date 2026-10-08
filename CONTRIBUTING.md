# Contributing

Bug reports are the most welcome thing: what you did, what you expected, what happened, and the version (the window's title says it). For a game that went wrong, the game's log helps most: `data\game_logs\`, the newest file. For anything about network play's safety, see [SECURITY.md](SECURITY.md) first.

Code is welcome too. Before a change of any size, read:

- [`CLAUDE.md`](CLAUDE.md): the project's rules (schema semantics, the module layers, what the seam to Forge must keep). It is written for Claude Code, which this project is developed with, and it is the most complete description of how the app is built.
- [`docs/adr/`](docs/adr/): why it is built as it is.
- [`README.md`](README.md): building and running it.

What a change needs:

- **Tests.** `gradlew test` from `app/` runs every module; a change says what it checked, and a fix comes with the test that would have caught it.
- **One logical change per commit**, described by what it does and why, with an entry in [`CHANGELOG.md`](CHANGELOG.md).
- **Discussion first** for anything that changes the schema, the network protocol, or Forge itself: open an issue.

By contributing you agree that your contribution is licensed under the GPL-3.0, as the project is ([`LICENSE`](LICENSE)).
