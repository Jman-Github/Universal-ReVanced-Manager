package app.urv.manager.patcher.worker

import app.urv.manager.patcher.ProgressEvent

data class PatcherWorkerProgressUpdate(
    val generation: Long,
    val sequence: Long,
    val event: ProgressEvent,
    val notificationProgressCurrent: Int? = null,
    val notificationProgressMax: Int? = null
)
