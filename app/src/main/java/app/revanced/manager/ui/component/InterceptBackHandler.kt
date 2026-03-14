package app.revanced.manager.ui.component

import android.os.Build
import androidx.activity.compose.BackHandler
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

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        PredictiveBackHandler(enabled = enabled) { progress ->
            try {
                progress.collect { }
                currentOnBack()
            } catch (_: CancellationException) {
            }
        }
    } else {
        BackHandler(enabled = enabled) {
            currentOnBack()
        }
    }
}
