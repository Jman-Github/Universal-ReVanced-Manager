package app.revanced.manager.patcher

import android.os.Parcelable
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

fun Throwable.toRemoteError() = RemoteError(
    type = this::class.java.name,
    message = this.message,
    stackTrace = this.stackTraceToString(),
)

inline fun <T> runStep(
    stepId: StepId,
    onEvent: (ProgressEvent) -> Unit,
    block: () -> T,
): T = try {
    val startTimeNs = System.nanoTime()
    val startMemMb = usedMemoryMb()
    onEvent(ProgressEvent.Started(stepId))
    val value = block()
    val elapsedMs = (System.nanoTime() - startTimeNs) / 1_000_000
    val endMemMb = usedMemoryMb()
    val deltaMemMb = endMemMb - startMemMb
    android.util.Log.d(
        "PatcherProgress",
        "step=${stepId::class.java.simpleName} duration=${elapsedMs}ms mem=${endMemMb}MB delta=${deltaMemMb}MB"
    )
    onEvent(ProgressEvent.Completed(stepId))
    value
} catch (error: Throwable) {
    onEvent(ProgressEvent.Failed(stepId, error.toRemoteError()))
    throw error
}

@PublishedApi
internal fun usedMemoryMb(): Long {
    val runtime = Runtime.getRuntime()
    val usedBytes = runtime.totalMemory() - runtime.freeMemory()
    return usedBytes / (1024 * 1024)
}
