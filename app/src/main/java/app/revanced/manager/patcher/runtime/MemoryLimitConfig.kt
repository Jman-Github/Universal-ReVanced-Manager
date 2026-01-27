package app.revanced.manager.patcher.runtime

import android.app.ActivityManager
import android.content.Context
import androidx.core.content.getSystemService
import kotlin.math.max

object MemoryLimitConfig {
    const val MIN_LIMIT_MB = 200
    private const val DEFAULT_FALLBACK_LIMIT_MB = 700

    fun maxLimitMb(context: Context): Int {
        val activityManager = context.getSystemService<ActivityManager>()
            ?: return DEFAULT_FALLBACK_LIMIT_MB
        return max(activityManager.memoryClass, activityManager.largeMemoryClass)
    }

    fun recommendedLimitMb(context: Context): Int = DEFAULT_FALLBACK_LIMIT_MB

    fun autoScaleLimitMb(context: Context, requestedMb: Int): Int {
        return requestedMb.coerceAtLeast(MIN_LIMIT_MB)
    }

    fun clampLimitMb(context: Context, requestedMb: Int): Int {
        val upperBound = max(MIN_LIMIT_MB, maxLimitMb(context))
        return requestedMb.coerceIn(MIN_LIMIT_MB, upperBound)
    }
}
