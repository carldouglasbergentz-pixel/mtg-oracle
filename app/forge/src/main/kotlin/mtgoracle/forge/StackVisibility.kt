package mtgoracle.forge

/**
 * Whether an item on the stack names its source to a seat that may not see
 * the source card where it is now.
 *
 * A spell is its card, so it follows the card's own visibility. An ability
 * on the stack is public together with its source (the stack is a public
 * zone, CR 400.2; an ability exists apart from its source, CR 113.7a), even
 * once the source has left for a hidden zone: Hawkeye's Trick Arrows
 * resolved with Hawkeye already Condemned to the bottom of the library, and
 * its "explosive" trigger read as "a hidden spell". An ability of a card in
 * hand is shown as the card is (cycling, channel). Only a face-down source
 * stays unnamed.
 */
internal fun abilityNamesItsSource(isAbility: Boolean, sourceFaceDown: Boolean): Boolean = isAbility && !sourceFaceDown
