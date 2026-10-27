/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */
package app.urv.manager.patcher.host

// Adapted from Morphe Manager; see third-party/NOTICE.
// https://github.com/MorpheApp/morphe-manager/commit/76b5477eca7a97b393a95cc5880d90d1ab47354a
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** A rolling window of elapsed time and cumulative collector time, both in milliseconds. */
internal class GcOverheadWindow(
    private val windowMs: Long = 120_000,
    private val limit: Double = 0.6
) {
    private val samples = ArrayDeque<Pair<Long, Long>>()

    fun sample(now: Long, collected: Long): Double? {
        if (collected < 0) { samples.clear(); return null }
        samples.lastOrNull()?.let { last ->
            if (now <= last.first || collected < last.second) samples.clear()
        }
        samples.addLast(now to collected)
        // Retain the sample immediately before the window's beginning.
        while (samples.size > 2 && now - samples.elementAt(1).first >= windowMs) samples.removeFirst()
        val first = samples.first()
        val elapsed = now - first.first
        if (elapsed < windowMs) return null
        val share = (collected - first.second).toDouble() / elapsed
        return share.takeIf { it >= limit }
    }
}

/** Only used by disposable JVM workers; ART never loads management classes. */
internal class GcOverheadWatch(
    private val gcMillis: () -> Long,
    private val onExhausted: (String) -> Unit,
    private val window: GcOverheadWindow = GcOverheadWindow(),
    private val pollMs: Long = 10_000,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 }
) : Closeable {
    private val closed = AtomicBoolean()
    private val watcher = thread(isDaemon = true, name = "GcOverheadWatch") {
        try {
            window.sample(clockMillis(), gcMillis())
            while (!closed.get()) {
                Thread.sleep(pollMs)
                val share = window.sample(clockMillis(), gcMillis()) ?: continue
                if (closed.get()) return@thread
                val runtime = Runtime.getRuntime()
                val used = (runtime.totalMemory() - runtime.freeMemory()) / 1_048_576
                onExhausted("java.lang.OutOfMemoryError: Collecting garbage took " +
                    "${(share * 100).toInt()}% of the last 120s, heap=${used}MB/${runtime.maxMemory() / 1_048_576}MB")
                return@thread
            }
        } catch (_: InterruptedException) {
            // The run completed or was cancelled.
        }
    }

    override fun close() {
        closed.set(true)
        watcher.interrupt()
        if (Thread.currentThread() != watcher) watcher.join(1_000)
    }
}
