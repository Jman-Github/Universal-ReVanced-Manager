package app.urv.manager.ui.model

import android.content.Context
import app.universal.revanced.manager.R
import app.urv.manager.domain.manager.SignatureMetadataInjectorStage
import app.urv.manager.domain.manager.SignatureMetadataWorkflowProgress
import app.urv.manager.patcher.StepId

data class PatcherProgressState(
    val steps: List<Step>,
    val subStepsById: Map<StepId, List<StepDetail>>,
    val progress: Float
)

/** Combine worker progress with the automatic work that follows APK signing. */
fun signatureMetadataPatcherProgress(
    context: Context,
    steps: List<Step>,
    subStepsById: Map<StepId, List<StepDetail>>,
    progress: Float,
    enabled: Boolean,
    injection: SignatureMetadataWorkflowProgress
): PatcherProgressState {
    if (!enabled) return PatcherProgressState(steps, subStepsById, progress)
    val signingIndex = steps.indexOfFirst { it.id == StepId.SignAPK }
    if (signingIndex == -1) return PatcherProgressState(steps, subStepsById, progress)

    val signing = steps[signingIndex]
    val injectionState = when {
        injection.error != null -> State.FAILED
        injection.completed -> State.COMPLETED
        injection.running -> State.RUNNING
        else -> State.WAITING
    }
    val signingState = when {
        signing.state == State.FAILED -> State.FAILED
        injectionState != State.WAITING -> injectionState
        signing.state == State.COMPLETED -> State.RUNNING
        else -> signing.state
    }
    val detail = StepDetail(
        title = context.getString(R.string.patcher_step_inject_signature_metadata),
        state = injectionState,
        message = injection.error ?: if (injection.running) {
            context.getString(injection.stage.messageResource())
        } else null,
        log = StepLog(
            entries = injection.logEntries,
            revision = injection.logRevision,
            sessionId = injection.logSessionId
        )
    )
    val displayedSteps = steps.toMutableList().apply {
        this[signingIndex] = signing.withState(
            state = signingState,
            message = if (injectionState != State.WAITING || signing.state == State.COMPLETED) {
                injection.error
            } else signing.message,
            progress = if (injectionState != State.WAITING) null else signing.progress
        )
    }
    val displayedSubSteps = subStepsById + (StepId.SignAPK to
        (subStepsById[StepId.SignAPK].orEmpty() + detail))
    // Reserve the signing step's completion until its injection substep finishes.
    val progressLimit = if (injection.completed) 1f else {
        (steps.size - 1f) / steps.size
    }
    return PatcherProgressState(
        displayedSteps,
        displayedSubSteps,
        progress.coerceAtMost(progressLimit)
    )
}

private fun SignatureMetadataInjectorStage.messageResource(): Int = when (this) {
    SignatureMetadataInjectorStage.ANALYZING -> R.string.tools_signature_metadata_injector_stage_analyzing
    SignatureMetadataInjectorStage.PREPARING_TARGET -> R.string.tools_signature_metadata_injector_stage_preparing_target
    SignatureMetadataInjectorStage.LOADING -> R.string.tools_signature_metadata_injector_stage_loading
    SignatureMetadataInjectorStage.INJECTING -> R.string.tools_signature_metadata_injector_stage_injecting
    SignatureMetadataInjectorStage.WRITING -> R.string.tools_signature_metadata_injector_stage_writing
    SignatureMetadataInjectorStage.SIGNING -> R.string.tools_signature_metadata_injector_stage_signing
    SignatureMetadataInjectorStage.VALIDATING -> R.string.tools_signature_metadata_injector_stage_validating
    SignatureMetadataInjectorStage.COMPLETE -> R.string.tools_signature_metadata_injector_stage_complete
}
