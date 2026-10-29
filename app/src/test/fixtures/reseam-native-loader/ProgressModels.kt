package app.universal.patch.manager.patcher

// Minimal manager models for the standalone ART harness; the production bridge is compiled unchanged.
sealed class StepId {
    data object DownloadAPK : StepId()
    data object LoadPatches : StepId()
    data object PrepareSplitApk : StepId()
    data object ReadAPK : StepId()
    data object ExecutePatches : StepId()
    data object WriteAPK : StepId()
    data object SignAPK : StepId()
    data class ExecutePatch(val index: Int) : StepId()
}
data class RemoteError(val type: String, val message: String?, val stackTrace: String)
sealed class ProgressEvent {
    data class Started(val stepId: StepId, val subSteps: List<String>?) : ProgressEvent()
    data class Completed(val stepId: StepId) : ProgressEvent()
    data class Progress(val stepId: StepId, val current: Long? = null, val total: Long? = null,
        val message: String? = null, val subSteps: List<String>? = null) : ProgressEvent()
    data class Failed(val stepId: StepId?, val error: RemoteError) : ProgressEvent()
}
