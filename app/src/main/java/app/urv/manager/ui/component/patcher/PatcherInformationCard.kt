package app.urv.manager.ui.component.patcher

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import app.universal.revanced.manager.BuildConfig
import app.universal.revanced.manager.R
import app.urv.manager.patcher.PatcherSessionInfo
import app.urv.manager.util.toast

data class PatcherInformation(
    val appVersion: String?,
    val appVersionCode: Long?,
    val patchCount: Int,
    val patchBundles: List<String>,
    val fallbackApkSizeBytes: Long? = null,
    val fallbackSplitApk: Boolean? = null,
    val fallbackPatcherEngine: String? = null,
    val session: PatcherSessionInfo = PatcherSessionInfo()
)

@Composable
fun PatcherInformationCard(
    information: PatcherInformation,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val deviceInformation = remember(
        context,
        expanded,
        information.session.apkSizeBytes,
        information.session.runtimeProcess
    ) {
        readDeviceInformation(context)
    }
    val apkSizeBytes = information.session.apkSizeBytes ?: information.fallbackApkSizeBytes
    val splitApk = information.session.splitApk ?: information.fallbackSplitApk
    val patchCount = information.session.patchCount ?: information.patchCount
    val patcherEngine = information.fallbackPatcherEngine
    val expandDescription = stringResource(
        if (expanded) R.string.patcher_information_collapse
        else R.string.patcher_information_expand
    )

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onExpandedChange(!expanded) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.patcher_information),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Icon(
                    imageVector = if (expanded) {
                        Icons.Outlined.ExpandLess
                    } else {
                        Icons.Outlined.ExpandMore
                    },
                    contentDescription = expandDescription,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        InformationRow(
                            first = InformationItem(
                                R.string.patcher_information_app_version,
                                appVersionDisplayValue(
                                    information.appVersion,
                                    information.appVersionCode
                                )
                            ),
                            second = InformationItem(
                                R.string.patcher_information_apk_size,
                                apkSizeBytes?.let { Formatter.formatFileSize(context, it) }
                                    .orPlaceholder()
                            )
                        )
                        InformationRow(
                            first = InformationItem(
                                R.string.patcher_information_patches,
                                patchCount.toString()
                            ),
                            second = InformationItem(
                                R.string.patcher_information_split_apk,
                                splitApk?.let {
                                    stringResource(
                                        if (it) R.string.patcher_information_yes
                                        else R.string.patcher_information_no
                                    )
                                }.orPlaceholder()
                            )
                        )
                        InformationItemContent(
                            item = InformationItem(
                                R.string.patcher_information_patch_bundles,
                                information.patchBundles
                                    .takeIf { it.isNotEmpty() }
                                    ?.joinToString(separator = "\n")
                                    .orPlaceholder()
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        InformationRow(
                            first = InformationItem(
                                R.string.patcher_information_manager,
                                BuildConfig.VERSION_NAME
                            ),
                            second = InformationItem(
                                R.string.patcher_information_patcher,
                                patcherEngine.orPlaceholder()
                            )
                        )
                        InformationRow(
                            first = InformationItem(
                                R.string.patcher_information_runtime,
                                runtimeDisplayValue(information.session)
                            ),
                            second = InformationItem(
                                R.string.patcher_information_native_libs,
                                information.session.nativeLibsStripped?.let {
                                    stringResource(
                                        if (it) R.string.patcher_information_native_libs_stripped
                                        else R.string.patcher_information_native_libs_kept
                                    )
                                }.orPlaceholder()
                            )
                        )
                        InformationRow(
                            first = InformationItem(
                                R.string.patcher_information_android,
                                stringResource(
                                    R.string.patcher_information_android_format,
                                    Build.VERSION.RELEASE,
                                    Build.VERSION.SDK_INT
                                )
                            ),
                            second = InformationItem(
                                R.string.patcher_information_device,
                                deviceInformation.deviceName
                            )
                        )
                        InformationRow(
                            first = InformationItem(
                                R.string.patcher_information_ram_free,
                                storageDisplayValue(
                                    context,
                                    deviceInformation.availableRamBytes,
                                    deviceInformation.totalRamBytes
                                )
                            ),
                            second = InformationItem(
                                R.string.patcher_information_storage_free,
                                storageDisplayValue(
                                    context,
                                    deviceInformation.availableStorageBytes,
                                    deviceInformation.totalStorageBytes
                                )
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun appVersionDisplayValue(version: String?, versionCode: Long?): String {
    val normalizedVersion = version?.takeIf(String::isNotBlank)
    return when {
        normalizedVersion != null && versionCode != null -> stringResource(
            R.string.patcher_information_app_version_format,
            normalizedVersion,
            versionCode
        )
        normalizedVersion != null -> normalizedVersion
        versionCode != null -> versionCode.toString()
        else -> INFORMATION_PLACEHOLDER
    }
}

@Composable
private fun runtimeDisplayValue(session: PatcherSessionInfo): String = when {
    session.runtimeProcess == true && session.memoryLimitMb != null -> stringResource(
        R.string.patcher_information_runtime_process_format,
        session.memoryLimitMb
    )
    session.runtimeProcess == true -> stringResource(R.string.patcher_information_runtime_process)
    session.runtimeProcess == false -> stringResource(R.string.patcher_information_runtime_in_process)
    session.memoryLimitMb != null -> stringResource(
        R.string.patcher_information_runtime_limit_format,
        session.memoryLimitMb
    )
    else -> INFORMATION_PLACEHOLDER
}

@Composable
private fun storageDisplayValue(context: Context, available: Long, total: Long): String =
    stringResource(
        R.string.patcher_information_storage_format,
        Formatter.formatFileSize(context, available),
        Formatter.formatFileSize(context, total)
    )

@Composable
private fun InformationRow(first: InformationItem, second: InformationItem) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        InformationItemContent(first, Modifier.weight(1f))
        InformationItemContent(second, Modifier.weight(1f))
    }
}

@Composable
private fun InformationItemContent(
    item: InformationItem,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService<ClipboardManager>() }
    val label = stringResource(item.labelRes)
    val scrollState = rememberScrollState()
    val copyEnabled = item.value != INFORMATION_PLACEHOLDER && clipboard != null

    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(enabled = copyEnabled) {
                clipboard?.setPrimaryClip(ClipData.newPlainText(label, item.value))
                context.toast(context.getString(R.string.toast_copied_to_clipboard))
            }
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = item.value,
            modifier = Modifier.horizontalScroll(scrollState),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            softWrap = false
        )
    }
}

