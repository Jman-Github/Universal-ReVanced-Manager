package app.urv.manager.ui.model

import androidx.compose.runtime.mutableStateListOf
import app.urv.manager.patcher.PatcherSessionInfo
import app.urv.manager.patcher.ProgressEvent
import app.urv.manager.patcher.StepId
import app.urv.manager.patcher.updatedFromLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PatcherProgressRegressionTest {
    @Test
    fun runtimeLogsDistinguishJvmFromArt() {
        val jvm = PatcherSessionInfo().updatedFromLog(
            "Runtime mode: in-app JVM (OpenJDK 17); memory budget: 768MB")
        assertEquals("JVM", jvm.runtimeName)
        assertEquals(false, jvm.runtimeProcess)
        val art = jvm.updatedFromLog("Runtime mode: bound ART service; memory budget: Android managed")
        assertEquals("ART", art.runtimeName)
        assertEquals(true, art.runtimeProcess)
    }

    @Test
    fun runtimeNamesDoNotIncludeLogSeparators() {
        for (runtime in listOf("MORPHE", "REVANCED", "RESEAM")) {
            val current = PatcherSessionInfo().updatedFromLog(
                "Patcher runtime: bundle=$runtime; automatic memory budget"
            )
            assertEquals(runtime, current.bundleType)
            val legacy = PatcherSessionInfo().updatedFromLog(
                "Patcher runtime: bundle=$runtime memoryLimit=2048MB"
            )
            assertEquals(runtime, legacy.bundleType)
            assertEquals(2048, legacy.memoryLimitMb)
        }
    }

    @Test
    fun morpheLogsKeepGenericResultDexEntriesOutOfTheChip() {
        val fixture = fixture("MORPHE")
        fixture.start()
        fixture.tracker.handleDexCompileLine("Writing patched files...")
        fixture.tracker.handleDexCompileLine("Processing 300 classes in parallel (4 threads)")
        fixture.tracker.handleDexCompileLine("Wrote 2 dex files to /cache/dex")
        fixture.tracker.handleDexCompileLine("Stripped 12 class_def entries from classes3.dex")
        fixture.progress("Compiling modified resources")
        // The result may contain copied or renamed DEX files; these are not modification reports.
        (1..14).forEach { index ->
            val name = if (index == 1) "classes.dex" else "classes$index.dex"
            fixture.progress("Compiling $name")
        }
        val group = fixture.dexGroup()
        assertEquals(
            listOf("Processing 300 classes", "Wrote 2 dex files", "Modified classes3.dex"),
            group.children.map { it.title }
        )
        assertEquals(State.COMPLETED, group.state)
        assertEquals(State.RUNNING, fixture.detail("Compiling modified resources").state)
    }

    @Test
    fun morpheSingleThreadedCompilationUsesTheDexChip() {
        val fixture = fixture("MORPHE")
        fixture.start()
        fixture.tracker.handleDexCompileLine("Writing patched files...")
        fixture.tracker.handleDexCompileLine("Processing 12 classes (single threaded mode)")
        assertEquals("Processing 12 classes", fixture.dexGroup().children.single().title)
        assertEquals(State.RUNNING, fixture.dexGroup().state)
    }

    @Test
    fun delayedDexAndResourceMessagesCannotReopenFinishedPhases() {
        for (runtime in listOf("MORPHE", "REVANCED")) {
            val fixture = fixture(runtime)
            fixture.start()
            fixture.progress("Applying patched changes")
            fixture.progress(if (runtime == "MORPHE") "Modified classes3.dex" else "Compiling classes3.dex")
            fixture.progress("Compiling modified resources")
            fixture.progress("Writing output APK")
            val lateTitle = if (runtime == "MORPHE") "Modified classes4.dex" else "Compiling classes4.dex"
            fixture.progress(lateTitle)
            assertEquals(State.COMPLETED, fixture.dexGroup().children.last().state)
            fixture.progress(fixture.dexGroup().title)
            fixture.progress("Compiling modified resources")
            assertEquals(State.COMPLETED, fixture.dexGroup().state)
            assertEquals(State.COMPLETED, fixture.detail("Compiling modified resources").state)
            assertEquals(State.RUNNING, fixture.detail("Writing output APK").state)
            fixture.progress("Finalizing output")
            fixture.progress("Writing output APK")
            assertEquals(State.RUNNING, fixture.detail("Finalizing output").state)
        }
    }

    @Test
    fun revancedDexProgressStillTracksEachCompiledFile() {
        val fixture = fixture("REVANCED")
        fixture.start()
        fixture.progress("Applying patched changes")
        fixture.progress("Compiling classes.dex")
        fixture.progress("Compiling classes2.dex")
        fixture.progress("Compiling modified resources")
        assertEquals(listOf("Compiling classes.dex", "Compiling classes2.dex"),
            fixture.dexGroup().children.map { it.title })
        assertTrue(fixture.dexGroup().children.all { it.state == State.COMPLETED })
        assertEquals(State.RUNNING, fixture.detail("Compiling modified resources").state)
    }

    @Test
    fun reseamKeepsItsOwnOutputPhases() {
        val fixture = fixture("RESEAM")
        fixture.tracker.processProgressEventLocked(ProgressEvent.Started(
            StepId.WriteAPK, listOf("Writing patched APKs", "Preparing unsigned APKs", "Finalizing output")
        ))
        fixture.progress("Writing patched APKs")
        fixture.progress("Preparing unsigned APKs")
        assertEquals(State.COMPLETED, fixture.detail("Writing patched APKs").state)
        assertEquals(State.RUNNING, fixture.detail("Preparing unsigned APKs").state)
        fixture.progress("Finalizing output")
        assertEquals(State.COMPLETED, fixture.detail("Preparing unsigned APKs").state)
        assertEquals(State.RUNNING, fixture.detail("Finalizing output").state)
        assertFalse(fixture.details().any { it.title.startsWith("Compiling DEX") })
    }

    @Test
    fun terminalRuntimeFailureKeepsTheReportedStage() {
        for (runtime in listOf("MORPHE", "REVANCED", "RESEAM")) {
            val fixture = fixture(runtime)
            fixture.start()
            val original = app.urv.manager.patcher.RemoteError(
                "fixture.PatcherError", "Writing failed", "fixture.PatcherError: Writing failed\n\tat fixture.write(Entry.kt:1)"
            )
            fixture.tracker.processProgressEventLocked(ProgressEvent.Failed(StepId.WriteAPK, original))
            val wrapper = app.urv.manager.patcher.runtime.BoundRuntimeFailureException(
                original.stackTrace, original.stackTrace
            )
            fixture.tracker.processProgressEventLocked(ProgressEvent.Failed(null, wrapper.toRemoteError()))
            assertEquals(State.FAILED, fixture.tracker.steps.first { it.id == StepId.WriteAPK }.state)
            assertEquals(State.WAITING, fixture.tracker.steps.first { it.id == StepId.SignAPK }.state)
        }
    }

    private fun fixture(runtime: String): Fixture {
        val session = PatcherSessionInfo().updatedFromLog(
            "Patcher runtime: bundle=$runtime; automatic memory budget"
        )
        val tracker = PatcherProgressTracker(
            steps = mutableStateListOf(
                Step(StepId.WriteAPK, "Writing APK", StepCategory.SAVING),
                Step(StepId.SignAPK, "Signing APK", StepCategory.SAVING)
            ),
            stepSubSteps = mutableMapOf(),
            isMorpheSelection = { session.bundleType == "MORPHE" },
            morpheBytecodeMode = { "STRIP_FAST" }
        )
        return Fixture(tracker, runtime)
    }

    private class Fixture(val tracker: PatcherProgressTracker, val runtime: String) {
        fun start() {
            tracker.processProgressEventLocked(ProgressEvent.Started(StepId.WriteAPK, listOf(
                if (runtime == "MORPHE") "Copy base APK" else "Copying base APK",
                "Applying patched changes",
                if (runtime == "MORPHE") "Compiling DEX files: FAST" else "Compiling DEX files",
                "Compiling modified resources", "Writing output APK", "Finalizing output"
            )))
        }
        fun progress(message: String) = tracker.processProgressEventLocked(
            ProgressEvent.Progress(StepId.WriteAPK, message = message)
        )
        fun details(): List<StepDetail> = tracker.stepSubSteps.getValue(StepId.WriteAPK)
        fun detail(title: String) = details().single { it.title == title }
        fun dexGroup() = details().single { it.title.startsWith("Compiling DEX files") }
    }
}
