package app.urv.manager.patcher.runtime.reseam

import android.content.Context
import android.os.Build
import android.os.Process
import app.urv.manager.network.runtime.PatcherRuntimeKind
import app.urv.manager.patcher.runtime.PatcherRuntimePluginRegistry
import app.urv.manager.util.managerStorageContext
import java.io.File
import java.io.IOException

object ReseamRuntimeAssets {
    fun isAvailable(context: Context): Boolean =
        PatcherRuntimePluginRegistry.runtimeFor(PatcherRuntimeKind.RESEAM) != null

    fun ensureRuntimeClassPath(context: Context): File =
        PatcherRuntimePluginRegistry.runtimeFor(PatcherRuntimeKind.RESEAM)?.apkFile
            ?: throw IOException("Reseam runtime plugin is not installed or trusted.")

    internal fun prepareRuntime(context: Context, apk: File): ReseamRuntimeFiles {
        val abis = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
        return prepareReseamRuntimeFiles(
            apk, File(context.managerStorageContext.codeCacheDir, "reseam-native"), abis.toList()
        )
    }

    /** Disposable workers use the same immutable snapshot as metadata inspection. */
    fun ensureNativeLibraryDir(context: Context, apk: File): File =
        prepareRuntime(context, apk).nativeLibraryDirectory
}