private data class InformationItem(val labelRes: Int, val value: String)

private data class DeviceInformation(
    val deviceName: String,
    val availableRamBytes: Long,
    val totalRamBytes: Long,
    val availableStorageBytes: Long,
    val totalStorageBytes: Long
)

private fun readDeviceInformation(context: Context): DeviceInformation {
    val memoryInfo = ActivityManager.MemoryInfo()
    context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memoryInfo)
    val storage = StatFs(context.filesDir.absolutePath)
    val manufacturer = Build.MANUFACTURER.trim()
    val model = Build.MODEL.trim()
    val deviceName = when {
        manufacturer.isBlank() -> model
        model.isBlank() || model.startsWith(manufacturer, ignoreCase = true) -> model
        else -> "${manufacturer.replaceFirstChar { it.uppercase() }} $model"
    }.ifBlank { Build.DEVICE }
    return DeviceInformation(
        deviceName = deviceName,
        availableRamBytes = memoryInfo.availMem,
        totalRamBytes = memoryInfo.totalMem,
        availableStorageBytes = storage.availableBytes,
        totalStorageBytes = storage.totalBytes
    )
}

private fun String?.orPlaceholder(): String =
    this?.takeIf(String::isNotBlank) ?: INFORMATION_PLACEHOLDER

private const val INFORMATION_PLACEHOLDER = "—"
