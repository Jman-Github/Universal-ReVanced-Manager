package app.urv.manager.domain.installer

import kotlin.test.Test
import kotlin.test.assertEquals

class ShizukuUserRoutingTest {
    @Test
    fun `android user id is derived from the app uid`() {
        assertEquals(0, androidUserIdForUid(10_123))
        assertEquals(10, androidUserIdForUid(1_010_123))
        assertEquals(12, androidUserIdForUid(1_210_123))
    }
}
