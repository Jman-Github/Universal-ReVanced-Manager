package app.universal.patch.manager.patcher.runtime.revanced

import android.content.Context
import java.io.File

object Revanced22RuntimeAssets {
    fun isAvailable(context: Context): Boolean =
        app.universal.patch.manager.patcher.runtime.PatcherRuntimePluginRegistry.runtimeFor(
            app.universal.patch.manager.network.runtime.PatcherRuntimeKind.REVANCED_V22) != null
    fun ensureRuntimeClassPath(context: Context): File =
        app.universal.patch.manager.patcher.runtime.PatcherRuntimePluginRegistry.runtimeFor(
            app.universal.patch.manager.network.runtime.PatcherRuntimeKind.REVANCED_V22)?.apkFile
            ?: throw java.io.IOException("ReVanced v22 runtime plugin is not installed or trusted.")
}
