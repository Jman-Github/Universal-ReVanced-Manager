package app.urv.manager.reseam.runtime

import app.reseam.sdk.PatchStatus
import app.reseam.sdk.RunEvent
import org.junit.Assert.*
import org.junit.Test

class ReseamProgressTest {
    private class Recorder : ReseamRuntimeCallback {
        val events = mutableListOf<Map<String, Any?>>()
        override fun event(value: Map<String, Any?>) { events += value }
        override fun log(level: String, message: String) {}
        override fun isCancelled() = false
    }

    @Test fun unselectedCatalogSkipsDoNotFailPatching() {
        val recorder = Recorder()
        val progress = ReseamProgress(listOf("b/a"), listOf("Readable name"), false, recorder)
        progress.start()
        progress.invoke(RunEvent.PatchFinished("b/unselected", PatchStatus.Skipped("not selected")))
        progress.invoke(RunEvent.PatchFinished("b/hidden", PatchStatus.Applied))
        progress.invoke(RunEvent.PatchStarted("b/a"))
        progress.invoke(RunEvent.PatchFinished("b/a", PatchStatus.Applied))
        progress.invoke(RunEvent.PatchFinished("b/another", PatchStatus.Skipped("not selected")))
        progress.beforePublish()
        progress.published()
        assertNull(progress.terminalFailure)
        assertFalse(recorder.events.any { it["type"] == "Failed" })
        val execution = recorder.events.first { it["subSteps"] != null }
        assertEquals(listOf("Readable name"), execution["subSteps"])
        val rows = recorder.events.filter { (it["stepId"] as Map<*, *>)["kind"] == "ExecutePatch" }
        assertEquals(listOf("Started", "Completed"), rows.map { it["type"] })
    }

    @Test fun selectedSkipIsReportedWithoutTerminatingNativeCallback() {
        val recorder = Recorder()
        val progress = ReseamProgress(listOf("b/a"), listOf("A"), false, recorder)
        progress.invoke(RunEvent.PatchFinished("b/a", PatchStatus.Skipped("dependency unavailable")))
        assertEquals("b/a", progress.terminalFailure)
        val failures = recorder.events.filter { it["type"] == "Failed" }
        assertEquals(listOf("ExecutePatch"),
            failures.map { (it["stepId"] as Map<*, *>)["kind"] })
        assertThrows(IllegalStateException::class.java) { progress.beforePublish() }
    }

    @Test fun selectedUnmountablePatchIsReportedWithoutTerminatingNativeCallback() {
        val recorder = Recorder()
        val progress = ReseamProgress(listOf("b/a"), listOf("A"), false, recorder)
        progress.invoke(RunEvent.PatchFinished("b/a", PatchStatus.Unmountable("manifest changed")))
        assertEquals("b/a", progress.terminalFailure)
        val failure = recorder.events.single { it["type"] == "Failed" }
        assertEquals("ExecutePatch", (failure["stepId"] as Map<*, *>)["kind"])
        assertThrows(IllegalStateException::class.java) { progress.beforePublish() }
    }

    @Test fun unselectedUnmountablePatchDoesNotFailRegularInstallation() {
        val recorder = Recorder()
        val progress = ReseamProgress(listOf("b/a"), listOf("A"), false, recorder)
        progress.invoke(RunEvent.PatchFinished("b/other", PatchStatus.Unmountable("manifest changed")))
        progress.invoke(RunEvent.PatchFinished("b/a", PatchStatus.Applied))
        progress.beforePublish()
        assertNull(progress.terminalFailure)
        assertFalse(recorder.events.any { it["type"] == "Failed" })
    }

    @Test fun revertedPatchesRequireFreshResultsBeforePublication() {
        val recorder = Recorder()
        val progress = ReseamProgress(listOf("b/a", "b/z"), listOf("A", "Z"), false, recorder)
        progress.invoke(RunEvent.PatchFinished("b/a", PatchStatus.Applied))
        progress.invoke(RunEvent.PatchFinished("b/z", PatchStatus.Applied))
        progress.invoke(RunEvent.PatchesReverted(listOf("b/z")))
        assertEquals(listOf("b/a"), progress.successfulReferences())
        assertFalse(progress.statuses.containsKey("b/z"))
        assertNull(progress.terminalFailure)
        assertThrows(IllegalStateException::class.java) { progress.beforePublish() }
        progress.invoke(RunEvent.PatchStarted("b/z"))
        progress.invoke(RunEvent.PatchFinished("b/z", PatchStatus.Applied))
        progress.beforePublish()
        val firstPatchRows = recorder.events.filter {
            val step = it["stepId"] as Map<*, *>
            step["kind"] == "ExecutePatch" && step["index"] == 0
        }
        assertEquals(listOf("Completed"), firstPatchRows.map { it["type"] })
    }

    @Test fun revertedFailureDoesNotBlockSuccessfulRerun() {
        val progress = ReseamProgress(listOf("b/a"), listOf("A"), false, Recorder())
        progress.invoke(RunEvent.PatchFinished("b/a", PatchStatus.Failed("rolled back")))
        assertEquals("b/a", progress.terminalFailure)
        progress.invoke(RunEvent.PatchesReverted(listOf("b/a")))
        assertNull(progress.terminalFailure)
        assertTrue(progress.successfulReferences().isEmpty())
        progress.invoke(RunEvent.PatchFinished("b/a", PatchStatus.Applied))
        progress.beforePublish()
        assertEquals(listOf("b/a"), progress.successfulReferences())
    }

