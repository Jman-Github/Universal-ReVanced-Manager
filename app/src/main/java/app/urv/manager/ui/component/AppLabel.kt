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
            val packageName = packageInfo?.packageName
            val launcherLabel = packageName
                ?.let { loadInstalledLauncherLabel(context, it) }
                ?.let { cleanWeirdLabel(it, packageName) }
                ?.takeIf { it.isNotBlank() && it != packageName }
            if (launcherLabel != null) return@withContext launcherLabel

            val installedLabel = packageName
                ?.let { loadInstalledLabel(context, it) }
                ?.let { cleanWeirdLabel(it, packageName) }
                ?.takeIf { it.isNotBlank() && it != packageName }
            if (installedLabel != null) return@withContext installedLabel

            val localLabel = runCatching {
                packageInfo?.applicationInfo?.loadLabel(context.packageManager)?.toString()
            }.getOrNull()
            val cleanedLocal = localLabel?.let { raw ->
                val cleaned = cleanWeirdLabel(raw, packageName)
                cleaned.takeIf { it.isNotBlank() && cleaned != packageName }
            }
            if (!cleanedLocal.isNullOrBlank()) return@withContext cleanedLocal

            packageInfo?.applicationInfo?.nonLocalizedLabel?.toString()
                ?.takeIf { it.isNotBlank() }
                ?: packageName
                ?: defaultText
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

private fun cleanWeirdLabel(raw: String, packageName: String?): String {
    val trimmed = raw.trim()
    if (shouldFallbackUnderscoreLabel(trimmed, packageName)) {
        packageName?.let(::fallbackLabelFromPackageName)?.let { return it }
    }
    val pkg = packageName.orEmpty()
    if (pkg.isNotEmpty() && (trimmed.startsWith(pkg) || trimmed.contains(pkg))) {
        val candidate = trimmed.substringAfterLast('.')
        val withoutSuffix = candidate.removeSuffix("Application")
        return withoutSuffix.ifBlank { candidate }.ifBlank { trimmed }
    }
    if (trimmed.endsWith("Application")) {
        val withoutSuffix = trimmed.removeSuffix("Application")
        return withoutSuffix.substringAfterLast('.').ifBlank { withoutSuffix }
    }
    return trimmed
}

private fun shouldFallbackUnderscoreLabel(label: String, packageName: String?): Boolean {
    if ('_' !in label) return false
    val normalizedPackageName = packageName.orEmpty()
    if (normalizedPackageName.isNotBlank() && label.contains(normalizedPackageName, ignoreCase = true)) {
        return true
    }
    if (!looksLikePackageLabel(label)) return false
    return true
}

private fun looksLikePackageLabel(label: String): Boolean {
    val segments = label.split('.')
    if (segments.size < 3) return false
    return segments.all { segment ->
        segment.isNotBlank() && segment.all { it.isLetterOrDigit() || it == '_' }
    }
}

private fun fallbackLabelFromPackageName(packageName: String): String {
    val tail = packageName.substringAfterLast('.')
        .replace('_', ' ')
        .replace('-', ' ')
        .trim()
    if (tail.isBlank()) return packageName
    return tail.split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .joinToString(" ") { segment ->
            segment.replaceFirstChar { ch ->
                if (ch.isLowerCase()) ch.titlecase() else ch.toString()
            }
        }
}

@Suppress("DEPRECATION")
private fun loadInstalledLabel(context: android.content.Context, packageName: String): String? =
    runCatching {
        val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
        appInfo.loadLabel(context.packageManager)?.toString()
    }.getOrNull()?.takeIf { it.isNotBlank() }

private fun loadInstalledLauncherLabel(
    context: android.content.Context,
    packageName: String
): String? = runCatching {
    val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        ?: return@runCatching null
    launchIntent.resolveActivityInfo(context.packageManager, 0)
        ?.loadLabel(context.packageManager)
        ?.toString()
}.getOrNull()?.takeIf { it.isNotBlank() }
