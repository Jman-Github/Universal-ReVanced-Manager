package app.urv.manager.patcher.host

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.*

class RunDiagnosticsTest {
    @Test fun telemetryDoesNotHideStallsAndReportsAreLimited() {
        var now = 0L
        val messages = mutableListOf<String>()
        StallWatch(messages::add, clock = { now }, dump = { "fixture stacks" },
            timeoutNanos = 1_000_000_000, maximumDumps = 5).use { watch ->
            repeat(6) {
                now += 1_000_000_000
                watch.onEvent("memory")
                watch.onEvent("log")
                watch.checkForStall()
            }
        }
        assertEquals(5, messages.count { it.startsWith("No progress") })
        assertEquals(5, messages.count { it == "fixture stacks" })
        assertTrue(messages.first().contains("1s"))
    }

    @Test fun patchingProgressResetsTheStallTimer() {
        var now = 0L
        val messages = mutableListOf<String>()
        StallWatch(messages::add, clock = { now }, dump = { "stacks" },
            timeoutNanos = 100).use { watch ->
            now = 90
            watch.onEvent("event")
            now = 180
            watch.checkForStall()
            assertTrue(messages.isEmpty())
            now = 190
            watch.checkForStall()
            assertEquals(2, messages.size)
        }
    }

    @Test fun aDumpCompletingAfterTheRunCannotWriteLateLogs() {
        val dumpStarted = CountDownLatch(1)
        val allowDump = CountDownLatch(1)
        var now = 0L
        val messages = mutableListOf<String>()
        val watch = StallWatch(messages::add, clock = { now }, dump = {
            dumpStarted.countDown()
            check(allowDump.await(5, TimeUnit.SECONDS))
            "late stacks"
        }, timeoutNanos = 1)
        now = 1
        val worker = thread { watch.checkForStall() }
        try {
            assertTrue(dumpStarted.await(5, TimeUnit.SECONDS))
            watch.close()
        } finally {
            allowDump.countDown()
            worker.join(5_000)
        }
        assertFalse(worker.isAlive)
        assertEquals(1, messages.size)
        assertTrue(messages.single().startsWith("No progress"))
        watch.checkForStall()
        assertEquals(1, messages.size)
    }

    @Test fun vmDescriptionIncludesTheActualRuntime() {
        val description = vmDescription()
        assertTrue(description.contains(System.getProperty("java.vm.name")))
        assertTrue(description.contains(System.getProperty("java.vm.version")))
        assertTrue(description.contains("arch="))
        assertTrue(description.contains("cores="))
    }
}
