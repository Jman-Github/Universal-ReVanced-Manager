package app.urv.manager.data.redux

import android.util.Log
import app.urv.manager.util.tag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

// This file implements React Redux-like state management.

class Store<S>(private val coroutineScope: CoroutineScope, initialState: S) : ActionContext {
    private val _state = MutableStateFlow(initialState)
    val state = _state.asStateFlow()

    // Do not touch these without the lock.
    private var isRunningActions = false
    private val queueChannel = Channel<QueuedAction<S>>(capacity = 10)
    private val lock = Mutex()
    private val dispatchContext = ThreadLocal<Store<*>?>()

    suspend fun dispatch(action: Action<S>) {
        val completion = CompletableDeferred<Unit>()
        lock.withLock {
            Log.d(tag, "Dispatching $action")
            queueChannel.send(QueuedAction(action, completion))

            if (!isRunningActions) {
                isRunningActions = true
                coroutineScope.launch(dispatchContext.asContextElement(this@Store)) {
                    runActions()
                }
            }
        }

        if (dispatchContext.get() === this) return
        completion.await()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun runActions() {
        while (true) {
            val queued = withTimeoutOrNull(200L) { queueChannel.receive() }
            if (queued == null) {
                Log.d(tag, "Stopping action runner")
                lock.withLock {
                    // New actions may be dispatched during the timeout.
                    isRunningActions = !queueChannel.isEmpty
                    if (!isRunningActions) return
                }
                continue
            }

            val action = queued.action
            Log.d(tag, "Running $action")
            try {
                _state.value = with(action) { this@Store.execute(_state.value) }
                queued.completion.complete(Unit)
            } catch (c: CancellationException) {
                // This is done without the lock, but cancellation usually means the store is no longer needed.
                isRunningActions = false
                queued.completion.completeExceptionally(c)
                throw c
            } catch (e: Exception) {
                action.catch(e)
                queued.completion.complete(Unit)
            }
        }
    }
}

private data class QueuedAction<S>(
    val action: Action<S>,
    val completion: CompletableDeferred<Unit>
)

interface ActionContext

interface Action<S> {
    suspend fun ActionContext.execute(current: S): S
    suspend fun catch(exception: Exception) {
        Log.e(tag, "Got exception while executing $this", exception)
    }
}
