/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */
package app.universal.patch.manager.patcher.host

// Code adapted from Morphe, see third-party/NOTICE.txt for more information.
// https://github.com/MorpheApp/morphe-manager/pull/1246

import java.io.Closeable
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** One run owns its watcher; telemetry and diagnostic logs do not count as patching progress. */
internal class StallWatch(
    private val warn: (String) -> Unit,
    private val clock: () -> Long = System::nanoTime,
    private val dump: () -> String = ::threadDump,
    private val timeoutNanos: Long = TimeUnit.SECONDS.toNanos(60),
    private val maximumDumps: Int = 5
) : Closeable {
    private val gate = Any()
    @Volatile private var active = true
    @Volatile private var progressedAt = clock()
    private var dumpedAt = progressedAt
    private var dumps = 0
    private val watcher = thread(start = false, isDaemon = true, name = "PatcherStallWatch") {
        try {
            while (active && dumps < maximumDumps) {
                Thread.sleep(5_000)
                checkForStall()
            }
        } catch (_: InterruptedException) { /* The run ended. */ }
    }

    fun start() = apply { watcher.start() }
    fun onEvent(type: String) { if (type == "event") progressedAt = clock() }

    internal fun checkForStall() {
        synchronized(gate) {
            val now = clock()
            if (!active || dumps >= maximumDumps || now - maxOf(progressedAt, dumpedAt) < timeoutNanos) return
            dumpedAt = now
            dumps++
            // Flush this first: collecting stacks can itself wait for a stuck VM safepoint.
            warn("No progress for ${TimeUnit.NANOSECONDS.toSeconds(now - progressedAt)}s, taking the stacks of all threads")
        }
        val stacks = try { dump() } catch (error: Exception) {
            "Could not collect thread stacks: ${error.javaClass.simpleName}: ${error.message}"
        }
        synchronized(gate) { if (active) warn(stacks) }
    }

    override fun close() {
        synchronized(gate) { active = false }
        watcher.interrupt()
    }
}

internal fun vmDescription(): String {
    fun property(name: String) = System.getProperty(name) ?: "?"
    return "Host VM: ${property("java.vm.name")} ${property("java.vm.version")} (${property("java.vm.info")}) " +
        "arch=${property("os.arch")} cores=${Runtime.getRuntime().availableProcessors()}"
}

private fun threadDump(): String {
    val runtime = Runtime.getRuntime()
    val used = (runtime.totalMemory() - runtime.freeMemory()) / 1048576
    return buildString {
        append("Threads, heap=${used}MB/${runtime.maxMemory() / 1048576}MB:")
        Thread.getAllStackTraces().entries.sortedBy { it.key.name }.forEach { (thread, frames) ->
            append("\n\"${thread.name}\" ${thread.state}")
            if (thread.isDaemon) append(" daemon")
            frames.take(40).forEach { append("\n    at ").append(it) }
            if (frames.size > 40) append("\n    ... ${frames.size - 40} more")
        }
    }
}
