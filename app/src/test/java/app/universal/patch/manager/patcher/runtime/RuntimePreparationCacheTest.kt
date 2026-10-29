package app.universal.patch.manager.patcher.runtime

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RuntimePreparationCacheTest {
    @Test fun warmCallsSkipPreparationButChangedOrMissingFilesRebuild() {
        val cache = RuntimePreparationCache<String>()
        var preparations = 0
        fun load(key: String, valid: Boolean = true) = cache.get(key, { valid }) {
            "prepared-" + (++preparations)
        }
        assertEquals("prepared-1", load("runtime-a"))
        repeat(100) { assertEquals("prepared-1", load("runtime-a")) }
        assertEquals(1, preparations)
        assertEquals("prepared-2", load("runtime-a", false))
        assertEquals("prepared-3", load("runtime-b"))
    }

    @Test fun concurrentFirstUsePreparesOnce() {
        val cache = RuntimePreparationCache<Any>()
        val calls = AtomicInteger()
        val executor = Executors.newFixedThreadPool(4)
        try {
            val results = (1..16).map {
                executor.submit<Any> { cache.get("same-runtime", { true }) { calls.incrementAndGet(); Any() } }
            }.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, calls.get())
            assertTrue(results.all { it === results.first() })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun failedPreparationCanBeRetried() {
        val cache = RuntimePreparationCache<String>()
        runCatching { cache.get("broken", { true }) { error("disk full") } }
        assertEquals("ok", cache.get("broken", { true }) { "ok" })
    }
}
