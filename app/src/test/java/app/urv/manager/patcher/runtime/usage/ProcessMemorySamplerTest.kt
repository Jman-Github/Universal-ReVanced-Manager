package app.urv.manager.patcher.runtime.usage

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProcessMemorySamplerTest {
    @Test
    fun nativeAndMappedResidentMemoryDoesNotUseHeapOrVirtualSize() {
        val status = Files.createTempFile("process-memory-", ".txt").toFile()
        try {
            status.writeText("VmSize:\t9000000 kB\nVmRSS:\t921600 kB\n")
            assertEquals(900L, processResidentMemoryMb(status))
            status.writeText("VmSize:\t9000000 kB\n")
            assertNull(processResidentMemoryMb(status))
            status.writeText("VmRSS: invalid kB\n")
            assertNull(processResidentMemoryMb(status))
            status.writeText("VmRSS: -1024 kB\n")
            assertNull(processResidentMemoryMb(status))
            status.writeText("VmRSS: 1024 bytes\n")
            assertNull(processResidentMemoryMb(status))
        } finally {
            status.delete()
        }
        assertNull(processResidentMemoryMb(status))
    }
}
