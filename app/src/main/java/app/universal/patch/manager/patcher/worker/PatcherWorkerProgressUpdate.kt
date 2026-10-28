package app.universal.patch.manager.patcher.worker

import app.universal.patch.manager.patcher.ProgressEvent

data class PatcherWorkerProgressUpdate(
    val generation: Long,
    val sequence: Long,
    val event: ProgressEvent? = null,
    val notificationProgressCurrent: Int? = null,
    val notificationProgressMax: Int? = null,
    val memoryUsage: PatcherMemoryUsage? = null,
    val isMemorySample: Boolean = false,
    val patchExecutionOrder: List<Int> = emptyList()
)
