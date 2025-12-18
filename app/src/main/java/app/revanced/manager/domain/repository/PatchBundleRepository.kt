package app.revanced.manager.domain.repository

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import app.revanced.library.mostCommonCompatibleVersions
import app.revanced.patcher.patch.Patch
import app.universal.revanced.manager.R
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
import app.revanced.manager.domain.bundles.GitHubPullRequestBundle
import app.revanced.manager.domain.bundles.JsonPatchBundle
import app.revanced.manager.data.room.bundles.Source as SourceInfo
import app.revanced.manager.domain.bundles.LocalPatchBundle
import app.revanced.manager.domain.bundles.PatchBundleDownloadProgress
import app.revanced.manager.domain.bundles.PatchBundleDownloadResult
import app.revanced.manager.domain.bundles.RemotePatchBundle
import app.revanced.manager.domain.bundles.PatchBundleSource
import app.revanced.manager.domain.bundles.PatchBundleSource.Extensions.isDefault
import app.revanced.manager.domain.manager.PreferencesManager
import app.revanced.manager.patcher.patch.PatchInfo
import app.revanced.manager.patcher.patch.PatchBundle
import app.revanced.manager.patcher.patch.PatchBundleInfo
import app.revanced.manager.util.PatchSelection
import app.revanced.manager.util.simpleMessage
import app.revanced.manager.util.tag
import app.revanced.manager.util.toast
import kotlinx.collections.immutable.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
import kotlinx.coroutines.withContext
import java.io.File
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
    val bundleInfoFlow = store.state.map { it.info }

    fun scopedBundleInfoFlow(packageName: String, version: String?) = bundleInfoFlow.map {
        it.map { (_, bundleInfo) ->
            bundleInfo.forPackage(
                packageName,
                version
            )
        }
    }

    val patchCountsFlow = bundleInfoFlow.map { it.mapValues { (_, info) -> info.patches.size } }

    val suggestedVersions = bundleInfoFlow.map {
        val allPatches =
            it.values.flatMap { bundle -> bundle.patches.map(PatchInfo::toPatcherPatch) }.toSet()

        suggestedVersionsFor(allPatches)
    }

    val suggestedVersionsByBundle = bundleInfoFlow.map { bundleInfos ->
        bundleInfos.mapValues { (_, info) ->
            val patches = info.patches.map(PatchInfo::toPatcherPatch).toSet()
            suggestedVersionsFor(patches)
        }
    }

    private val manualUpdateInfoFlow = MutableStateFlow<Map<Int, ManualBundleUpdateInfo>>(emptyMap())
    val manualUpdateInfo: StateFlow<Map<Int, ManualBundleUpdateInfo>> = manualUpdateInfoFlow.asStateFlow()

    private val bundleUpdateProgressFlow = MutableStateFlow<BundleUpdateProgress?>(null)
    val bundleUpdateProgress: StateFlow<BundleUpdateProgress?> = bundleUpdateProgressFlow.asStateFlow()

    private val bundleImportProgressFlow = MutableStateFlow<ImportProgress?>(null)
    val bundleImportProgress: StateFlow<ImportProgress?> = bundleImportProgressFlow.asStateFlow()

    private var bundleImportAutoClearJob: Job? = null

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
            delay(1_000)
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

    private suspend fun loadMetadata(sources: Map<Int, PatchBundleSource>): Map<Int, PatchBundleInfo.Global> {
        // Map bundles -> sources
        val map = sources.mapNotNull { (_, src) ->
            (src.patchBundle ?: return@mapNotNull null) to src
        }.toMap()

        if (map.isEmpty()) return emptyMap()

        val failures = mutableListOf<Pair<Int, Throwable>>()

        val metadata = map.mapNotNull { (bundle, src) ->
            try {
                src.uid to PatchBundleInfo.Global(
                    src.displayTitle,
                    bundle.manifestAttributes?.version,
                    src.uid,
                    PatchBundle.Loader.metadata(bundle)
                )
            } catch (error: Throwable) {
                failures += src.uid to error
                Log.e(tag, "Failed to load bundle ${src.name}", error)
                null
            }
        }.toMap()

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
            is SourceInfo.Local -> LocalPatchBundle(actualName, uid, normalizedDisplayName, createdAt, updatedAt, null, dir)
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
            )
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
                autoUpdate
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

    private suspend fun ensureUniqueName(requestedName: String?): String {
        val trimmed = requestedName?.trim().orEmpty()
        if (trimmed.isEmpty()) return trimmed

        val existing = dao.all().map { it.name.lowercase(Locale.US) }.toSet()
        if (trimmed.lowercase(Locale.US) !in existing) return trimmed

        var suffix = 2
        var candidate: String
        do {
            candidate = "$trimmed ($suffix)"
            suffix += 1
        } while (candidate.lowercase(Locale.US) in existing)
        return candidate
    }

    private suspend fun createEntity(
        name: String,
        source: Source,
        autoUpdate: Boolean = false,
        displayName: String? = null,
        uid: Int? = null,
        sortOrder: Int? = null,
        createdAt: Long? = null,
        updatedAt: Long? = null
    ): PatchBundleEntity {
        val resolvedUid = uid ?: generateUid()
        val existingProps = dao.getProps(resolvedUid)
        val normalizedDisplayName = displayName?.takeUnless { it.isBlank() }
            ?: existingProps?.displayName?.takeUnless { it.isBlank() }
            ?: if (resolvedUid == DEFAULT_SOURCE_UID) "Official ReVanced Patches" else null
        val normalizedName = ensureUniqueName(name)
        val assignedSortOrder = when {
            sortOrder != null -> sortOrder
            else -> existingProps?.sortOrder ?: nextSortOrder()
        }
        val now = System.currentTimeMillis()
        val resolvedCreatedAt = createdAt ?: existingProps?.createdAt ?: now
        val resolvedUpdatedAt = updatedAt ?: now
        val entity = PatchBundleEntity(
            uid = resolvedUid,
            name = normalizedName,
            displayName = normalizedDisplayName,
            versionHash = null,
            source = source,
            autoUpdate = autoUpdate,
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

    suspend fun createLocal(createStream: suspend () -> InputStream) = dispatchAction("Add bundle") {
        val tempFile = withContext(Dispatchers.IO) {
            File.createTempFile("local_bundle", ".jar", app.cacheDir)
        }
        try {
            withContext(Dispatchers.IO) {
                tempFile.outputStream().use { output ->
                    createStream().use { input -> input.copyTo(output) }
                }
            }

            val manifestName = runCatching {
                PatchBundle(tempFile.absolutePath).manifestAttributes?.name
            }.getOrNull()?.takeUnless { it.isNullOrBlank() }

            val uid = stableLocalUid(manifestName, tempFile)
            val existingProps = dao.getProps(uid)
            val entity = createEntity(
                name = manifestName ?: existingProps?.name.orEmpty(),
                source = SourceInfo.Local,
                uid = uid,
                displayName = existingProps?.displayName
            )
            val localBundle = entity.load() as LocalPatchBundle

            with(localBundle) {
                try {
                    tempFile.inputStream().use { patches -> replace(patches) }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(tag, "Got exception while importing bundle", e)
                    withContext(Dispatchers.Main) {
                        app.toast(app.getString(R.string.patches_replace_fail, e.simpleMessage()))
                    }

                    deleteLocalFile()
                }
            }
        } finally {
            tempFile.delete()
        }

        doReload()
    }

    private fun stableLocalUid(manifestName: String?, file: File): Int {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashedFile = runCatching {
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    digest.update(buffer, 0, read)
                }
            }
        }.isSuccess

        if (!hashedFile) {
            val normalizedName = manifestName?.trim()?.takeUnless(String::isEmpty)
            if (normalizedName != null) {
                digest.update("local:name".toByteArray(StandardCharsets.UTF_8))
                digest.update(normalizedName.lowercase(Locale.US).toByteArray(StandardCharsets.UTF_8))
            } else {
                digest.update(file.absolutePath.toByteArray(StandardCharsets.UTF_8))
            }
        }

        val raw = ByteBuffer.wrap(digest.digest(), 0, 4).order(ByteOrder.BIG_ENDIAN).int
        return if (raw != 0) raw else 1
    }

    suspend fun createRemote(
        url: String,
        autoUpdate: Boolean,
        createdAt: Long? = null,
        updatedAt: Long? = null,
        onProgress: PatchBundleDownloadProgress? = null,
    ) =
        dispatchAction("Add bundle ($url)") { state ->
            val src = createEntity("", SourceInfo.from(url), autoUpdate, createdAt = createdAt, updatedAt = updatedAt).load() as RemotePatchBundle
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

    suspend fun redownloadRemoteBundles() = store.dispatch(Update(force = true))

    /**
     * Updates all bundles that should be automatically updated.
     */
    suspend fun updateCheck() {
        store.dispatch(Update { it.autoUpdate })
        checkManualUpdates()
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
        private val onPerBundleProgress: ((bundle: RemotePatchBundle, bytesRead: Long, bytesTotal: Long?) -> Unit)? = null,
        private val predicate: (bundle: RemotePatchBundle) -> Boolean = { true },
    ) : Action<State> {
        private suspend fun toast(@StringRes id: Int, vararg args: Any?) =
            withContext(Dispatchers.Main) { app.toast(app.getString(id, *args)) }

        override fun toString() = if (force) "Redownload remote bundles" else "Update check"

        override suspend fun ActionContext.execute(
            current: State
        ) = coroutineScope {
            val allowMeteredUpdates = prefs.allowMeteredUpdates.get()
            if (!allowUnsafeNetwork && !allowMeteredUpdates && !networkInfo.isSafe()) {
                Log.d(tag, "Skipping update check because the network is down or metered.")
                bundleUpdateProgressFlow.value = null
                return@coroutineScope current
            }

            val targets = current.sources.values
                .filterIsInstance<RemotePatchBundle>()
                .filter { predicate(it) }

            if (targets.isEmpty()) {
                if (showToast) toast(R.string.patches_update_unavailable)
                bundleUpdateProgressFlow.value = null
                return@coroutineScope current
            }

            bundleUpdateProgressFlow.value = BundleUpdateProgress(
                total = targets.size,
                completed = 0,
                phase = BundleUpdatePhase.Checking,
            )

            val updated = try {
                val results = LinkedHashMap<RemotePatchBundle, PatchBundleDownloadResult>()
                var completed = 0

                for (bundle in targets) {
                    Log.d(tag, "Updating patch bundle: ${bundle.name}")

                    bundleUpdateProgressFlow.value = BundleUpdateProgress(
                        total = targets.size,
                        completed = completed,
                        currentBundleName = progressLabelFor(bundle),
                        phase = BundleUpdatePhase.Checking,
                        bytesRead = 0L,
                        bytesTotal = null,
                    )

                    val onProgress: PatchBundleDownloadProgress = { bytesRead, bytesTotal ->
                        bundleUpdateProgressFlow.update { progress ->
                            progress?.copy(
                                currentBundleName = progressLabelFor(bundle),
                                phase = BundleUpdatePhase.Downloading,
                                bytesRead = bytesRead,
                                bytesTotal = bytesTotal,
                            )
                        }
                        onPerBundleProgress?.invoke(bundle, bytesRead, bytesTotal)
                    }

                    val result = with(bundle) {
                        if (force) downloadLatest(onProgress) else update(onProgress)
                    }

                    val downloadedName = runCatching {
                        PatchBundle(bundle.patchesJarFile.absolutePath).manifestAttributes?.name
                    }.getOrNull()?.trim().takeUnless { it.isNullOrBlank() }
                    if (downloadedName != null) {
                        bundleUpdateProgressFlow.update { progress ->
                            progress?.copy(currentBundleName = downloadedName)
                        }
                    }

                    completed = (completed + 1).coerceAtMost(targets.size)
                    bundleUpdateProgressFlow.update { progress ->
                        progress?.copy(
                            completed = completed,
                            currentBundleName = downloadedName ?: progressLabelFor(bundle),
                            phase = BundleUpdatePhase.Finalizing,
                            bytesRead = 0L,
                            bytesTotal = null,
                        )
                    }

                    if (result != null) {
                        results[bundle] = result
                    }
                }

                results
            } finally {
                bundleUpdateProgressFlow.value = null
            }
            if (updated.isEmpty()) {
                if (showToast) toast(R.string.patches_update_unavailable)
                return@coroutineScope current
            }

            updated.forEach { (src, downloadResult) ->
                val name = runCatching {
                    PatchBundle(src.patchesJarFile.absolutePath).manifestAttributes?.name
                }.getOrNull()?.trim().takeUnless { it.isNullOrBlank() } ?: src.name
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

            if (updated.isNotEmpty()) {
                val updatedUids = updated.keys.map(RemotePatchBundle::uid).toSet()
                manualUpdateInfoFlow.update { currentMap -> currentMap - updatedUids }
            }

            if (showToast) toast(R.string.patches_update_success)
            doReload()
        }

        override suspend fun catch(exception: Exception) {
            Log.e(tag, "Failed to update patches", exception)
            toast(R.string.patches_download_fail, exception.simpleMessage())
        }
    }

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
                            val latestSignature = info.version.takeUnless { it.isBlank() }
                            val installedSignature = bundle.installedVersionSignature
                            val hasUpdate = latestSignature == null || installedSignature != latestSignature
                            if (!hasUpdate) return@async bundle.uid to null
                            bundle.uid to ManualBundleUpdateInfo(
                                latestVersion = latestSignature ?: bundle.version,
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

    private fun suggestedVersionsFor(patches: Set<Patch<*>>): Map<String, String?> {
        val versionCounts = patches.mostCommonCompatibleVersions(countUnusedPatches = true)

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
    ) {
        val ratio: Float?
            get() {
                val safeTotal = total.coerceAtLeast(1)
                val base = processed.coerceAtLeast(0).toFloat() / safeTotal
                if (phase != BundleImportPhase.Downloading) return base.coerceIn(0f, 1f)

                val totalBytes = bytesTotal?.takeIf { it > 0L } ?: return null
                val perBundleFraction = (bytesRead.toFloat() / totalBytes).coerceIn(0f, 1f)
                return (base + (perBundleFraction / safeTotal)).coerceIn(0f, 1f)
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

    private companion object {
        const val DEFAULT_SOURCE_UID = 0
        fun defaultSource() = PatchBundleEntity(
            uid = DEFAULT_SOURCE_UID,
            name = "",
            displayName = null,
            versionHash = null,
            source = Source.API,
            autoUpdate = false,
            sortOrder = 0,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
    }
}
