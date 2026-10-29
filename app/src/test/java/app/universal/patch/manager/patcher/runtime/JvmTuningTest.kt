package app.universal.patch.manager.patcher.runtime

import kotlin.test.*

class JvmTuningTest {
    @Test fun thirtyTwoBitUsesSerialGcAndCapsTheCodeCache() {
        assertEquals(listOf("-Djava.awt.headless=true", "-XX:+UseSerialGC", "-XX:ReservedCodeCacheSize=64m"), JvmTuning.options(false))
    }

    @Test fun sixtyFourBitUsesCompactHeadersOnJdk25() {
        assertEquals(listOf("-Djava.awt.headless=true", "-XX:TieredStopAtLevel=1", "-XX:+UseCompactObjectHeaders"), JvmTuning.options(true))
        assertTrue(JvmTuning.options(false).none { it.contains("CompactObjectHeaders") })
    }
}
