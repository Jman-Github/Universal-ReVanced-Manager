package app.urv.manager.domain.batch

import app.urv.manager.data.platform.Filesystem
import app.urv.manager.domain.repository.PatchOptionInputManager
import app.urv.manager.ui.model.SelectedApp
import app.urv.manager.util.Options
import app.urv.manager.util.PatchSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

data class ManualBatchPatchEntry(
    val input: SelectedApp,
    val selection: PatchSelection,
    val options: Options
)

class ManualBatchPatchQueue(
    private val filesystem: Filesystem,
    private val patchOptionInputManager: PatchOptionInputManager
) {
    private val lock = Any()
    private val mutableEntries = MutableStateFlow<List<ManualBatchPatchEntry>>(emptyList())
    val entries = mutableEntries.asStateFlow()
    private val ownedSourcePaths = mutableSetOf<String>()
    private val optionOwnership = patchOptionInputManager.pendingInputOwnership()
    private var queueGeneration = 0L

    suspend fun upsert(
        input: SelectedApp,
        selection: PatchSelection,
        options: Options
    ) {
        val generationAtStart = synchronized(lock) { queueGeneration }
        val (preparedInput, ownedPath) = prepareInput(input)
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                if (generationAtStart != queueGeneration) {
                    ownedPath?.let { path -> runCatching { File(path).delete() } }
                    return@synchronized
                }
                ownedPath?.let(ownedSourcePaths::add)
                val current = mutableEntries.value
                val previous = current.firstOrNull {
                    it.input.packageName == preparedInput.packageName
                }
                val replacement = ManualBatchPatchEntry(
                    input = preparedInput,
                    selection = selection.filterValues { it.isNotEmpty() },
                    options = options
                )
                mutableEntries.value = if (previous == null) {
                    current + replacement
                } else {
                    current.map { entry ->
                        if (entry.input.packageName == preparedInput.packageName) replacement
                        else entry
                    }
                }
                deleteOwnedInput(previous?.input, except = preparedInput)
                reconcileOptions()
            }
        }
    }

    fun updateConfiguration(
        packageName: String,
        selection: PatchSelection,
        options: Options
    ) {
        synchronized(lock) {
            mutableEntries.value = mutableEntries.value.map { entry ->
                if (entry.input.packageName == packageName) {
                    entry.copy(
                        selection = selection.filterValues { it.isNotEmpty() },
                        options = options
                    )
                } else {
                    entry
                }
            }
            reconcileOptions()
        }
    }

    fun remove(packageName: String) {
        synchronized(lock) {
            val previous = mutableEntries.value.firstOrNull {
                it.input.packageName == packageName
            }
            mutableEntries.value = mutableEntries.value.filterNot {
                it.input.packageName == packageName
            }
            deleteOwnedInput(previous?.input)
            reconcileOptions()
        }
    }

    fun clear() {
        synchronized(lock) {
            queueGeneration++
            mutableEntries.value = emptyList()
            ownedSourcePaths.toList().forEach { path ->
                runCatching { File(path).delete() }
            }
            ownedSourcePaths.clear()
            optionOwnership.reconcile(emptySet())
        }
    }

    fun snapshot(): List<ManualBatchPatchEntry> = synchronized(lock) {
        mutableEntries.value.toList()
    }

    fun reorder(packageNames: List<String>) {
        synchronized(lock) {
            val order = packageNames.distinct().withIndex().associate { it.value to it.index }
            mutableEntries.value = mutableEntries.value.withIndex()
                .sortedWith(
                    compareBy<IndexedValue<ManualBatchPatchEntry>> {
                        order[it.value.input.packageName] ?: Int.MAX_VALUE
                    }.thenBy { it.index }
                )
                .map { it.value }
        }
    }

    private suspend fun prepareInput(
        input: SelectedApp
    ): Pair<SelectedApp, String?> = withContext(Dispatchers.IO) {
        prepareManualBatchInput(
            input = input,
            targetDirectory = filesystem.uiTempDir
        )
    }

    private fun reconcileOptions() {
        val referenced = mutableEntries.value
            .flatMapTo(mutableSetOf()) { entry ->
                patchOptionInputManager.pendingInputsIn(entry.options)
            }
        optionOwnership.reconcile(referenced)
    }

    private fun deleteOwnedInput(
        input: SelectedApp?,
        except: SelectedApp? = null
    ) {
        val file = (input as? SelectedApp.Local)?.file ?: return
        val path = runCatching(file::getCanonicalPath).getOrElse { file.absolutePath }
        val exceptPath = (except as? SelectedApp.Local)?.file?.let { exceptFile ->
            runCatching(exceptFile::getCanonicalPath).getOrElse { exceptFile.absolutePath }
        }
        if (path == exceptPath || path !in ownedSourcePaths) return
        runCatching { file.delete() }
        ownedSourcePaths.remove(path)
    }
}


internal fun prepareManualBatchInput(
    input: SelectedApp,
    targetDirectory: File,
    uniqueSuffix: Long = System.nanoTime()
): Pair<SelectedApp, String?> {
    if (input !is SelectedApp.Local || !input.temporary || !input.file.isFile) {
        return input to null
    }
    val extension = input.file.extension.ifBlank { "apk" }
    val safePackage = input.packageName.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val target = targetDirectory.resolve(
        "manual_batch_${safePackage}_$uniqueSuffix.$extension"
    )
    target.parentFile?.mkdirs()
    var copied = false
    try {
        input.file.copyTo(target, overwrite = true)
        check(target.isFile && target.length() == input.file.length()) {
            "Failed to verify the manual batch APK copy"
        }
        copied = true
    } finally {
        if (!copied) target.delete()
    }
    val ownedPath = runCatching(target::getCanonicalPath).getOrElse { target.absolutePath }
    return input.copy(file = target, temporary = false) to ownedPath
}
