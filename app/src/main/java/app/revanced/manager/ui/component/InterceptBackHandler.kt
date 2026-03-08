package app.revanced.manager.ui.component

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect

@Composable
fun InterceptBackHandler(
    enabled: Boolean = true,
    onBack: () -> Unit
) {
    val currentOnBack by rememberUpdatedState(onBack)

    PredictiveBackHandler(enabled = enabled) { progress ->
        try {
            progress.collect()
        } catch (_: CancellationException) {
            return@PredictiveBackHandler
        }

        currentOnBack()
    }
}
