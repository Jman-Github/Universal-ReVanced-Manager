package app.urv.manager.patcher.runtime.usage

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CpuUsageSamplerTest {
    private val procStat = File.createTempFile("urv-cpu-stat", ".txt")
    private val sampler = CpuUsageSampler(procStatFile = procStat, coreCount = 2)

    @AfterTest
    fun cleanUp() {
        procStat.delete()
    }

    private fun read(cpu0: String, cpu1: String? = null): List<Int> {
        procStat.writeText(buildString {
            appendLine("cpu  0 0 0 0 0 0 0 0")
            appendLine("cpu0 $cpu0")
            cpu1?.let { appendLine("cpu1 $it") }
            appendLine("intr 0")
        })
        return sampler.sample()
    }

    @Test
    fun firstPollOnlyEstablishesBaseline() {
        assertTrue(read("100 0 0 900 0 0 0 0", "500 0 0 500 0 0 0 0").isEmpty())
        assertEquals(listOf(20, 50), read("120 0 0 980 0 0 0 0", "550 0 0 550 0 0 0 0"))
    }

    @Test
    fun returningCoreDoesNotPublishLifetimeAverageAsCurrentLoad() {
        read("100 0 0 900 0 0 0 0", "900 0 0 100 0 0 0 0")
        assertEquals(listOf(20, 0), read("120 0 0 980 0 0 0 0"))
        assertEquals(listOf(20, 0), read("140 0 0 1060 0 0 0 0", "950 0 0 250 0 0 0 0"))
        assertEquals(listOf(20, 25), read("160 0 0 1140 0 0 0 0", "1000 0 0 400 0 0 0 0"))
    }

    @Test
    fun newlyOnlineCoreNeedsItsOwnBaseline() {
        read("100 0 0 900 0 0 0 0")
        assertEquals(listOf(20, 0), read("120 0 0 980 0 0 0 0", "900 0 0 100 0 0 0 0"))
        assertEquals(listOf(20, 10), read("140 0 0 1060 0 0 0 0", "910 0 0 190 0 0 0 0"))
    }

    @Test
    fun counterResetDoesNotCreatePeakAndLaterPollRecovers() {
        read("900 0 0 100 0 0 0 0")
        assertEquals(listOf(0, 0), read("1 0 0 9 0 0 0 0"))
        assertEquals(listOf(40, 0), read("5 0 0 15 0 0 0 0"))
    }
}
