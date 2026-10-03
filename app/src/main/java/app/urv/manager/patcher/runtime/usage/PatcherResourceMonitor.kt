/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.urv.manager.patcher.runtime.usage

import android.os.Process
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

// Code adapted from Morphe, see third-party/NOTICE for more information.
// https://github.com/MorpheApp/morphe-manager/blob/03fe5f5a3cb89d87bb9898933d39430e4134f154/app/src/main/java/app/morphe/manager/patcher/runtime/ResourceMonitor.kt

/** A session owns its thread and samplers, so queued runs cannot share polling state. */
object PatcherResourceMonitor {
    const val LOG_PREFIX = "URV resources:"
    private const val MEMORY_INTERVAL_MS = 500L
    private const val BYTES_PER_MB = 1024L * 1024L

    /** Sends one complete snapshot through the existing process log channel. */
    fun start(onSample: (String) -> Unit): Session {
        val running = AtomicBoolean(true)
        val thread = Thread {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
            val runtime = Runtime.getRuntime()
            val cpuSampler = CpuUsageSampler()
            val ioSampler = IoUsageSampler()
            while (running.get()) {
                val now = SystemClock.elapsedRealtime()
                val cpu = runCatching { cpuSampler.sample() }.getOrDefault(emptyList())
                val io = runCatching { ioSampler.sample() }.getOrNull()
                val usedMb = ((runtime.totalMemory() - runtime.freeMemory()) / BYTES_PER_MB)
                    .coerceAtLeast(0L)
                val maxMb = (runtime.maxMemory() / BYTES_PER_MB).coerceAtLeast(1L)
                val fields = buildList {
                    add("used=$usedMb")
                    add("max=$maxMb")
                    add("time=${System.nanoTime() / 1_000_000L}")
                    add("resourceTime=$now")
                    if (cpu.isNotEmpty()) {
                        add("cpu=${cpu.joinToString(",")}")
                        add("cpuSystem=${cpuSampler.systemWide}")
                    }
                    io?.let { sample ->
                        add("ioRead=${sample.readKbPerSec}")
                        add("ioWrite=${sample.writeKbPerSec}")
                        add("ioBlock=${ioSampler.blockAccounting}")
                    }
                }
                // Stop cannot publish a late sample into the next queued patch run.
                if (!running.get()) break
                runCatching { onSample("$LOG_PREFIX ${fields.joinToString(" ")}") }
                try {
                    Thread.sleep(MEMORY_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.apply {
            name = "PatcherResourceMonitor"
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
                runCatching { thread.join(250L) }
            }
        }
    }
}
