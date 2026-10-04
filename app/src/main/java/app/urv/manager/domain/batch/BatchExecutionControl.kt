package app.urv.manager.domain.batch

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

internal fun shouldRetainBatchExecutionAfterFinish(
    scheduled: Boolean,
    liveScheduledExecution: Boolean
): Boolean = scheduled && liveScheduledExecution

class BatchExecutionGate {
    private val lock = Any()
    private var owner: Any? = null

    fun tryAcquire(candidate: Any): Boolean = synchronized(lock) {
        when {
            owner === candidate -> true
            owner == null -> {
                owner = candidate
                true
            }
            else -> false
        }
    }

    fun release(candidate: Any): Boolean = synchronized(lock) {
        if (owner !== candidate) return@synchronized false
        owner = null
        true
    }

    internal fun isOwnedBy(candidate: Any): Boolean = synchronized(lock) {
        owner === candidate
    }
}

internal suspend fun <T> awaitBatchRequest(
    timeoutMs: Long,
    completion: CompletableDeferred<T>,
    timeoutResult: T,
    sendRequest: suspend () -> Unit
): T = try {
    withTimeout(timeoutMs) {
        sendRequest()
        completion.await()
    }
} catch (_: TimeoutCancellationException) {
    timeoutResult
} finally {
    if (!completion.isCompleted) completion.complete(timeoutResult)
}
