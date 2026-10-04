package app.urv.manager.domain.batch

import kotlinx.coroutines.CompletableDeferred

internal class PendingRequestRegistry<T> {
    private val pending = mutableMapOf<String, CompletableDeferred<T>>()

    @Synchronized
    fun register(requestId: String, completion: CompletableDeferred<T>): Boolean =
        pending.putIfAbsent(requestId, completion) == null

    @Synchronized
    fun remove(requestId: String, completion: CompletableDeferred<T>) {
        if (pending[requestId] === completion) pending.remove(requestId)
    }

    @Synchronized
    fun complete(requestId: String, value: T): Boolean =
        pending.remove(requestId)?.complete(value) == true

    @Synchronized
    fun fail(requestId: String, error: Throwable): Boolean =
        pending.remove(requestId)?.completeExceptionally(error) == true

    @Synchronized
    fun cancel(requestId: String, value: T): Boolean =
        pending.remove(requestId)?.complete(value) == true

    @Synchronized
    fun cancelAll(value: T) {
        pending.values.toList().forEach { it.complete(value) }
        pending.clear()
    }
}
