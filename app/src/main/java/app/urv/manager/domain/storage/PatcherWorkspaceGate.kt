package app.urv.manager.domain.storage

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * WorkManager cancellation can finish before the cancelled coroutine's cleanup.
 * Replacement patches must wait until the previous owner releases the shared workspace.
 */
internal object PatcherWorkspaceGate {
    private val mutex = Mutex()

    suspend fun <T> withWorkspace(block: suspend () -> T): T = mutex.withLock {
        currentCoroutineContext().ensureActive()
        CacheCleanupGuard.withCacheInUse(block)
    }
}
