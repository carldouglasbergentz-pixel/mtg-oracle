# ADR 0003 — Limited: Forge's packs, our pools, each side opening its own

- **Status:** accepted 2026-10-08. Step 1 (sealed against the AI) and step 2 (sealed at a network table, protocol 2) are built.

## Context

The user wants limited play: a tab of its own in the lobby, a set chosen together (with a friend at a network table, or against the AI), sealed first, and later something like a role-playing game, where a pool grows as games are won. Forge already has the engine of it: each edition file says what a pack holds (`Booster=`, which for the newer sets is the play booster), `BoosterGenerator` opens one, `SealedDeckBuilder` builds the AI's deck from a pool, and its quest mode is a whole economy of credits and packs. Forge's quest and sealed screens are tied to its own GUI; the generator and the deck builder are not.

Two questions decide the design. Where does a pool live? And at a network table, who opens whose packs, and can anyone cheat?

## Decision

1. **Forge opens the packs and builds the AI's deck, behind `forge` (`ForgeLimited`).** The sets offered are Forge's editions with a booster, without the digital-only ones (`Type=Online`: Alchemy, MTGO's remasters) and the Un-sets, whose cards Forge mostly lacks. `LimitedTest` opens every offered set and finds each card in the database.
2. **A pool is ours, in the database, written once as it was opened** (schema v5: `limited_pools`, `limited_pool_cards`, each card in the printing it came in). A limited deck is an ordinary deck (`decks.format = 'sealed'`, `decks.pool_id`) whose sideboard starts as the whole pool: building it is moving cards to the main deck, and analysis, export, play and records work as for any deck. The pool rule (`PoolRule`) holds the deck to what was opened, basic lands free. It is a deck rule like the others: `--force` passes it, and the deck says so (`3 beyond the pool!`).
3. **The AI's pool is stored, its deck never.** It is opened with yours, linked to it (`rival_pool_id`), and built by Forge's AI as a match starts: the player doesn't see what the AI opened, and a game against it is recorded with no deck id for the AI.
4. **Each side opens its own pool, from a seed neither can steer** (step 2). Forge's random numbers are seeded per opening (`MyRandom`, set for the call and put back), so the same Forge opens the same packs from the same seed in any JVM; `ForgeLimitedTest` holds recorded digests to it. At a table:
   1. each side draws a secret and sends only its hash (the sealed envelope);
   2. each then sends the other an open random number;
   3. a pool is opened from its owner's secret and the other side's number, so its owner can't choose a good one (the other's number came after the secret was fixed), and the other side can't see it (it lacks the secret);
   4. a deck is sent with its owner's secret: the other side checks the secret against the envelope, opens the same packs itself, and checks the deck against them with `PoolRule`.
5. **Both sides must have the same packs.** Network play requires the same app version, and a sealed table's hello carries a digest of what the host's Forge opens for the set from a fixed seed (`ForgeLimited.packsDigest`): that is what packs depend on, measured rather than listed. A guest whose app opens other packs is turned away at the door.

The steps are `SealedHost` (the host's, before the match) and `RemoteSeat` with a `SealedSeat` (the guest's), over messages of their own (`Envelope`, `Nonce`, `Ready`, `Deck`, `Verdict`, `Reveal`); `Sealed.judge` is the one judgement of a deck against a pool, on both sides. The host's `Ready` carries a digest of its deck, and the `Reveal` after the match must match it. What a peer sends is checked before anything reads it: hex of the right length, a table of 1 to 12 packs, the set as this app names it, a refusal's text cut and cleaned; twenty decks turned away close the table.
6. **The cards are the record, the seed is the proof.** A pool's cards are stored, not only its seed: a later Forge may open other packs from the same seed.

## What Forge had to change

`BoosterSlot.replaceSlot()` rolled with `Math.random()`, the one draw in booster generation that `MyRandom.setRandom()` could not seed, so every edition written with booster slots (Bloomburrow, Duskmourn, Foundations and the newer sets) opened differently under the same seed. It draws from `MyRandom` now: one line, offered to Forge as a pull request of its own, and carried in our Forge build (`tools/forge-dc`, branch `mtg-oracle-forge`: the Duel Commander branch plus that commit) until Forge has it.

## Trust

The host is still trusted (ADR 0002): once a match starts, the guest's deck and sideboard (the rest of their pool) are in the host's Forge, which runs the game. What the envelope adds is that neither side sees the other's pool while it is built, and neither can open packs until it likes them. A host can still close the room and open another, which reads as a new invite.

## Alternatives considered

- **The host opens both pools.** Simple, and the guest can't cheat, since the host checks the deck against the pool it sent. But the host sees the guest's pool while it is built, and could open its own again until it liked it. The user wanted two instances, each opening its own.
- **Each side opens its own, unchecked.** A changed client could claim any pool.
- **A pool as a deck only, no tables of its own.** Nothing would hold a deck to what was opened, and nothing would be left to grow into a campaign.
- **Forge's quest mode as it is.** Tied to its GUI singletons and its own saves; the formulas and price lists can be borrowed when the campaign comes, the controller can't.

## Consequences

- A Forge update that changes packs changes `ForgeLimitedTest`'s digests: deliberate, and both sides of a table then need that Forge.
- Packs are opened only while no game is on, since a game draws from the same random numbers.
- The campaign (a pool that grows) has its place: more `limited_pool_cards` rows, with `pack_no` 0 for a card that came from no pack.
