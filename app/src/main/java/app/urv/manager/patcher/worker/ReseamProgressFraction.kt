package app.urv.manager.patcher.worker

import app.urv.manager.patcher.ProgressEvent
import app.urv.manager.patcher.StepId

/** SDK/host phases have different counters. Keep each inside its part of the stage. */
internal fun reseamStageFraction(event: ProgressEvent.Progress, splitSubSteps: List<String>): Float? {
    val fraction = event.total?.takeIf { it > 0L }?.let { total ->
        event.current?.let { (it.toDouble() / total).coerceIn(0.0, 1.0) }
    } ?: 0.0
    val message = event.message ?: return null
    val result = when (event.stepId) {
        StepId.PrepareSplitApk -> when {
            message == "Extracting split APKs" -> fraction / 3
            message == "Inspecting split APKs" -> (1 + fraction) / 3
            message.startsWith("Preparing ") -> {
                val components = splitSubSteps.filter { it.startsWith("Preparing ") }
                val index = components.indexOf(message)
                if (index < 0 || components.isEmpty()) return null
                (2 + (index + fraction) / components.size) / 3
            }
            else -> return null
        }
        StepId.WriteAPK -> when (message) {
            "Writing patched APKs" -> fraction / 3
            "Preparing unsigned APKs" -> (1 + fraction) / 3
            "Finalizing output" -> (2 + fraction) / 3
            else -> return null
        }
        else -> return null
    }
    // Publication and the stage's completion are reported separately.
    return result.coerceIn(0.0, 0.99).toFloat()
}
