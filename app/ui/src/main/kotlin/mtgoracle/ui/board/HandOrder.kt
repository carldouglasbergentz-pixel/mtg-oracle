package mtgoracle.ui.board

/**
 * The order the viewer has put their hand in, by card id: only the screen's,
 * never Forge's or the seat's. A card the viewer hasn't placed (a draw) comes
 * after the placed ones, in Forge's order; a card that left is forgotten.
 */
fun arrangeHand(placed: List<Int>, hand: List<Int>): List<Int> {
    val inHand = hand.toSet()
    val kept = placed.filter { it in inHand }
    val keptSet = kept.toSet()
    return kept + hand.filter { it !in keptSet }
}

/** [order] with [id] taken out and put back at [to] (an index into the rest, clamped). */
fun moveInHand(order: List<Int>, id: Int, to: Int): List<Int> {
    if (id !in order) return order
    val rest = order - id
    return rest.toMutableList().apply { add(to.coerceIn(0, rest.size), id) }
}

/**
 * Where a card dropped at [centre] lands: after every other card whose centre
 * is left of it. [centres] are the hand's cards' centres in order, the
 * dragged card's own among them at [from].
 */
fun dropIndex(centres: List<Float>, from: Int, centre: Float): Int =
    centres.withIndex().count { (i, c) -> i != from && c < centre }
