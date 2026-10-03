package app.urv.manager.ui.component.patcher

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.floor
import app.universal.revanced.manager.R
import app.urv.manager.patcher.worker.PatcherMemoryUsage
import kotlinx.coroutines.launch

/** Natural section heights align history plots across all metrics. */
data class ResourceGraphLayout(
    val headerHeight: Dp,
    val footerHeight: Dp,
    val onHeaderMeasured: (Int) -> Unit,
    val onFooterMeasured: (Int) -> Unit,
    val onHeaderPlaced: (Int) -> Unit,
    val onFooterPlaced: (Int) -> Unit,
    val onHistoryReady: (Boolean) -> Unit
)

@Composable
internal fun ResourceGraphSection(
    minHeight: Dp?,
    onMeasured: ((Int) -> Unit)?,
    compact: Boolean,
    onPlaced: ((Int) -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = minHeight ?: 0.dp)
            .onGloballyPositioned { onPlaced?.invoke(it.size.height) }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().onSizeChanged { onMeasured?.invoke(it.height) },
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp),
            content = content
        )
    }
}

/** Keep startup measurements hidden until all three cards have their final aligned sections. */
private class ResourceGraphPreparation {
    val headerHeights = mutableStateMapOf<Int, Int>()
    val footerHeights = mutableStateMapOf<Int, Int>()
    val placedHeaders = mutableStateMapOf<Int, Int>()
    val placedFooters = mutableStateMapOf<Int, Int>()
    val historiesReady = mutableStateMapOf<Int, Boolean>()
    var revealed by mutableStateOf(false)

    fun ready(pageCount: Int): Boolean =
        sectionsAligned(pageCount) && (0 until pageCount).all { historiesReady[it] == true }

    fun sectionsAligned(pageCount: Int): Boolean {
        if (headerHeights.size != pageCount || footerHeights.size != pageCount) return false
        val header = headerHeights.values.maxOrNull() ?: 0
        val footer = footerHeights.values.maxOrNull() ?: 0
        return (0 until pageCount).all { page ->
            (placedHeaders[page] ?: -1) >= header &&
                (placedFooters[page] ?: -1) >= footer
        }
    }
}

