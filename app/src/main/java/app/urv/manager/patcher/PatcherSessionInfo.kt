package app.urv.manager.patcher

import kotlinx.serialization.Serializable

@Serializable
data class PatcherSessionInfo(
    val apkSizeBytes: Long? = null,
    val splitApk: Boolean? = null,
    val patchCount: Int? = null,
    val runtimeProcess: Boolean? = null,
    val memoryLimitMb: Int? = null,
    val nativeLibsStripped: Boolean? = null,
    val startedAtElapsedRealtimeMs: Long? = null,
    val elapsedMs: Long? = null
)

fun PatcherSessionInfo.withFallback(fallback: PatcherSessionInfo): PatcherSessionInfo =
    PatcherSessionInfo(
        apkSizeBytes = apkSizeBytes ?: fallback.apkSizeBytes,
        splitApk = splitApk ?: fallback.splitApk,
        patchCount = patchCount ?: fallback.patchCount,
        runtimeProcess = runtimeProcess ?: fallback.runtimeProcess,
        memoryLimitMb = memoryLimitMb ?: fallback.memoryLimitMb,
        nativeLibsStripped = nativeLibsStripped ?: fallback.nativeLibsStripped,
        startedAtElapsedRealtimeMs =
            startedAtElapsedRealtimeMs ?: fallback.startedAtElapsedRealtimeMs,
        elapsedMs = elapsedMs ?: fallback.elapsedMs
    )

fun parsePatcherSessionInfo(messages: Iterable<String>): PatcherSessionInfo =
    messages.fold(PatcherSessionInfo()) { info, message ->
        info.updatedFromLog(message)
    }

fun PatcherSessionInfo.updatedFromLog(message: String): PatcherSessionInfo {
    if (message.startsWith("Patching session started ")) {
        return copy(
            startedAtElapsedRealtimeMs = PATCH_SESSION_START.find(message)
                ?.groupValues
                ?.getOrNull(1)
                ?.toLongOrNull()
                ?: startedAtElapsedRealtimeMs,
            elapsedMs = null
        )
    }
    if (message.startsWith("Patching session finished ")) {
        return copy(
            elapsedMs = PATCH_SESSION_ELAPSED.find(message)
                ?.groupValues
                ?.getOrNull(1)
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: elapsedMs
        )
    }
    if (message.startsWith("Patching started at ")) {
        return copy(
            apkSizeBytes = PATCH_START_SIZE.find(message)
                ?.groupValues
                ?.getOrNull(1)
                ?.toLongOrNull()
                ?: apkSizeBytes,
            splitApk = PATCH_START_SPLIT.find(message)
                ?.groupValues
                ?.getOrNull(1)
                ?.toBooleanStrictOrNull()
                ?: splitApk,
            patchCount = PATCH_START_COUNT.find(message)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: patchCount,
            nativeLibsStripped = PATCH_START_NATIVE_LIBS.find(message)
                ?.groupValues
                ?.getOrNull(1)
                ?.toBooleanStrictOrNull()
                ?: nativeLibsStripped
        )
    }
    if (message.startsWith("Patcher runtime:")) {
        return copy(
            memoryLimitMb = MEMORY_LIMIT.find(message)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: memoryLimitMb
        )
    }
    if (message.startsWith("Runtime mode:")) {
        return copy(
            runtimeProcess = when (message.removePrefix("Runtime mode:").trim()) {
                "process" -> true
                "in-process" -> false
                else -> runtimeProcess
            }
        )
    }
    return this
}

private val PATCH_SESSION_START = Regex("\\belapsedRealtime=(\\d+)ms\\b")
private val PATCH_SESSION_ELAPSED = Regex("\\belapsed=(\\d+)ms\\b")
private val PATCH_START_SIZE = Regex("\\bsize=(\\d+)")
private val PATCH_START_SPLIT = Regex("\\bsplit=(true|false)\\b")
private val PATCH_START_COUNT = Regex("\\bpatches=(\\d+)\\b")
private val PATCH_START_NATIVE_LIBS = Regex("\\bnativeLibsStripped=(true|false)\\b")
private val MEMORY_LIMIT = Regex("\\bmemoryLimit=(\\d+)MB\\b")
