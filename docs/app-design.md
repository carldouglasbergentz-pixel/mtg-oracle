# MTG Oracle — App Design Intent

Forward-looking design document for when the SQLite knowledge base graduates
into a user-facing companion app (target platform: Android, likely also
desktop). Not a technical spec — a North Star for aesthetic and interaction
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

## Explicit non-goals

- No color beyond the two-tone palette (optional accent aside).
- No card images / foil effects / animations.
- No social / collection / marketplace features.
- No attempt to replicate official MTG client UX.
