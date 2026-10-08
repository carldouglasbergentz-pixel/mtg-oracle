# Roadmap

What MTG Oracle does today is in the [README](README.md), and how it got there in the [CHANGELOG](CHANGELOG.md). This is what comes next, and the ideas waiting their turn. Nothing here is a promise or has a date.

## Next

- **A limited campaign: a pool that grows.** Sealed works today, against the AI and with a friend ([ADR 0003](docs/adr/0003-limited-pools.md)). The next step is a pool that lives on: win games, earn packs, swap cards in, in the spirit of Forge's quest mode, whose formulas and price lists can be borrowed. Not designed yet.

## Ideas waiting their turn

- **Commander for four.** A game of multiplayer Commander, you against three AIs first, people over the network after. Forge's engine already plays it for any number of players (40 life, commander damage, each creature attacking the player or planeswalker it is sent at), and its AI plays it soundly if simply. What is two-player is ours: the board's two halves, a result as "me or the opponent", and a game that ends when you leave it, where at a table of four the others play on.
- **Packages that carry limited pools.** A `.mtgoracle` export keeps decks, history and games, but not the packs a sealed deck was opened from: imported, it is an ordinary deck of format `sealed`.
- **A board you arrange.** Move your own permanents on the table as on a real one, as the hand already can be, without breaking that nothing moves on a click but what changed.
- **More community formats**: Leviathan, Penny Dreadful, and Old School where the community's lists differ from Scryfall's.
- **Printed and flavour names**: a Universes Beyond card exported under its printed name, resolved to its Oracle name on import.
- **Fuzzy combo matching**: combos found where a deck holds a card that does the same job as the one Spellbook names.
- **A relay for network play**, for two players whose routers can't host ([ADR 0002](docs/adr/0002-network-play.md)): designed, parked.
- **Back to Forge's own releases** once one plays Duel Commander ([Card-Forge/forge#12090](https://github.com/Card-Forge/forge/pull/12090)).
- **Further out**: a mobile port (Compose and Forge both run on Android), and a layer that answers questions about your decks in words.

Bug reports and ideas are welcome as issues ([CONTRIBUTING.md](CONTRIBUTING.md)).
