package app.urv.manager.ui.component.patcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.universal.revanced.manager.R
import app.urv.manager.patcher.worker.PatcherMemoryUsage

@Composable
fun PatcherMemoryUsageCard(
    samples: List<PatcherMemoryUsage>,
    @StringRes titleRes: Int = R.string.patcher_memory_usage
) {
    val latest = samples.lastOrNull() ?: PatcherMemoryUsage(usedMb = 0L, maxMb = 1L)
    val chartSamples = samples.takeLast(MEMORY_USAGE_SAMPLE_LIMIT)
    val displayMaxMb = latest.maxMb.coerceAtLeast(1L)
    val barColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.82f).toArgb()
    val trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f).toArgb()
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = stringResource(R.string.patcher_memory_usage_format, latest.usedMb, displayMaxMb),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End
                )
            }
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                factory = { context -> MemoryUsageGraphView(context) },
                update = { view ->
                    val scaleMb = displayMaxMb.coerceAtLeast(1L)
                    view.barColor = barColor
                    view.trackColor = trackColor
                    view.values = chartSamples.map { sample ->
                        sample.usedMb.toFloat() / scaleMb.toFloat()
                    }
                }
            )
        }
    }
}

private class MemoryUsageGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var values: List<Float> = emptyList()
        set(value) {
            field = value
            invalidate()
        }
    var barColor: Int = 0
        set(value) {
            field = value
            invalidate()
        }
    var trackColor: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val gap = 3f * resources.displayMetrics.density
        val count = MEMORY_USAGE_SAMPLE_LIMIT
        val barWidth = ((width - gap * (count - 1)) / count).coerceAtLeast(1f)
        repeat(count) { index ->
            val left = index * (barWidth + gap)
            val right = left + barWidth
            val radius = barWidth / 2f
            paint.color = trackColor
            canvas.drawRoundRect(left, 0f, right, height.toFloat(), radius, radius, paint)
            val valueIndex = index - (count - values.size)
            val fraction = values.getOrNull(valueIndex)?.coerceIn(0f, 1f) ?: return@repeat
            if (fraction <= 0f) return@repeat
            val barHeight = height * fraction
            paint.color = barColor
            canvas.drawRoundRect(left, height - barHeight, right, height.toFloat(), radius, radius, paint)
        }
    }
}

private const val MEMORY_USAGE_SAMPLE_LIMIT = 48
