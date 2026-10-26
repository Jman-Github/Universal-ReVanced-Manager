package app.urv.manager.patcher.runtime

import kotlin.test.*

class JvmTuningTest {
    @Test fun thirtyTwoBitUsesSerialGcAndCapsTheCodeCache() {
        assertEquals(listOf("-Djava.awt.headless=true", "-XX:+UseSerialGC", "-XX:ReservedCodeCacheSize=64m"), JvmTuning.options(false))
    }

    @Test fun sixtyFourBitUsesC1OptionsSupportedByJdk17() {
        assertEquals(listOf("-Djava.awt.headless=true", "-XX:TieredStopAtLevel=1"), JvmTuning.options(true))
        assertTrue(JvmTuning.options(true).none { it.contains("CompactObjectHeaders") })
    }
}
