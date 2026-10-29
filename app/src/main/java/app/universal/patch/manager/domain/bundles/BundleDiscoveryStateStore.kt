package app.universal.patch.manager.domain.bundles

import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** App-scoped state keeps navigation immediate while ordered DataStore writes finish in the background. */
class BundleDiscoveryStateStore(
    initial: BundleDiscoveryState,
    persist: suspend (BundleDiscoveryState) -> Unit,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    onWriteError: (IOException) -> Unit = {}
) {
    private val current = MutableStateFlow(initial)
    val state = current.asStateFlow()
    private val updates = Channel<BundleDiscoveryState>(Channel.CONFLATED)

    init {
        scope.launch {
            for (next in updates) {
                try { persist(next) }
                catch (error: IOException) { onWriteError(error) }
            }
        }
    }

    @Synchronized
    fun update(transform: (BundleDiscoveryState) -> BundleDiscoveryState) {
        val next = transform(current.value)
        if (next == current.value) return
        current.value = next
        updates.trySend(next)
    }
}
