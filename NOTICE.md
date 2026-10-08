# Notices and credits

**MTG Oracle is unofficial Fan Content permitted under the Fan Content Policy. Not approved/endorsed by Wizards. Portions of the materials used are property of Wizards of the Coast. ©Wizards of the Coast LLC.**

MTG Oracle is free software under the GNU General Public License, version 3 ([`LICENSE`](LICENSE)). It is free and always will be: nothing in it is behind a payment, an account or a survey.

## Forge

The rules engine and the AI opponent are [Forge](https://github.com/Card-Forge/forge), © its contributors, under the GNU General Public License, version 3. Forge is embedded in the app, which is why the app is GPL-3.0 too.

A release ships Forge built from a branch of our fork, the Duel Commander work ([Card-Forge/forge#12090](https://github.com/Card-Forge/forge/pull/12090)) until a Forge release has it: [`carldouglasbergentz-pixel/forge`, branch `mtg-oracle-forge`](https://github.com/carldouglasbergentz-pixel/forge/tree/mtg-oracle-forge). Each release's `FORGE-SOURCE.txt`, beside the exe, names the exact commit it was built from.

## Card data, images and rules

- **[Scryfall](https://scryfall.com)**: card data (the daily bulk exports), rulings, printings, and the card images the app fetches and caches on your machine. The images are shown whole or as Scryfall's art crop with the artist's credit, never altered. Scryfall doesn't endorse this app. See [Scryfall's API guidelines](https://scryfall.com/docs/api).
- **[Scryfall Tagger](https://tagger.scryfall.com)**: the community's oracle tags (what a card does: removal, ramp, card draw).
- **[Commander Spellbook](https://commanderspellbook.com)**: the combo database. Commander Spellbook is unofficial Fan Content as well; its code is MIT-licensed.
- **The Magic: The Gathering Comprehensive Rules**, © Wizards of the Coast, from [magic.wizards.com](https://magic.wizards.com/en/rules).

Card names, card text, the rules and the card images are property of Wizards of the Coast; card art is © its artists. The test fixture in `app/data/src/testFixtures/fixture/raw/` holds a small cut of these exports (3,000 cards, their rulings and tags, the combos among them, and the rules), so the tests run on data that does not move. The app itself fetches everything from its source, and nothing of it is redistributed in a release.

## The app's own code

MTG Oracle's own code, © the MTG Oracle contributors, is GPL-3.0. It is written with an AI coding assistant (Claude), as the commits credit.
