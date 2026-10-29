package app.universal.patch.manager.reseam.runtime

import app.reseam.sdk.ClosureRunEvent
import app.reseam.sdk.PatchRef
import app.reseam.sdk.PatchStatus
import app.reseam.sdk.RunEvent

private val PatchRef.reference: String get() = if (bundle.isEmpty()) id else "$bundle/$id"

/** Translate dependency-ordered SDK events into UPM's indexed progress contract. */
internal class ReseamProgress(
    private val references: List<String>,
    private val names: List<String>,
    private val continueOnPatchError: Boolean,
    private val callback: ReseamRuntimeCallback
) : ClosureRunEvent {
    private val indexes = references.withIndex().associate { it.value to it.index }
    private val completed = mutableSetOf<String>()
    val statuses = mutableMapOf<String, PatchStatus>()
    var activeStep = "ReadAPK"
        private set
    var terminalFailure: String? = null
        private set
    private var executing = false
    private var writing = false

    fun start() = started("ReadAPK")

    private fun beginExecution() {
        if (executing) return
        completed("ReadAPK")
        activeStep = "ExecutePatches"
        callback.event(mapOf("type" to "Started", "stepId" to step(activeStep), "subSteps" to names))
        executing = true
    }

    private fun beginWriting() {
        if (writing) return
        beginExecution()
        completed("ExecutePatches")
        activeStep = "WriteAPK"
        callback.event(mapOf("type" to "Started", "stepId" to step(activeStep),
            "subSteps" to listOf("Writing patched APKs", "Preparing unsigned APKs", "Finalizing output")))
        outputProgress("Writing patched APKs", null, null)
        writing = true
    }

    override fun invoke(arg0: RunEvent) {
        when (arg0) {
            is RunEvent.Info -> {
                callback.log("INFO", arg0.message)
                if (arg0.message == "Loading bundles") beginExecution()
                if (arg0.message.startsWith("Writing signed output")) beginWriting()
            }
            is RunEvent.PatchStarted -> {
                beginExecution()
                val ref = arg0.patch.reference
                if (ref !in completed) {
                    indexes[ref]?.let { started("ExecutePatch", it, updateActive = false) }
                }
            }
            is RunEvent.PatchFinished -> {
                beginExecution()
                finishPatch(arg0.patch, arg0.status)
            }
            is RunEvent.PatchesReverted -> {
                callback.log("WARN", "Reseam reverted patches and will run them again: " +
                    arg0.patches.joinToString { it.reference })
                arg0.patches.forEach { patchRef ->
                    val reference = patchRef.reference
                    statuses.remove(reference)
                    completed.remove(reference)
                }
                updateTerminalFailure()
            }
            is RunEvent.PatchLog -> {
                val entry = arg0.field0
                callback.log(
                    if (entry.level.name == "DEBUG") "TRACE" else entry.level.name,
                    "[" + entry.patch.reference + "] " + entry.message
                )
            }
        }
    }

    fun finishPatch(patch: PatchRef, status: PatchStatus) = finishPatch(patch.reference, status)

    fun finishPatch(reference: String, status: PatchStatus) {
        val previousStatus = statuses.put(reference, status)
        // Individual row failures are nonfatal. Defer the enclosing failure until JNI
        // returns so the SDK can close its files before the host terminates the process.
        indexes[reference]?.let { index ->
            if (completed.add(reference) || previousStatus != status) {
                when (status) {
                    PatchStatus.Applied -> completed("ExecutePatch", index)
                    is PatchStatus.Failed -> failed("ExecutePatch", status.reason, index)
                    is PatchStatus.Skipped -> failed("ExecutePatch", status.reason, index)
                    is PatchStatus.Unmountable -> failed("ExecutePatch", status.reason, index)
                }
            }
        }
        updateTerminalFailure()
    }

    private fun updateTerminalFailure() {
        // Unselected catalog skips are expected. Failed dependencies still block output.
        // Recompute after rollback or a changed result so stale failures cannot survive.
        terminalFailure = if (continueOnPatchError) null else statuses.entries.firstOrNull { (reference, status) ->
            status is PatchStatus.Failed ||
                (reference in indexes && (status is PatchStatus.Skipped || status is PatchStatus.Unmountable))
        }?.key
    }

    fun successfulReferences(): List<String> = references.filter { statuses[it] is PatchStatus.Applied }

    fun beforePublish() {
        check(terminalFailure == null) { "Reseam patch failed: " + terminalFailure }
        check(completed.size == references.size) { "Reseam did not report every selected patch." }
        beginWriting()
        outputProgress("Writing patched APKs", 1, 1)
    }

    fun preparingUnsigned(current: Int, total: Int) =
        outputProgress("Preparing unsigned APKs", current.toLong(), total.toLong())

    fun finalizing(current: Long? = null, total: Long? = null) =
        outputProgress("Finalizing output", current, total)

    fun published() = completed("WriteAPK")

    private fun outputProgress(message: String, current: Long?, total: Long?) = callback.event(mapOf(
        "type" to "Progress", "stepId" to step("WriteAPK"), "message" to message,
        "current" to current, "total" to total
    ))

    private fun started(kind: String, index: Int? = null, updateActive: Boolean = true) {
        if (updateActive) activeStep = kind
        callback.event(mapOf("type" to "Started", "stepId" to step(kind, index)))
    }

    private fun completed(kind: String, index: Int? = null) =
        callback.event(mapOf("type" to "Completed", "stepId" to step(kind, index)))

    fun failed(kind: String, message: String, index: Int? = null) = callback.event(mapOf(
        "type" to "Failed", "stepId" to step(kind, index),
        "error" to mapOf("type" to "ReseamPatchError", "message" to message, "stackTrace" to message)
    ))

    private fun step(kind: String, index: Int? = null): Map<String, Any?> =
        if (index == null) mapOf("kind" to kind) else mapOf("kind" to kind, "index" to index)
}
