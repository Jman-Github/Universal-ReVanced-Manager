package app.urv.manager.patcher.worker

import app.urv.manager.patcher.runtime.usage.PatcherResourceMonitor

data class PatcherMemoryUsage(
    val usedMb: Long,
    val maxMb: Long,
    val requestedMaxMb: Long = maxMb,
    val sampledAtElapsedRealtimeMs: Long = System.nanoTime() / 1_000_000L,
    val resourceSampleTimeMs: Long? = null,
    val cpuCoreLoads: List<Int> = emptyList(),
    val cpuSystemWide: Boolean = false,
    val ioReadKbPerSec: Int? = null,
    val ioWriteKbPerSec: Int? = null,
    val ioBlockAccounting: Boolean = true
)

/** Decodes telemetry before log filtering so raw polls never enter the user's log. */
internal fun parsePatcherResourceUsage(message: String): PatcherMemoryUsage? {
    val prefix = PatcherResourceMonitor.LOG_PREFIX
    if (!message.startsWith("$prefix ")) return null
    val fields = message.removePrefix("$prefix ").split(' ').associate { field ->
        field.substringBefore('=') to field.substringAfter('=', "")
    }
    val usedMb = fields["used"]?.toLongOrNull()?.takeIf { it >= 0L } ?: return null
    val maxMb = fields["max"]?.toLongOrNull()?.takeIf { it > 0L } ?: return null
    val time = fields["time"]?.toLongOrNull()?.takeIf { it >= 0L } ?: return null
    val resourceTime = fields["resourceTime"]?.toLongOrNull()?.takeIf { it >= 0L }
    val parsedCores = fields["cpu"]?.split(',')?.map { value ->
        value.toIntOrNull()?.takeIf { it in 0..100 }
    }.orEmpty()
    // Reject the whole CPU metric if a value is invalid, keeping core indices stable.
    val cores = if (parsedCores.all { it != null }) parsedCores.filterNotNull() else emptyList()
    val read = fields["ioRead"]?.toIntOrNull()?.takeIf { it >= 0 }
    val write = fields["ioWrite"]?.toIntOrNull()?.takeIf { it >= 0 }
    return PatcherMemoryUsage(
        usedMb = usedMb,
        maxMb = maxMb,
        sampledAtElapsedRealtimeMs = time,
        resourceSampleTimeMs = resourceTime,
        cpuCoreLoads = cores,
        cpuSystemWide = fields["cpuSystem"] == "true",
        ioReadKbPerSec = read.takeIf { write != null },
        ioWriteKbPerSec = write.takeIf { read != null },
        ioBlockAccounting = fields["ioBlock"] != "false"
    )
}
