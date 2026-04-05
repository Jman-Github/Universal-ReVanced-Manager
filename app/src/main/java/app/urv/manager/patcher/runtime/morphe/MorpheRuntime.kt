package app.urv.manager.patcher.runtime.morphe

import android.content.Context
import app.urv.manager.data.platform.Filesystem
import app.urv.manager.domain.manager.PreferencesManager
import app.urv.manager.domain.repository.PatchBundleRepository
import app.urv.manager.patcher.ProgressEvent
import app.urv.manager.patcher.aapt.Aapt
import app.urv.manager.patcher.logger.Logger
import app.urv.manager.patcher.patch.PatchBundleType
import app.urv.manager.util.Options
import app.urv.manager.util.PatchSelection
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.FileNotFoundException

sealed class MorpheRuntime(context: Context) : KoinComponent {
    private val fs: Filesystem by inject()
    private val patchBundlesRepo: PatchBundleRepository by inject()
    protected val prefs: PreferencesManager by inject()

    protected val cacheDir: String = fs.tempDir.absolutePath
    protected val aaptPath = Aapt.binary(context)?.absolutePath
        ?: throw FileNotFoundException("Could not resolve Morphe aapt.")
    protected val frameworkPath: String =
        context.cacheDir.resolve("framework_morphe").also { it.mkdirs() }.absolutePath

    protected suspend fun bundles() = patchBundlesRepo.bundlesByType(PatchBundleType.MORPHE).first()

    protected fun resolveAaptPath(inputFile: File, logger: Logger): String = aaptPath

    abstract suspend fun execute(
        inputFile: String,
        outputFile: String,
        packageName: String,
        selectedPatches: PatchSelection,
        options: Options,
        logger: Logger,
        onEvent: (ProgressEvent) -> Unit,
        stripNativeLibs: Boolean,
        skipUnneededSplits: Boolean,
    )

    open fun cancel() = Unit
}
