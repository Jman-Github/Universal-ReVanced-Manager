package app.revanced.manager.domain.repository

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import app.revanced.library.mostCommonCompatibleVersions as revancedMostCommonCompatibleVersions
import app.revanced.patcher.patch.Patch as RevancedPatch
import app.universal.revanced.manager.R
import app.universal.revanced.manager.BuildConfig
import app.revanced.manager.data.platform.NetworkInfo
import app.revanced.manager.data.redux.Action
import app.revanced.manager.data.redux.ActionContext
import app.revanced.manager.data.redux.Store
import app.revanced.manager.data.room.AppDatabase
import app.revanced.manager.data.room.AppDatabase.Companion.generateUid
import app.revanced.manager.data.room.bundles.PatchBundleEntity
import app.revanced.manager.data.room.bundles.PatchBundleProperties
import app.revanced.manager.data.room.bundles.Source
import app.revanced.manager.domain.bundles.APIPatchBundle
import app.revanced.manager.domain.bundles.ExternalBundleMetadata
import app.revanced.manager.domain.bundles.ExternalBundleMetadataStore
import app.revanced.manager.domain.bundles.ExternalGraphqlPatchBundle
import app.revanced.manager.domain.bundles.GitHubPullRequestBundle
import app.revanced.manager.domain.bundles.JsonPatchBundle
import app.revanced.manager.data.room.bundles.Source as SourceInfo
import app.revanced.manager.domain.bundles.LocalPatchBundle
import app.revanced.manager.domain.bundles.PatchBundleChangelogEntry
import app.revanced.manager.domain.bundles.PatchBundleDownloadProgress
import app.revanced.manager.domain.bundles.PatchBundleDownloadResult
import app.revanced.manager.domain.bundles.RemotePatchBundle
import app.revanced.manager.domain.bundles.PatchBundleSource
import app.revanced.manager.domain.bundles.PatchBundleSource.Extensions.isDefault
import app.revanced.manager.network.dto.ExternalBundleSnapshot
import app.revanced.manager.domain.manager.PreferencesManager
import app.revanced.manager.patcher.morphe.MorpheRuntimeBridge
import app.revanced.manager.patcher.patch.PatchInfo
import app.revanced.manager.patcher.patch.PatchBundle
import app.revanced.manager.patcher.patch.PatchBundleInfo
import app.revanced.manager.patcher.patch.PatchBundleType
import app.revanced.manager.util.PatchSelection
import app.revanced.manager.util.Options
import app.revanced.manager.util.simpleMessage
import app.revanced.manager.util.tag
import app.revanced.manager.util.toast
import kotlinx.collections.immutable.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlin.coroutines.coroutineContext
import io.ktor.http.Url
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale
import kotlin.collections.LinkedHashSet
import kotlin.collections.joinToString
import kotlin.collections.map
import kotlin.text.ifEmpty
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PatchBundleRepository(
    private val app: Application,
    private val networkInfo: NetworkInfo,
    private val prefs: PreferencesManager,
    db: AppDatabase,
) {
    private val dao = db.patchBundleDao()
    private val bundlesDir = app.getDir("patch_bundles", Context.MODE_PRIVATE)

    private val scope = CoroutineScope(Dispatchers.Default)
    private val store = Store(scope, State())

    val sources = store.state.map { it.sources.values.toList() }
    val bundles = store.state.map {
        it.sources.mapNotNull { (uid, src) ->
            uid to (src.patchBundle ?: return@mapNotNull null)
        }.toMap()
    }
    val allBundlesInfoFlow = store.state.map { it.info }
    val enabledBundlesInfoFlow = allBundlesInfoFlow.map { info ->
        info.filter { (_, bundleInfo) -> bundleInfo.enabled }
    }
    val bundleInfoFlow = enabledBundlesInfoFlow

    fun bundlesByType(type: PatchBundleType) = store.state.map { state ->
        state.sources.mapNotNull { (uid, src) ->
            val bundle = src.patchBundle ?: return@mapNotNull null
            val info = state.info[uid] ?: return@mapNotNull null
            if (info.bundleType != type) return@mapNotNull null
            uid to bundle
        }.toMap()
    }

    suspend fun selectionBundleTypes(selection: PatchSelection): Set<PatchBundleType> {
        if (selection.isEmpty()) return emptySet()
        val activeSelection = selection.filterValues { it.isNotEmpty() }
        if (activeSelection.isEmpty()) return emptySet()
        val info = allBundlesInfoFlow.first()
        return activeSelection.keys.mapNotNull { uid -> info[uid]?.bundleType }.toSet()
    }

    suspend fun selectionBundleType(selection: PatchSelection): PatchBundleType? {
        val types = selectionBundleTypes(selection)
        return if (types.size == 1) types.first() else null
    }

    suspend fun selectionHasMixedBundleTypes(selection: PatchSelection): Boolean =
        selectionBundleTypes(selection).size > 1

    fun scopedBundleInfoFlow(packageName: String, version: String?) = enabledBundlesInfoFlow.map {
        it.map { (_, bundleInfo) ->
            bundleInfo.forPackage(
                packageName,
                version
            )
        }
    }

    val patchCountsFlow = allBundlesInfoFlow.map { it.mapValues { (_, info) -> info.patches.size } }

    val suggestedVersions = enabledBundlesInfoFlow.map { bundleInfos ->
        val revancedPatches = bundleInfos.values
            .filter { it.bundleType == PatchBundleType.REVANCED }
            .flatMap { info -> info.patches.map(PatchInfo::toPatcherPatch) }
            .toSet()
        val morphePatches = bundleInfos.values
            .filter { it.bundleType == PatchBundleType.MORPHE }
            .flatMap { info -> info.patches }

        val morpheSuggested = suggestedVersionsForMorphe(morphePatches)
        val revancedSuggested = suggestedVersionsForRevanced(revancedPatches)
        morpheSuggested + revancedSuggested
    }

    val suggestedVersionsByBundle = enabledBundlesInfoFlow.map { bundleInfos ->
        bundleInfos.mapValues { (_, info) ->
            when (info.bundleType) {
                PatchBundleType.REVANCED -> {
                    val patches = info.patches.map(PatchInfo::toPatcherPatch).toSet()
                    suggestedVersionsForRevanced(patches)
                }
                PatchBundleType.MORPHE -> {
                    suggestedVersionsForMorphe(info.patches)
                }
            }
        }
    }

    private val manualUpdateInfoFlow = MutableStateFlow<Map<Int, ManualBundleUpdateInfo>>(emptyMap())
    val manualUpdateInfo: StateFlow<Map<Int, ManualBundleUpdateInfo>> = manualUpdateInfoFlow.asStateFlow()

    private val bundleUpdateProgressFlow = MutableStateFlow<BundleUpdateProgress?>(null)
    val bundleUpdateProgress: StateFlow<BundleUpdateProgress?> = bundleUpdateProgressFlow.asStateFlow()

    private val bundleImportProgressFlow = MutableStateFlow<ImportProgress?>(null)
    val bundleImportProgress: StateFlow<ImportProgress?> = bundleImportProgressFlow.asStateFlow()
    private val discoveryImportProgressFlow =
        MutableStateFlow<Map<String, DiscoveryImportProgress>>(emptyMap())
    val discoveryImportProgress: StateFlow<Map<String, DiscoveryImportProgress>> =
        discoveryImportProgressFlow.asStateFlow()
    private val discoveryImportQueuedFlow = MutableStateFlow<Set<String>>(emptySet())
    val discoveryImportQueued: StateFlow<Set<String>> = discoveryImportQueuedFlow.asStateFlow()

    private val updateJobMutex = Mutex()
    private var updateJob: Job? = null
    private val updateStateMutex = Mutex()
    private val changelogHistoryMutex = Mutex()
    private val bundleCacheMutex = Mutex()
    @Volatile
    private var bundleCacheInitialized = false
    @Volatile
    private var activeUpdateUids: Set<Int> = emptySet()
    @Volatile
    private var cancelledUpdateUids: Set<Int> = emptySet()
    private val pendingUpdateRequests = mutableListOf<UpdateRequest>()
    private val localImportMutex = Mutex()
    private val localImportStateMutex = Mutex()
    private var localImportQueued = 0
    @Volatile
    private var localImportProcessedSteps = 0
    @Volatile
    private var localImportTotalSteps = 0

    private var bundleImportAutoClearJob: Job? = null
    private var bundleUpdateAutoClearJob: Job? = null
    private val discoveryImportMutex = Mutex()
    private val discoveryImportQueue = ArrayDeque<DiscoveryImportRequest>()
    private val discoveryImportQueuedKeys = LinkedHashSet<String>()
    private var discoveryImportJob: Job? = null
    private val changelogHistoryJson = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun setBundleImportProgress(progress: ImportProgress?) {
        bundleImportProgressFlow.value = progress
        bundleImportAutoClearJob?.cancel()
        if (progress == null) return

        val isDownloadComplete = progress.bytesTotal?.takeIf { it > 0L }?.let { total ->
            progress.bytesRead >= total
        } ?: false

        val isDone = progress.processed >= progress.total &&
            (progress.phase != BundleImportPhase.Downloading || isDownloadComplete)

        if (!isDone) return

        bundleImportAutoClearJob = scope.launch {
            delay(8_000)
            val current = bundleImportProgressFlow.value ?: return@launch
            val currentDownloadComplete = current.bytesTotal?.takeIf { it > 0L }?.let { total ->
                current.bytesRead >= total
            } ?: false
            val currentDone = current.processed >= current.total &&
                (current.phase != BundleImportPhase.Downloading || currentDownloadComplete)
            if (currentDone) {
                bundleImportProgressFlow.value = null
            }
        }
    }

    private fun cancelBundleUpdateAutoClear() {
        bundleUpdateAutoClearJob?.cancel()
        bundleUpdateAutoClearJob = null
    }

    private fun scheduleBundleUpdateProgressClear() {
        cancelBundleUpdateAutoClear()
        val current = bundleUpdateProgressFlow.value ?: return
        if (current.total <= 0 || current.completed < current.total) return

        bundleUpdateAutoClearJob = scope.launch {
            delay(8_000)
            val progress = bundleUpdateProgressFlow.value ?: return@launch
            if (progress.total > 0 && progress.completed >= progress.total) {
                bundleUpdateProgressFlow.value = null
            }
        }
    }

    private fun currentUpdateTotal(defaultTotal: Int): Int {
        val active = activeUpdateUids
        return if (active.isNotEmpty()) active.size else defaultTotal
    }

    private suspend fun markActiveUpdateUids(uids: Set<Int>) {
        updateStateMutex.withLock {
            activeUpdateUids = uids
            cancelledUpdateUids = emptySet()
        }
    }

    private suspend fun clearActiveUpdateState() {
        updateStateMutex.withLock {
            activeUpdateUids = emptySet()
            cancelledUpdateUids = emptySet()
        }
    }

    private suspend fun cancelRemoteUpdates(uids: Set<Int>): Pair<Int, Int> {
        return updateStateMutex.withLock {
            if (activeUpdateUids.isEmpty()) return@withLock 0 to 0
            val affected = activeUpdateUids.intersect(uids)
            if (affected.isEmpty()) return@withLock 0 to activeUpdateUids.size
            activeUpdateUids = activeUpdateUids - affected
            cancelledUpdateUids = cancelledUpdateUids + affected
            affected.size to activeUpdateUids.size
        }
    }

    private fun isRemoteUpdateCancelled(uid: Int): Boolean = cancelledUpdateUids.contains(uid)

    private suspend fun cancelUpdateJob() {
        updateJobMutex.withLock {
            updateJob?.cancel()
            updateJob = null
        }
    }

    private suspend fun updateProgressAfterRemoval(affectedCount: Int, remaining: Int) {
        if (affectedCount <= 0) return
        if (remaining <= 0) {
            cancelBundleUpdateAutoClear()
            bundleUpdateProgressFlow.value = null
            cancelUpdateJob()
            return
        }
        bundleUpdateProgressFlow.update { progress ->
            if (progress == null) return@update null
            val clampedCompleted = progress.completed.coerceAtMost(remaining)
            progress.copy(total = remaining, completed = clampedCompleted)
        }
    }

    private suspend fun enqueueLocalImport() {
        localImportStateMutex.withLock {
            localImportQueued += 1
            localImportTotalSteps += LOCAL_IMPORT_STEPS
            val total = localImportTotalSteps
            bundleImportProgressFlow.update { progress ->
                if (progress?.isStepBased != true) return@update progress
                progress.copy(
                    total = total,
                    processed = progress.processed.coerceAtMost(total)
                )
            }
        }
    }

    private suspend fun completeLocalImport() {
        localImportStateMutex.withLock {
            localImportQueued = (localImportQueued - 1).coerceAtLeast(0)
            localImportProcessedSteps += LOCAL_IMPORT_STEPS
            if (localImportQueued == 0 && localImportProcessedSteps >= localImportTotalSteps) {
                localImportProcessedSteps = 0
                localImportTotalSteps = 0
            }
        }
    }

    private fun localImportBaseSteps(): Int = localImportProcessedSteps

    private fun localImportTotalSteps(): Int = localImportTotalSteps.coerceAtLeast(LOCAL_IMPORT_STEPS)

    private fun changelogHistoryFile(uid: Int): File =
        directoryOf(uid).resolve("changelog_history.json")

    suspend fun getChangelogHistory(uid: Int): List<PatchBundleChangelogEntry> =
        withContext(Dispatchers.IO) {
            changelogHistoryMutex.withLock {
                readChangelogHistoryInternal(uid)
            }
        }

    suspend fun setChangelogHistory(uid: Int, entries: List<PatchBundleChangelogEntry>) {
        withContext(Dispatchers.IO) {
            changelogHistoryMutex.withLock {
                writeChangelogHistoryInternal(uid, entries)
            }
        }
    }

    suspend fun recordChangelog(uid: Int, asset: app.revanced.manager.network.dto.ReVancedAsset) {
        val entry = PatchBundleChangelogEntry.fromAsset(asset)
        withContext(Dispatchers.IO) {
            changelogHistoryMutex.withLock {
                val current = readChangelogHistoryInternal(uid)
                val updated = current
                    .filterNot { isSameChangelogEntry(it, entry) }
                    .toMutableList()
                updated.add(0, entry)
                writeChangelogHistoryInternal(uid, updated)
            }
        }
    }

    private fun isSameChangelogEntry(
        existing: PatchBundleChangelogEntry,
        candidate: PatchBundleChangelogEntry
    ): Boolean {
        val existingVersion = existing.version.trim()
        val candidateVersion = candidate.version.trim()
        if (candidateVersion.isNotBlank() && existingVersion.equals(candidateVersion, ignoreCase = true)) {
            return true
        }
        val published = candidate.publishedAtMillis
        return published != null &&
            published > 0 &&
            existing.publishedAtMillis == published &&
            existing.description.trim() == candidate.description.trim()
    }

    private fun readChangelogHistoryInternal(uid: Int): List<PatchBundleChangelogEntry> {
        val file = changelogHistoryFile(uid)
        if (!file.exists()) return emptyList()
        val content = runCatching { file.readText() }.getOrDefault("")
        if (content.isBlank()) return emptyList()
        return runCatching {
            changelogHistoryJson.decodeFromString<List<PatchBundleChangelogEntry>>(content)
        }.getOrDefault(emptyList())
    }

    private fun writeChangelogHistoryInternal(uid: Int, entries: List<PatchBundleChangelogEntry>) {
        val file = changelogHistoryFile(uid)
        if (entries.isEmpty()) {
            runCatching { file.delete() }
            return
        }
        file.parentFile?.mkdirs()
        runCatching {
            file.writeText(changelogHistoryJson.encodeToString(entries))
        }
    }

    private fun setLocalImportProgress(
        baseProcessed: Int,
        offset: Int,
        displayName: String?,
        phase: BundleImportPhase,
        bytesRead: Long = 0L,
        bytesTotal: Long? = null,
    ) {
        val total = localImportTotalSteps()
        val processed = (baseProcessed + offset).coerceAtMost(total)
        setBundleImportProgress(
            ImportProgress(
                processed = processed,
                total = total,
                currentBundleName = displayName?.takeIf { it.isNotBlank() },
                phase = phase,
                bytesRead = bytesRead,
                bytesTotal = bytesTotal,
                isStepBased = true
            )
        )
    }

    private fun progressLabelFor(bundle: RemotePatchBundle): String {
        val explicitDisplayName = bundle.displayName?.trim().takeUnless { it.isNullOrBlank() }
        if (explicitDisplayName != null) return explicitDisplayName

        val unnamed = app.getString(R.string.patches_name_fallback)
        if (bundle.name == unnamed) {
            guessNameFromEndpoint(bundle.endpoint)?.let { return it }
        }
        return bundle.name
    }

    private fun guessNameFromEndpoint(endpoint: String): String? {
        val uri = try {
            URI(endpoint)
        } catch (_: URISyntaxException) {
            return null
        }
        val host = uri.host?.lowercase(Locale.US) ?: return null
        val segments = uri.path?.trim('/')?.split('/')?.filter { it.isNotBlank() }.orEmpty()

        // Prefer a segment containing "bundle" (case-insensitive), e.g. ".../piko-latest-patches-bundle.json".
        val bundleCandidates = segments.filter { it.contains("bundle", ignoreCase = true) }
        val chosen = bundleCandidates
            .lastOrNull { seg ->
                val normalized = seg.lowercase(Locale.US)
                normalized !in setOf("bundle", "bundles")
            }
            ?: bundleCandidates.lastOrNull()

        if (chosen != null) {
            val withoutExt = chosen.replace(Regex("\\.[A-Za-z0-9]+$"), "")
            val normalized = withoutExt
                .replace(Regex("[._\\-]+"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .lowercase(Locale.US)

            if (normalized.isNotBlank()) {
                return normalized.replaceFirstChar { c -> c.titlecase(Locale.US) }
            }
        }

        // Fallbacks for common GitHub URL patterns.
        if (segments.isEmpty()) return host
        return when {
            host == "github.com" && segments.size >= 2 -> segments[1]
            host == "api.github.com" && segments.size >= 3 && segments[0] == "repos" -> segments[2]
            else -> host
        }
    }

    suspend fun enforceOfficialOrderPreference() = dispatchAction("Enforce official order preference") { state ->
        val storedOrder = prefs.officialBundleSortOrder.get()
        if (storedOrder < 0) return@dispatchAction state
        val entities = dao.all().sortedBy { entity -> entity.sortOrder }
        val currentIndex = entities.indexOfFirst { entity -> entity.uid == DEFAULT_SOURCE_UID }
        if (currentIndex == -1) return@dispatchAction state
        val targetIndex = storedOrder.coerceIn(0, entities.lastIndex)
        if (currentIndex == targetIndex) return@dispatchAction state

        val adjusted = entities.toMutableList()
        val defaultEntity = adjusted.removeAt(currentIndex)
        adjusted.add(targetIndex, defaultEntity)
        adjusted.forEachIndexed { index, entity ->
            dao.updateSortOrder(entity.uid, index)
        }
        doReload()
    }

    suspend fun getOfficialBundleSortOrder(): Int? =
        prefs.officialBundleSortOrder.get().takeIf { it >= 0 }

    suspend fun setOfficialBundleSortOrder(order: Int?) {
        val value = order?.takeIf { it >= 0 } ?: -1
        prefs.officialBundleSortOrder.update(value)
    }

    suspend fun snapshotSelection(selection: PatchSelection) =
        selection.toPayload(sources.first(), bundleInfoFlow.first())

    suspend fun snapshotSelection(selection: PatchSelection, options: Options) =
        selection.toPayload(sources.first(), bundleInfoFlow.first(), options)

    private suspend inline fun dispatchAction(
        name: String,
        crossinline block: suspend ActionContext.(current: State) -> State
    ) {
        store.dispatch(object : Action<State> {
            override suspend fun ActionContext.execute(current: State) = block(current)
            override fun toString() = name
        })
    }

    /**
     * Performs a reload. Do not call this outside of a store action.
     */
    private suspend fun doReload(): State {
        val entities = loadEntitiesEnforcingOfficialOrder()

        val sources = entities.associate { it.uid to it.load() }.toMutableMap()
        val entityByUid = entities.associateBy { it.uid }
        sources.forEach { (uid, source) ->
            val remote = source as? RemotePatchBundle ?: return@forEach
            val entity = entityByUid[uid] ?: return@forEach
            val bundleVersion = remote.version?.takeUnless { it.isBlank() } ?: return@forEach
            val bundleNormalized = normalizeVersionForCompare(bundleVersion) ?: return@forEach
            val storedNormalized = normalizeVersionForCompare(entity.versionHash)
            if (storedNormalized == bundleNormalized) return@forEach
            updateDb(uid) { it.copy(versionHash = bundleVersion) }
            sources[uid] = entity.copy(versionHash = bundleVersion).load()
        }

        val hasOutOfDateNames = sources.values.any { it.isNameOutOfDate }
        if (hasOutOfDateNames) dispatchAction(
            "Sync names"
        ) { state ->
            val nameChanges = state.sources.mapNotNull { (_, src) ->
                if (!src.isNameOutOfDate) return@mapNotNull null
                val newName = src.patchBundle?.manifestAttributes?.name?.takeIf { it != src.name }
                    ?: return@mapNotNull null

                src.uid to newName
            }
            val sources = state.sources.toMutableMap()
            val info = state.info.toMutableMap()
            nameChanges.forEach { (uid, name) ->
                updateDb(uid) { it.copy(name = name) }
                sources[uid] = sources[uid]!!.copy(name = name)
                info[uid] = info[uid]?.copy(name = name) ?: return@forEach
            }

            State(sources.toPersistentMap(), info.toPersistentMap())
        }
        val info = loadMetadata(sources).toMutableMap()

        val officialSource = sources[0]
        val officialDisplayName = "Official ReVanced Patches"
        if (officialSource != null) {
            val storedCustomName = prefs.officialBundleCustomDisplayName.get().takeIf { it.isNotBlank() }
            val currentName = officialSource.displayName
            when {
                storedCustomName != null && currentName != storedCustomName -> {
                    updateDb(officialSource.uid) { it.copy(displayName = storedCustomName) }
                    sources[officialSource.uid] = officialSource.copy(displayName = storedCustomName)
                }
                storedCustomName == null && currentName.isNullOrBlank() -> {
                    updateDb(officialSource.uid) { it.copy(displayName = officialDisplayName) }
                    sources[officialSource.uid] = officialSource.copy(displayName = officialDisplayName)
                }
                storedCustomName == null && !currentName.isNullOrBlank() && currentName != officialDisplayName -> {
                    prefs.officialBundleCustomDisplayName.update(currentName)
                }
            }
        }

        manualUpdateInfoFlow.update { current ->
            current.filterKeys { uid ->
                val bundle = sources[uid] as? RemotePatchBundle
                bundle != null && !bundle.autoUpdate
            }
        }

        return State(sources.toPersistentMap(), info.toPersistentMap())
    }

    suspend fun reload() = dispatchAction("Full reload") {
        doReload()
    }

    private suspend fun loadFromDb(): List<PatchBundleEntity> {
        val all = dao.all()
        if (all.isEmpty()) {
            val shouldRestoreDefault = !prefs.officialBundleRemoved.get()
            if (shouldRestoreDefault) {
                val default = createDefaultEntityWithStoredOrder()
                dao.upsert(default)
                return listOf(default)
            }
            return emptyList()
        }

        return all
    }

    fun discoveryImportKey(bundle: ExternalBundleSnapshot): String {
        if (bundle.bundleId > 0) {
            val channel = if (bundle.isPrerelease) "prerelease" else "release"
            return "${bundle.bundleId}|$channel"
        }
        val owner = bundle.ownerName.trim().lowercase(Locale.US)
        val repo = bundle.repoName.trim().lowercase(Locale.US)
        val base = when {
            owner.isNotBlank() || repo.isNotBlank() ->
                listOf(owner, repo).filter { it.isNotBlank() }.joinToString("/")
            bundle.sourceUrl.isNotBlank() -> bundle.sourceUrl.trim().lowercase(Locale.US)
            !bundle.downloadUrl.isNullOrBlank() ->
                bundle.downloadUrl.trim().lowercase(Locale.US)
            else -> "unknown"
        }
        val channel = if (bundle.isPrerelease) "prerelease" else "release"
        return "$base|$channel"
    }

    suspend fun enqueueDiscoveryImport(
        bundle: ExternalBundleSnapshot,
        searchUpdate: Boolean,
        autoUpdate: Boolean
    ): DiscoveryImportEnqueueResult {
        val bundleKey = discoveryImportKey(bundle)
        return discoveryImportMutex.withLock {
            val inProgress = discoveryImportProgressFlow.value.containsKey(bundleKey)
            val alreadyQueued = discoveryImportQueuedKeys.contains(bundleKey)
            if (inProgress || alreadyQueued) {
                return@withLock DiscoveryImportEnqueueResult.Duplicate
            }
            val wasActive = discoveryImportJob?.isActive == true ||
                discoveryImportProgressFlow.value.isNotEmpty() ||
                discoveryImportQueuedKeys.isNotEmpty()
            discoveryImportQueuedKeys.add(bundleKey)
            discoveryImportQueue.addLast(
                DiscoveryImportRequest(bundleKey, bundle, searchUpdate, autoUpdate)
            )
            discoveryImportProgressFlow.update { current ->
                if (current.containsKey(bundleKey)) current
                else current + (bundleKey to DiscoveryImportProgress(0L, null, DiscoveryImportStatus.Queued))
            }
            updateDiscoveryQueuedLocked()
            if (discoveryImportJob?.isActive != true) {
                discoveryImportJob = scope.launch { runDiscoveryImportQueue() }
            }
            if (wasActive) {
                DiscoveryImportEnqueueResult.Queued
            } else {
                DiscoveryImportEnqueueResult.Started
            }
        }
    }

    private data class BundleDigestInfo(
        val hash: String,
        val size: Long,
        val lastModified: Long,
    )

    private fun bundleDigestFile(uid: Int) = directoryOf(uid).resolve("patches.sha256")

    private fun readBundleDigestInfo(uid: Int): BundleDigestInfo? {
        val file = bundleDigestFile(uid)
        if (!file.exists()) return null
        val content = runCatching { file.readText() }.getOrDefault("").trim()
        if (content.isBlank()) return null
        val parts = content.split('|')
        if (parts.size < 3) return null
        val hash = parts[0].trim().takeIf { it.isNotEmpty() } ?: return null
        val size = parts[1].toLongOrNull() ?: return null
        val lastModified = parts[2].toLongOrNull() ?: return null
        return BundleDigestInfo(hash, size, lastModified)
    }

    private fun writeBundleDigestInfo(uid: Int, info: BundleDigestInfo) {
        val file = bundleDigestFile(uid)
        file.parentFile?.mkdirs()
        runCatching { file.writeText("${info.hash}|${info.size}|${info.lastModified}") }
    }

    private fun computeBundleHash(file: File): String? {
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    digest.update(buffer, 0, read)
                }
            }
            val bytes = digest.digest()
            val hexChars = "0123456789abcdef".toCharArray()
            val result = CharArray(bytes.size * 2)
            var index = 0
            for (byte in bytes) {
                val value = byte.toInt() and 0xff
                result[index++] = hexChars[value ushr 4]
                result[index++] = hexChars[value and 0x0f]
            }
            String(result)
        }.getOrNull()
    }

    private fun clearBundleOdex(uid: Int) {
        runCatching {
            val oatDir = directoryOf(uid).resolve("oat")
            if (oatDir.exists()) oatDir.deleteRecursively()
        }
    }

    private fun clearAllBundleOdex() {
        runCatching {
            bundlesDir.listFiles()?.forEach { dir ->
                val oatDir = dir.resolve("oat")
                if (oatDir.exists()) oatDir.deleteRecursively()
            }
        }
    }

    private suspend fun ensureBundleCacheInitialized() {
        if (bundleCacheInitialized) return
        bundleCacheMutex.withLock {
            if (bundleCacheInitialized) return
            val currentVersion = BuildConfig.VERSION_CODE
            val cachedVersion = prefs.patchBundleCacheVersionCode.get()
            if (cachedVersion != currentVersion) {
                withContext(Dispatchers.IO) {
                    clearAllBundleOdex()
                }
                prefs.patchBundleCacheVersionCode.update(currentVersion)
            }
            bundleCacheInitialized = true
        }
    }

    private suspend fun ensureBundleCacheValid(uid: Int, bundle: PatchBundle) {
        withContext(Dispatchers.IO) {
            val file = File(bundle.patchesJar)
            if (!file.exists()) return@withContext

            val size = runCatching { file.length() }.getOrDefault(0L)
            val lastModified = runCatching { file.lastModified() }.getOrDefault(0L)
            val existing = readBundleDigestInfo(uid)
            if (existing != null && existing.size == size && existing.lastModified == lastModified) {
                return@withContext
            }

            val hash = computeBundleHash(file) ?: return@withContext
            val changed = existing == null || existing.hash != hash
            if (changed) {
                clearBundleOdex(uid)
            }
            writeBundleDigestInfo(uid, BundleDigestInfo(hash, size, lastModified))
        }
    }

    private fun loadBundleMetadataInternal(bundle: PatchBundle): Pair<PatchBundleType, List<PatchInfo>> {
        val bundlePath = bundle.patchesJar
        val extension = File(bundlePath).extension.lowercase(Locale.US)
        if (extension == "rvp") {
            return PatchBundleType.REVANCED to PatchBundle.Loader.metadata(bundle)
        }
        if (extension == "mpp") {
            return PatchBundleType.MORPHE to MorpheRuntimeBridge.loadMetadata(bundlePath)
        }

        val revancedResult = runCatching { PatchBundle.Loader.metadata(bundle) }
        if (revancedResult.isSuccess) {
            return PatchBundleType.REVANCED to revancedResult.getOrThrow()
        }

        val morpheResult = runCatching { MorpheRuntimeBridge.loadMetadata(bundlePath) }
        if (morpheResult.isSuccess) {
            return PatchBundleType.MORPHE to morpheResult.getOrThrow()
        }

        val error = IllegalStateException("Failed to load patch bundle metadata")
        revancedResult.exceptionOrNull()?.let(error::addSuppressed)
        morpheResult.exceptionOrNull()?.let(error::addSuppressed)
        throw error
    }

    private suspend fun loadBundleMetadata(uid: Int, bundle: PatchBundle): Pair<PatchBundleType, List<PatchInfo>> {
        ensureBundleCacheValid(uid, bundle)
        val initial = runCatching { loadBundleMetadataInternal(bundle) }
        if (initial.isSuccess) return initial.getOrThrow()

        clearBundleOdex(uid)
        val retry = runCatching { loadBundleMetadataInternal(bundle) }
        if (retry.isSuccess) return retry.getOrThrow()

        val error = IllegalStateException("Failed to load patch bundle metadata")
        initial.exceptionOrNull()?.let(error::addSuppressed)
        retry.exceptionOrNull()?.let(error::addSuppressed)
        throw error
    }

    private suspend fun loadMetadata(sources: Map<Int, PatchBundleSource>): Map<Int, PatchBundleInfo.Global> {
        // Map bundles -> sources
        val map = sources.mapNotNull { (_, src) ->
            (src.patchBundle ?: return@mapNotNull null) to src
        }.toMap()

        if (map.isEmpty()) return emptyMap()

        ensureBundleCacheInitialized()

        val failures = mutableListOf<Pair<Int, Throwable>>()
        val metadata = mutableMapOf<Int, PatchBundleInfo.Global>()

        for ((bundle, src) in map) {
            try {
                val (bundleType, patches) = loadBundleMetadata(src.uid, bundle)
                metadata[src.uid] = PatchBundleInfo.Global(
                    src.displayTitle,
                    bundle.manifestAttributes?.version,
                    src.uid,
                    bundleType,
                    src.enabled,
                    patches
                )
            } catch (error: Throwable) {
                failures += src.uid to error
                Log.e(tag, "Failed to load bundle ${src.name}", error)
            }
        }

        if (failures.isNotEmpty()) {
            dispatchAction("Mark bundles as failed") { state ->
                state.copy(sources = state.sources.mutate {
                    failures.forEach { (uid, throwable) ->
                        it[uid] = it[uid]?.copy(error = throwable) ?: return@forEach
                    }
                })
            }
        }

        return metadata
    }

    suspend fun isVersionAllowed(packageName: String, version: String) =
        withContext(Dispatchers.Default) {
            if (!prefs.suggestedVersionSafeguard.get()) return@withContext true

            val suggestedVersion = suggestedVersions.first()[packageName] ?: return@withContext true
            suggestedVersion == version
        }

    /**
     * Get the directory of the [PatchBundleSource] with the specified [uid], creating it if needed.
     */
    private fun directoryOf(uid: Int) = bundlesDir.resolve(uid.toString()).also { it.mkdirs() }

    private fun PatchBundleEntity.load(): PatchBundleSource {
        val dir = directoryOf(uid)
        val actualName =
            name.ifEmpty { app.getString(if (uid == 0) R.string.patches_name_default else R.string.patches_name_fallback) }
        val normalizedDisplayName = displayName?.takeUnless { it.isBlank() }

        return when (source) {
            is SourceInfo.Local -> LocalPatchBundle(
                actualName,
                uid,
                normalizedDisplayName,
                createdAt,
                updatedAt,
                null,
                dir,
                enabled
            )
            is SourceInfo.API -> APIPatchBundle(
                actualName,
                uid,
                normalizedDisplayName,
                createdAt,
                updatedAt,
                versionHash,
                null,
                dir,
                SourceInfo.API.SENTINEL,
                autoUpdate,
                searchUpdate,
                lastNotifiedVersion,
                enabled,
            )

            is SourceInfo.Remote -> JsonPatchBundle(
                actualName,
                uid,
                normalizedDisplayName,
                createdAt,
                updatedAt,
                versionHash,
                null,
                dir,
                source.url.toString(),
                autoUpdate,
                searchUpdate,
                lastNotifiedVersion,
                enabled,
            ).let { jsonBundle ->
                val external = ExternalBundleMetadataStore.read(dir)
                if (external == null) {
                    jsonBundle
                } else {
                    ExternalGraphqlPatchBundle(
                        actualName,
                        uid,
                        normalizedDisplayName,
                        createdAt,
                        updatedAt,
                        versionHash,
                        null,
                        dir,
                        source.url.toString(),
                        autoUpdate,
                        searchUpdate,
                        lastNotifiedVersion,
                        enabled,
                        external
                    )
                }
            }
            // PR #35: https://github.com/Jman-Github/Universal-ReVanced-Manager/pull/35
            is SourceInfo.GitHubPullRequest -> GitHubPullRequestBundle(
                actualName,
                uid,
                normalizedDisplayName,
                createdAt,
                updatedAt,
                versionHash,
                null,
                dir,
                source.url.toString(),
                autoUpdate,
                searchUpdate,
                lastNotifiedVersion,
                enabled
            )
        }
    }

    private suspend fun loadEntitiesEnforcingOfficialOrder(): List<PatchBundleEntity> {
        var entities = loadFromDb()
        if (enforceOfficialSortOrderIfNeeded(entities)) {
            entities = loadFromDb()
        }
        entities.forEach { Log.d(tag, "Bundle: $it") }
        return entities
    }

    private suspend fun enforceOfficialSortOrderIfNeeded(entities: List<PatchBundleEntity>): Boolean {
        if (entities.isEmpty()) return false
        val ordered = entities.sortedBy { it.sortOrder }
        val currentIndex = ordered.indexOfFirst { it.uid == DEFAULT_SOURCE_UID }
        if (currentIndex == -1) return false

        val desiredOrder = prefs.officialBundleSortOrder.get()
        val currentOrder = currentIndex.coerceAtLeast(0)
        if (desiredOrder < 0) {
            prefs.officialBundleSortOrder.update(currentOrder)
            return false
        }

        val targetIndex = desiredOrder.coerceIn(0, ordered.lastIndex)
        if (currentIndex == targetIndex) {
            prefs.officialBundleSortOrder.update(currentOrder)
            return false
        }

        val reordered = ordered.toMutableList()
        val defaultEntity = reordered.removeAt(currentIndex)
        reordered.add(targetIndex, defaultEntity)

        reordered.forEachIndexed { index, entity ->
            dao.updateSortOrder(entity.uid, index)
        }
        prefs.officialBundleSortOrder.update(targetIndex)
        return true
    }

    private suspend fun createDefaultEntityWithStoredOrder(): PatchBundleEntity {
        val storedOrder = prefs.officialBundleSortOrder.get().takeIf { it >= 0 }
        val base = defaultSource()
        return storedOrder?.let { base.copy(sortOrder = it) } ?: base
    }

    private suspend fun nextSortOrder(): Int = (dao.maxSortOrder() ?: -1) + 1

    private suspend fun ensureUniqueName(requestedName: String?, excludeUid: Int? = null): String {
        val base = requestedName?.trim().takeUnless { it.isNullOrBlank() }
            ?: app.getString(R.string.patches_name_fallback)

        val existing = dao.all()
            .filterNot { entity -> excludeUid != null && entity.uid == excludeUid }
            .map { it.name.lowercase(Locale.US) }
            .toSet()

        if (base.lowercase(Locale.US) !in existing) return base

        var suffix = 2
        var candidate: String
        do {
            candidate = "$base ($suffix)"
            suffix += 1
        } while (candidate.lowercase(Locale.US) in existing)
        return candidate
    }

    private suspend fun createEntity(
        name: String,
        source: Source,
        autoUpdate: Boolean = false,
        searchUpdate: Boolean = true,
        displayName: String? = null,
        uid: Int? = null,
        sortOrder: Int? = null,
        createdAt: Long? = null,
        updatedAt: Long? = null,
        lastNotifiedVersion: String? = null
    ): PatchBundleEntity {
        val resolvedUid = uid ?: generateUid()
        val existingProps = dao.getProps(resolvedUid)
        val normalizedDisplayName = displayName?.takeUnless { it.isBlank() }
            ?: existingProps?.displayName?.takeUnless { it.isBlank() }
            ?: if (resolvedUid == DEFAULT_SOURCE_UID) "Official ReVanced Patches" else null
        val normalizedName = if (resolvedUid == DEFAULT_SOURCE_UID) {
            name
        } else {
            ensureUniqueName(name, resolvedUid)
        }
        val assignedSortOrder = when {
            sortOrder != null -> sortOrder
            else -> existingProps?.sortOrder ?: nextSortOrder()
        }
        val now = System.currentTimeMillis()
        val resolvedCreatedAt = createdAt ?: existingProps?.createdAt ?: now
        val resolvedUpdatedAt = updatedAt ?: now
        val resolvedEnabled = existingProps?.enabled ?: true
        val resolvedSearchUpdate = existingProps?.searchUpdate ?: searchUpdate
        val resolvedLastNotifiedVersion = lastNotifiedVersion ?: existingProps?.lastNotifiedVersion
        val entity = PatchBundleEntity(
            uid = resolvedUid,
            name = normalizedName,
            displayName = normalizedDisplayName,
            versionHash = null,
            source = source,
            autoUpdate = autoUpdate,
            searchUpdate = resolvedSearchUpdate,
            lastNotifiedVersion = resolvedLastNotifiedVersion,
            enabled = resolvedEnabled,
            sortOrder = assignedSortOrder,
            createdAt = resolvedCreatedAt,
            updatedAt = resolvedUpdatedAt
        )
        dao.upsert(entity)
        return entity
    }

    /**
     * Updates a patch bundle in the database. Do not use this outside an action.
     */
    private suspend fun updateDb(
        uid: Int,
        block: (PatchBundleProperties) -> PatchBundleProperties
    ) {
        val previous = dao.getProps(uid)!!
        val new = block(previous)
        dao.upsert(
            PatchBundleEntity(
                uid = uid,
                name = new.name,
                displayName = new.displayName?.takeUnless { it.isBlank() },
                versionHash = new.versionHash,
                source = new.source,
                autoUpdate = new.autoUpdate,
                searchUpdate = new.searchUpdate,
                lastNotifiedVersion = new.lastNotifiedVersion,
                enabled = new.enabled,
                sortOrder = new.sortOrder,
                createdAt = new.createdAt,
                updatedAt = new.updatedAt
            )
        )
    }

    suspend fun reset() = dispatchAction("Reset") { state ->
        dao.reset()
        prefs.officialBundleRemoved.update(false)
        state.sources.keys.forEach { directoryOf(it).deleteRecursively() }
        doReload()
    }

    private suspend fun toast(@StringRes id: Int, vararg args: Any?) =
        withContext(Dispatchers.Main) { app.toast(app.getString(id, *args)) }

    private data class UpdateRequest(
        val force: Boolean,
        val showToast: Boolean,
        val allowUnsafeNetwork: Boolean,
        val showProgress: Boolean,
        val onPerBundleProgress: ((bundle: RemotePatchBundle, bytesRead: Long, bytesTotal: Long?) -> Unit)?,
        val predicate: (bundle: RemotePatchBundle) -> Boolean,
    )

    private fun mergeUpdateRequests(requests: List<UpdateRequest>): UpdateRequest {
        val callbacks = requests.mapNotNull { it.onPerBundleProgress }
        val mergedCallback: ((RemotePatchBundle, Long, Long?) -> Unit)? = if (callbacks.isEmpty()) {
            null
        } else {
            { bundle, read, total -> callbacks.forEach { it(bundle, read, total) } }
        }
        return UpdateRequest(
            force = requests.any { it.force },
            showToast = requests.any { it.showToast },
            allowUnsafeNetwork = requests.any { it.allowUnsafeNetwork },
            showProgress = requests.any { it.showProgress },
            onPerBundleProgress = mergedCallback,
            predicate = { bundle -> requests.any { it.predicate(bundle) } }
        )
    }

    private suspend fun enqueueUpdateRequest(request: UpdateRequest) {
        updateStateMutex.withLock {
            pendingUpdateRequests += request
        }
    }

    private suspend fun drainPendingUpdateRequests(): UpdateRequest? {
        return updateStateMutex.withLock {
            if (pendingUpdateRequests.isEmpty()) return@withLock null
            val drained = pendingUpdateRequests.toList()
            pendingUpdateRequests.clear()
            mergeUpdateRequests(drained)
        }
    }

    suspend fun disable(vararg bundles: PatchBundleSource) =
        dispatchAction("Disable (${bundles.map { it.uid }.joinToString(",")})") {
            bundles.forEach { bundle ->
                updateDb(bundle.uid) { it.copy(enabled = !it.enabled) }
            }
            doReload()
        }

    suspend fun setEnabledStates(states: Map<Int, Boolean>) =
        dispatchAction("Set bundle enabled states") { state ->
            val updates = states.filter { (uid, enabled) ->
                state.sources[uid]?.enabled != enabled
            }
            if (updates.isEmpty()) return@dispatchAction state

            updates.forEach { (uid, enabled) ->
                updateDb(uid) { it.copy(enabled = enabled) }
            }

            val sources = state.sources.mutate { map ->
                updates.forEach { (uid, enabled) ->
                    map[uid] = map[uid]?.copy(enabled = enabled) ?: return@forEach
                }
            }
            val info = state.info.mutate { map ->
                updates.forEach { (uid, enabled) ->
                    map[uid] = map[uid]?.copy(enabled = enabled) ?: return@forEach
                }
            }

            state.copy(sources = sources, info = info)
        }

    suspend fun remove(vararg bundles: PatchBundleSource) =
        dispatchAction("Remove (${bundles.map { it.uid }.joinToString(",")})") { state ->
            val sources = state.sources.toMutableMap()
            val info = state.info.toMutableMap()
            bundles.forEach {
                if (it.isDefault) {
                    prefs.officialBundleRemoved.update(true)
                    val storedOrder = dao.getProps(it.uid)?.sortOrder ?: 0
                    prefs.officialBundleSortOrder.update(storedOrder.coerceAtLeast(0))
                }

                dao.remove(it.uid)
                directoryOf(it.uid).deleteRecursively()
                sources.remove(it.uid)
                info.remove(it.uid)
            }

            val (affectedCount, remaining) = cancelRemoteUpdates(bundles.map { it.uid }.toSet())
            updateProgressAfterRemoval(affectedCount, remaining)

            State(sources.toPersistentMap(), info.toPersistentMap())
        }

    suspend fun restoreDefaultBundle() = dispatchAction("Restore default bundle") {
        prefs.officialBundleRemoved.update(false)
        dao.upsert(createDefaultEntityWithStoredOrder())
        doReload()
    }

    suspend fun refreshDefaultBundle() = store.dispatch(Update(force = true) { it.uid == DEFAULT_SOURCE_UID })

    enum class DisplayNameUpdateResult {
        SUCCESS,
        NO_CHANGE,
        DUPLICATE,
        NOT_FOUND
    }

    suspend fun setDisplayName(uid: Int, displayName: String?): DisplayNameUpdateResult {
        val normalized = displayName?.trim()?.takeUnless { it.isEmpty() }

        val result = withContext(Dispatchers.IO) {
            val props = dao.getProps(uid) ?: return@withContext DisplayNameUpdateResult.NOT_FOUND
            val currentName = props.displayName?.trim()

            if (normalized == null && currentName == null) {
                return@withContext DisplayNameUpdateResult.NO_CHANGE
            }
            if (normalized != null && currentName != null && normalized == currentName) {
                return@withContext DisplayNameUpdateResult.NO_CHANGE
            }

            if (normalized != null && dao.hasDisplayNameConflict(uid, normalized)) {
                return@withContext DisplayNameUpdateResult.DUPLICATE
            }

            dao.upsert(
                PatchBundleEntity(
                    uid = uid,
                    name = props.name,
                    displayName = normalized,
                    versionHash = props.versionHash,
                    source = props.source,
                    autoUpdate = props.autoUpdate,
                    searchUpdate = props.searchUpdate,
                    lastNotifiedVersion = props.lastNotifiedVersion,
                    enabled = props.enabled,
                    sortOrder = props.sortOrder,
                    createdAt = props.createdAt,
                    updatedAt = props.updatedAt
                )
            )
            DisplayNameUpdateResult.SUCCESS
        }

        if (result == DisplayNameUpdateResult.SUCCESS || result == DisplayNameUpdateResult.NO_CHANGE) {
            dispatchAction("Sync display name ($uid)") { state ->
                val src = state.sources[uid] ?: return@dispatchAction state
                val updated = src.copy(displayName = normalized)
                state.copy(sources = state.sources.put(uid, updated))
            }
        }

        if (uid == DEFAULT_SOURCE_UID && result == DisplayNameUpdateResult.SUCCESS) {
            prefs.officialBundleCustomDisplayName.update(normalized.orEmpty())
        }

        return result
    }

    suspend fun updateRemoteEndpoint(
        src: RemotePatchBundle,
        newUrl: String,
        onProgress: PatchBundleDownloadProgress? = null,
    ): Boolean {
        val normalizedUrl = try {
            normalizeRemoteBundleUrl(newUrl)
        } catch (e: IllegalArgumentException) {
            withContext(Dispatchers.Main) {
                app.toast(e.message ?: "Invalid bundle URL")
            }
            return false
        }

        if (normalizedUrl == src.endpoint) return false

        dispatchAction("Update bundle url (${src.uid})") { state ->
            val props = dao.getProps(src.uid) ?: return@dispatchAction state
            val now = System.currentTimeMillis()
            updateDb(src.uid) {
                it.copy(
                    source = SourceInfo.from(normalizedUrl),
                    versionHash = null,
                    lastNotifiedVersion = null,
                    updatedAt = now
                )
            }
            val updatedProps = props.copy(
                source = SourceInfo.from(normalizedUrl),
                versionHash = null,
                lastNotifiedVersion = null,
                updatedAt = now
            )
            val entity = PatchBundleEntity(
                uid = src.uid,
                name = updatedProps.name,
                displayName = updatedProps.displayName,
                versionHash = updatedProps.versionHash,
                source = updatedProps.source,
                autoUpdate = updatedProps.autoUpdate,
                searchUpdate = updatedProps.searchUpdate,
                lastNotifiedVersion = updatedProps.lastNotifiedVersion,
                enabled = updatedProps.enabled,
                sortOrder = updatedProps.sortOrder,
                createdAt = updatedProps.createdAt,
                updatedAt = updatedProps.updatedAt
            )
            val updatedSource = entity.load()
            State(
                sources = state.sources.put(src.uid, updatedSource),
                info = state.info.remove(src.uid)
            )
        }

        val updatedSource = store.state.value.sources[src.uid] as? RemotePatchBundle ?: return false
        val allowUnsafeDownload = prefs.allowMeteredUpdates.get()
        return updateNow(
            force = true,
            allowUnsafeNetwork = allowUnsafeDownload,
            onPerBundleProgress = { bundle, bytesRead, bytesTotal ->
                if (bundle.uid == updatedSource.uid) onProgress?.invoke(bytesRead, bytesTotal)
            }
        ) { it.uid == updatedSource.uid }
    }

    suspend fun updateTimestamps(src: PatchBundleSource, createdAt: Long?, updatedAt: Long?) {
        if (createdAt == null && updatedAt == null) return

        dispatchAction("Update timestamps (${src.uid})") { state ->
            val currentSource = state.sources[src.uid] ?: return@dispatchAction state
            updateDb(src.uid) {
                it.copy(
                    createdAt = createdAt ?: it.createdAt,
                    updatedAt = updatedAt ?: it.updatedAt
                )
            }

            state.copy(
                sources = state.sources.put(
                    src.uid,
                    currentSource.copy(
                        createdAt = createdAt ?: currentSource.createdAt,
                        updatedAt = updatedAt ?: currentSource.updatedAt
                    )
                )
            )
        }
    }

    suspend fun createLocal(expectedSize: Long? = null, createStream: suspend () -> InputStream) {
        var copyTotal: Long? = expectedSize?.takeIf { it > 0L }
        var copyRead = 0L
        var displayName: String? = null
        enqueueLocalImport()
        localImportMutex.withLock {
            val baseProcessed = localImportBaseSteps()
            try {
                setLocalImportProgress(
                    baseProcessed = baseProcessed,
                    offset = 0,
                    displayName = displayName,
                    phase = BundleImportPhase.Downloading,
                    bytesRead = 0L,
                    bytesTotal = null,
                )

                val tempFile = withContext(Dispatchers.IO) {
                    File.createTempFile("local_bundle", ".jar", app.cacheDir)
                }
                try {
                    val sha256 = MessageDigest.getInstance("SHA-256")
                    withContext(Dispatchers.IO) {
                        tempFile.outputStream().use { output ->
                            createStream().use { input ->
                                if (copyTotal == null) {
                                    copyTotal = when (input) {
                                        is FileInputStream -> runCatching { input.channel.size() }.getOrNull()
                                        else -> runCatching { input.available().takeIf { it > 0 }?.toLong() }.getOrNull()
                                    }
                                }
                                setLocalImportProgress(
                                    baseProcessed = baseProcessed,
                                    offset = 0,
                                    displayName = displayName,
                                    phase = BundleImportPhase.Downloading,
                                    bytesRead = 0L,
                                    bytesTotal = copyTotal,
                                )

                                val buffer = ByteArray(256 * 1024)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read == -1) break
                                    output.write(buffer, 0, read)
                                    sha256.update(buffer, 0, read)
                                    copyRead += read
                                    setLocalImportProgress(
                                        baseProcessed = baseProcessed,
                                        offset = 0,
                                        displayName = displayName,
                                        phase = BundleImportPhase.Downloading,
                                        bytesRead = copyRead,
                                        bytesTotal = copyTotal,
                                    )
                                }
                            }
                        }
                    }
                    val precomputedDigest = sha256.digest()
                    if (copyTotal == null && copyRead > 0L) {
                        copyTotal = copyRead
                    }

                    val manifestName = runCatching {
                        PatchBundle(tempFile.absolutePath).manifestAttributes?.name
                    }.getOrNull()?.takeUnless { it.isNullOrBlank() }

                    val uid = stableLocalUid(manifestName, tempFile, precomputedDigest)
                    val existingProps = dao.getProps(uid)
                    displayName = (manifestName ?: existingProps?.name).orEmpty()

                    val replaceTotal = tempFile.length().takeIf { it > 0L } ?: copyTotal
                    setLocalImportProgress(
                        baseProcessed = baseProcessed,
                        offset = 1,
                        displayName = displayName,
                        phase = BundleImportPhase.Processing,
                        bytesRead = 0L,
                        bytesTotal = replaceTotal,
                    )

                    val entity = createEntity(
                        name = manifestName ?: existingProps?.name.orEmpty(),
                        source = SourceInfo.Local,
                        uid = uid,
                        displayName = existingProps?.displayName
                    )
                    val localBundle = entity.load() as LocalPatchBundle

                    try {
                        val moved = localBundle.replaceFromTempFile(
                            tempFile,
                            totalBytes = replaceTotal
                        ) { read, total ->
                            setLocalImportProgress(
                                baseProcessed = baseProcessed,
                                offset = 1,
                                displayName = displayName,
                                phase = BundleImportPhase.Processing,
                                bytesRead = read,
                                bytesTotal = total,
                            )
                        }
                        if (!moved) {
                            tempFile.inputStream().use { patches ->
                                localBundle.replace(
                                    patches,
                                    totalBytes = replaceTotal
                                ) { read, total ->
                                    setLocalImportProgress(
                                        baseProcessed = baseProcessed,
                                        offset = 1,
                                        displayName = displayName,
                                        phase = BundleImportPhase.Processing,
                                        bytesRead = read,
                                        bytesTotal = total,
                                    )
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.e(tag, "Got exception while importing bundle", e)
                        withContext(Dispatchers.Main) {
                            app.toast(app.getString(R.string.patches_replace_fail, e.simpleMessage()))
                        }

                        withContext(Dispatchers.IO) {
                            runCatching {
                                localBundle.patchesJarFile.setWritable(true, true)
                            }
                            runCatching {
                                localBundle.patchesJarFile.delete()
                            }
                        }
                    }
                } finally {
                    tempFile.delete()
                }
                setLocalImportProgress(
                    baseProcessed = baseProcessed,
                    offset = LOCAL_IMPORT_STEPS - 1,
                    displayName = displayName,
                    phase = BundleImportPhase.Finalizing,
                    bytesRead = 0L,
                    bytesTotal = null,
                )
                dispatchAction("Add bundle") { doReload() }
                setLocalImportProgress(
                    baseProcessed = baseProcessed,
                    offset = LOCAL_IMPORT_STEPS,
                    displayName = displayName,
                    phase = BundleImportPhase.Finalizing,
                    bytesRead = 0L,
                    bytesTotal = null,
                )
            } finally {
                completeLocalImport()
            }
        }
    }

    private fun stableLocalUid(manifestName: String?, file: File, precomputedDigest: ByteArray? = null): Int {
        val digest = precomputedDigest?.let { MessageDigest.getInstance("SHA-256").also { d -> d.update(it) } }
            ?: MessageDigest.getInstance("SHA-256").also { d ->
                val hashedFile = runCatching {
                    file.inputStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            d.update(buffer, 0, read)
                        }
                    }
                }.isSuccess

                if (!hashedFile) {
                    val normalizedName = manifestName?.trim()?.takeUnless(String::isEmpty)
                    if (normalizedName != null) {
                        d.update("local:name".toByteArray(StandardCharsets.UTF_8))
                        d.update(normalizedName.lowercase(Locale.US).toByteArray(StandardCharsets.UTF_8))
                    } else {
                        d.update(file.absolutePath.toByteArray(StandardCharsets.UTF_8))
                    }
                }
            }

        val raw = ByteBuffer.wrap(digest.digest(), 0, 4).order(ByteOrder.BIG_ENDIAN).int
        return if (raw != 0) raw else 1
    }

    suspend fun createRemote(
        url: String,
        searchUpdate: Boolean,
        autoUpdate: Boolean,
        createdAt: Long? = null,
        updatedAt: Long? = null,
        onProgress: PatchBundleDownloadProgress? = null,
    ) =
        dispatchAction("Add bundle ($url)") { state ->
            val normalizedUrl = try {
                normalizeRemoteBundleUrl(url)
            } catch (e: IllegalArgumentException) {
                withContext(Dispatchers.Main) {
                    app.toast(e.message ?: "Invalid bundle URL")
                }
                return@dispatchAction state
            }

            val src = createEntity(
                "",
                SourceInfo.from(normalizedUrl),
                autoUpdate,
                searchUpdate = searchUpdate,
                createdAt = createdAt,
                updatedAt = updatedAt
            ).load() as RemotePatchBundle
            val allowUnsafeDownload = prefs.allowMeteredUpdates.get()
            update(
                src,
                allowUnsafeNetwork = allowUnsafeDownload,
                onPerBundleProgress = { bundle, bytesRead, bytesTotal ->
                    if (bundle.uid == src.uid) onProgress?.invoke(bytesRead, bytesTotal)
                }
            )
            state.copy(sources = state.sources.put(src.uid, src))
        }

    suspend fun createRemoteFromDiscovery(
        bundle: ExternalBundleSnapshot,
        searchUpdate: Boolean,
        autoUpdate: Boolean,
        onProgress: PatchBundleDownloadProgress? = null,
    ) {
        if (bundle.isBundleV3) {
            toast(R.string.patch_bundle_discovery_v3_warning)
            return
        }
        val rawUrl = externalBundleEndpoint(bundle).trim()
        if (rawUrl.isBlank()) {
            toast(R.string.patch_bundle_discovery_error)
            return
        }

        val validatedUrl = try {
            validateDiscoveryBundleUrl(rawUrl)
        } catch (e: IllegalArgumentException) {
            withContext(Dispatchers.Main) { app.toast(e.message ?: "Invalid bundle URL") }
            return
        }

        val metadata = ExternalBundleMetadata(
            bundleId = bundle.bundleId,
            downloadUrl = validatedUrl,
            signatureDownloadUrl = bundle.signatureDownloadUrl,
            version = bundle.version.ifBlank { "unknown" },
            createdAt = bundle.createdAt.takeUnless { it.isBlank() },
            description = bundle.description,
            ownerName = bundle.ownerName.takeIf { it.isNotBlank() },
            repoName = bundle.repoName.takeIf { it.isNotBlank() },
            isPrerelease = bundle.isPrerelease
        )

        val entity = createEntity(
            "",
            SourceInfo.from(externalBundleEndpoint(bundle)),
            autoUpdate,
            searchUpdate = searchUpdate
        )
        ExternalBundleMetadataStore.write(directoryOf(entity.uid), metadata)

        val src = entity.load() as RemotePatchBundle
        dispatchAction("Add bundle (${bundle.bundleId})") { state ->
            state.copy(sources = state.sources.put(src.uid, src))
        }
        withTimeoutOrNull(2_000) {
            sources.first { list -> list.any { it.uid == src.uid } }
        }
        val allowUnsafeDownload = prefs.allowMeteredUpdates.get()
        updateNow(
            allowUnsafeNetwork = allowUnsafeDownload,
            showProgress = false,
            onPerBundleProgress = { bundleSrc, bytesRead, bytesTotal ->
                if (bundleSrc.uid == src.uid) onProgress?.invoke(bytesRead, bytesTotal)
            },
            predicate = { it.uid == src.uid }
        )
    }

    private fun externalBundleEndpoint(bundle: ExternalBundleSnapshot): String {
        val owner = bundle.ownerName.trim()
        val repo = bundle.repoName.trim()
        if (owner.isNotBlank() && repo.isNotBlank()) {
            val prerelease = bundle.isPrerelease
            return "https://revanced-external-bundles.brosssh.com/api/v1/bundle/$owner/$repo/latest?prerelease=$prerelease"
        }
        return externalBundleEndpoint(bundle.bundleId)
    }

    private fun externalBundleEndpoint(bundleId: Int): String =
        "https://revanced-external-bundles.brosssh.com/bundles/id?id=$bundleId"

    private fun validateRemoteBundleUrl(input: String): String {
        val trimmed = input.trim()
        val parsed = try {
            Url(trimmed)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid bundle URL: ${e.message ?: trimmed}")
        }

        val pathNoQuery = parsed.encodedPath.substringBefore('?').substringBefore('#')
        val isJson = pathNoQuery.endsWith(".json", ignoreCase = true)
        val host = parsed.host.lowercase(Locale.US)
        val isExternalBundlesHost = host == "revanced-external-bundles.brosssh.com" ||
            host == "revanced-external-bundles-dev.brosssh.com"
        val isExternalBundlesEndpoint = isExternalBundlesHost &&
            (pathNoQuery.startsWith("/api/v1/bundle/") || pathNoQuery.startsWith("/bundles/id"))
        if (!isJson && !isExternalBundlesEndpoint) {
            throw IllegalArgumentException(
                "Patch bundle URL must point to a .json file or a supported external bundles API URL."
            )
        }

        val query = parsed.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
        val scheme = if (parsed.protocol.name.equals("https", ignoreCase = true)) "https" else "http"
        return "$scheme://${parsed.host}${parsed.encodedPath}$query"
    }

    private fun validateDiscoveryBundleUrl(input: String): String {
        val trimmed = input.trim()
        val parsed = try {
            Url(trimmed)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid bundle URL: ${e.message ?: trimmed}")
        }

        val host = parsed.host.lowercase(Locale.US)
        val isExternalBundlesHost = host == "revanced-external-bundles.brosssh.com" ||
            host == "revanced-external-bundles-dev.brosssh.com"
        if (!isExternalBundlesHost) {
            throw IllegalArgumentException(
                "Patch bundle URL must point to a supported external bundles API URL."
            )
        }

        val query = parsed.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
        val scheme = if (parsed.protocol.name.equals("https", ignoreCase = true)) "https" else "http"
        return "$scheme://${parsed.host}${parsed.encodedPath}$query"
    }

    private fun normalizeRemoteBundleUrl(input: String): String {
        val trimmed = input.trim()
        val parsed = try {
            Url(trimmed)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid bundle URL: ${e.message ?: trimmed}")
        }

        var host = parsed.host
        var pathSegments = parsed.encodedPath.trim('/').split('/').filter { it.isNotBlank() }

        // Allow GitHub pull request URLs untouched (handled separately by Source.from / GitHubPullRequestBundle).
        if (host.equals("github.com", ignoreCase = true) &&
            pathSegments.size >= 3 &&
            pathSegments[2] == "pull"
        ) {
            val scheme = if (parsed.protocol.name.equals("https", ignoreCase = true)) "https" else "http"
            val basePath = "/" + pathSegments.joinToString("/")
            val query = parsed.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
            return "$scheme://$host$basePath$query"
        }

        if (host.equals("github.com", ignoreCase = true) && pathSegments.size >= 4 && pathSegments[2] == "blob") {
            // https://github.com/{owner}/{repo}/blob/{branch}/path -> raw.githubusercontent.com/{owner}/{repo}/{branch}/path
            val owner = pathSegments[0]
            val repo = pathSegments[1]
            val branchAndPath = pathSegments.drop(3)
            host = "raw.githubusercontent.com"
            pathSegments = listOf(owner, repo) + branchAndPath
        }

        val normalizedPath = "/" + pathSegments.joinToString("/")
        val pathNoQuery = normalizedPath.substringBefore('?').substringBefore('#')
        if (host.equals("revanced-external-bundles.brosssh.com", ignoreCase = true) ||
            host.equals("revanced-external-bundles-dev.brosssh.com", ignoreCase = true)
        ) {
            if (pathNoQuery.startsWith("/bundles/id") || pathNoQuery.startsWith("/api/v1/bundle/")) {
                val query = parsed.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
                return "https://$host$normalizedPath$query"
            }
        }
        val isJson = pathNoQuery.endsWith(".json", ignoreCase = true)
        if (!isJson) {
            throw IllegalArgumentException(
                "Patch bundle URL must point to a .json file or a supported external bundles API URL."
            )
        }

        val query = parsed.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
        return "https://$host$normalizedPath$query"
    }

    suspend fun reloadApiBundles() = dispatchAction("Reload API bundles") {
        this@PatchBundleRepository.sources.first().filterIsInstance<APIPatchBundle>().forEach {
            with(it) { deleteLocalFile() }
            updateDb(it.uid) { it.copy(versionHash = null) }
        }

        doReload()
    }

    suspend fun RemotePatchBundle.setAutoUpdate(value: Boolean) {
        dispatchAction("Set auto update ($name, $value)") { state ->
            updateDb(uid) { it.copy(autoUpdate = value) }
            val newSrc = (state.sources[uid] as? RemotePatchBundle)?.copy(autoUpdate = value)
                ?: return@dispatchAction state

            state.copy(sources = state.sources.put(uid, newSrc))
        }

        if (value) {
            manualUpdateInfoFlow.update { map -> map - uid }
        } else {
            checkManualUpdates(uid)
        }
    }

    suspend fun RemotePatchBundle.setSearchUpdate(value: Boolean) {
        dispatchAction("Set search update ($name, $value)") { state ->
            updateDb(uid) { it.copy(searchUpdate = value) }
            val newSrc = (state.sources[uid] as? RemotePatchBundle)?.copy(searchUpdate = value)
                ?: return@dispatchAction state

            state.copy(sources = state.sources.put(uid, newSrc))
        }
    }

    private suspend fun updateLastNotifiedVersion(uid: Int, version: String?) {
        dispatchAction("Set last notified version ($uid)") { state ->
            updateDb(uid) { it.copy(lastNotifiedVersion = version) }
            val src = (state.sources[uid] as? RemotePatchBundle) ?: return@dispatchAction state
            val updated = src.copy(lastNotifiedVersion = version)
            state.copy(sources = state.sources.put(uid, updated))
        }
    }

    suspend fun update(
        vararg sources: RemotePatchBundle,
        showToast: Boolean = false,
        allowUnsafeNetwork: Boolean = false,
        onPerBundleProgress: ((bundle: RemotePatchBundle, bytesRead: Long, bytesTotal: Long?) -> Unit)? = null,
    ) {
        val uids = sources.map { it.uid }.toSet()
        store.dispatch(
            Update(
                showToast = showToast,
                allowUnsafeNetwork = allowUnsafeNetwork,
                onPerBundleProgress = onPerBundleProgress,
            ) { it.uid in uids }
        )
    }

    suspend fun updateNow(
        force: Boolean = false,
        allowUnsafeNetwork: Boolean = false,
        showProgress: Boolean = true,
        onPerBundleProgress: ((bundle: RemotePatchBundle, bytesRead: Long, bytesTotal: Long?) -> Unit)? = null,
        onBundleUpdated: ((bundle: RemotePatchBundle, updatedName: String?) -> Unit)? = null,
        predicate: (bundle: RemotePatchBundle) -> Boolean = { true },
    ): Boolean {
        while (true) {
            val activeJob = updateJobMutex.withLock {
                if (updateJob?.isActive == true) {
                    updateJob
                } else {
                    updateJob = coroutineContext.job
                    null
                }
            }
            if (activeJob == null) break
            activeJob.join()
        }

        return try {
            performRemoteUpdate(
                force = force,
                showToast = false,
                allowUnsafeNetwork = allowUnsafeNetwork,
                showProgress = showProgress,
                onPerBundleProgress = onPerBundleProgress,
                onBundleUpdated = onBundleUpdated,
                predicate = predicate
            )
        } finally {
            updateJobMutex.withLock {
                if (updateJob == coroutineContext.job) {
                    updateJob = null
                }
            }
            val next = drainPendingUpdateRequests()
            if (next != null) {
                startRemoteUpdateJob(
                    force = next.force,
                    showToast = next.showToast,
                    allowUnsafeNetwork = next.allowUnsafeNetwork,
                    showProgress = next.showProgress,
                    onPerBundleProgress = next.onPerBundleProgress,
                    predicate = next.predicate
                )
            }
        }
    }

    suspend fun redownloadRemoteBundles() = store.dispatch(Update(force = true))

    /**
     * Updates all bundles that should be automatically updated.
     */
    suspend fun updateCheck() {
        store.dispatch(Update { it.autoUpdate })
        checkManualUpdates()
    }

    suspend fun fetchUpdatesAndNotify(
        context: Context,
        predicate: (bundle: RemotePatchBundle) -> Boolean = { true },
        onNotification: (bundle: RemotePatchBundle, bundleVersion: String) -> Boolean
    ): Boolean = coroutineScope {
        val allowMeteredUpdates = prefs.allowMeteredUpdates.get()
        if (!allowMeteredUpdates && !networkInfo.isSafe()) {
            Log.d(tag, "Skipping background update check because the network is down or metered.")
            return@coroutineScope false
        }

        var notifiedAny = false
        sources.first()
            .filterIsInstance<RemotePatchBundle>()
            .forEach { bundle ->
                if (!predicate(bundle)) return@forEach
                if (!bundle.searchUpdate || !bundle.enabled) return@forEach
                if (bundle.state !is PatchBundleSource.State.Available) return@forEach

                val info = runCatching { bundle.fetchLatestReleaseInfo() }.getOrElse { error ->
                    Log.e(tag, "Failed to check update for ${bundle.name}", error)
                    return@forEach
                }

                val latestSignature = normalizeVersionForCompare(info.version) ?: return@forEach
                val installedSignature = normalizeVersionForCompare(bundle.installedVersionSignature)
                val manifestSignature = normalizeVersionForCompare(bundle.version)
                val currentSignature = installedSignature ?: manifestSignature ?: return@forEach
                if (currentSignature == latestSignature) return@forEach

                val versionLabel = latestSignature
                if (normalizeVersionForCompare(bundle.lastNotifiedVersion) == versionLabel) return@forEach

                val notified = onNotification(bundle, info.version)
                if (notified) {
                    updateLastNotifiedVersion(bundle.uid, versionLabel)
                    notifiedAny = true
                }
            }
        notifiedAny
    }

    suspend fun checkManualUpdates(vararg bundleUids: Int) =
        store.dispatch(ManualUpdateCheck(bundleUids.toSet().takeIf { it.isNotEmpty() }))

    suspend fun reorderBundles(prioritizedUids: List<Int>) = dispatchAction("Reorder bundles") { state ->
        val currentOrder = state.sources.keys.toList()
        if (currentOrder.isEmpty()) return@dispatchAction state

        val sanitized = LinkedHashSet(prioritizedUids.filter { it in currentOrder })
        if (sanitized.isEmpty()) return@dispatchAction state

        val finalOrder = buildList {
            addAll(sanitized)
            currentOrder.filterNotTo(this) { it in sanitized }
        }

        if (finalOrder == currentOrder) {
            return@dispatchAction state
        }

        finalOrder.forEachIndexed { index, uid ->
            dao.updateSortOrder(uid, index)
        }
        val defaultIndex = finalOrder.indexOf(DEFAULT_SOURCE_UID)
        if (defaultIndex != -1) {
            prefs.officialBundleSortOrder.update(defaultIndex)
        }

        doReload()
    }

    private inner class Update(
        private val force: Boolean = false,
        private val showToast: Boolean = false,
        private val allowUnsafeNetwork: Boolean = false,
        private val showProgress: Boolean = true,
        private val onPerBundleProgress: ((bundle: RemotePatchBundle, bytesRead: Long, bytesTotal: Long?) -> Unit)? = null,
        private val predicate: (bundle: RemotePatchBundle) -> Boolean = { true },
    ) : Action<State> {
        override fun toString() = if (force) "Redownload remote bundles" else "Update check"

        override suspend fun ActionContext.execute(
            current: State
        ): State {
            startRemoteUpdateJob(
                force = force,
                showToast = showToast,
                allowUnsafeNetwork = allowUnsafeNetwork,
                showProgress = showProgress,
                onPerBundleProgress = onPerBundleProgress,
                predicate = predicate
            )
            return current
        }

        override suspend fun catch(exception: Exception) {
            Log.e(tag, "Failed to update patches", exception)
            toast(R.string.patches_download_fail, exception.simpleMessage())
        }
    }

    private suspend fun startRemoteUpdateJob(
        force: Boolean,
        showToast: Boolean,
        allowUnsafeNetwork: Boolean,
        showProgress: Boolean,
        onPerBundleProgress: ((bundle: RemotePatchBundle, bytesRead: Long, bytesTotal: Long?) -> Unit)?,
        predicate: (bundle: RemotePatchBundle) -> Boolean,
    ) {
        val request = UpdateRequest(
            force,
            showToast,
            allowUnsafeNetwork,
            showProgress,
            onPerBundleProgress,
            predicate
        )
        var queued = false
        updateJobMutex.withLock {
            if (updateJob?.isActive == true) {
                queued = true
            } else {
                updateJob = scope.launch {
                    try {
                        performRemoteUpdate(
                            force = request.force,
                            showToast = request.showToast,
                            allowUnsafeNetwork = request.allowUnsafeNetwork,
                            showProgress = request.showProgress,
                            onPerBundleProgress = request.onPerBundleProgress,
                            onBundleUpdated = null,
                            predicate = request.predicate
                        )
                    } finally {
                        updateJobMutex.withLock {
                            updateJob = null
                        }
                        val next = drainPendingUpdateRequests()
                        if (next != null) {
                            startRemoteUpdateJob(
                                force = next.force,
                                showToast = next.showToast,
                                allowUnsafeNetwork = next.allowUnsafeNetwork,
                                showProgress = next.showProgress,
                                onPerBundleProgress = next.onPerBundleProgress,
                                predicate = next.predicate
                            )
                        }
                    }
                }
            }
        }
        if (queued) {
            enqueueUpdateRequest(request)
        }
    }

    private suspend fun performRemoteUpdate(
        force: Boolean,
        showToast: Boolean,
        allowUnsafeNetwork: Boolean,
        showProgress: Boolean,
        onPerBundleProgress: ((bundle: RemotePatchBundle, bytesRead: Long, bytesTotal: Long?) -> Unit)?,
        onBundleUpdated: ((bundle: RemotePatchBundle, updatedName: String?) -> Unit)?,
        predicate: (bundle: RemotePatchBundle) -> Boolean,
    ): Boolean = coroutineScope {
        try {
            if (showProgress) {
                cancelBundleUpdateAutoClear()
            }
            val allowMeteredUpdates = prefs.allowMeteredUpdates.get()
            if (!allowUnsafeNetwork && !allowMeteredUpdates && !networkInfo.isSafe()) {
                Log.d(tag, "Skipping update check because the network is down or metered.")
                if (showProgress) {
                    cancelBundleUpdateAutoClear()
                    bundleUpdateProgressFlow.value = null
                }
                return@coroutineScope false
            }

            val targets = store.state.value.sources.values
                .filterIsInstance<RemotePatchBundle>()
                .filter { predicate(it) }

            if (targets.isEmpty()) {
                if (showToast) toast(R.string.patches_update_unavailable)
                if (showProgress) {
                    cancelBundleUpdateAutoClear()
                    bundleUpdateProgressFlow.value = null
                }
                return@coroutineScope false
            }

            markActiveUpdateUids(targets.map(RemotePatchBundle::uid).toSet())

            if (showProgress) {
                bundleUpdateProgressFlow.value = BundleUpdateProgress(
                    total = currentUpdateTotal(targets.size),
                    completed = 0,
                    phase = BundleUpdatePhase.Checking,
                )
            }

            val updated: Map<RemotePatchBundle, PatchBundleDownloadResult> = try {
                val results = LinkedHashMap<RemotePatchBundle, PatchBundleDownloadResult>()
                var completed = 0

                for (bundle in targets) {
                    val total = currentUpdateTotal(targets.size)
                    if (total <= 0) {
                        if (showProgress) {
                            bundleUpdateProgressFlow.value = null
                        }
                        return@coroutineScope false
                    }
                    if (isRemoteUpdateCancelled(bundle.uid)) {
                        continue
                    }

                    Log.d(tag, "Updating patch bundle: ${bundle.name}")

                    if (showProgress) {
                        bundleUpdateProgressFlow.value = BundleUpdateProgress(
                            total = total,
                            completed = completed.coerceAtMost(total),
                            currentBundleName = progressLabelFor(bundle),
                            phase = BundleUpdatePhase.Checking,
                            bytesRead = 0L,
                            bytesTotal = null,
                        )
                    }
                    onPerBundleProgress?.invoke(bundle, 0L, null)

                    val onProgress: PatchBundleDownloadProgress = { bytesRead, bytesTotal ->
                        if (isRemoteUpdateCancelled(bundle.uid)) {
                            throw BundleUpdateCancelled(bundle.uid)
                        }
                        if (showProgress) {
                            bundleUpdateProgressFlow.update { progress ->
                                progress?.copy(
                                    currentBundleName = progressLabelFor(bundle),
                                    phase = BundleUpdatePhase.Downloading,
                                    bytesRead = bytesRead,
                                    bytesTotal = bytesTotal,
                                )
                            }
                        }
                        onPerBundleProgress?.invoke(bundle, bytesRead, bytesTotal)
                    }

                    val result = try {
                        if (force) bundle.downloadLatest(onProgress) else bundle.update(onProgress)
                    } catch (e: BundleUpdateCancelled) {
                        continue
                    }

                    val downloadedName = runCatching {
                        PatchBundle(bundle.patchesJarFile.absolutePath).manifestAttributes?.name
                    }.getOrNull()?.trim().takeUnless { it.isNullOrBlank() }
                    if (downloadedName != null && showProgress) {
                        bundleUpdateProgressFlow.update { progress ->
                            progress?.copy(currentBundleName = downloadedName)
                        }
                    }

                    val nextTotal = currentUpdateTotal(targets.size)
                    completed = (completed + 1).coerceAtMost(nextTotal)
                    if (showProgress) {
                        bundleUpdateProgressFlow.update { progress ->
                            progress?.copy(
                                completed = completed,
                                currentBundleName = downloadedName ?: progressLabelFor(bundle),
                                phase = BundleUpdatePhase.Finalizing,
                                bytesRead = 0L,
                                bytesTotal = null,
                            )
                        }
                    }

                    if (result != null) {
                        results[bundle] = result
                        runCatching { recordChangelog(bundle.uid, bundle.fetchLatestReleaseInfo()) }
                        onBundleUpdated?.invoke(bundle, downloadedName)
                    }
                }

                results
            } catch (e: Exception) {
                Log.e(tag, "Failed to update patches", e)
                toast(R.string.patches_download_fail, e.simpleMessage())
                emptyMap()
            } finally {
                if (showProgress) {
                    scheduleBundleUpdateProgressClear()
                }
            }

            if (updated.isEmpty()) {
                if (showToast) toast(R.string.patches_update_unavailable)
                return@coroutineScope false
            }

            dispatchAction("Apply updated bundles") {
                updated.forEach { (src, downloadResult) ->
                    if (dao.getProps(src.uid) == null) return@forEach
                    val rawName = runCatching {
                        PatchBundle(src.patchesJarFile.absolutePath).manifestAttributes?.name
                    }.getOrNull()?.trim().takeUnless { it.isNullOrBlank() } ?: src.name
                    val name = if (src.uid == DEFAULT_SOURCE_UID) rawName else ensureUniqueName(rawName, src.uid)
                    val now = System.currentTimeMillis()

                    updateDb(src.uid) {
                        it.copy(
                            versionHash = downloadResult.versionSignature,
                            name = name,
                            createdAt = downloadResult.assetCreatedAtMillis ?: it.createdAt,
                            updatedAt = now
                        )
                    }
                }

                doReload()
            }

            val updatedUids = updated.keys.map(RemotePatchBundle::uid).toSet()
            manualUpdateInfoFlow.update { currentMap -> currentMap - updatedUids }
            if (showToast) toast(R.string.patches_update_success)
            true
        } finally {
            clearActiveUpdateState()
        }
    }

    private class BundleUpdateCancelled(val uid: Int) : Exception()

    private inner class ManualUpdateCheck(
        private val targetUids: Set<Int>? = null
    ) : Action<State> {
        override suspend fun ActionContext.execute(current: State) = coroutineScope {
            val manualBundles = current.sources.values
                .filterIsInstance<RemotePatchBundle>()
                .filter {
                    targetUids?.contains(it.uid) ?: !it.autoUpdate
                }

            if (manualBundles.isEmpty()) {
                if (targetUids != null) {
                    manualUpdateInfoFlow.update { it - targetUids }
                } else {
                    manualUpdateInfoFlow.update { map ->
                        map.filterKeys { uid ->
                            val bundle = current.sources[uid] as? RemotePatchBundle
                            bundle != null && !bundle.autoUpdate
                        }
                    }
                }
                return@coroutineScope current
            }

            val allowMeteredUpdates = prefs.allowMeteredUpdates.get()
            if (!allowMeteredUpdates && !networkInfo.isSafe()) {
                Log.d(tag, "Skipping manual update check because the network is down or metered.")
                return@coroutineScope current
            }

            val results = manualBundles
                .map { bundle ->
                    async {
                        try {
                            val info = bundle.fetchLatestReleaseInfo()
                            val latestSignature = normalizeVersionForCompare(info.version)
                                ?: return@async bundle.uid to null
                            val installedSignature = normalizeVersionForCompare(bundle.installedVersionSignature)
                                ?: normalizeVersionForCompare(bundle.version)
                                ?: return@async bundle.uid to null
                            if (installedSignature == latestSignature) return@async bundle.uid to null
                            bundle.uid to ManualBundleUpdateInfo(
                                latestVersion = info.version,
                                pageUrl = info.pageUrl
                            )
                        } catch (t: Throwable) {
                            Log.e(tag, "Failed to check manual update for ${bundle.name}", t)
                            bundle.uid to null
                        }
                    }
                }
                .awaitAll()

            manualUpdateInfoFlow.update { map ->
                val next = map.toMutableMap()
                val manualUids = manualBundles.map(RemotePatchBundle::uid).toSet()
                next.keys.retainAll(manualUids)
                results.forEach { (uid, info) ->
                    if (info == null) next.remove(uid) else next[uid] = info
                }
                next
            }

            current
        }
    }

    private fun suggestedVersionsForRevanced(patches: Set<RevancedPatch<*>>): Map<String, String?> {
        val versionCounts = patches.revancedMostCommonCompatibleVersions(countUnusedPatches = true)

        return versionCounts.mapValues { (_, versions) ->
            if (versions.keys.size < 2) {
                return@mapValues versions.keys.firstOrNull()
            }

            var currentHighestPatchCount = -1
            versions.entries.last { (_, patchCount) ->
                if (patchCount >= currentHighestPatchCount) {
                    currentHighestPatchCount = patchCount
                    true
                } else false
            }.key
        }
    }

    private fun suggestedVersionsForMorphe(patches: Iterable<PatchInfo>): Map<String, String?> {
        val versionCounts = mutableMapOf<String, MutableMap<String, Int>>()

        patches.forEach { patch ->
            patch.compatiblePackages?.forEach { pkg ->
                val versions = pkg.versions ?: return@forEach
                val counts = versionCounts.getOrPut(pkg.packageName) { linkedMapOf() }
                versions.sorted().forEach { version ->
                    counts[version] = (counts[version] ?: 0) + 1
                }
            }
        }

        return versionCounts.mapValues { (_, versions) ->
            if (versions.isEmpty()) return@mapValues null
            if (versions.keys.size < 2) return@mapValues versions.keys.firstOrNull()

            var currentHighestPatchCount = -1
            versions.entries.last { (_, patchCount) ->
                if (patchCount >= currentHighestPatchCount) {
                    currentHighestPatchCount = patchCount
                    true
                } else false
            }.key
        }
    }

    data class State(
        val sources: PersistentMap<Int, PatchBundleSource> = persistentMapOf(),
        val info: PersistentMap<Int, PatchBundleInfo.Global> = persistentMapOf()
    )

    data class BundleUpdateProgress(
        val total: Int,
        val completed: Int,
        val currentBundleName: String? = null,
        val phase: BundleUpdatePhase = BundleUpdatePhase.Checking,
        val bytesRead: Long = 0L,
        val bytesTotal: Long? = null,
    )

    enum class BundleUpdatePhase {
        Checking,
        Downloading,
        Finalizing,
    }

    data class ImportProgress(
        val processed: Int,
        val total: Int,
        val currentBundleName: String? = null,
        val phase: BundleImportPhase = BundleImportPhase.Processing,
        val bytesRead: Long = 0L,
        val bytesTotal: Long? = null,
        val isStepBased: Boolean = false,
    ) {
        val ratio: Float?
            get() {
                val safeTotal = total.coerceAtLeast(1)
                val clampedProcessed = processed.coerceIn(0, safeTotal)
                if (clampedProcessed >= safeTotal) return 1f

                val totalBytes = bytesTotal?.takeIf { it > 0L }
                    ?: return (clampedProcessed.toFloat() / safeTotal).coerceIn(0f, 1f)

                val perStepFraction = (bytesRead.toFloat() / totalBytes).coerceIn(0f, 1f)
                return ((clampedProcessed + perStepFraction) / safeTotal).coerceIn(0f, 1f)
            }
    }

    enum class BundleImportPhase {
        Processing,
        Downloading,
        Finalizing,
    }

    data class ManualBundleUpdateInfo(
        val latestVersion: String?,
        val pageUrl: String?,
    )

    private fun normalizeVersionForCompare(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val noPrefix = trimmed.removePrefix("v").removePrefix("V")
        val noBuild = noPrefix.substringBefore('+')
        return noBuild.ifBlank { null }
    }

    private companion object {
        const val DEFAULT_SOURCE_UID = 0
        const val LOCAL_IMPORT_STEPS = 2
        fun defaultSource() = PatchBundleEntity(
            uid = DEFAULT_SOURCE_UID,
            name = "",
            displayName = null,
            versionHash = null,
            source = Source.API,
            autoUpdate = false,
            searchUpdate = true,
            lastNotifiedVersion = null,
            enabled = true,
            sortOrder = 0,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
    }

    private data class DiscoveryImportRequest(
        val key: String,
        val bundle: ExternalBundleSnapshot,
        val searchUpdate: Boolean,
        val autoUpdate: Boolean
    )

    data class DiscoveryImportProgress(
        val bytesRead: Long,
        val bytesTotal: Long?,
        val status: DiscoveryImportStatus
    )

    enum class DiscoveryImportStatus {
        Queued,
        Importing
    }

    enum class DiscoveryImportEnqueueResult {
        Started,
        Queued,
        Duplicate
    }

    private fun updateDiscoveryQueuedLocked() {
        discoveryImportQueuedFlow.value = discoveryImportQueuedKeys.toSet()
    }

    private suspend fun runDiscoveryImportQueue() {
        var completed = 0
        while (true) {
            val request = discoveryImportMutex.withLock {
                val next = discoveryImportQueue.removeFirstOrNull()
                if (next != null) {
                    discoveryImportQueuedKeys.remove(next.key)
                }
                updateDiscoveryQueuedLocked()
                next
            } ?: break

            val bundleKey = request.key
            val label = discoveryImportLabel(request.bundle)
            discoveryImportProgressFlow.update { current ->
                current + (bundleKey to DiscoveryImportProgress(0L, null, DiscoveryImportStatus.Importing))
            }
            updateDiscoveryImportBanner(
                completed = completed,
                total = discoveryImportTotal(completed),
                label = label,
                phase = BundleImportPhase.Processing,
                bytesRead = 0L,
                bytesTotal = null
            )
            try {
                withContext(NonCancellable) {
                    createRemoteFromDiscovery(
                        bundle = request.bundle,
                        searchUpdate = request.searchUpdate,
                        autoUpdate = request.autoUpdate,
                        onProgress = { bytesRead, bytesTotal ->
                            discoveryImportProgressFlow.update { current ->
                                if (!current.containsKey(bundleKey)) current
                                else current + (bundleKey to DiscoveryImportProgress(bytesRead, bytesTotal, DiscoveryImportStatus.Importing))
                            }
                            updateDiscoveryImportBanner(
                                completed = completed,
                                total = discoveryImportTotal(completed),
                                label = label,
                                phase = BundleImportPhase.Downloading,
                                bytesRead = bytesRead,
                                bytesTotal = bytesTotal
                            )
                        }
                    )
                }
            } catch (e: CancellationException) {
                Log.w(tag, "Discovery import cancelled for $label", e)
            } catch (e: Exception) {
                toast(R.string.patches_download_fail, e.simpleMessage())
            } finally {
                discoveryImportProgressFlow.update { current -> current - bundleKey }
                completed += 1
                val total = discoveryImportTotal(completed - 1)
                if (discoveryImportQueue.isEmpty()) {
                    updateDiscoveryImportBanner(
                        completed = completed,
                        total = total.coerceAtLeast(completed),
                        label = label,
                        phase = BundleImportPhase.Finalizing,
                        bytesRead = 0L,
                        bytesTotal = null
                    )
                }
            }
        }
    }

    private fun discoveryImportTotal(completed: Int): Int {
        val queued = discoveryImportQueuedFlow.value.size
        return (completed + queued + 1).coerceAtLeast(1)
    }

    private fun updateDiscoveryImportBanner(
        completed: Int,
        total: Int,
        label: String,
        phase: BundleImportPhase,
        bytesRead: Long,
        bytesTotal: Long?
    ) {
        setBundleImportProgress(
            ImportProgress(
                processed = completed.coerceAtMost(total),
                total = total.coerceAtLeast(1),
                currentBundleName = label,
                phase = phase,
                bytesRead = bytesRead,
                bytesTotal = bytesTotal,
            )
        )
    }

    private fun discoveryImportLabel(bundle: ExternalBundleSnapshot): String {
        val owner = bundle.ownerName.trim()
        val repo = bundle.repoName.trim()
        return listOf(owner, repo).filter { it.isNotBlank() }.joinToString("/").ifBlank {
            bundle.sourceUrl
        }
    }
}
