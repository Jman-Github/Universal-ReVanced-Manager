package app.urv.manager.patcher.worker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PatcherResourceUsageParserTest {
    @Test
    fun mergeTelemetryPreservesCpuCorePositionsAndIoScope() {
        val sample = parsePatcherResourceUsage(
            "URV resources: used=64 max=512 time=1500 resourceTime=1500 " +
                "cpu=0,25,80 cpuSystem=false ioRead=1024 ioWrite=2048 ioBlock=false"
        )!!
        assertEquals(64L, sample.usedMb)
        assertEquals(512L, sample.maxMb)
        assertEquals(1500L, sample.resourceSampleTimeMs)
        assertEquals(listOf(0, 25, 80), sample.cpuCoreLoads)
        assertFalse(sample.cpuSystemWide)
        assertEquals(1024, sample.ioReadKbPerSec)
        assertEquals(2048, sample.ioWriteKbPerSec)
        assertFalse(sample.ioBlockAccounting)
    }

    @Test
    fun unavailableMetricsDoNotBecomeZeroReadings() {
        val sample = parsePatcherResourceUsage(
            "URV resources: used=64 max=512 time=2000 resourceTime=2000"
        )!!
        assertTrue(sample.cpuCoreLoads.isEmpty())
        assertNull(sample.ioReadKbPerSec)
        assertNull(sample.ioWriteKbPerSec)
    }

    @Test
    fun invalidCoreAndPartialIoDoNotPublishMisleadingReadings() {
        val sample = parsePatcherResourceUsage(
            "URV resources: used=64 max=512 time=2500 resourceTime=2500 " +
                "cpu=10,invalid,30 ioRead=100"
        )!!
        assertTrue(sample.cpuCoreLoads.isEmpty())
        assertNull(sample.ioReadKbPerSec)
        assertNull(sample.ioWriteKbPerSec)
        assertEquals(64L, sample.usedMb)
    }

    @Test
    fun malformedMemoryOrOrdinaryLogsAreIgnored() {
        assertNull(parsePatcherResourceUsage("URV resources: used=-1 max=512 time=3000"))
        assertNull(parsePatcherResourceUsage("URV resources: used=64 max=0 time=3000"))
        assertNull(parsePatcherResourceUsage("URV resources: used=64 max=512 time=invalid"))
        assertNull(parsePatcherResourceUsage("Merging resources"))
    }
}
