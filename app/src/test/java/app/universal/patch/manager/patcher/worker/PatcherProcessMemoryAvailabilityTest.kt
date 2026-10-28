package app.universal.patch.manager.patcher.worker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PatcherProcessMemoryAvailabilityTest {
    @Test
    fun heapHeartbeatsDoNotClearNativeMemoryButFailedResourcePollsDo() {
        val native = PatcherMemoryUsage(
            usedMb = 64, maxMb = 512, sampledAtElapsedRealtimeMs = 1000,
            resourceSampleTimeMs = 1000, processMemoryMb = 900
        )
        val heartbeat = PatcherMemoryUsage(70, 512, sampledAtElapsedRealtimeMs = 1250)
        assertEquals(900L, listOf(native, heartbeat).latestResourceUsage()?.processMemoryMb)

        val unavailable = native.copy(
            sampledAtElapsedRealtimeMs = 1500, resourceSampleTimeMs = 1500, processMemoryMb = null
        )
        assertNull(listOf(native, unavailable, heartbeat).latestResourceUsage()?.processMemoryMb)
    }
}
