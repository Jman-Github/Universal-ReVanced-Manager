package app.universal.patch.manager.patcher.logger

import androidx.annotation.StringRes
import app.universal.patch.manager.R

@get:StringRes
val PatcherLogMode.displayName: Int get() = when (this) {
    PatcherLogMode.DEFAULT -> R.string.patcher_log_mode_default
    PatcherLogMode.VERBOSE -> R.string.patcher_log_mode_verbose
}
