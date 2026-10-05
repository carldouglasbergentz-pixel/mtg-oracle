package mtgoracle.forge

import com.google.common.eventbus.EventBus
import com.google.common.eventbus.Subscribe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A handler of ours that throws inside an event bus is reported, not left
 * to Guava's stderr line; a failure with no frame of ours is Forge's own.
 */
class HandlerFailuresTest {
    class Throws(private val error: () -> RuntimeException) {
        @Subscribe fun on(@Suppress("UNUSED_PARAMETER") event: String) { throw error() }
    }

    @Test
    fun `a subscriber of ours that throws is reported with where it failed`() {
        HandlerFailures.install()
        val heard = mutableListOf<String>()
        val before = HandlerFailures.count
        HandlerFailures.listen { heard += it }.use {
            EventBus("test").apply { register(Throws { NullPointerException("a null in a cache") }) }.post("event")
        }
        assertEquals(before + 1, HandlerFailures.count)
        assertTrue(heard.single().let { it.startsWith("HandlerFailuresTest") && it.endsWith("java.lang.NullPointerException: a null in a cache") }, "where it failed and what: $heard")
    }

    @Test
    fun `a failure with no frame of ours is left to Forge`() {
        HandlerFailures.install()
        val before = HandlerFailures.count
        val foreign = { RuntimeException("Forge's own").apply { stackTrace = arrayOf(StackTraceElement("forge.game.Game", "fire", null, 1)) } }
        EventBus("test").apply { register(Throws(foreign)) }.post("event")
        assertEquals(before, HandlerFailures.count)
    }
}
