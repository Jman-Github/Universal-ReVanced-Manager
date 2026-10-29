package app.universal.patch.manager.domain.repository

import kotlinx.coroutines.delay

/** Package broadcasts can arrive before PackageManager exposes the replacement APK. */
internal suspend fun refreshRuntimePluginAfterPackageChange(
    pause: suspend (Long) -> Unit = { delay(it) },
    refresh: suspend () -> Boolean
): Boolean {
    for (waitMs in listOf(250L, 500L, 1_000L, 2_000L, 4_000L)) {
        pause(waitMs)
        if (refresh()) return true
    }
    return false
}
