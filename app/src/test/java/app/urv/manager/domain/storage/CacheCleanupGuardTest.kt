package app.urv.manager.domain.storage

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CacheCleanupGuardTest {
    @Test
    fun nestedUsersNotifyOnlyAfterTheLastRelease() {
        val generation = CacheCleanupGuard.idleGeneration.value
        val first = CacheCleanupGuard.begin()
        val second = CacheCleanupGuard.begin()
        try {
            first.close()
            first.close()
            assertTrue(CacheCleanupGuard.isCacheInUse)
            assertEquals(generation, CacheCleanupGuard.idleGeneration.value)
            second.close()
            assertFalse(CacheCleanupGuard.isCacheInUse)
            assertEquals(generation + 1, CacheCleanupGuard.idleGeneration.value)
            second.close()
            assertEquals(generation + 1, CacheCleanupGuard.idleGeneration.value)
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun failedCacheUserStillNotifiesCleanup() = runBlocking {
        val generation = CacheCleanupGuard.idleGeneration.value
        runCatching {
            CacheCleanupGuard.withCacheInUse { error("failed operation") }
        }
        assertFalse(CacheCleanupGuard.isCacheInUse)
        assertEquals(generation + 1, CacheCleanupGuard.idleGeneration.value)
    }

    @Test
    fun briefIdleTransitionsAreNotLost() {
        val generation = CacheCleanupGuard.idleGeneration.value
        repeat(2) { CacheCleanupGuard.begin().close() }
        assertEquals(generation + 2, CacheCleanupGuard.idleGeneration.value)
    }
}
