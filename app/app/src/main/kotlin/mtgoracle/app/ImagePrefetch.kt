package mtgoracle.app

import mtgoracle.core.deck.AiCopy
import mtgoracle.core.deck.Deck
import mtgoracle.forge.ForgeRuntime

/** The image keys a deck needs: its printings, plus what its AI copy plays instead. */
object ImagePrefetch {
    fun keysFor(deck: Deck): List<String> {
        val rows = AiCopy.asBuilt(deck).cards + (AiCopy.aiCopy(deck)?.cards ?: emptyList())
        return rows.mapNotNull { ForgeRuntime.images.keyFor(it.forgeName, it.setCode, it.collectorNumber) }.distinct()
    }
}
