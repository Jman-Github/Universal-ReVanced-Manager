package app.revanced.manager.patcher.runtime.process

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.RawValue

@Parcelize
data class AmpleParameters(
    val cacheDir: String,
    val aaptPath: String,
    val frameworkDir: String,
    val apkEditorJarPath: String?,
    val apkEditorMergeJarPath: String?,
    val packageName: String,
    val inputFile: String,
    val outputFile: String,
    val configurations: List<AmplePatchConfiguration>,
    val stripNativeLibs: Boolean,
    val skipUnneededSplits: Boolean,
    val propOverridePath: String?,
    val mergeMemoryLimitMb: Int?,
    val appProcessPath: String?,
) : Parcelable

@Parcelize
data class AmplePatchConfiguration(
    val bundlePath: String,
    val patches: Set<String>,
    val options: @RawValue Map<String, Map<String, Any?>>
) : Parcelable
