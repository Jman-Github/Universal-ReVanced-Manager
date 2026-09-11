package app.urv.manager.domain.installer.root

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MountTableReaderTest {
    private val reader = MountTableReader()

    @Test
    fun `parses escaped paths and optional fields`() {
        val entry = reader.parseLine(
            "36 25 0:32 /source\\040apk /data/app/pkg\\040name/base.apk rw,nosuid shared:7 - ext4 /dev/block/dm-3 rw,seclabel"
        )
        requireNotNull(entry)
        assertEquals("/source apk", entry.root)
        assertEquals("/data/app/pkg name/base.apk", entry.mountPoint)
        assertEquals("/dev/block/dm-3", entry.source)
        assertEquals(setOf("rw", "nosuid"), entry.options)
    }

    @Test
    fun `rejects truncated mountinfo`() {
        assertNull(reader.parseLine("1 2 3"))
    }
}
