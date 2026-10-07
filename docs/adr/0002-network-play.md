# ADR 0002 — Network play: the host's engine, a remote seat, a dumb relay

- **Status:** accepted 2026-10-07. Steps 1 (two people at one table) and 2 (the protocol) are built; 3 (a remote seat over localhost) is next, the relay after it.

## Context

The user wants to play their decks against friends, not only against Forge's AI. The seam was built for it from the start (CLAUDE.md, "the seat is the future network boundary"): a `GameSeat` is plain data in and four gestures out, hidden information is filtered per viewer inside `forge`, and answers are asynchronous. A spike on 2026-10-07 put two `SeatGui`s at one Forge table and played whole games, the user's Duel Commander decks included, with neither seat reading the other's hand.

Two constraints came from the user: no install beyond the app (no Tailscale or similar), and no open lobby. Their Cloudflare account (Workers Paid, used by the music library's Workers) is available.

## Decision

1. **The host runs Forge; the guest runs no engine.** The guest's window is the same `BoardScreen` over a `RemoteSeat`, a `GameSeat` fed by messages. The host is authoritative, and what reaches the guest has already been filtered for the guest inside `forge`.
2. **Our own protocol, not Forge's net play.** Forge's is tied to its GUI and Java serialisation. Ours is the seam's own types, `@Serializable` in `core`, one JSON object per line (`net/Wire.kt`), under one `PROTOCOL_VERSION` that both sides must share exactly. The board travels without its log; the log travels as the lines the guest lacks (`LogStream.kt`), because a board carries the whole match's log and is sent on every change.
3. **`net` is a module of its own,** depending on `core` only: messages, the handshake, the log stream, and later the links. No Forge, no Compose, no database in it.
4. **The transport is a dumb relay on Cloudflare** (a third Worker on the user's account, a Durable Object per room, nothing shared with the music library). It pairs two connections and forwards bytes. The room code has two halves: the room's name, which the relay sees, and a secret it never does, from which both sides derive an end-to-end key. The relay sees only ciphertext, so the protocol can change without it.
5. **No open lobby.** A room waits for exactly one guest with the right code.

## Alternatives considered

- **Tailscale or a similar private network.** No server, but every friend installs and joins it. The user ruled it out.
- **Direct IP with port forwarding.** No server, but a port open to the internet, router setup, and nothing at all behind carrier-grade NAT, which is common.
- **NAT hole punching (WebRTC and the like).** A large dependency and far more ways to fail.
- **A hand-written codec instead of the serialization plugin.** No new build plugin, but some 40 fields kept in step by hand.

## Consequences

- The seam's rules now bind harder: a type a `GameSeat` carries must stay serialisable, and a change to one is a protocol change (`PROTOCOL_VERSION`).
- Two people's games are not recorded until the relay is real: `games.mode` needs a schema v4 for them.
- Measured on 2026-10-07 over real games: a board message is 7–11 KB on average, at most 17 KB; a log message under 1 KB on average. The whole log, which a board would otherwise carry, reached 160 KB in twelve turns.
- The relay costs money only past what Workers Paid includes; it needs limits of its own (step 4).
