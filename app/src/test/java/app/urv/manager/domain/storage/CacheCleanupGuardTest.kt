package app.urv.manager.domain.storage

import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
    fun cleanupWaitsForTheLastCacheUser() {
        val first = CacheCleanupGuard.begin()
        val second = CacheCleanupGuard.begin()
        var deleted = false
        try {
            assertEquals(null, CacheCleanupGuard.runIfIdle { deleted = true })
            first.close()
            assertEquals(null, CacheCleanupGuard.runIfIdle { deleted = true })
            assertFalse(deleted)
            second.close()
            assertEquals(123, CacheCleanupGuard.runIfIdle { deleted = true; 123 })
            assertTrue(deleted)
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun newCacheUserCannotStartDuringFileDetachment() {
        val executor = Executors.newSingleThreadExecutor()
        val attempting = CountDownLatch(1)
        val started = CountDownLatch(1)
        try {
            val user = CacheCleanupGuard.runIfIdle {
                val future = executor.submit {
                    attempting.countDown()
                    CacheCleanupGuard.begin().use { started.countDown() }
                }
                assertTrue(attempting.await(5, TimeUnit.SECONDS))
                assertFalse(started.await(100, TimeUnit.MILLISECONDS))
                future
            }
            requireNotNull(user).get(5, TimeUnit.SECONDS)
            assertEquals(0L, started.count)
            assertFalse(CacheCleanupGuard.isCacheInUse)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun failedCleanupReleasesTheLock() {
        runCatching { CacheCleanupGuard.runIfIdle { error("cannot delete") } }
        CacheCleanupGuard.begin().use {
            assertTrue(CacheCleanupGuard.isCacheInUse)
        }
        assertFalse(CacheCleanupGuard.isCacheInUse)
    }

    @Test
    fun briefIdleTransitionsAreNotLost() {
        val generation = CacheCleanupGuard.idleGeneration.value
        repeat(2) { CacheCleanupGuard.begin().close() }
        assertEquals(generation + 2, CacheCleanupGuard.idleGeneration.value)
    }
}
