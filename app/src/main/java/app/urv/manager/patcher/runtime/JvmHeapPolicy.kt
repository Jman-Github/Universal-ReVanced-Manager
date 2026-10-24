package app.urv.manager.patcher.runtime

// Code adapted from Morphe, see third-party/NOTICE for more information.
// https://github.com/MorpheApp/morphe-manager/pull/1223
internal object JvmHeapPolicy {
    /** Leave address space for the 64 MB code cache and metaspace outside the contiguous heap. */
    fun fit32Bit(ramBudgetMb: Int, reservableMb: Int): Int =
        minOf(ramBudgetMb, 1024, (reservableMb - 128).coerceAtLeast(0))
}
