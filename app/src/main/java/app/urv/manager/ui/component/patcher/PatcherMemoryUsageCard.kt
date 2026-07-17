package app.urv.manager.ui.component.patcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.universal.revanced.manager.R
import app.urv.manager.patcher.worker.PatcherMemoryUsage
import kotlinx.coroutines.launch

@Composable
fun PatcherMemoryUsageCard(
    samples: List<PatcherMemoryUsage>,
    isActive: Boolean,
    @StringRes titleRes: Int = R.string.patcher_memory_usage
) {
    val latest = samples.last()
    val requestedMaxMb = latest.requestedMaxMb.coerceAtLeast(1L)
    val peakMb = samples.maxOf { sample -> sample.usedMb }
    val trendRes = memoryTrendRes(samples, requestedMaxMb)
    val statusRes = if (isActive) {
        R.string.patcher_memory_usage_live
    } else {
        R.string.patcher_memory_usage_final
    }
    val status = stringResource(statusRes)
    val trend = stringResource(trendRes)
    val accessibilityText = stringResource(
        R.string.patcher_memory_usage_accessibility,
        latest.usedMb,
        requestedMaxMb,
        peakMb,
        trend,
        status
    )
    val graphBars = buildMemoryGraphBars(samples, requestedMaxMb)
    val graphScrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    val sessionStartTime = samples.first().sampledAtElapsedRealtimeMs
    val latestSampleTime = latest.sampledAtElapsedRealtimeMs
    val isGraphDragged by graphScrollState.interactionSource.collectIsDraggedAsState()
    var followLatest by remember(sessionStartTime) { mutableStateOf(true) }
    var programmaticScrollCount by remember(sessionStartTime) { mutableIntStateOf(0) }
    val showJumpToLatest = !followLatest
    val normalColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.82f).toArgb()
    val warningColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.9f).toArgb()
    val dangerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.9f).toArgb()
    val trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f).toArgb()

    LaunchedEffect(graphScrollState, sessionStartTime) {
        var userScrollObserved = false
        snapshotFlow {
            Triple(
                isGraphDragged,
                graphScrollState.isScrollInProgress,
                graphScrollState.canScrollForward
            ) to (programmaticScrollCount > 0)
        }.collect { (scroll, isProgrammatic) ->
            val (isDragged, isScrolling, canScrollForward) = scroll
            val isUserScrolling = !isProgrammatic && (isDragged || isScrolling)
            if (isUserScrolling && canScrollForward) {
                userScrollObserved = true
                followLatest = false
            } else if (userScrollObserved && !isScrolling) {
                if (!canScrollForward) {
                    followLatest = true
                }
                userScrollObserved = false
            }
        }
    }
    LaunchedEffect(graphScrollState, sessionStartTime, latestSampleTime) {
        if (!followLatest) return@LaunchedEffect
        withFrameNanos { }
        if (followLatest && !isGraphDragged) {
            programmaticScrollCount++
            try {
                graphScrollState.scrollTo(graphScrollState.maxValue)
            } finally {
                programmaticScrollCount = (programmaticScrollCount - 1).coerceAtLeast(0)
            }
        }
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(titleRes),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isActive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            Text(
                text = stringResource(
                    R.string.patcher_memory_usage_requested_format,
                    latest.usedMb,
                    requestedMaxMb
                ),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val slotCount = maxOf(MEMORY_USAGE_VISIBLE_BAR_COUNT, graphBars.size)
                val graphWidth = maxWidth * (
                    slotCount.toFloat() / MEMORY_USAGE_VISIBLE_BAR_COUNT.toFloat()
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(graphScrollState)
                ) {
                    AndroidView(
                        modifier = Modifier
                            .width(graphWidth)
                            .height(64.dp)
                            .clearAndSetSemantics {
                                contentDescription = accessibilityText
                            },
                        factory = { context -> MemoryUsageGraphView(context) },
                        update = { view ->
                            view.normalColor = normalColor
                            view.warningColor = warningColor
                            view.dangerColor = dangerColor
                            view.trackColor = trackColor
                            view.bars = graphBars
                        }
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.patcher_memory_usage_history),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                AnimatedVisibility(
                    visible = showJumpToLatest,
                    enter = expandHorizontally(expandFrom = Alignment.End) +
                        slideInHorizontally(initialOffsetX = { fullWidth -> fullWidth }) +
                        fadeIn(),
                    exit = shrinkHorizontally(shrinkTowards = Alignment.End) +
                        slideOutHorizontally(targetOffsetX = { fullWidth -> fullWidth }) +
                        fadeOut()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = {
                                coroutineScope.launch {
                                    var reachedLatest = false
                                    programmaticScrollCount++
                                    try {
                                        withFrameNanos { }
                                        graphScrollState.scrollTo(graphScrollState.maxValue)
                                        reachedLatest = !graphScrollState.canScrollForward
                                    } finally {
                                        programmaticScrollCount =
                                            (programmaticScrollCount - 1).coerceAtLeast(0)
                                        followLatest = reachedLatest
                                    }
                                }
                            },
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text(text = stringResource(R.string.patcher_memory_usage_latest))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                }
                Text(
                    text = stringResource(R.string.patcher_memory_usage_peak_format, peakMb),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@StringRes
private fun memoryTrendRes(
    samples: List<PatcherMemoryUsage>,
    requestedMaxMb: Long
): Int {
    if (samples.size < TREND_SAMPLE_COUNT * 2) {
        return R.string.patcher_memory_usage_trend_steady
    }
    val recent = samples.takeLast(TREND_SAMPLE_COUNT)
    val previous = samples.dropLast(TREND_SAMPLE_COUNT).takeLast(TREND_SAMPLE_COUNT)
    val recentAverage = recent.map { it.usedMb }.average()
    val previousAverage = previous.map { it.usedMb }.average()
    val threshold = requestedMaxMb * TREND_CHANGE_FRACTION
    return when {
        recentAverage - previousAverage > threshold ->
            R.string.patcher_memory_usage_trend_rising
        previousAverage - recentAverage > threshold ->
            R.string.patcher_memory_usage_trend_falling
        else -> R.string.patcher_memory_usage_trend_steady
    }
}

private fun buildMemoryGraphBars(
    samples: List<PatcherMemoryUsage>,
    requestedMaxMb: Long
): List<MemoryGraphBar> {
    val bars = ArrayList<MemoryGraphBar>(samples.size)
    var rollingStartIndex = 0
    var rollingUsedMb = 0.0
    samples.forEachIndexed { index, sample ->
        rollingUsedMb += sample.usedMb.toDouble()
        val rollingStartTime = sample.sampledAtElapsedRealtimeMs - PRESSURE_ROLLING_WINDOW_MS
        while (
            rollingStartIndex < index &&
            samples[rollingStartIndex].sampledAtElapsedRealtimeMs < rollingStartTime
        ) {
            rollingUsedMb -= samples[rollingStartIndex].usedMb.toDouble()
            rollingStartIndex++
        }
        val rollingSampleCount = index - rollingStartIndex + 1
        bars += MemoryGraphBar(
            heightFraction = (
                sample.usedMb.toDouble() / requestedMaxMb.toDouble()
            ).toFloat().coerceIn(0f, 1f),
            pressureFraction = (
                (rollingUsedMb / rollingSampleCount.toDouble()) /
                    sample.maxMb.coerceAtLeast(1L).toDouble()
            ).toFloat().coerceAtLeast(0f)
        )
    }
    return bars
}

private data class MemoryGraphBar(
    val heightFraction: Float,
    val pressureFraction: Float
)

private class MemoryUsageGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var bars: List<MemoryGraphBar> = emptyList()
        set(value) {
            field = value
            invalidate()
        }
    var normalColor: Int = 0
    var warningColor: Int = 0
    var dangerColor: Int = 0
    var trackColor: Int = 0

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val density = resources.displayMetrics.density
        val count = maxOf(MEMORY_USAGE_VISIBLE_BAR_COUNT, bars.size)
        val gap = minOf(3f * density, width.toFloat() / (count * 3f))
        val barWidth = (
            (width - gap * (count - 1)) / count
        ).coerceAtLeast(0.5f)
        val graphHeight = height.toFloat()

        repeat(count) { index ->
            val left = index * (barWidth + gap)
            val right = minOf(width.toFloat(), left + barWidth)
            val radius = barWidth / 2f
            paint.color = trackColor
            canvas.drawRoundRect(left, 0f, right, graphHeight, radius, radius, paint)

            val barIndex = index - (count - bars.size)
            val bar = bars.getOrNull(barIndex) ?: return@repeat
            if (bar.heightFraction <= 0f) return@repeat
            val barHeight = graphHeight * bar.heightFraction
            paint.color = when {
                bar.pressureFraction >= DANGER_PRESSURE_FRACTION -> dangerColor
                bar.pressureFraction >= WARNING_PRESSURE_FRACTION -> warningColor
                else -> normalColor
            }
            canvas.drawRoundRect(
                left,
                graphHeight - barHeight,
                right,
                graphHeight,
                radius,
                radius,
                paint
            )
        }
    }
}

private const val MEMORY_USAGE_VISIBLE_BAR_COUNT = 80
private const val PRESSURE_ROLLING_WINDOW_MS = 2_000L
private const val TREND_SAMPLE_COUNT = 3
private const val TREND_CHANGE_FRACTION = 0.03
private const val WARNING_PRESSURE_FRACTION = 0.70f
private const val DANGER_PRESSURE_FRACTION = 0.85f
