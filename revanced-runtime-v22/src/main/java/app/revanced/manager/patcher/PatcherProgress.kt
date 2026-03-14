package app.revanced.manager.patcher

import android.os.Parcelable
import kotlinx.coroutines.CancellationException
import kotlinx.parcelize.Parcelize

@Parcelize
sealed class ProgressEvent : Parcelable {
    abstract val stepId: StepId?

    data class Started(override val stepId: StepId) : ProgressEvent()

    data class Progress(
        override val stepId: StepId,
        val current: Long? = null,
        val total: Long? = null,
        val message: String? = null,
        val subSteps: List<String>? = null,
    ) : ProgressEvent()

    data class Completed(
        override val stepId: StepId,
    ) : ProgressEvent()

    data class Failed(
        override val stepId: StepId?,
        val error: RemoteError,
    ) : ProgressEvent()
}

@Parcelize
data class ProgressEventParcel(val event: ProgressEvent) : Parcelable

fun ProgressEventParcel.toEvent(): ProgressEvent = event
fun ProgressEvent.toParcel(): ProgressEventParcel = ProgressEventParcel(this)

@Parcelize
sealed class StepId : Parcelable {
    data object DownloadAPK : StepId()
    data object LoadPatches : StepId()
    data object PrepareSplitApk : StepId()
    data object ReadAPK : StepId()
    data object ExecutePatches : StepId()
    data class ExecutePatch(val index: Int) : StepId()
    data object WriteAPK : StepId()
    data object SignAPK : StepId()
}

@Parcelize
data class RemoteError(
    val type: String,
    val message: String?,
    val stackTrace: String,
) : Parcelable

fun Exception.toRemoteError() = RemoteError(
    type = this::class.java.name,
    message = this.message,
    stackTrace = this.stackTraceToString(),
)

inline fun <T> runStep(
    stepId: StepId,
    onEvent: (ProgressEvent) -> Unit,
    checkCancelled: () -> Unit = {},
    block: () -> T,
): T = try {
    checkCancelled()
    onEvent(ProgressEvent.Started(stepId))
    checkCancelled()
    val value = block()
    checkCancelled()
    onEvent(ProgressEvent.Completed(stepId))
    value
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    onEvent(ProgressEvent.Failed(stepId, error.toRemoteError()))
    throw error
}
