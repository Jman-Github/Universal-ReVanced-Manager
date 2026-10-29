package app.universal.patch.manager.patcher.runtime.morphe

import app.universal.patch.manager.util.managerStorageContext

import android.content.Context
import app.universal.patch.manager.data.platform.Filesystem
import app.universal.patch.manager.domain.manager.PreferencesManager
import app.universal.patch.manager.domain.repository.PatchBundleRepository
import app.universal.patch.manager.patcher.ProgressEvent
import app.universal.patch.manager.patcher.aapt.Aapt
import app.universal.patch.manager.patcher.logger.Logger
import app.universal.patch.manager.patcher.patch.PatchBundleType
import app.universal.patch.manager.util.Options
import app.universal.patch.manager.util.PatchSelection
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
        context.managerStorageContext.cacheDir.resolve("framework_morphe").also { it.mkdirs() }.absolutePath

    protected suspend fun bundles() = patchBundlesRepo.bundlesByType(PatchBundleType.MORPHE).first()

    protected fun logAaptChoice(logger: Logger) {
        logger.info("AAPT2: N/A")
    }

    protected fun resolveAaptPath(inputFile: File, logger: Logger): String {
        logAaptChoice(logger)
        return aaptPath
    }

    abstract suspend fun execute(
        inputFile: String,
        outputFile: String,
        packageName: String,
        selectedPatches: PatchSelection,
        options: Options,
        logger: Logger,
        onEvent: (ProgressEvent) -> Unit,
        onMemoryUsage: (usedMb: Long, maxMb: Long) -> Unit,
        stripNativeLibs: Boolean,
        skipUnneededSplits: Boolean,
    )

    open fun cancel() = Unit
}
