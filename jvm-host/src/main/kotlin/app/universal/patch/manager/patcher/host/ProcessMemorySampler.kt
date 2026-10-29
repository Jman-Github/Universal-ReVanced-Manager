package app.universal.patch.manager.patcher.runtime.usage

import java.io.File

/** Resident memory includes Reseam's native allocations and mapped APK data. */
internal fun processResidentMemoryMb(status: File = File("/proc/self/status")): Long? =
    runCatching {
        status.useLines { lines ->
            lines.firstOrNull { it.startsWith("VmRSS:") }
                ?.substringAfter(':')?.trim()?.split(Regex("\\s+"))
                ?.takeIf { it.size == 2 && it[1] == "kB" }
                ?.first()?.toLongOrNull()?.takeIf { it > 0L }?.div(1024L)
        }
    }.getOrNull()
