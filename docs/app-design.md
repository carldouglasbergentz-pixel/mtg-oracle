# MTG Oracle — App Design Intent

Forward-looking design document for when the SQLite knowledge base graduates
into a user-facing companion app (target platform: Android, likely also
desktop). Since 2026-09-29 that app is the Kotlin/Compose JVM app with Forge
embedded (`docs/adr/0001-standalone-jvm-app-with-embedded-forge.md`). Not a technical spec — a North Star for aesthetic and interaction
decisions so implementation choices stay coherent.

## Visual language

**Terminal / monochrome developer-tool aesthetic.** Think Notepad++, VS Code
(high-contrast dark theme), Claude Code, classic Linux console. Explicitly
*not* trying to emulate a paper Magic card or a glossy TCG client.

- Primary palette: two colors — background and foreground. Optional subtle
  accent for selection / active state. Default is dark-on-light-text.
- Typography: monospace everywhere. Fixed-width grid is load-bearing —
  layout, ASCII art, and alignment all depend on it.
- Chrome: minimal. Borders drawn with box-drawing characters
  (`┌─┐ │ └─┘`) rather than GUI widgets. No rounded corners, no drop
  shadows, no skeuomorphism.
- Information density favored over whitespace. Assume the user wants a lot
  of data per screen.

## Card rendering: ASCII art per card

Every card in the UI is represented as a text-block rendering generated
from `cards.oracle_text`, `type_line`, mana cost, and (for creatures) P/T.
Mock-up for a single card view:

```
┌────────────────────────────────────┐
│ Deathrite Shaman          {B/G}    │
│────────────────────────────────────│
│ Creature - Elf Shaman              │
│────────────────────────────────────│
│ {T}: Exile target instant or       │
│ sorcery card from a graveyard.     │
│ Each opponent loses 2 life.        │
│                                    │
│ {B}, {T}: Exile target creature    │
│ card from a graveyard. Each        │
│ opponent loses 2 life.             │
│                                    │
│ {G}, {T}: Exile target land card   │
│ from a graveyard. Add one mana of  │
│ any color.                         │
│────────────────────────────────────│
│ 1 / 2                              │
└────────────────────────────────────┘
```

Open questions for later (do not solve now):
- Fixed width (40 cols? 50?) vs. adaptive to viewport?
- Art rendering: pure text-only, or should we also generate ASCII-art
  silhouettes from Scryfall image data per card?
- Multi-face cards: render side-by-side (`side_a │ side_b`) or stacked?

## Layout & interaction

Inspiration: IDE-style multi-pane split with keyboard navigation.

Rough intent (not locked):
- **Left pane**: navigation / search / deck list.
- **Center pane**: current card(s) rendered as ASCII blocks; or rules text
  when a rules query is active.
- **Right / bottom pane**: context — rulings for the focused card, related
  CR rules, combos involving the card.
- **Status line** (bottom): current mode, source freshness (last sync), any
  active filter.
- **Command palette** (`:` or `Ctrl-K`): run any query as a text command.
  `ruling deathrite shaman`, `combos with thassa's oracle`, etc.

Keyboard-first. Mouse/touch support is a secondary concern, not a driver of
the layout.

## Data/app boundary

The app is a thin client over the same SQLite schema currently being built.
It consumes `cards`, `rulings`, `rules`, `combos`, and the planned
`card_tags` / `card_abilities` tables. It does **not** own any data — a
fresh install can bootstrap by running the existing `sync.py` orchestrator.

LLM integration (when added) sits beside the app, not inside it: a
tool-use agent reads the same DB via the same SQL tools a human query
would use.

## Card images (decided 2026-09-29)

The app is no longer text-only. Card art is used the way an IDE uses an image preview, not the way a TCG client uses a card:

- **On the board, cards stay compact frames of our own.** The frame is monospace and two-tone, drawn with box characters, with name, cost, type and P/T as text. The card's real art sits inside the frame, and density stays the priority.
- **A zoom pane** shows the full card image for the card under the cursor or selected, as in MTGO. This is where you read an unfamiliar card.
- **A text-mode toggle** drops the art and gives the ASCII blocks above, for maximum density.
- **Art follows the printing.** A deck row keeps the printing the user chose: set plus collector number, taken from the paste (Moxfield writes `1 Sol Ring (C18) 263`), and changeable in the app. It carries through to Forge (`Sol Ring|C18`).
- **Source:** Forge fetches images from Scryfall and caches them per edition. With the engine in-process we use its image keys and its cache rather than building our own. Images are cached, so the app works offline once they are fetched. Scryfall's terms apply: never crop off the copyright or the artist, and never distort or recolour.

## The game board (decided 2026-09-29)

The board shares the visual language above. From MTGO, the client built for exactly the timing-heavy play the user favours, it takes the interaction model:

- **Phase stops** per step, user-configurable: where you get priority and where the game flows past. Without them a draw-go deck either stops constantly or misses the window for its instant.
- **Keys:**
  - F2: OK or pass once.
  - F4: done for the turn, but stop if the opponent acts.
  - F6: skip the rest of the turn.
  - F3: cancel auto-yields.
  - Enter / Esc / 1–9 in prompts.
  Keyboard-first, like the rest of the app.
- **The stack is its own pane,** always visible. Lands sit on their own row, apart from other permanents. Each player has a chess clock (optional).
- **Every game is recorded:** events, prompts and answers, feeding per-card statistics later.

## Explicit non-goals

- No color beyond the two-tone palette (optional accent aside). Card art inside our own frames is the one exception, and it can be switched off.
- No foil effects, no animations.
- No social / collection / marketplace features.
- No attempt to replicate official MTG client UX. We borrow MTGO's interaction model, not its look.
