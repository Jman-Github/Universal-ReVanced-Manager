package app.urv.manager.patcher.revanced

import android.content.Context
import app.urv.manager.patcher.ProgressEvent
import app.urv.manager.patcher.patch.PatchInfo
import app.urv.manager.patcher.logger.LogLevel
import app.urv.manager.patcher.logger.Logger
import app.urv.manager.revanced.runtime.RevancedRuntimeCallback
import app.urv.manager.revanced.runtime.RevancedRuntimeEntry
import kotlinx.coroutines.CancellationException

object Revanced22RuntimeBridge {
    private const val CANCELLATION_SENTINEL = "__PATCHING_CANCELLED__"

    fun initialize(context: Context) = Unit

    fun loadMetadata(bundlePath: String): List<PatchInfo> =
        RevancedRuntimeEntry.loadMetadataForBundle(bundlePath).map(PatchInfo::fromMorpheMetadata)

    fun loadMetadata(bundlePaths: List<String>): Map<String, List<PatchInfo>> =
        RevancedRuntimeEntry.loadMetadata(bundlePaths).mapValues { (_, metadata) ->
            metadata.map(PatchInfo::fromMorpheMetadata)
        }

    fun runPatcher(
        params: Map<String, Any?>,
        logger: Logger,
        onEvent: (ProgressEvent) -> Unit,
        isCancelled: () -> Boolean = { false }
    ): String? {
        val callback = object : RevancedRuntimeCallback {
            override fun log(level: String, message: String) {
                logger.log(LogLevel.valueOf(level), message)
            }

            override fun event(event: Map<String, Any?>) {
                onEvent(mapToProgressEvent(event))
            }

            override fun isCancelled(): Boolean = isCancelled()
        }

        val result = RevancedRuntimeEntry.runPatcher(params, callback)
        if (result == CANCELLATION_SENTINEL) {
            throw CancellationException("Patching cancelled")
        }
        return result
    }

    private fun mapToProgressEvent(map: Map<String, Any?>): ProgressEvent {
        val type = map["type"] as? String ?: return ProgressEvent.Progress(app.urv.manager.patcher.StepId.LoadPatches)
        val stepId = mapToStepId(map["stepId"] as? Map<*, *>)
        return when (type) {
            "Started" -> ProgressEvent.Started(
                stepId ?: app.urv.manager.patcher.StepId.LoadPatches,
                (map["subSteps"] as? Iterable<*>)?.mapNotNull { it as? String }
            )

            "Completed" -> ProgressEvent.Completed(stepId ?: app.urv.manager.patcher.StepId.LoadPatches)

            "Progress" -> ProgressEvent.Progress(
                stepId = stepId ?: app.urv.manager.patcher.StepId.LoadPatches,
                current = (map["current"] as? Number)?.toLong(),
                total = (map["total"] as? Number)?.toLong(),
                message = map["message"] as? String,
                subSteps = (map["subSteps"] as? Iterable<*>)?.mapNotNull { it as? String }
            )

            "Failed" -> {
                val error = map["error"] as? Map<*, *> ?: emptyMap<Any, Any?>()
                ProgressEvent.Failed(
                    stepId,
                    app.urv.manager.patcher.RemoteError(
                        type = error["type"] as? String ?: "UnknownError",
                        message = error["message"] as? String,
                        stackTrace = error["stackTrace"] as? String ?: ""
                    )
                )
            }

            else -> ProgressEvent.Progress(stepId ?: app.urv.manager.patcher.StepId.LoadPatches)
        }
    }

    private fun mapToStepId(map: Map<*, *>?): app.urv.manager.patcher.StepId? {
        val kind = map?.get("kind") as? String ?: return null
        return when (kind) {
            "DownloadAPK" -> app.urv.manager.patcher.StepId.DownloadAPK
            "LoadPatches" -> app.urv.manager.patcher.StepId.LoadPatches
            "PrepareSplitApk" -> app.urv.manager.patcher.StepId.PrepareSplitApk
            "ReadAPK" -> app.urv.manager.patcher.StepId.ReadAPK
            "ExecutePatches" -> app.urv.manager.patcher.StepId.ExecutePatches
            "WriteAPK" -> app.urv.manager.patcher.StepId.WriteAPK
            "SignAPK" -> app.urv.manager.patcher.StepId.SignAPK
            "ExecutePatch" -> {
                val index = (map["index"] as? Number)?.toInt() ?: 0
                app.urv.manager.patcher.StepId.ExecutePatch(index)
            }

            else -> null
        }
    }
}

class Revanced22BridgeFailureException(val originalStackTrace: String) :
    Exception("ReVanced v22 in-process runtime failed")
