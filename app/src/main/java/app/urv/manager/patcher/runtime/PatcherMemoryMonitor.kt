package app.urv.manager.patcher.runtime

import java.lang.Runtime
import java.util.concurrent.atomic.AtomicBoolean

object PatcherMemoryMonitor {
    private const val INTERVAL_MS = 500L
    private const val JOIN_TIMEOUT_MS = 250L
    private const val BYTES_PER_MB = 1024L * 1024L

    fun start(onSample: (usedMb: Long, maxHeapMb: Long) -> Unit): Session {
        val running = AtomicBoolean(true)
        val thread = Thread {
            val runtime = Runtime.getRuntime()
            while (running.get()) {
                val usedMb = ((runtime.totalMemory() - runtime.freeMemory()) / BYTES_PER_MB)
                    .coerceAtLeast(0L)
                val maxMb = (runtime.maxMemory() / BYTES_PER_MB).coerceAtLeast(1L)
                runCatching { onSample(usedMb, maxMb) }
                try {
                    Thread.sleep(INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.apply {
            name = "PatcherMemoryMonitor"
            isDaemon = true
            start()
        }
        return Session(running, thread)
    }

    class Session internal constructor(
        private val running: AtomicBoolean,
        private val thread: Thread
    ) {
        fun stop() {
            running.set(false)
            thread.interrupt()
            if (Thread.currentThread() != thread) {
                runCatching { thread.join(JOIN_TIMEOUT_MS) }
            }
        }
    }
}
