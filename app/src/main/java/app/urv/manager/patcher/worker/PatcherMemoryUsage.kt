package app.urv.manager.patcher.worker

data class PatcherMemoryUsage(
    val usedMb: Long,
    val maxMb: Long,
    val requestedMaxMb: Long = maxMb,
    val sampledAtElapsedRealtimeMs: Long = System.nanoTime() / 1_000_000L
)
