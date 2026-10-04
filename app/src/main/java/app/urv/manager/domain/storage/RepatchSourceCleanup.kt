package app.urv.manager.domain.storage

import android.util.Log
import app.urv.manager.data.platform.Filesystem
import app.urv.manager.domain.batch.BatchResultSnapshot
import app.urv.manager.domain.manager.PreferencesManager
import app.urv.manager.domain.repository.InstalledAppRepository
import app.urv.manager.domain.worker.WorkerRepository
import app.urv.manager.patcher.worker.PatcherWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class RepatchSourceCleanup(
    private val filesystem: Filesystem,
    private val installedAppRepository: InstalledAppRepository,
    private val prefs: PreferencesManager,
    private val workerRepository: WorkerRepository,
    private val json: Json
) {
    private val cleanupMutex = Mutex()

    suspend fun observe() {
        combine(
            installedAppRepository.getAll(),
            prefs.lastBatchPatchResult.flow,
            prefs.lastAutoPatchResult.flow,
            workerRepository.workManager.getWorkInfosForUniqueWorkFlow(
                PatcherWorker.UNIQUE_WORK_NAME
            ),
            CacheCleanupGuard.idleGeneration
        ) { _, _, _, _, _ -> Unit }
            .conflate()
            .collect { pruneUnusedSources() }
    }

    suspend fun pruneUnusedSources(): Unit = withContext(Dispatchers.IO) {
        cleanupMutex.withLock {
            try {
                if (isPatcherActive()) return@withLock
                val workInfos = workerRepository.workManager
                    .getWorkInfosForUniqueWork(PatcherWorker.UNIQUE_WORK_NAME).get()
                if (workInfos.any { !it.state.isFinished }) return@withLock

                // Decode strictly: unreadable pending results cannot prove that a source is unused.
                val retainedPaths = buildSet {
                    listOf(
                        prefs.lastBatchPatchResult.get(),
                        prefs.lastAutoPatchResult.get()
                    ).filter(String::isNotBlank).forEach { serialized ->
                        json.decodeFromString<BatchResultSnapshot>(serialized)
                            .items.mapNotNullTo(this) { it.repatchSourcePath }
                    }
                    workInfos.mapNotNullTo(this) {
                        it.outputData.getString(PatcherWorker.REPATCH_SOURCE_PATH_KEY)
                    }
                }
                if (isPatcherActive()) return@withLock
                installedAppRepository.pruneRepatchInputs(retainedPaths)
                filesystem.pruneRepatchInputStagingFiles(retainedPaths)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("RepatchSourceCleanup", "Unable to prune unused Repatch sources", error)
            }
        }
    }

    private fun isPatcherActive(): Boolean =
        CacheCleanupGuard.isCacheInUse ||
            workerRepository.activeUniqueWorkId(PatcherWorker.UNIQUE_WORK_NAME) != null
}
