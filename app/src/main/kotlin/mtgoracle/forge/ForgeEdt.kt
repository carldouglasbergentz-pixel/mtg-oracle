package mtgoracle.forge

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The thread Forge believes is its GUI thread.
 *
 * Forge's GUI code assumes one "EDT": Inputs show their messages there, event
 * batches are delivered there, and `FThreads.assertExecutedByEdt` checks it.
 * On desktop that is Swing's EDT. Here it is a dedicated thread that is *not*
 * Compose's UI thread, so whenever Forge blocks its EDT (an ability menu
 * while a card is being played, say), our window keeps painting and can still
 * deliver the answer.
 *
 * The name must not start with "Game": `ThreadUtil.isGameThread()` is a
 * name-prefix check.
 */
class ForgeEdt {
    @Volatile private var thread: Thread? = null
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "forge-edt").also { it.isDaemon = true; thread = it }
    }
    private val ticker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "forge-edt-tick").also { it.isDaemon = true }
    }
    private val afterEachTask = CopyOnWriteArrayList<() -> Unit>()

    fun isCurrent(): Boolean = Thread.currentThread() === thread

    fun later(task: Runnable) {
        executor.execute { runGuarded(task) }
    }

    fun andWait(task: Runnable) {
        if (isCurrent()) runGuarded(task) else executor.submit { runGuarded(task) }.get()
    }

    /** Runs [hook] on this thread after every task — where prompt state is re-read. */
    fun afterEachTask(hook: () -> Unit) {
        afterEachTask += hook
    }

    /** Also runs the hooks every [millis], so a quiet engine still gets re-read. */
    fun startTicking(millis: Long) {
        ticker.scheduleAtFixedRate({ later {} }, millis, millis, TimeUnit.MILLISECONDS)
    }

    private fun runGuarded(task: Runnable) {
        try {
            task.run()
        } catch (e: Throwable) {
            // Forge's own EDT tasks throwing is a Forge bug or a seam bug; either
            // way it must be visible, and it must not kill the only EDT.
            SpikeLog.error("forge-edt task failed", e)
        }
        for (hook in afterEachTask) {
            try {
                hook()
            } catch (e: Throwable) {
                SpikeLog.error("forge-edt after-task hook failed", e)
            }
        }
    }
}
