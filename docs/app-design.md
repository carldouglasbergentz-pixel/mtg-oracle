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
  Themes (F8, remembered) are each such a pair, a dimmed tone and one
  accent: the house default, Rosé Pine, a light "paper", a code-editor
  dark and Solarized dark. The first run takes the TUI's saved theme.
  One exception, a *status* tone rather than a third colour: **tapped**
  has a red of its own in every theme (Rosé Pine's love, VS Code's error
  red, a Solarized red lightened to read on base03). It is used only for
  the TAPPED label and a tapped frame's border, at 4.5:1 or better against
  the background, because a tapped card missed is a game lost.
- **Looks of other eras** (F8, beside the themes): Windows 95 so far, with
  XP, Vista, 7 and a modern Linux desktop (KDE Breeze) to follow, for people
  who don't take to the IDE look. These are a deliberate exception to the
  rules here: they draw bevels, title bars and buttons (`Theme.chrome`). The
  house look stays the default and is unchanged. Two rules hold for them as
  well: the chrome is drawn in the cells the character border takes, so a
  look never moves anything, and the panes' content stays in the grid font.
- On the table a tapped permanent is also *turned* a quarter clockwise, art
  and all, as a hand turns it, and scaled by the same factor both ways to
  stay inside its upright slot, so tapping moves nothing around it. R
  turns this off (remembered). Text-mode frames stay upright and rely on
  the tone.
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
- **Floating mana is a stop.** Mana you floated is mana you meant to use. While it is in your pool, the game never passes for you, whatever the stops, F4 or F6 say. Passing when that could end the step (the stack is empty) asks first: `{W}{W} is floating and empties when this step ends — pass anyway?`.
- **Keys:**
  - F2: OK or pass once.
  - F4: done for the turn, but stop if the opponent acts.
  - F6: skip the rest of the turn.
  - F3: cancel auto-yields.
  - Enter / Esc / 1–9 in prompts.
  Keyboard-first, like the rest of the app.
- **A table, in type zones.** From the midline out to each player's edge: creatures (an animated manland or artifact creature among them, attachments under their host), then planeswalkers and battles, then artifacts and enchantments, then lands at the edge. As on a physical table, the nonland zones sit at the midline and the lands at the player's own edge (just above your hand; at the top of the opponent's half), with the free rows between them, so the lands stay put as creatures come and go. Lands are stacked MTGO-style (`Island ×3`) and split whenever state differs. Each zone is labelled on its rule line, and piles of one card stay side by side: untapped first, tapped right after.
- **Space goes where the cards are, and no card is ever cut off.** Each half gets the rows its cards need and no more (the opponent's gives up what it doesn't use), and the zones within a half share rows when there are few. When a half can't show every card at full size, its frames step down: first a compact art frame (a shorter crop, the credit, stats with cost and type on one line), then a text-sized frame. The hand follows your side's size. Sizes change only when the window does or a zone crosses a row, never on a hover, click or tap. A row with more cards than width overlaps them MTGO-style, each card keeping its title strip showing and clickable, and the one under the mouse comes to the top. Only when even that can't hold them does the row scroll sideways, with a `+N ▸` count. The zone column gives the graveyard and exile lists what height is left (the stop ladder folds to one line in a short window), so nothing is drawn over anything. Placement is computed, so a click, a hover or a tap moves nothing; only a card arriving or leaving can re-plan its row. Each player has a chess clock (optional).
- **The news is at the top, the middle is quiet.** A fixed header above the opponent's half carries a heavy rule line with turn, phase, whose turn and priority, the stack's top item, combat and the match, and under it the trail. The midline between the halves is only the heavy `═` rule, so nothing flickers in the middle of the table.
- **The stack floats over the table** while anything is on it. It is a solid, bordered panel anchored on the midline at the table's right end. Each item shows its source's card frame with art (a sacrificed fetchland's too), who put it there (▲ across the table, ▼ you), whether it is a spell or an activated or triggered ability, its text, and its targets. The targets are marked `◄` on the board. The panel takes no layout space: it can be dragged by its top edge (the place is remembered), folded with S to a one-line `stack: N` bar on the header's rule, and it steps across the midline, or folds, whenever it would cover a card the current prompt wants clicked. It is the stack's only view: the header names the top item (`STACK 2: Mana Leak`) and any combat, a click on an item picks it as a target, and hovering an item shows its full text and targets in the zoom pane. The right column is the zoom pane and the log.
- **The other side's actions are visible, not only logged.** Anything the opponent puts on the stack stops you, as on MTGO, F4 included. The prompt says so: "AI activated Polluted Delta — respond?". What never waits on the stack, or resolves before you notice, goes on the trail: up to two lines in the header with the other side's last few actions (older ones are in the log), such as `AI: played Polluted Delta · Polluted Delta → graveyard · life 20→19 · fetched Swamp`, new ones in the accent. Cards they touched carry a `*` until your next decision. Hidden cards are counted, never named ("drew a card").
- **Every game is recorded:** events, prompts and answers, feeding per-card statistics later.

## Explicit non-goals

- No color beyond the two-tone palette (optional accent aside). Card art inside our own frames is the one exception, and it can be switched off.
- No foil effects, no animations.
- No social / collection / marketplace features.
- No attempt to replicate official MTG client UX. We borrow MTGO's interaction model, not its look.
