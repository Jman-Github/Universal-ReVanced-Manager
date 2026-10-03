/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.urv.manager.patcher.runtime.usage

import android.os.SystemClock
import java.io.File

// Code adapted from Morphe, see third-party/NOTICE for more information.
// https://github.com/MorpheApp/morphe-manager/blob/03fe5f5a3cb89d87bb9898933d39430e4134f154/app/src/main/java/app/morphe/manager/patcher/runtime/usage/IoUsageSampler.kt

/** One storage throughput reading of the patcher process, in kilobytes per second. */
internal data class IoUsage(val readKbPerSec: Int, val writeKbPerSec: Int)

/**
 * Storage throughput of the patcher process, taken from the IO accounting of its own task.
 *
 * Block level counters are preferred because they are what actually reaches storage; the syscall
 * counters stand in for kernels built without task IO accounting, where the former are missing.
 */
internal class IoUsageSampler {
    var blockAccounting: Boolean = true
        private set

    private var previousRead = -1L
    private var previousWrite = -1L
    private var previousUptimeMs = 0L

    /** Throughput since the previous call, or null until two readings exist to subtract. */
    fun sample(): IoUsage? {
        val counters = readCounters() ?: return null
        val (read, write) = counters

        val uptimeMs = SystemClock.elapsedRealtime()
        val elapsed = uptimeMs - previousUptimeMs
        val hadReading = previousRead >= 0L
        val readDelta = read - previousRead
        val writeDelta = write - previousWrite

        previousRead = read
        previousWrite = write
        previousUptimeMs = uptimeMs

        if (!hadReading || elapsed <= 0L) return null

        return IoUsage(
            readKbPerSec = rate(readDelta, elapsed),
            writeKbPerSec = rate(writeDelta, elapsed)
        )
    }

    private fun readCounters(): Pair<Long, Long>? = try {
        val fields = mutableMapOf<String, Long>()

        File(PROC_SELF_IO).forEachLine { line ->
            val value = line.substringAfter(':', "").trim().toLongOrNull() ?: return@forEachLine
            fields[line.substringBefore(':')] = value
        }

        blockAccounting = fields.containsKey(FIELD_READ_BYTES) && fields.containsKey(FIELD_WRITE_BYTES)
        val read = if (blockAccounting) fields[FIELD_READ_BYTES] else fields[FIELD_RCHAR]
        val write = if (blockAccounting) fields[FIELD_WRITE_BYTES] else fields[FIELD_WCHAR]

        if (read != null && write != null) read to write else null
    } catch (_: Exception) {
        null
    }

    private fun rate(bytes: Long, elapsedMs: Long) =
        ((bytes * 1000) / (elapsedMs * 1024)).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    private companion object {
        const val PROC_SELF_IO = "/proc/self/io"
        const val FIELD_READ_BYTES = "read_bytes"
        const val FIELD_WRITE_BYTES = "write_bytes"
        const val FIELD_RCHAR = "rchar"
        const val FIELD_WCHAR = "wchar"
    }
}
