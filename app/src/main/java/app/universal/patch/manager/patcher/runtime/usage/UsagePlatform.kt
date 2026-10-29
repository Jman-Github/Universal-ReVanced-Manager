package app.universal.patch.manager.patcher.runtime.usage

import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants

/** Android implementation of the resource sampler's platform operations. */
internal object UsagePlatform {
    val coreCount get() = Os.sysconf(OsConstants._SC_NPROCESSORS_CONF).toInt()
    val clockTicks get() = Os.sysconf(OsConstants._SC_CLK_TCK)
    fun uptimeMillis() = SystemClock.uptimeMillis()
    fun elapsedMillis() = SystemClock.elapsedRealtime()
    fun lowerPriority() = Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
}