/** History gestures stay inside the plot; card headers swipe between metrics. */
@Composable
fun PatcherResourceUsageCards(
    samples: List<PatcherMemoryUsage>,
    isActive: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
    merger: Boolean = false
) {
    if (samples.isEmpty()) return
    val labels = listOf(
        stringResource(R.string.patcher_memory_usage),
        stringResource(R.string.patcher_cpu_usage),
        stringResource(R.string.patcher_storage_io_usage)
    )
    val pagerState = rememberPagerState(pageCount = { labels.size })
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val sideBySide = compact || maxWidth >= 720.dp
        val sessionStart = samples.first().sampledAtElapsedRealtimeMs
        val hasCpu = samples.any { it.cpuCoreLoads.isNotEmpty() }
        val hasIo = samples.any { it.ioReadKbPerSec != null && it.ioWriteKbPerSec != null }
        val preparation = remember(maxWidth, density, sideBySide, sessionStart, hasCpu, hasIo, labels) {
            ResourceGraphPreparation()
        }
        val headerHeight = with(density) {
            (preparation.headerHeights.values.maxOrNull() ?: 0).toDp()
        }
        val footerHeight = with(density) {
            (preparation.footerHeights.values.maxOrNull() ?: 0).toDp()
        }
        // Rate samplers need two polls; unavailable metrics must not keep the UI hidden forever.
        val telemetryInitialized = !isActive || (hasCpu && hasIo) ||
            samples.asSequence().mapNotNull { it.resourceSampleTimeMs }.distinct().take(2).count() == 2
        val prepared = telemetryInitialized && preparation.ready(labels.size)
        LaunchedEffect(preparation, prepared) {
            if (!prepared || preparation.revealed) return@LaunchedEffect
            // Commit the aligned layout and initial history scroll before the first visible frame.
            withFrameNanos { }
            withFrameNanos { }
            if (preparation.ready(labels.size)) preparation.revealed = true
        }
        fun canShowGraphs() = preparation.revealed && preparation.sectionsAligned(labels.size)
        val visible = canShowGraphs()
        val presentation = Modifier.fillMaxWidth()
            .clipToBounds()
            .drawWithContent {
                // Placement callbacks can change readiness in this frame, after measurement.
                if (size.height > 0f && canShowGraphs()) drawContent()
            }
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minHeight = 0))
                val height = if (canShowGraphs()) placeable.height else 0
                layout(placeable.width, height) {
                    // Hidden content still needs placement to finish measuring and scrolling.
                    placeable.placeRelative(0, 0)
                }
            }
            .then(if (visible) Modifier else Modifier.clearAndSetSemantics { })
        fun graphLayout(page: Int) = ResourceGraphLayout(
            headerHeight, footerHeight,
            { preparation.headerHeights[page] = it },
            { preparation.footerHeights[page] = it },
            { preparation.placedHeaders[page] = it },
            { preparation.placedFooters[page] = it },
            { preparation.historiesReady[page] = it }
        )
        if (sideBySide) {
            Row(
                modifier = presentation,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                labels.indices.forEach { page ->
                    ResourcePage(
                        page = page,
                        samples = samples,
                        isActive = isActive,
                        compact = true,
                        merger = merger,
                        modifier = Modifier.weight(1f),
                        layout = graphLayout(page)
                    )
                }
            }
        } else {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = visible,
                modifier = presentation,
                pageSpacing = 16.dp,
                // Keeping these three pages measured also preserves each metric's history position.
                beyondViewportPageCount = labels.lastIndex,
                key = { it },
                verticalAlignment = Alignment.Top
            ) { page ->
                ResourcePage(
                    page, samples, isActive, compact = false, merger = merger,
                    layout = graphLayout(page),
                    pageControls = {
                        ResourcePageDots(labels, pagerState, enabled = visible) { selected ->
                            scope.launch { pagerState.animateScrollToPage(selected) }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ResourcePageDots(
    labels: List<String>,
    pagerState: PagerState,
    enabled: Boolean,
    onSelect: (Int) -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Box(Modifier.width(20.dp * labels.size).height(32.dp)) {
            Canvas(Modifier.matchParentSize()) {
                val spacing = 20.dp.toPx()
                val radius = 3.dp.toPx()
                fun center(page: Float): Float {
                    val x = spacing * (page + 0.5f)
                    return if (isRtl) size.width - x else x
                }
                labels.indices.forEach { page ->
                    drawCircle(inactive, 2.dp.toPx(), Offset(center(page.toFloat()), size.height / 2f))
                }
                val position = (pagerState.currentPage + pagerState.currentPageOffsetFraction)
                    .coerceIn(0f, labels.lastIndex.toFloat())
                val page = floor(position)
                val progress = position - page
                // The leading end stretches first, then the trailing end catches up with the swipe.
                val start = center(page + (progress * 2f - 1f).coerceIn(0f, 1f))
                val end = center(page + (progress * 2f).coerceIn(0f, 1f))
                drawRoundRect(
                    color = primary,
                    topLeft = Offset(minOf(start, end) - radius, size.height / 2f - radius),
                    size = Size(kotlin.math.abs(end - start) + radius * 2f, radius * 2f),
                    cornerRadius = CornerRadius(radius, radius)
                )
            }
            Row(Modifier.matchParentSize().selectableGroup()) {
                labels.forEachIndexed { page, label ->
                    Box(
                        Modifier.width(20.dp).height(32.dp)
                            .selectable(
                                selected = pagerState.currentPage == page,
                                enabled = enabled,
                                role = Role.Tab,
                                onClick = { onSelect(page) }
                            )
                            .semantics { contentDescription = label }
                    )
                }
            }
        }
    }
}

@Composable
private fun ResourcePage(
    page: Int,
    samples: List<PatcherMemoryUsage>,
    isActive: Boolean,
    compact: Boolean,
    merger: Boolean,
    modifier: Modifier = Modifier,
    layout: ResourceGraphLayout? = null,
    pageControls: (@Composable () -> Unit)? = null
) {
    when (page) {
        0 -> PatcherMemoryUsageCard(
            samples, isActive, modifier = modifier, compact = compact,
            layout = layout, pageControls = pageControls
        )
        1 -> CpuUsageCard(samples, isActive, compact, merger, modifier, layout, pageControls)
        2 -> IoUsageCard(samples, isActive, compact, merger, modifier, layout, pageControls)
    }
}
@Composable
private fun CpuUsageCard(
    samples: List<PatcherMemoryUsage>,
    isActive: Boolean,
    compact: Boolean,
    merger: Boolean,
    modifier: Modifier,
    layout: ResourceGraphLayout?,
    pageControls: (@Composable () -> Unit)?
) {
    val readings = samples.filter { it.cpuCoreLoads.isNotEmpty() }
        .distinctBy { it.resourceSampleTimeMs }
    val title = stringResource(R.string.patcher_cpu_usage)
    if (readings.isEmpty()) {
        UnavailableUsageCard(title, modifier, compact, layout, pageControls)
        return
    }
    val latest = readings.last()
    val currentAvailable = samples.last().cpuCoreLoads.isNotEmpty()
    val current = latest.cpuCoreLoads.average().toInt()
    val peak = readings.maxOf { it.cpuCoreLoads.average().toInt() }
    val scope = stringResource(
        if (latest.cpuSystemWide) R.string.patcher_cpu_system
        else if (merger) R.string.merger_cpu_process
        else R.string.patcher_cpu_process
    )
    val cores = pluralStringResource(
        R.plurals.patcher_cpu_core_count, latest.cpuCoreLoads.size, latest.cpuCoreLoads.size
    )
    PatcherHistoryUsageCard(
        samples = readings,
        isActive = isActive,
        title = title,
        headline = if (currentAvailable) stringResource(R.string.patcher_cpu_percent, current) else "—",
        peak = stringResource(R.string.patcher_cpu_peak, peak),
        accessibilityText = if (currentAvailable) {
            stringResource(R.string.patcher_cpu_accessibility, current, peak, scope)
        } else {
            stringResource(
                R.string.patcher_resource_unavailable_accessibility,
                title,
                stringResource(R.string.patcher_cpu_peak, peak)
            )
        },
        graphBars = readings.map {
            val load = it.cpuCoreLoads.average().toFloat() / 100f
            ResourceGraphBar(load, load)
        },
        modifier = modifier,
        compact = compact,
        detail = if (currentAvailable) "$scope · $cores"
            else stringResource(R.string.patcher_resource_unavailable),
        coreLoads = if (currentAvailable) latest.cpuCoreLoads else emptyList(),
        currentAvailable = currentAvailable,
        layout = layout,
        pageControls = pageControls
    )
}

@Composable
private fun IoUsageCard(
    samples: List<PatcherMemoryUsage>,
    isActive: Boolean,
    compact: Boolean,
    merger: Boolean,
    modifier: Modifier,
    layout: ResourceGraphLayout?,
    pageControls: (@Composable () -> Unit)?
) {
    val readings = samples.filter { it.ioReadKbPerSec != null && it.ioWriteKbPerSec != null }
        .distinctBy { it.resourceSampleTimeMs }
    val title = stringResource(R.string.patcher_storage_io_usage)
    if (readings.isEmpty()) {
        UnavailableUsageCard(title, modifier, compact, layout, pageControls)
        return
    }
    val latest = readings.last()
    val currentAvailable = samples.last().let {
        it.ioReadKbPerSec != null && it.ioWriteKbPerSec != null
    }
    val peak = readings.maxOf { it.ioTotalKbPerSec }
    val scale = peak.coerceAtLeast(1L).toFloat()
    val read = formatIoRate(latest.ioReadKbPerSec!!.toLong())
    val write = formatIoRate(latest.ioWriteKbPerSec!!.toLong())
    val scope = stringResource(
        if (latest.ioBlockAccounting) {
            if (merger) R.string.merger_io_storage else R.string.patcher_io_storage
        } else {
            if (merger) R.string.merger_io_cached else R.string.patcher_io_cached
        }
    )
    PatcherHistoryUsageCard(
        samples = readings,
        isActive = isActive,
        title = title,
        headline = if (currentAvailable) formatIoRate(latest.ioTotalKbPerSec) else "—",
        peak = stringResource(R.string.patcher_io_peak, formatIoRate(peak)),
        accessibilityText = if (currentAvailable) {
            stringResource(R.string.patcher_io_accessibility, read, write, formatIoRate(peak), scope)
        } else {
            stringResource(
                R.string.patcher_resource_unavailable_accessibility,
                title,
                stringResource(R.string.patcher_io_peak, formatIoRate(peak))
            )
        },
        graphBars = readings.map { ResourceGraphBar(it.ioTotalKbPerSec / scale, 0f) },
        modifier = modifier,
        compact = compact,
        detail = if (currentAvailable) {
            if (compact) scope else stringResource(R.string.patcher_io_rates, read, write) + "\n" + scope
        } else stringResource(R.string.patcher_resource_unavailable),
        scrollableDetailLines = if (compact && currentAvailable) {
            stringResource(R.string.patcher_io_rates, read, write).lines()
        } else emptyList(),
        currentAvailable = currentAvailable,
        layout = layout,
        pageControls = pageControls
    )
}

private val PatcherMemoryUsage.ioTotalKbPerSec: Long
    get() = (ioReadKbPerSec ?: 0).toLong() + (ioWriteKbPerSec ?: 0).toLong()

@Composable
private fun formatIoRate(kbPerSec: Long): String = if (kbPerSec >= 1024L) {
    stringResource(R.string.patcher_io_rate_mb, kbPerSec / 1024.0)
} else {
    stringResource(R.string.patcher_io_rate_kb, kbPerSec)
}

@Composable
private fun UnavailableUsageCard(
    title: String,
    modifier: Modifier,
    compact: Boolean,
    layout: ResourceGraphLayout?,
    pageControls: (@Composable () -> Unit)?
) {
    SideEffect { layout?.onHistoryReady?.invoke(true) }
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(if (compact) 8.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp)
        ) {
            ResourceGraphSection(
                layout?.headerHeight, layout?.onHeaderMeasured, compact, layout?.onHeaderPlaced
            ) {
                pageControls?.invoke()
                Text(
                    text = title,
                    style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(R.string.patcher_resource_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(64.dp))
            ResourceGraphSection(
                layout?.footerHeight, layout?.onFooterMeasured, compact, layout?.onFooterPlaced
            ) { }
        }
    }
}
