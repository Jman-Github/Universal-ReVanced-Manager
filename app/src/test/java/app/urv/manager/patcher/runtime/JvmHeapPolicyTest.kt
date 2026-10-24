package app.urv.manager.patcher.runtime

import kotlin.test.Test
import kotlin.test.assertEquals

class JvmHeapPolicyTest {
    @Test fun leavesNativeHeadroomAndCapsThe32BitHeap() {
        assertEquals(1024, JvmHeapPolicy.fit32Bit(3072, 1152))
        assertEquals(768, JvmHeapPolicy.fit32Bit(1024, 896))
        assertEquals(512, JvmHeapPolicy.fit32Bit(1024, 640))
        assertEquals(448, JvmHeapPolicy.fit32Bit(1024, 576))
        assertEquals(0, JvmHeapPolicy.fit32Bit(1024, 64))
        assertEquals(0, JvmHeapPolicy.fit32Bit(1024, 0))
    }
}
