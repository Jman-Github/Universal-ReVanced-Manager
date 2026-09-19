package app.urv.manager.domain.storage

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object CacheCleanupGuard {
    private val activeCacheUsers = AtomicInteger(0)

    val isCacheInUse: Boolean
        get() = activeCacheUsers.get() > 0

    fun begin(): AutoCloseable {
        activeCacheUsers.incrementAndGet()
        val closed = AtomicBoolean(false)
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                activeCacheUsers.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
            }
        }
    }

    suspend fun <T> withCacheInUse(block: suspend () -> T): T {
        val token = begin()
        return try {
            block()
        } finally {
            token.close()
        }
    }
}
