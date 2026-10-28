package app.universal.patch.manager.patcher

import app.universal.patch.manager.patcher.logger.LogLevel
import app.universal.patch.manager.patcher.logger.Logger
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import sun.misc.Unsafe

class SessionWriteProgressTest {
    // Skip APK decoding to inject failures specifically at the write workflow boundaries.
    private fun fixture(block: suspend (Session, File, MutableList<ProgressEvent>) -> Unit) {
        val root = Files.createTempDirectory("v21-write-progress-").toFile()
        try {
            val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
            val session = unsafe.allocateInstance(Session::class.java) as Session
            fun field(name: String, value: Any) {
                Session::class.java.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
            }
            val events = mutableListOf<ProgressEvent>()
            val apk = root.resolve("patcher/apk")
            apk.resolve("res").mkdirs()
            apk.resolve("AndroidManifest.xml").writeText("<manifest/>")
            field("tempDir", root.resolve("patcher"))
            field("input", root.resolve("input.apk").apply { writeText("original APK") })
            field("onEvent", { event: ProgressEvent -> events.add(event); Unit })
            field("checkCancelled", { Unit })
            field("logger", object : Logger() { override fun log(level: LogLevel, message: String) {} })
            runBlocking { block(session, root, events) }
        } finally { root.deleteRecursively() }
    }

    @Test fun validationFailureDoesNotPretendToCopyOrCompile() = fixture { session, root, events ->
        root.resolve("patcher/apk/res/layout").mkdirs()
        root.resolve("patcher/apk/res/layout/view.xml").writeText("<View icon=\"@drawable/absent\"/>")
        val error = runCatching { session.writeOutput(root.resolve("output.apk"), Session.ExecutedPatches(false)) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals(listOf("Validating resources"), events.filterIsInstance<ProgressEvent.Progress>().map { it.message })
        assertFalse(root.resolve("patcher/result.apk").exists())
        assertEquals(StepId.WriteAPK, events.filterIsInstance<ProgressEvent.Failed>().single().stepId)
        assertEquals("Validating resources", events.filterIsInstance<ProgressEvent.Started>().single().subSteps!!.first())
    }

    @Test fun sdkCompilationBeginsAfterCopyAndReportsItsOwnStageOnFailure() = fixture { session, root, events ->
        // The intentionally uninitialized SDK fails at requirePatcher(), after the copy succeeds.
        val error = runCatching { session.writeOutput(root.resolve("output.apk"), Session.ExecutedPatches(false)) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals("original APK", root.resolve("patcher/result.apk").readText())
        assertEquals(listOf("Validating resources", "Copying base APK", "Applying patched changes",
            "Compiling patched dex files"), events.filterIsInstance<ProgressEvent.Progress>().map { it.message })
        assertFalse(root.resolve("output.apk").exists())
    }
}
