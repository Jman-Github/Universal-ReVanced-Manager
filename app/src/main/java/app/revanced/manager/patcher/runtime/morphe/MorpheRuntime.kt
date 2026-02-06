package app.revanced.manager.patcher.runtime.morphe

import android.content.Context
import app.revanced.manager.data.platform.Filesystem
import app.revanced.manager.domain.manager.PreferencesManager
import app.revanced.manager.domain.repository.PatchBundleRepository
import app.revanced.manager.patcher.ProgressEvent
import app.revanced.manager.patcher.aapt.Aapt
import app.revanced.manager.patcher.aapt.AaptSelector
import app.revanced.manager.patcher.aapt.MorpheAapt
import app.revanced.manager.patcher.logger.Logger
import app.revanced.manager.patcher.patch.PatchBundleType
import app.revanced.manager.util.Options
import app.revanced.manager.util.PatchSelection
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
    protected val aaptPrimaryPath = MorpheAapt.binary(context)?.absolutePath
        ?: throw FileNotFoundException("Could not resolve Morphe aapt.")
    protected val aaptFallbackPath = Aapt.binary(context)?.absolutePath
    protected val frameworkPath: String =
        context.cacheDir.resolve("framework_morphe").also { it.mkdirs() }.absolutePath

    protected suspend fun bundles() = patchBundlesRepo.bundlesByType(PatchBundleType.MORPHE).first()

    protected fun resolveAaptPath(inputFile: File, logger: Logger): String =
        AaptSelector.select(aaptPrimaryPath, aaptFallbackPath, inputFile, logger)

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
