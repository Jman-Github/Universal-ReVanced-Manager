package app.urv.manager.util

import android.annotation.SuppressLint
import android.app.Notification
import android.os.Build
import java.lang.reflect.Method

/**
 * Applies the common metadata used by active progress notifications.
 *
 * Standard progress fields support existing Android and OEM presentations. Android 16 also
 * receives [Notification.ProgressStyle], while Android 16 QPR releases can request promoted
 * ongoing presentation when that public API is available at runtime.
 */
fun Notification.Builder.applyProgressNotification(
    max: Int,
    current: Int,
    indeterminate: Boolean,
    ongoing: Boolean = true
): Notification.Builder {
    val safeMax = max.coerceAtLeast(0)
    val safeCurrent = current.coerceIn(0, safeMax)

    setCategory(Notification.CATEGORY_PROGRESS)
    setOnlyAlertOnce(true)
    setOngoing(ongoing)
    setShowWhen(false)
    setProgress(safeMax, safeCurrent, indeterminate)

    if (Build.VERSION.SDK_INT >= 36) {
        applyAndroid16ProgressStyle(safeMax, safeCurrent, indeterminate, ongoing)
    }

    return this
}

@SuppressLint("NewApi")
private fun Notification.Builder.applyAndroid16ProgressStyle(
    max: Int,
    current: Int,
    indeterminate: Boolean,
    ongoing: Boolean
) {
    val percent = if (!indeterminate && max > 0) {
        ((current.toLong() * 100L) / max.toLong()).toInt().coerceIn(0, 100)
    } else {
        null
    }

    val style = Notification.ProgressStyle()
        .setProgressIndeterminate(indeterminate)
    percent?.let {
        style.setProgress(it)
        setShortCriticalText("$it%")
    }
    setStyle(style)

    if (ongoing) {
        runCatching {
            requestPromotedOngoingMethod?.invoke(this, true)
        }
    }
}

private val requestPromotedOngoingMethod: Method? by lazy {
    runCatching {
        Notification.Builder::class.java.getMethod(
            "setRequestPromotedOngoing",
            Boolean::class.javaPrimitiveType
        )
    }.getOrNull()
}
