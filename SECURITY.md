# Security

MTG Oracle talks to the network in three ways: the daily sync (Scryfall, Commander Spellbook, Wizards' rules page), card images from Scryfall, and network play, where two apps connect directly ([ADR 0002](docs/adr/0002-network-play.md), [ADR 0003](docs/adr/0003-limited-pools.md)). Network play is where a stranger could reach the app: a hosted room listens on a port the router opens, every line between the apps is sealed with the invite's secret (AES-GCM), and everything a peer sends is checked before anything reads it.

If you find a way past that (into a room without the invite, a crash or a file written from a peer's message, a pool or deck check that can be cheated, anything that leaks a hidden card), please report it privately through GitHub's **Security → Report a vulnerability** on this repository rather than in a public issue. Say what you did and what happened; a way to reproduce it helps most.

Network play trusts the host, by design: the host runs the game and its log keeps everything. A host reading the guest's hand is not a vulnerability; a guest reading the host's is.
