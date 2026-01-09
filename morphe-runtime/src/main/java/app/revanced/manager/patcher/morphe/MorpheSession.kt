package app.revanced.manager.patcher.morphe

import app.morphe.library.ApkUtils.applyTo
import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.patch.Patch
import app.morphe.patcher.patch.PatchResult
import app.revanced.manager.patcher.ProgressEvent
import app.revanced.manager.patcher.StepId
import app.revanced.manager.patcher.logger.Logger
import app.revanced.manager.patcher.morphe.MorpheSession.Companion.component1
import app.revanced.manager.patcher.morphe.MorpheSession.Companion.component2
import app.revanced.manager.patcher.runStep
import app.revanced.manager.patcher.toRemoteError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal typealias MorphePatchList = List<Patch<*>>

class MorpheSession(
    cacheDir: String,
    frameworkDir: String,
    aaptPath: String,
    private val logger: Logger,
    private val input: File,
    private val onEvent: (ProgressEvent) -> Unit,
) : Closeable {
    private val tempDir = File(cacheDir).resolve("patcher").also { it.mkdirs() }
    private val patcher = Patcher(
        PatcherConfig(
            apkFile = input,
            temporaryFilesPath = tempDir,
            frameworkFileDirectory = frameworkDir,
            aaptBinaryPath = aaptPath
        )
    )

    private suspend fun Patcher.applyPatchesVerbose(selectedPatches: MorphePatchList) {
        val patchIndexByName = selectedPatches.mapIndexedNotNull { index, patch ->
            patch.name?.takeIf { it.isNotBlank() }?.let { it to index }
        }.toMap()

        this().collect { (patch, exception) ->
            val patchName = patch.name?.takeIf { it.isNotBlank() } ?: return@collect
            val index = patchIndexByName[patchName] ?: return@collect

            if (exception != null) {
                onEvent(
                    ProgressEvent.Failed(
                        StepId.ExecutePatch(index),
                        exception.toRemoteError(),
                    )
                )
                logger.error("${patch.name} failed:")
                logger.error(exception.stackTraceToString())
                throw exception
            }

            onEvent(
                ProgressEvent.Completed(
                    StepId.ExecutePatch(index),
                )
            )

            logger.info("${patch.name} succeeded")
        }
    }

    suspend fun run(output: File, selectedPatches: MorphePatchList) {
        runStep(StepId.ExecutePatches, onEvent) {
            java.util.logging.Logger.getLogger("").apply {
                handlers.forEach {
                    it.close()
                    removeHandler(it)
                }

                addHandler(logger.handler)
            }

            with(patcher) {
                logger.info("Merging integrations")
                this += selectedPatches.toSet()

                logger.info("Applying patches...")
                applyPatchesVerbose(selectedPatches.sortedBy { it.name })
            }
        }

        runStep(StepId.WriteAPK, onEvent) {
            logger.info("Writing patched files...")
            val result = patcher.get()

            val patched = tempDir.resolve("result.apk")
            withContext(Dispatchers.IO) {
                fastCopy(input, patched)
            }
            result.applyTo(patched)

            logger.info("Patched apk saved to $patched")

            withContext(Dispatchers.IO) {
                try {
                    Files.move(
                        patched.toPath(),
                        output.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                    )
                } catch (_: Exception) {
                    Files.move(
                        patched.toPath(),
                        output.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                }
            }
        }
    }

    private fun fastCopy(source: File, target: File) {
        FileInputStream(source).channel.use { input ->
            FileOutputStream(target).channel.use { output ->
                var position = 0L
                val size = input.size()
                while (position < size) {
                    position += input.transferTo(position, size - position, output)
                }
            }
        }
    }

    override fun close() {
        tempDir.deleteRecursively()
        patcher.close()
    }

    companion object {
        operator fun PatchResult.component1() = patch
        operator fun PatchResult.component2() = exception
    }
}
