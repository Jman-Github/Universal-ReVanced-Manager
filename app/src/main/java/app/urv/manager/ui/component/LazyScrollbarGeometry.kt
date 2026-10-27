package app.urv.manager.ui.component

internal data class LazyScrollbarSample(val index: Int, val key: Any, val size: Int)

/** Remember measured heights so a tall header is not treated as an ordinary card. */
internal class LazyScrollbarMeasurements {
    private val measured = mutableMapOf<Int, LazyScrollbarSample>()

    fun sizes(total: Int, visible: List<LazyScrollbarSample>, fallback: Int): List<Int> {
        if (visible.any { sample -> measured[sample.index]?.let { it.key != sample.key } == true }) {
            measured.clear()
        }
        measured.keys.removeAll { it >= total }
        visible.forEach { measured[it.index] = it }
        // The first item is often a large filter/header panel. Do not use its
        // height to estimate every card that has not been composed yet.
        val known = measured.values.filter { it.index > 0 }.map { it.size }.filter { it > 0 }.sorted()
        val estimate = known.getOrNull((known.size - 1).coerceAtLeast(0) / 2) ?: fallback.coerceAtLeast(1)
        return List(total) { measured[it]?.size?.coerceAtLeast(1) ?: estimate }
    }
}

internal data class LazyScrollbarGeometry(
    val sizes: List<Int>,
    val spacing: Int,
    val afterPadding: Int,
    val contentSize: Double,
    val viewportSize: Int,
    val offset: Double,
    val reverse: Boolean,
    val scrollable: Boolean,
) {
    val range get() = (contentSize - viewportSize).coerceAtLeast(0.0)
    val progress: Float get() {
        val fraction = if (range == 0.0) 0f else (offset / range).toFloat().coerceIn(0f, 1f)
        return if (reverse) 1f - fraction else fraction
    }

    fun thumbSize(track: Float, minimum: Float, fixedLength: Float? = null): Float =
        (fixedLength ?: if (contentSize <= 0) track else (track * viewportSize / contentSize).toFloat())
            .coerceIn(minimum.coerceAtMost(track), track)

    fun target(visualProgress: Float): Pair<Int, Int> {
        if (sizes.isEmpty()) return 0 to 0
        val fraction = (if (reverse) 1f - visualProgress else visualProgress).coerceIn(0f, 1f)
        if (fraction == 1f) return sizes.lastIndex to (sizes.last() + afterPadding)
        var remaining = range * fraction
        for (index in sizes.indices) {
            val stride = sizes[index] + if (index < sizes.lastIndex) spacing else 0
            if (remaining < stride || index == sizes.lastIndex) return index to remaining.toInt()
            remaining -= stride
        }
        return 0 to 0
    }
}

internal fun lazyScrollbarGeometry(
    sizes: List<Int>, firstIndex: Int, firstOffset: Int, spacing: Int,
    beforePadding: Int, afterPadding: Int, viewport: Int,
    canScrollBackward: Boolean, canScrollForward: Boolean, reverse: Boolean,
): LazyScrollbarGeometry {
    val content = sizes.sumOf(Int::toDouble) + spacing.toDouble() * (sizes.size - 1).coerceAtLeast(0) +
        beforePadding + afterPadding
    val range = (content - viewport).coerceAtLeast(0.0)
    val offset = when {
        !canScrollBackward -> 0.0
        !canScrollForward -> range
        else -> sizes.take(firstIndex.coerceIn(0, sizes.size)).sumOf(Int::toDouble) +
            firstIndex * spacing.toDouble() + firstOffset
    }.coerceIn(0.0, range)
    return LazyScrollbarGeometry(sizes, spacing, afterPadding, content, viewport, offset, reverse,
        canScrollBackward || canScrollForward)
}
