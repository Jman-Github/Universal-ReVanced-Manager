package app.universal.patch.manager.patcher.runtime.usage

// Code adapted from Morphe, see third-party/NOTICE for more information.
// https://github.com/MorpheApp/morphe-manager/pull/1202

/** Linux /proc exposes USER_HZ ticks; patching holds a wake lock during sampling. */
internal object UsagePlatform {
    val coreCount get() = Runtime.getRuntime().availableProcessors()
    val clockTicks get() = 100L
    fun uptimeMillis() = System.nanoTime() / 1_000_000L
    fun elapsedMillis() = uptimeMillis()
    fun lowerPriority() { Thread.currentThread().priority = Thread.MIN_PRIORITY }
}
