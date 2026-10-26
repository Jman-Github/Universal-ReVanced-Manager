package app.urv.manager.ui.model

import androidx.compose.runtime.mutableStateListOf
import app.urv.manager.patcher.ProgressEvent
import app.urv.manager.patcher.RemoteError
import app.urv.manager.patcher.StepId
import org.junit.Assert.*
import org.junit.Test

class PatcherProgressTrackerTest {
    private fun tracker(id: StepId) = PatcherProgressTracker(
        steps = mutableStateListOf(
            Step(StepId.DownloadAPK, "Download", StepCategory.PREPARING, State.COMPLETED),
            Step(StepId.LoadPatches, "Load", StepCategory.PREPARING),
            Step(id, "Work", StepCategory.SAVING)
        ),
        stepSubSteps = mutableMapOf(),
        isMorpheSelection = { false },
        morpheBytecodeMode = { null }
    )

    private fun PatcherProgressTracker.report(id: StepId, title: String, current: Long? = null, total: Long? = null) =
        processProgressEventLocked(ProgressEvent.Progress(id, current, total, title))

    private fun PatcherProgressTracker.current(id: StepId) = steps.single { it.id == id }.progress!!.first

    @Test fun splitExtractionDoesNotRestartAtInspectionOrAtEveryComponent() {
        val id = StepId.PrepareSplitApk
        val tracker = tracker(id)
        tracker.processProgressEventLocked(ProgressEvent.Started(id,
            listOf("Extracting split APKs", "Inspecting split APKs", "Preparing split APKs")))
        val readings = mutableListOf<Long>()
        fun report(title: String, current: Long, total: Long) {
            tracker.report(id, title, current, total)
            readings += tracker.current(id)
        }
        report("Extracting split APKs", 0, 100)
        report("Extracting split APKs", 50, 100)
        report("Extracting split APKs", 100, 100)
        assertEquals(333L, readings.last())
        report("Inspecting split APKs", 0, 1)
        tracker.processProgressEventLocked(ProgressEvent.Progress(id, subSteps = listOf(
            "Extracting split APKs", "Inspecting split APKs", "Preparing base.apk", "Preparing config.en.apk"
        )))
        report("Preparing base.apk", 0, 1)
        report("Preparing base.apk", 1, 1)
        report("Preparing config.en.apk", 0, 1)
        report("Preparing config.en.apk", 1, 1)
        assertTrue(readings.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(999L, readings.last())
        assertEquals(1000L, tracker.steps.single { it.id == id }.progress!!.second)
        tracker.processProgressEventLocked(ProgressEvent.Completed(id))
        assertEquals(State.COMPLETED, tracker.steps.single { it.id == id }.state)
    }

    @Test fun freshSplitAttemptClearsItsRowsWhilePreservingTheDownload() {
        val id = StepId.PrepareSplitApk
        val tracker = tracker(id)
        val phases = listOf("Extracting split APKs", "Inspecting split APKs", "Preparing split APKs")
        tracker.processProgressEventLocked(ProgressEvent.Started(id, phases))
        tracker.processProgressEventLocked(ProgressEvent.Completed(id))
        tracker.processProgressEventLocked(ProgressEvent.Started(StepId.LoadPatches))
        tracker.processProgressEventLocked(ProgressEvent.Completed(StepId.LoadPatches))
        tracker.processProgressEventLocked(ProgressEvent.Started(id, phases))
        tracker.report(id, "Extracting split APKs", 0, 100)
        assertEquals(0L, tracker.current(id))
        assertEquals(State.RUNNING, tracker.steps.single { it.id == id }.state)
        assertEquals(State.COMPLETED, tracker.steps.single { it.id == StepId.DownloadAPK }.state)
        assertTrue(tracker.stepSubSteps[id]!!.none { it.state == State.COMPLETED })
    }

    @Test fun writeProgressReservesWorkForUnsignedConversionAndPackaging() {
        val id = StepId.WriteAPK
        val tracker = tracker(id)
        tracker.processProgressEventLocked(ProgressEvent.Started(id,
            listOf("Writing patched APKs", "Preparing unsigned APKs", "Finalizing output")))
        tracker.report(id, "Writing patched APKs")
        assertEquals(0L, tracker.current(id))
        tracker.report(id, "Writing patched APKs", 1, 1)
        assertEquals(333L, tracker.current(id))
        tracker.report(id, "Preparing unsigned APKs", 0, 2)
        assertEquals(333L, tracker.current(id))
        tracker.report(id, "Preparing unsigned APKs", 1, 2)
        assertEquals(500L, tracker.current(id))
        tracker.report(id, "Preparing unsigned APKs", 2, 2)
        tracker.report(id, "Finalizing output", 0, 100)
        assertEquals(666L, tracker.current(id))
        tracker.report(id, "Finalizing output", 50, 100)
        val beforeStale = tracker.current(id)
        tracker.report(id, "Preparing unsigned APKs", 0, 2)
        assertEquals(beforeStale, tracker.current(id))
        tracker.report(id, "Finalizing output", 100, 100)
        assertEquals(999L, tracker.current(id))
        assertEquals(State.RUNNING, tracker.steps.single { it.id == id }.state)
        tracker.processProgressEventLocked(ProgressEvent.Completed(id))
        assertTrue(tracker.stepSubSteps[id]!!.all { it.state == State.COMPLETED })
    }

    @Test fun lateKnownWritePhaseCannotReactivateAnEarlierRow() {
        val id = StepId.WriteAPK
        val tracker = tracker(id)
        tracker.processProgressEventLocked(ProgressEvent.Started(id,
            listOf("Writing output APK", "Finalizing output")))
        tracker.report(id, "Writing output APK", 10, 100)
        tracker.report(id, "Finalizing output", 20, 100)
        val before = tracker.current(id)
        tracker.report(id, "Writing output APK", 0, 100)
        assertEquals(before, tracker.current(id))
        assertEquals(State.RUNNING, tracker.stepSubSteps[id]!!.last().state)
        assertEquals(State.COMPLETED, tracker.stepSubSteps[id]!!.first().state)
    }

    @Test fun failureClearsIndicatorAndDoesNotCompleteOutput() {
        val id = StepId.WriteAPK
        val tracker = tracker(id)
        tracker.processProgressEventLocked(ProgressEvent.Started(id, listOf("Finalizing output")))
        tracker.report(id, "Finalizing output", 50, 100)
        tracker.processProgressEventLocked(ProgressEvent.Failed(id, RemoteError("IOException", "full", "full")))
        assertEquals(State.FAILED, tracker.steps.single { it.id == id }.state)
        assertNull(tracker.steps.single { it.id == id }.progress)
        assertEquals(State.FAILED, tracker.stepSubSteps[id]!!.single().state)
    }

    @Test fun downloadHandoffKeepsPreparationActiveUntilTheWorkerReports() {
        val tracker = PatcherProgressTracker(
            steps = mutableStateListOf(
                Step(StepId.DownloadAPK, "Download", StepCategory.PREPARING, State.RUNNING),
                Step(StepId.LoadPatches, "Load", StepCategory.PREPARING),
                Step(StepId.ReadAPK, "Read", StepCategory.PREPARING)
            ),
            stepSubSteps = mutableMapOf(),
            isMorpheSelection = { false },
            morpheBytecodeMode = { null }
        )
        tracker.processProgressEventLocked(ProgressEvent.Completed(StepId.DownloadAPK))
        assertEquals(State.RUNNING, tracker.steps.single { it.id == StepId.LoadPatches }.state)
        assertNull(tracker.steps.single { it.id == StepId.LoadPatches }.progress)
        assertEquals(State.WAITING, tracker.steps.single { it.id == StepId.ReadAPK }.state)

        tracker.processProgressEventLocked(ProgressEvent.Started(StepId.LoadPatches))
        tracker.processProgressEventLocked(ProgressEvent.Completed(StepId.LoadPatches))
        assertEquals(State.COMPLETED, tracker.steps.single { it.id == StepId.LoadPatches }.state)
        assertEquals(State.RUNNING, tracker.steps.single { it.id == StepId.ReadAPK }.state)
        tracker.processProgressEventLocked(ProgressEvent.Started(StepId.ReadAPK))
        assertEquals(1, tracker.steps.count { it.state == State.RUNNING })
    }

    @Test fun splitHandoffKeepsTheNextPreparationActiveAndPreservesFailureAttribution() {
        val tracker = PatcherProgressTracker(
            steps = mutableStateListOf(
                Step(StepId.DownloadAPK, "Download", StepCategory.PREPARING, State.RUNNING),
                Step(StepId.PrepareSplitApk, "Prepare splits", StepCategory.PREPARING),
                Step(StepId.LoadPatches, "Load", StepCategory.PREPARING),
                Step(StepId.ReadAPK, "Read", StepCategory.PREPARING)
            ),
            stepSubSteps = mutableMapOf(),
            isMorpheSelection = { false },
            morpheBytecodeMode = { null }
        )
        tracker.processProgressEventLocked(ProgressEvent.Completed(StepId.DownloadAPK))
        assertEquals(State.RUNNING, tracker.steps.single { it.id == StepId.PrepareSplitApk }.state)
        assertEquals(State.WAITING, tracker.steps.single { it.id == StepId.LoadPatches }.state)
        tracker.processProgressEventLocked(ProgressEvent.Started(StepId.PrepareSplitApk))
        tracker.processProgressEventLocked(ProgressEvent.Completed(StepId.PrepareSplitApk))
        assertEquals(State.RUNNING, tracker.steps.single { it.id == StepId.LoadPatches }.state)
        tracker.processProgressEventLocked(ProgressEvent.Started(StepId.LoadPatches))
        tracker.processProgressEventLocked(ProgressEvent.Failed(
            StepId.LoadPatches, RemoteError("IOException", "Invalid bundle", "Invalid bundle")
        ))
        assertEquals(State.FAILED, tracker.steps.single { it.id == StepId.LoadPatches }.state)
        assertEquals(State.COMPLETED, tracker.steps.single { it.id == StepId.PrepareSplitApk }.state)
        assertEquals(State.WAITING, tracker.steps.single { it.id == StepId.ReadAPK }.state)
        assertTrue(tracker.steps.none { it.state == State.RUNNING })
    }

    @Test fun downloadCountersStayInBytesAndZeroTotalsAreSafe() {
        val tracker = tracker(StepId.WriteAPK)
        val index = tracker.steps.indexOfFirst { it.id == StepId.DownloadAPK }
        tracker.steps[index] = tracker.steps[index].copy(state = State.WAITING)
        tracker.report(StepId.DownloadAPK, "Download", 100, 200)
        assertEquals(100L to 200L, tracker.steps[index].progress)
        val details = listOf(StepDetail("Finalizing output", State.RUNNING, progress = 0L to 0L))
        assertEquals(0.0, expandableStepProgressFraction(StepId.WriteAPK, details)!!, 0.00001)
    }

    @Test fun groupedDexWorkStillCountsChildrenAndIgnoresSkippedWork() {
        val details = listOf(
            StepDetail("Compiling DEX files", State.RUNNING, children = listOf(
                StepDetail("classes.dex", State.COMPLETED),
                StepDetail("classes2.dex", State.RUNNING, progress = 1L to 2L),
                StepDetail("unused.dex", skipped = true)
            )),
            StepDetail("Finalizing output", State.WAITING)
        )
        assertEquals(0.5, expandableStepProgressFraction(StepId.WriteAPK, details)!!, 0.00001)
        assertTrue(isComponentCountProgress("Preparing unsigned APKs"))
        assertTrue(isComponentCountProgress("Preparing config.en.apk"))
        assertFalse(isComponentCountProgress("Extracting split APKs"))
        assertFalse(isComponentCountProgress("Finalizing output"))
    }

    private fun v21WriteStages() = listOf("Validating resources", "Copying base APK", "Applying patched changes",
        "Compiling DEX files", "Compiling modified resources", "Writing patched APK", "Writing output APK", "Finalizing output")

    @Test fun v21ValidationFailureIsAttachedToValidationRatherThanCopying() {
        val id = StepId.WriteAPK
        val tracker = tracker(id)
        tracker.processProgressEventLocked(ProgressEvent.Started(id, v21WriteStages()))
        tracker.report(id, "Validating resources")
        tracker.processProgressEventLocked(ProgressEvent.Failed(id,
            RemoteError("IllegalStateException", "Missing resource references detected.", "")))
        val rows = tracker.stepSubSteps.getValue(id)
        assertEquals("Validating resources", rows.single { it.state == State.FAILED }.title)
        assertEquals(State.WAITING, rows.single { it.title == "Copying base APK" }.state)
    }

    @Test fun v21CompilationAndPackagingProgressDoNotMoveBackwards() {
        val id = StepId.WriteAPK
        val tracker = tracker(id)
        tracker.processProgressEventLocked(ProgressEvent.Started(id, v21WriteStages()))
        tracker.report(id, "Validating resources")
        tracker.report(id, "Copying base APK")
        tracker.report(id, "Applying patched changes")
        tracker.handleDexCompileLine("Writing patched files...")
        tracker.report(id, "Compiling patched dex files")
        tracker.handleDexCompileLine("Compiled classes.dex")
        tracker.handleDexCompileLine("Compiling modified resources")
        tracker.report(id, "Writing patched APK")
        val before = tracker.progress
        tracker.handleDexCompileLine("Compiled classes2.dex")
        val rows = tracker.stepSubSteps.getValue(id)
        assertEquals("Writing patched APK", rows.single { it.state == State.RUNNING }.title)
        assertTrue(tracker.progress >= before)
        tracker.report(id, "Writing output APK")
        tracker.report(id, "Finalizing output")
        tracker.processProgressEventLocked(ProgressEvent.Completed(id))
        assertTrue(tracker.stepSubSteps.getValue(id).all { it.state == State.COMPLETED })
    }
}
