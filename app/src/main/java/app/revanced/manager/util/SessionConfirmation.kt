package app.revanced.manager.util

import kotlinx.coroutines.flow.first
import ru.solrudev.ackpine.session.Failure
import ru.solrudev.ackpine.session.Session
import ru.solrudev.ackpine.session.state

suspend fun <F : Failure> Session<F>.awaitUserConfirmation(): Boolean {
    val committed = state.first { it is Session.State.Committed || it.isTerminal }
    if (committed.isTerminal) return false

    if (AppForeground.isFocused) {
        val focusLost = AppForeground.awaitFocusLost(CONFIRMATION_FOCUS_WINDOW_MS)
        if (focusLost) {
            AppForeground.awaitFocusGained()
        } else if (AppForeground.isResumed) {
            val paused = AppForeground.awaitPause(CONFIRMATION_PAUSE_WINDOW_MS)
            if (paused) {
                AppForeground.awaitResume()
            }
        }
    } else {
        AppForeground.awaitFocusGained()
    }
    val current = state.first()
    return !current.isTerminal
}

private const val CONFIRMATION_PAUSE_WINDOW_MS = 1500L
private const val CONFIRMATION_FOCUS_WINDOW_MS = 1500L
