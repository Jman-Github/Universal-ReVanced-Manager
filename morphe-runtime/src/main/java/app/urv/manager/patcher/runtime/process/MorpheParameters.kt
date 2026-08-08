package app.urv.manager.patcher.runtime.process

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.RawValue

@Parcelize
data class MorpheParameters(
    val cacheDir: String,
    val aaptPath: String,
    val frameworkDir: String,
    val bytecodeMode: String,
    val packageName: String,
    val inputFile: String,
    val outputFile: String,
    val configurations: List<MorphePatchConfiguration>,
    val stripNativeLibs: Boolean,
    val skipUnneededSplits: Boolean,
    val continueOnPatchError: Boolean,
    val patcherLogMode: String,
) : Parcelable

@Parcelize
data class MorphePatchConfiguration(
    val bundlePath: String,
    val patches: Set<String>,
    val options: @RawValue Map<String, Map<String, Any?>>
) : Parcelable
