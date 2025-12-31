package app.revanced.manager.util

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

object AppForeground {
    private val resumeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val pauseEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val focusEvents = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)

    @Volatile
    var isResumed: Boolean = false
        private set

    @Volatile
    var isFocused: Boolean = false
        private set

    fun onResumed() {
        isResumed = true
        resumeEvents.tryEmit(Unit)
    }

    fun onPaused() {
        isResumed = false
        pauseEvents.tryEmit(Unit)
    }

    fun onWindowFocusChanged(hasFocus: Boolean) {
        isFocused = hasFocus
        focusEvents.tryEmit(hasFocus)
    }

    suspend fun awaitResume() {
        if (isResumed) return
        resumeEvents.first()
    }

    suspend fun awaitPause(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            pauseEvents.first()
            true
        } ?: false

    suspend fun awaitFocusLost(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            focusEvents.first { !it }
            true
        } ?: false

    suspend fun awaitFocusGained() {
        if (isFocused) return
        focusEvents.first { it }
    }

}
