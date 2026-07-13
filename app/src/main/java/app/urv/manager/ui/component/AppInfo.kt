package app.urv.manager.ui.component

import android.content.pm.PackageInfo
import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import app.universal.revanced.manager.R

@Composable
fun AppInfo(
    appInfo: PackageInfo?,
    labelOverride: String? = null,
    iconOverride: Drawable? = null,
    placeholderLabel: String? = null,
    placeholderMetaLines: Int = 1,
    showExtraContentWhenLoading: Boolean = false,
    extraContent: @Composable () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (appInfo == null) {
            ShimmerBox(
                modifier = Modifier
                    .size(100.dp)
                    .padding(bottom = 5.dp),
                shape = CircleShape
            )
            ShimmerBox(
                modifier = Modifier
                    .padding(top = 16.dp)
                    .width(200.dp)
                    .height(22.dp)
            )
            val placeholderLineCount = if (showExtraContentWhenLoading) {
                placeholderMetaLines.coerceAtLeast(0)
            } else {
                placeholderMetaLines.coerceAtLeast(1)
            }
            repeat(placeholderLineCount) { index ->
                val lineWidth = when (index) {
                    0 -> 150.dp
                    1 -> 130.dp
                    else -> 110.dp
                }
                ShimmerBox(
                    modifier = Modifier
                        .padding(top = if (index == 0) 8.dp else 6.dp)
                        .width(lineWidth)
                        .height(14.dp)
                )
            }
            if (showExtraContentWhenLoading) {
                extraContent()
            }
        } else {
            AppIcon(
                appInfo,
                iconOverride = iconOverride,
                contentDescription = null,
                modifier = Modifier
                    .size(100.dp)
                    .padding(bottom = 5.dp)
            )

            AppLabel(
                appInfo,
                labelOverride = labelOverride,
                modifier = Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.titleLarge,
                centered = true,
                defaultText = placeholderLabel
            )

            extraContent()
        }
    }
}

@Composable
fun appVersionLabel(
    versionName: String,
    appInfo: PackageInfo?,
    displayVersion: String = versionName
): String {
    if (appInfo == null) return displayVersion

    val versionCode = PackageInfoCompat.getLongVersionCode(appInfo)
    val matchesPackage = appInfo.versionName == versionName || versionCode.toString() == versionName
    if (!matchesPackage) return displayVersion

    return stringResource(
        R.string.app_version_with_code,
        appInfo.versionName?.takeIf { it.isNotBlank() } ?: displayVersion,
        versionCode
    )
}

@Composable
fun suggestedVersionLabel(
    versionName: String,
    versionCodes: Set<Long> = emptySet(),
    displayVersion: String = versionName
): String {
    if (versionCodes.isEmpty()) return displayVersion

    return stringResource(
        R.string.app_version_with_code,
        displayVersion,
        versionCodes.sorted().joinToString(", ")
    )
}

@Composable
fun AppVersion(
    appInfo: PackageInfo?,
    versionName: String? = appInfo?.versionName,
    modifier: Modifier = Modifier,
    style: TextStyle? = null
) {
    val displayVersion = versionName?.takeIf { it.isNotBlank() } ?: return

    ExpandableText(
        text = appVersionLabel(
            versionName = displayVersion,
            appInfo = appInfo,
            displayVersion = displayVersion
        ),
        modifier = modifier,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = style ?: MaterialTheme.typography.bodySmall
    )
}