    @Test fun dependencyOrderUsesSelectionIndexes() {
        val recorder = Recorder()
        val progress = ReseamProgress(listOf("b/a", "b/z"), listOf("A", "Z"), false, recorder)
        progress.start()
        progress.invoke(RunEvent.PatchStarted("b/z"))
        progress.invoke(RunEvent.PatchFinished("b/z", PatchStatus.Applied))
        progress.invoke(RunEvent.PatchFinished("b/hidden", PatchStatus.Applied))
        progress.invoke(RunEvent.PatchStarted("b/a"))
        progress.invoke(RunEvent.PatchFinished("b/a", PatchStatus.Applied))
        progress.beforePublish()
        progress.published()
        val patches = recorder.events.filter { (it["stepId"] as Map<*, *>)["kind"] == "ExecutePatch" }
        assertEquals(listOf(1, 1, 0, 0), patches.map { (it["stepId"] as Map<*, *>)["index"] })
        assertEquals(listOf("Started", "Completed", "Started", "Completed"), patches.map { it["type"] })
        assertEquals("WriteAPK", (recorder.events.last()["stepId"] as Map<*, *>)["kind"])
        assertEquals("Completed", recorder.events.last()["type"])
    }

    @Test fun rebuildDoesNotRestartCompletedRows() {
        val recorder = Recorder()
        val progress = ReseamProgress(listOf("b/a", "b/z"), listOf("A", "Z"), true, recorder)
        progress.invoke(RunEvent.PatchStarted("b/a"))
        progress.invoke(RunEvent.PatchFinished("b/a", PatchStatus.Applied))
        progress.invoke(RunEvent.PatchFinished("b/z", PatchStatus.Failed("failure")))
        val before = recorder.events.size
        assertEquals(listOf("b/a"), progress.successfulReferences())
        progress.invoke(RunEvent.PatchStarted("b/a"))
        progress.invoke(RunEvent.PatchFinished("b/a", PatchStatus.Applied))
        assertEquals(before, recorder.events.size)
        progress.beforePublish()
        assertNull(progress.terminalFailure)
    }

    @Test fun rebuildReportsChangedResultWithoutRestartingRow() {
        val recorder = Recorder()
        val progress = ReseamProgress(listOf("b/a", "b/z"), listOf("A", "Z"), true, recorder)
        progress.invoke(RunEvent.PatchStarted("b/a"))
        progress.invoke(RunEvent.PatchFinished("b/a", PatchStatus.Applied))
        progress.invoke(RunEvent.PatchFinished("b/z", PatchStatus.Failed("failure")))
        val before = recorder.events.size
        progress.invoke(RunEvent.PatchStarted("b/a"))
        progress.invoke(RunEvent.PatchFinished("b/a", PatchStatus.Skipped("dependency unavailable")))
        val changed = recorder.events.drop(before)
        assertEquals(1, changed.size)
        assertEquals("Failed", changed.single()["type"])
        assertEquals(0, (changed.single()["stepId"] as Map<*, *>)["index"])
        assertTrue(progress.successfulReferences().isEmpty())
        progress.beforePublish()
    }

    @Test fun selectedFailureCannotKillSdkBeforeItUnwinds() {
        val recorder = Recorder()
        val progress = ReseamProgress(listOf("b/a"), listOf("A"), false, recorder)
        progress.invoke(RunEvent.PatchFinished("b/a", PatchStatus.Failed("failure")))
        val failures = recorder.events.filter { it["type"] == "Failed" }
        assertEquals(listOf("ExecutePatch"),
            failures.map { (it["stepId"] as Map<*, *>)["kind"] })
        assertThrows(IllegalStateException::class.java) { progress.beforePublish() }
    }

    @Test fun dependencyFailureStopsNormalPatching() {
        val recorder = Recorder()
        val progress = ReseamProgress(listOf("b/a"), listOf("A"), false, recorder)
        progress.invoke(RunEvent.PatchFinished("b/hidden", PatchStatus.Failed("failure")))
        assertEquals("b/hidden", progress.terminalFailure)
        assertFalse(recorder.events.any { it["type"] == "Failed" })
        assertThrows(IllegalStateException::class.java) { progress.beforePublish() }
    }

    @Test fun outputPreparationHasProgressWithoutClaimingPublicationEarly() {
        val recorder = Recorder()
        val progress = ReseamProgress(listOf("b/a"), listOf("A"), false, recorder)
        progress.invoke(RunEvent.PatchFinished("b/a", PatchStatus.Applied))
        progress.invoke(RunEvent.Info("Writing signed output to /scratch/output"))
        progress.beforePublish()
        progress.preparingUnsigned(0, 2)
        progress.preparingUnsigned(1, 2)
        progress.preparingUnsigned(2, 2)
        progress.finalizing()
        progress.finalizing(0, 1024)
        progress.finalizing(512, 1024)
        progress.finalizing(1024, 1024)
        val writeEvents = recorder.events.filter { (it["stepId"] as Map<*, *>)["kind"] == "WriteAPK" }
        assertFalse(writeEvents.any { it["type"] == "Completed" })
        val nativeWrite = writeEvents.first { it["message"] == "Writing patched APKs" }
        assertNull(nativeWrite["current"])
        assertNull(nativeWrite["total"])
        val packaging = writeEvents.filter { it["message"] == "Finalizing output" && it["current"] != null }
        assertEquals(listOf(0L, 512L, 1024L), packaging.map { it["current"] })
        val unsigned = writeEvents.filter { it["message"] == "Preparing unsigned APKs" }
        assertEquals(listOf(0L, 1L, 2L), unsigned.map { it["current"] })
        assertTrue(unsigned.all { it["total"] == 2L })
        progress.published()
        assertEquals("Completed", recorder.events.last()["type"])
    }

    @Test(expected = IllegalStateException::class)
    fun missingCompletionCannotPublish() {
        ReseamProgress(listOf("b/a"), listOf("A"), true, Recorder()).beforePublish()
    }
}
