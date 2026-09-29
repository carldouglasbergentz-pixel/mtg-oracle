# ADR 0001 — A standalone JVM app with Forge embedded

- **Status:** accepted. The step-1 spike passed on 2026-09-29 (see "Spike result").
- **Supersedes:** the 2026-08-19 "stay on Textual" decision in `docs/project-plan.md`

## Context

MTG Oracle is a Python package (SQLite data layer, services, analysis) behind a Textual TUI and a CLI. On 2026-08-19 we chose to stay on Textual, because the UI toolkit was not the bottleneck. Three things have changed since then:

- **Playtesting became the main want.** The user plays reactive decks, and goldfishing says nothing about them. Forge (GPL-3, Java) supplies the rules engine and an AI. Integration v1 runs Forge beside the app: it exports `.dck` files, launches the GUI, and runs `sim` as a subprocess. The user's own games in Forge's window leave no log we can read. Rendering our own board, or recording games, means driving Forge's engine directly, and that engine only runs on the JVM.
- **The user wants a full application, not a terminal program.** Other people may use it later, so a single installable is wanted.
- **The aesthetic still stands.** It should be monospace, two-tone, drawn with box characters, dense, and IDE-like (`docs/app-design.md`). The game board is to share that style: "avskalat", no modern-UI fluff.

## Decision

1. **Kotlin on the JVM, with Compose Multiplatform for the UI.** A desktop window we draw ourselves: monospace grid, box-drawing borders, cards rendered as text blocks, the board included. Compose's Android target keeps Phase 5 (mobile) open. Forge already runs on Android.
2. **Forge is embedded in-process as a library,** not launched beside the app. We use its engine (`forge-game`) and AI (`forge-ai`), and implement its human-player seam (`IGuiGame`, which `PlayerControllerHuman` calls) with our own board. We pin one Forge version. Everything Forge-specific sits behind our own `forge` module, because Forge's internal API is not stable.
3. **The SQLite database and its schema are the contract during the migration.** The new app reads the same `data/mtg.db`. The Python sync keeps producing it until the sync itself is ported.
4. **The Python code is ported, not wrapped.** Its tests (491 on 2026-09-28) are the specification and move with it. The port is incremental: the old TUI keeps working until the new app replaces it.
5. **License: GPL-3, open source.** Embedding Forge makes the whole app a derivative work. The user is fine with this: it is a hobby project, mainly for their own use.
6. **Distribution:** `jpackage` builds one installer with a bundled Java runtime, plus the Forge `res/` data.

## Alternatives considered

- **Keep Python as a local HTTP backend, with a JVM UI and Forge.** No rewrite, but two runtimes to ship and keep in step. That is fine for one user and heavy for more.
- **Stay on Textual, plus a Java sidecar bridge.** This keeps the stack and gets board state through a socket. The board would still be drawn in a terminal, and the user wants to leave terminal mode.
- **Our own rules engine.** Rejected: every one of 30,000+ cards would need executable behaviour, and an AI on top.
- **XMage (MIT).** Client-server only. Its server does not run on a phone, and our client would have to be JVM anyway.

## Consequences

- There is a port to do: search language, decks and format rules, roles/analytics/probability, and eventually the sync. Tests are ported first, feature by feature.
- **The biggest risk is the human-player seam.** `PlayerControllerHuman` makes about 168 UI calls, and the engine thread blocks until each prompt is answered. The step-1 spike exists to prove we can answer real prompts (mulligan, land drop, targets) from our own window before anything is rewritten.
- Forge upgrades become deliberate. Integration is pinned to a version, and upstream changes to the seam can break it.
- The Python code stays in the repo until each piece is replaced, and CLAUDE.md's layering rules carry over to the Kotlin modules.

## Step-1 spike: definition of done

A Kotlin/Compose desktop window, in the monospace/box-drawing style, that:

1. **initialises Forge in-process** from the pinned install (`tools/forge`);
2. **runs an AI-vs-AI game** of two of the user's exported decks, and renders the live board (hands, battlefield, graveyards, life, stack, phase) from Forge's game view;
3. **lets a human seat make real decisions** in our UI: keep or mulligan, play a land, cast a spell, and choose a target;
4. **writes every game event to a log file**, which is the recording that Forge's own GUI can't do.

If (3) proves infeasible, this ADR is revisited before any porting begins.

## Spike result (2026-09-29): the decision holds

`app/` is a Gradle project. It uses the Gradle 9.8 wrapper, Kotlin 2.4.20, Compose Multiplatform 1.12.1 and a JDK 25 toolchain, with the Forge jar as a local file dependency. Every import of Forge is confined to the `mtgoracle.forge` package.

1. **Forge initialises in-process in 6.5–9 s.** Its user data is sandboxed under `app/build/`, and the app refuses to start if the sandbox would point at the real `%APPDATA%\Forge`.
2. **AI vs AI renders live** in the house style, both in a real window and headless: hands, battlefields with tapped and attacking marks, life, library, graveyard and exile counts, the stack, phase, priority and the log.
3. **A human seat decides through our own UI.** A scripted run made 159 decisions as hit-tested clicks on the window's own composable, rendered offscreen, and the game replays identically from a seed. The decisions covered keep and mulligan, play or draw, lands, casting, optional costs, targets, mana payment, attacks, discards and library search.
   - **How the seam works.** Forge's `Input` prompts block the engine on a latch. We answer them with the same four gestures Forge's own board uses: click a card, click a player, OK, Cancel. Direct dialogs block on a future that the UI completes. Forge's "GUI thread" is our own dedicated thread, so the Compose thread never blocks.
   - **What we implemented.** 53 `IGuiGame` overrides on top of Forge's `AbstractGuiGame`. Ordering, damage assignment, number choice and sideboarding are auto-answered, and each auto-answer is logged as `UNHANDLED`.
4. **Every game is recorded** to `app/build/game-logs/`: GameLog lines, about 5,900 engine events, and every prompt, click and answer, roughly 7,000 lines per game.

**Not yet verified:** real mouse and keyboard input in the window, blocking, London-mulligan bottoming, X costs, and targeting spells on the stack.

**Carried into the port:**
- Build from Forge's `forge-gui`, `forge-game` and `forge-ai` modules rather than the 39 MB desktop jar, which bundles Swing.
- Ship our own `res/` and `forge.profile.properties` instead of redirecting `APPDATA`.
- Re-check the seam on every Forge upgrade.
- Keep the board to box-drawing and ASCII characters, because a glyph missing from the font breaks the grid.
