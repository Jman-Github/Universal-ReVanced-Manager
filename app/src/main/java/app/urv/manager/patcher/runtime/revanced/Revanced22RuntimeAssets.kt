package app.urv.manager.patcher.runtime.revanced

import android.content.Context
import java.io.File

object Revanced22RuntimeAssets {
    fun isAvailable(context: Context): Boolean = true
    fun ensureRuntimeClassPath(context: Context): File = File(context.applicationInfo.sourceDir)
}
