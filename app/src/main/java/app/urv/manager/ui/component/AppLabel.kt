package app.urv.manager.ui.component

import android.content.pm.PackageInfo
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import app.universal.revanced.manager.R
import app.urv.manager.util.resolveAppDisplayLabel
import io.github.fornewid.placeholder.material3.placeholder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AppLabel(
    packageInfo: PackageInfo?,
    labelOverride: String? = null,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    centered: Boolean = false,
    defaultText: String? = stringResource(R.string.not_installed)
) {
    val context = LocalContext.current

    var label: String? by rememberSaveable { mutableStateOf(null) }

    LaunchedEffect(packageInfo, labelOverride) {
        if (!labelOverride.isNullOrBlank()) {
            label = labelOverride
            return@LaunchedEffect
        }
        label = null
        label = withContext(Dispatchers.IO) {
            resolveAppDisplayLabel(context, packageInfo, defaultText)
        }
    }

    Text(
        labelOverride ?: label ?: stringResource(R.string.loading),
        modifier = (if (centered) modifier.fillMaxWidth() else modifier)
            .placeholder(
                visible = labelOverride == null && label == null,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                shape = RoundedCornerShape(100)
            ),
        style = style,
        textAlign = if (centered) TextAlign.Center else TextAlign.Start
    )
}
