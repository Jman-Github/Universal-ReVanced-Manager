package app.universal.patch.manager.patcher.worker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PatcherResourceAvailabilityTest {
    @Test
    fun heapHeartbeatsDoNotClearTheLatestResourcePoll() {
        val history = mutableListOf<PatcherMemoryUsage>()
        repeat(4) { index ->
            val time = (index + 1) * 1000L
            val poll = parsePatcherResourceUsage(
                "UPM resources: used=64 max=512 time=$time resourceTime=$time " +
                    "cpu=0,25 ioRead=0 ioWrite=100"
            )!!
            history += poll
            history += PatcherMemoryUsage(70, 512, sampledAtElapsedRealtimeMs = time + 250)
            assertEquals(poll, history.latestResourceUsage())
        }
    }

    @Test
    fun failedResourcePollsRemainUnavailableUntilARealRecovery() {
        val available = parsePatcherResourceUsage(
            "UPM resources: used=64 max=512 time=1000 resourceTime=1000 " +
                "cpu=20,40 ioRead=100 ioWrite=200"
        )!!
        val unavailable = parsePatcherResourceUsage(
            "UPM resources: used=65 max=512 time=1500 resourceTime=1500"
        )!!
        val history = mutableListOf(available, unavailable)
        history += PatcherMemoryUsage(70, 512, sampledAtElapsedRealtimeMs = 1750)
        assertEquals(unavailable, history.latestResourceUsage())
        assertTrue(history.latestResourceUsage()!!.cpuCoreLoads.isEmpty())
        assertNull(history.latestResourceUsage()!!.ioReadKbPerSec)

        val recovered = available.copy(sampledAtElapsedRealtimeMs = 2000, resourceSampleTimeMs = 2000)
        history += recovered
        assertEquals(recovered, history.latestResourceUsage())
    }

    @Test
    fun memoryOnlySessionsStayUnavailableAndZeroResourceReadingsAreValid() {
        val memoryOnly = PatcherMemoryUsage(64, 512)
        assertNull(emptyList<PatcherMemoryUsage>().latestResourceUsage())
        assertNull(listOf(memoryOnly).latestResourceUsage())
        // Older samples may carry metrics without a resource timestamp.
        val cpu = memoryOnly.copy(cpuCoreLoads = listOf(0, 0))
        val io = memoryOnly.copy(ioReadKbPerSec = 0, ioWriteKbPerSec = 0)
        assertEquals(cpu, listOf(cpu, memoryOnly).latestResourceUsage())
        assertEquals(io, listOf(io, memoryOnly).latestResourceUsage())
    }
}
