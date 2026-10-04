package app.urv.manager.domain.storage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object CacheCleanupGuard {
    private val activeCacheUsers = AtomicInteger(0)
    private val mutableIdleGeneration = MutableStateFlow(0L)

    // A generation preserves brief idle transitions even when observers conflate updates.
    internal val idleGeneration = mutableIdleGeneration.asStateFlow()

    val isCacheInUse: Boolean
        get() = activeCacheUsers.get() > 0

    fun begin(): AutoCloseable {
        activeCacheUsers.incrementAndGet()
        val closed = AtomicBoolean(false)
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                if (activeCacheUsers.decrementAndGet() == 0) {
                    mutableIdleGeneration.update { it + 1 }
                }
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
