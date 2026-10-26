/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */
package app.urv.manager.patcher.runtime

// Code adapted from Morphe, see third-party/NOTICE.txt for more information.
// https://github.com/MorpheApp/morphe-manager/pull/1246

/** Options supported by the JDK 17 server and 32-bit client VM ports. */
internal object JvmTuning {
    fun options(is64Bit: Boolean): List<String> = listOf("-Djava.awt.headless=true") + if (is64Bit) {
        listOf("-XX:TieredStopAtLevel=1")
    } else {
        listOf("-XX:+UseSerialGC", "-XX:ReservedCodeCacheSize=64m")
    }
}
