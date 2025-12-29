package app.revanced.manager.patcher.runtime

import android.app.ActivityManager
import android.content.Context
import androidx.core.content.getSystemService
import kotlin.math.max
import kotlin.math.roundToInt

object MemoryLimitConfig {
    const val MIN_LIMIT_MB = 200
    private const val DEFAULT_FALLBACK_LIMIT_MB = 700
    private const val RECOMMENDED_SCALE = 0.8f

    fun recommendedLimitMb(context: Context): Int {
        val maxLimit = maxLimitMb(context)
        val scaled = (maxLimit * RECOMMENDED_SCALE).roundToInt()
        return max(DEFAULT_FALLBACK_LIMIT_MB, scaled).coerceAtMost(maxLimit)
    }

    fun autoScaleLimitMb(context: Context, requestedMb: Int): Int {
        val maxLimit = maxLimitMb(context)
        val recommended = recommendedLimitMb(context)
        return requestedMb
            .coerceAtLeast(MIN_LIMIT_MB)
            .coerceAtMost(recommended)
            .coerceAtMost(maxLimit)
    }

    fun maxLimitMb(context: Context): Int {
        val activityManager = context.getSystemService<ActivityManager>()
            ?: return DEFAULT_FALLBACK_LIMIT_MB
        return max(activityManager.memoryClass, activityManager.largeMemoryClass)
    }

    fun clampLimitMb(context: Context, requestedMb: Int): Int {
        val upperBound = max(MIN_LIMIT_MB, maxLimitMb(context))
        return requestedMb.coerceIn(MIN_LIMIT_MB, upperBound)
    }
}
