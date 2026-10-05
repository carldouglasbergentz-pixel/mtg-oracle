package mtgoracle.forge

import com.google.common.eventbus.Subscribe
import forge.game.Game
import forge.game.event.GameEventSpellAbilityCast
import forge.game.trigger.TriggerHandler
import forge.game.trigger.TriggerType
import forge.game.zone.ZoneType

/**
 * A diagnostic for cast triggers Forge leaves out. In real games prowess
 * missed Ghost Vacuum, and Jori En and Cori-Steel Cutter missed a second
 * spell, though the rules say they trigger and every staged game has them
 * trigger. At each spell cast this asks Forge's own trigger state: are cast
 * triggers suppressed, and is each cast trigger on the caster's permanents
 * among the active ones? It writes a `NOTE TRIGGERS` line only when something
 * is off, so the next game it happens in says why.
 */
internal class TriggerWatch(private val recorder: GameRecorder) {
    @Volatile private var game: Game? = null

    fun attach(game: Game) {
        if (this.game === game) return
        this.game = game
        game.subscribeToEvents(this)
    }

    @Subscribe
    fun onCast(event: GameEventSpellAbilityCast) {
        val item = event.si() ?: return
        if (item.isAbility || item.isTrigger) return
        val g = game ?: return
        runCatching {
            val handler = g.triggerHandler
            val caster = g.players.firstOrNull { it.id == item.activatingPlayer?.id } ?: return
            val active = field<List<*>>(handler, "activeTriggers")
            val problems = buildList {
                if (field<Boolean>(handler, "allSuppressed")) add("all triggers suppressed")
                if (TriggerType.SpellCast in field<Set<*>>(handler, "suppressedModes")) add("cast triggers suppressed")
                val missing = caster.getCardsIn(ZoneType.Battlefield).flatMap { card ->
                    card.triggers.filter { it.mode == TriggerType.SpellCast && active.none { a -> a === it } }.map { card.name }
                }
                if (missing.isNotEmpty()) add("cast triggers not active on ${missing.joinToString()}")
            }
            if (problems.isNotEmpty()) recorder.note("TRIGGERS casting ${item.sourceCard?.name}: ${problems.joinToString("; ")}")
        }.onFailure { recorder.note("TRIGGERS check failed: $it") }
    }

    private companion object {
        @Suppress("UNCHECKED_CAST")
        fun <T> field(handler: TriggerHandler, name: String): T =
            TriggerHandler::class.java.getDeclaredField(name).apply { isAccessible = true }.get(handler) as T
    }
}
