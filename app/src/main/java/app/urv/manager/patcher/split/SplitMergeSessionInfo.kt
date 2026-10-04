package app.urv.manager.patcher.split

data class SplitMergeSessionInfo(
    val inputApkSizeBytes: Long? = null,
    val outputApkSizeBytes: Long? = null,
    val arscLibVersion: String? = null,
    val startedAtElapsedRealtimeMs: Long? = null,
    val elapsedMs: Long? = null,
    val memoryLimitMb: Int? = null,
    val includedSplitCount: Int? = null,
    val totalSplitCount: Int? = null,
    val appName: String? = null,
    val packageName: String? = null
) {
    fun finished(nowElapsedRealtimeMs: Long): SplitMergeSessionInfo = copy(
        elapsedMs = elapsedMs ?: startedAtElapsedRealtimeMs?.let {
            (nowElapsedRealtimeMs - it).coerceAtLeast(0L)
        }
    )
}
