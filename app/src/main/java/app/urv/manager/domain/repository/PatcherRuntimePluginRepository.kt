package app.urv.manager.domain.repository

import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import app.urv.manager.data.platform.NetworkInfo
import app.urv.manager.domain.manager.PreferencesManager
import app.urv.manager.network.api.ReVancedAPI
import app.urv.manager.network.api.successOrThrow
import app.urv.manager.network.dto.GitHubAsset
import app.urv.manager.network.dto.GitHubRelease
import app.urv.manager.network.runtime.LoadedPatcherRuntimePlugin
import app.urv.manager.network.runtime.PatcherRuntimeKind
import app.urv.manager.network.runtime.PatcherRuntimePluginSourceEntry
import app.urv.manager.network.runtime.PatcherRuntimePluginSourceState
import app.urv.manager.network.runtime.PatcherRuntimePluginState
import app.urv.manager.network.service.HttpService
import app.urv.manager.util.DownloadProgressNotifier
import app.urv.manager.util.PM
import app.urv.manager.util.tag
import io.ktor.client.request.url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import java.util.UUID

class PatcherRuntimePluginRepository(
    private val pm: PM,
    private val prefs: PreferencesManager,
    private val app: Application,
    private val api: ReVancedAPI,
    private val httpService: HttpService,
    private val networkInfo: NetworkInfo,
    private val downloadProgressNotifier: DownloadProgressNotifier
) {
    private val managedSourceRoot = app.getDir("managed_patcher_runtime_plugins", Context.MODE_PRIVATE)
    private val _pluginStates = MutableStateFlow(emptyMap<String, PatcherRuntimePluginState>())
    private val _sourceStates = MutableStateFlow(emptyMap<String, PatcherRuntimePluginSourceState>())
    private val _hasLoadedInitialState = MutableStateFlow(false)
    private val installedPluginPackageNames = MutableStateFlow(emptySet<String>())

    val pluginStates = _pluginStates.asStateFlow()
    val sourceStates = _sourceStates.asStateFlow()
    val hasLoadedInitialState = _hasLoadedInitialState.asStateFlow()
    val newPluginPackageNames = combine(
        installedPluginPackageNames,
        prefs.acknowledgedPatcherRuntimePlugins.flow
    ) { installed, acknowledged ->
        installed subtract acknowledged
    }

    val loadedRuntimes = combine(pluginStates, sourceStates) { installed, sources ->
        val sourcePlugins = sources.values
            .mapNotNull { (it.state as? PatcherRuntimePluginSourceState.State.Loaded)?.plugin }
        val installedPlugins = installed.values
            .mapNotNull { (it as? PatcherRuntimePluginState.Loaded)?.plugin }
        (sourcePlugins + installedPlugins)
            .distinctBy { it.kind }
            .associateBy { it.kind }
    }

    val loadedRuntimeSnapshot: Map<PatcherRuntimeKind, LoadedPatcherRuntimePlugin>
        get() {
            val sourcePlugins = _sourceStates.value.values
                .mapNotNull { (it.state as? PatcherRuntimePluginSourceState.State.Loaded)?.plugin }
            val installedPlugins = _pluginStates.value.values
                .mapNotNull { (it as? PatcherRuntimePluginState.Loaded)?.plugin }
            return (sourcePlugins + installedPlugins)
                .distinctBy { it.kind }
                .associateBy { it.kind }
        }

    suspend fun reload() {
        val installedPlugins = withContext(Dispatchers.IO) {
            pm.getPackagesWithFeature(RUNTIME_PLUGIN_FEATURE)
                .associate { it.packageName to loadInstalledPlugin(it.packageName) }
        }
        val managedSources = withContext(Dispatchers.IO) {
            buildMap {
                readSourceEntries().forEach { entry ->
                    put(entry.id, loadManagedSource(entry))
                }
            }
        }

        _pluginStates.value = installedPlugins
        _sourceStates.value = managedSources
        installedPluginPackageNames.value = installedPlugins.keys
        _hasLoadedInitialState.value = true

        val acknowledged = prefs.acknowledgedPatcherRuntimePlugins.get()
        val uninstalled = acknowledged subtract installedPluginPackageNames.value
        if (uninstalled.isNotEmpty()) {
            prefs.acknowledgedPatcherRuntimePlugins.update(acknowledged subtract uninstalled)
            val trusted = readTrustedPackages() - uninstalled
            writeTrustedPackages(trusted)
        }
    }

    suspend fun importSourcesFromUrl(rawUrl: String): Int = withContext(Dispatchers.IO) {
        val imported = importSourceEntries(parseImportUrl(rawUrl))
        reload()
        imported.size
    }

    suspend fun updateSource(id: String) = withContext(Dispatchers.IO) {
        val entries = readSourceEntries().toMutableList()
        val index = entries.indexOfFirst { it.id == id }
        if (index == -1) return@withContext
        entries[index] = updateEntry(entries[index], force = true)
        writeSourceEntries(entries)
        reload()
    }

    suspend fun removeSource(id: String) = withContext(Dispatchers.IO) {
        val entries = readSourceEntries().filterNot { it.id == id }
        managedSourceDirectory(id).deleteRecursively()
        writeSourceEntries(entries)
        reload()
    }

    suspend fun trustSource(id: String) = withContext(Dispatchers.IO) {
        val entries = readSourceEntries().toMutableList()
        val index = entries.indexOfFirst { it.id == id }
        if (index == -1) return@withContext
        val entry = entries[index]
        val packageInfo = readArchivePackageInfo(sourceFile(entry))
        resolvePlugin(packageInfo, sourceId = entry.id)
        entries[index] = entry.copy(trustedSignatureHex = archiveSignatureHex(packageInfo))
        writeSourceEntries(entries)
        reload()
    }

    suspend fun revokeTrustForSource(id: String) = withContext(Dispatchers.IO) {
        val entries = readSourceEntries().map { entry ->
            if (entry.id == id) entry.copy(trustedSignatureHex = null) else entry
        }
        writeSourceEntries(entries)
        reload()
    }

    suspend fun setSourceAutoUpdate(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val entries = readSourceEntries().map { entry ->
            if (entry.id == id) entry.copy(autoUpdate = enabled) else entry
        }
        writeSourceEntries(entries)
        reload()
    }

    suspend fun setSourceLatest(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val entries = readSourceEntries().map { entry ->
            if (entry.id == id) {
                entry.copy(
                    latest = enabled,
                    prerelease = if (enabled) false else entry.prerelease
                )
            } else {
                entry
            }
        }
        writeSourceEntries(entries)
        reload()
    }

    suspend fun setSourcePrerelease(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val entries = readSourceEntries().map { entry ->
            if (entry.id == id) entry.copy(prerelease = enabled) else entry
        }
        writeSourceEntries(entries)
        reload()
    }

    suspend fun trustPackage(packageName: String) = withContext(Dispatchers.IO) {
        val packageInfo = pm.getPackageInfo(packageName, flags = signingPackageInfoFlags)
            ?: throw IllegalArgumentException("Runtime plugin $packageName is not installed")
        resolvePlugin(packageInfo, sourceId = null)
        val trusted = readTrustedPackages() + (packageName to archiveSignatureHex(packageInfo))
        writeTrustedPackages(trusted)
        prefs.acknowledgedPatcherRuntimePlugins.update(
            prefs.acknowledgedPatcherRuntimePlugins.get() + packageName
        )
        reload()
    }

    suspend fun revokeTrustForPackage(packageName: String) = withContext(Dispatchers.IO) {
        writeTrustedPackages(readTrustedPackages() - packageName)
        reload()
    }

    suspend fun acknowledgeAllNewPlugins() =
        prefs.acknowledgedPatcherRuntimePlugins.update(installedPluginPackageNames.value)

    suspend fun removePlugin(packageName: String) {
        writeTrustedPackages(readTrustedPackages() - packageName)
        prefs.acknowledgedPatcherRuntimePlugins.update(
            prefs.acknowledgedPatcherRuntimePlugins.get() - packageName
        )
        _pluginStates.value = _pluginStates.value - packageName
        installedPluginPackageNames.value = installedPluginPackageNames.value - packageName
    }

    suspend fun updateCheck() {
        if (!networkInfo.isConnected()) return
        if (!prefs.allowMeteredUpdates.get() && !networkInfo.isUnmetered()) return

        val entries = withContext(Dispatchers.IO) { readSourceEntries() }
        if (entries.none { it.autoUpdate && it.trustedSignatureHex != null }) return
        val currentSourceStates = sourceStates.value

        val updatedEntries = buildList {
            entries.forEach { entry ->
                val updateMode = shouldAutoUpdate(entry, currentSourceStates[entry.id]?.state)
                if (updateMode == SourceUpdateMode.SKIP) {
                    add(entry)
                    return@forEach
                }
                val updated = runCatching { updateEntry(entry, force = updateMode == SourceUpdateMode.FORCE) }
                    .onFailure { Log.e(tag, "Failed to update patcher runtime source ${entry.repoUrl}", it) }
                    .getOrElse { entry }
                add(updated)
            }
        }

        withContext(Dispatchers.IO) {
            writeSourceEntries(updatedEntries)
        }
        reload()
    }

    private suspend fun loadInstalledPlugin(packageName: String): PatcherRuntimePluginState {
        return try {
            val packageInfo = pm.getPackageInfo(
                packageName,
                flags = signingPackageInfoFlags
            ) ?: throw IllegalArgumentException("Runtime plugin $packageName is not installed")
            if (!verifyPackageTrust(packageInfo)) return PatcherRuntimePluginState.Untrusted
            PatcherRuntimePluginState.Loaded(resolvePlugin(packageInfo, sourceId = null))
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(tag, "Failed to load patcher runtime plugin $packageName", t)
            PatcherRuntimePluginState.Failed(t)
        }
    }

    private fun loadManagedSource(entry: PatcherRuntimePluginSourceEntry): PatcherRuntimePluginSourceState {
        val file = sourceFile(entry)
        val fallbackName = entry.assetSelector.toReadableSourceName()
        if (!file.exists()) {
            return PatcherRuntimePluginSourceState(
                entry = entry,
                name = fallbackName,
                version = null,
                repoUrl = entry.repoUrl,
                state = PatcherRuntimePluginSourceState.State.Missing
            )
        }

        return try {
            ensureManagedSourceIsReadOnly(file)
            val packageInfo = readArchivePackageInfo(file)
            when (val trust = verifyArchiveTrust(entry, packageInfo)) {
                ArchiveTrust.Trusted -> Unit
                is ArchiveTrust.Untrusted -> {
                    return PatcherRuntimePluginSourceState(
                        entry = entry,
                        name = fallbackName,
                        version = packageInfo.versionName,
                        repoUrl = entry.repoUrl,
                        state = PatcherRuntimePluginSourceState.State.Untrusted(
                            packageName = packageInfo.packageName,
                            signature = trust.signatureHex
                        )
                    )
                }
                is ArchiveTrust.Mismatch -> {
                    return PatcherRuntimePluginSourceState(
                        entry = entry,
                        name = fallbackName,
                        version = packageInfo.versionName,
                        repoUrl = entry.repoUrl,
                        state = PatcherRuntimePluginSourceState.State.Untrusted(
                            packageName = packageInfo.packageName,
                            signature = trust.signatureHex
                        )
                    )
                }
                is ArchiveTrust.Unreadable -> {
                    return PatcherRuntimePluginSourceState(
                        entry = entry,
                        name = fallbackName,
                        version = packageInfo.versionName,
                        repoUrl = entry.repoUrl,
                        state = PatcherRuntimePluginSourceState.State.Failed(trust.throwable)
                    )
                }
            }
            val plugin = resolvePlugin(packageInfo, sourceId = entry.id)
            PatcherRuntimePluginSourceState(
                entry = entry,
                name = plugin.name,
                version = plugin.version,
                repoUrl = entry.repoUrl,
                state = PatcherRuntimePluginSourceState.State.Loaded(plugin)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(tag, "Failed to load managed patcher runtime source ${entry.repoUrl}", t)
            PatcherRuntimePluginSourceState(
                entry = entry,
                name = fallbackName,
                version = null,
                repoUrl = entry.repoUrl,
                state = PatcherRuntimePluginSourceState.State.Failed(t)
            )
        }
    }

    private fun resolvePlugin(packageInfo: PackageInfo, sourceId: String?): LoadedPatcherRuntimePlugin {
        val applicationInfo = packageInfo.applicationInfo
            ?: throw IllegalStateException("Missing ApplicationInfo for ${packageInfo.packageName}")
        val kind = PatcherRuntimeKind.fromId(
            applicationInfo.metaData?.getString(METADATA_RUNTIME_KIND)
        ) ?: throw IllegalArgumentException(
            "Missing patcher runtime metadata $METADATA_RUNTIME_KIND in ${packageInfo.packageName}"
        )
        val sourceDir = applicationInfo.sourceDir
            ?: throw IllegalStateException("Missing source path for ${packageInfo.packageName}")
        return LoadedPatcherRuntimePlugin(
            packageName = packageInfo.packageName,
            name = with(pm) { packageInfo.label() },
            version = packageInfo.versionName ?: "0",
            kind = kind,
            sourceId = sourceId,
            apkFile = File(sourceDir)
        )
    }

    private suspend fun verifyPackageTrust(packageInfo: PackageInfo): Boolean {
        val expectedSignature = readTrustedPackages()[packageInfo.packageName] ?: return false
        val actualSignature = archiveSignatureHex(packageInfo)
        return actualSignature == expectedSignature
    }

    private suspend fun readSourceEntries(): List<PatcherRuntimePluginSourceEntry> {
        val raw = prefs.patcherRuntimePluginSourcesJson.get()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<PatcherRuntimePluginSourceEntry>>(raw)
        }.getOrElse {
            Log.e(tag, "Failed to decode patcher runtime source entries", it)
            emptyList()
        }
    }

    private suspend fun writeSourceEntries(entries: List<PatcherRuntimePluginSourceEntry>) {
        prefs.patcherRuntimePluginSourcesJson.update(
            json.encodeToString(entries.sortedBy { "${it.repoUrl}|${it.assetSelector}|${it.id}" })
        )
    }

    private suspend fun readTrustedPackages(): Map<String, String> {
        val raw = prefs.trustedPatcherRuntimePluginsJson.get()
        if (raw.isBlank()) return emptyMap()
        return runCatching { json.decodeFromString<Map<String, String>>(raw) }
            .getOrElse {
                Log.e(tag, "Failed to decode trusted patcher runtime plugins", it)
                emptyMap()
            }
    }

    private suspend fun writeTrustedPackages(entries: Map<String, String>) {
        prefs.trustedPatcherRuntimePluginsJson.update(
            if (entries.isEmpty()) "" else json.encodeToString<Map<String, String>>(entries.toSortedMap().toMap())
        )
    }

    private fun readArchivePackageInfo(file: File): PackageInfo =
        pm.getPackageInfo(file, includeSigning = true)
            ?: throw Exception("Failed to read patcher runtime archive ${file.name}")

    private fun archiveSignatureHex(packageInfo: PackageInfo): String {
        val signature = pm.getSignature(packageInfo)?.toByteArray()
            ?: throw SecurityException("Failed to read signer for ${packageInfo.packageName}")
        return signature.joinToString(separator = "") { byte -> "%02X".format(byte) }
    }

    private fun verifyArchiveTrust(
        entry: PatcherRuntimePluginSourceEntry,
        packageInfo: PackageInfo
    ): ArchiveTrust {
        val actualSignatureHex = runCatching { archiveSignatureHex(packageInfo) }
            .getOrElse { return ArchiveTrust.Unreadable(it) }
        val expectedSignatureHex = entry.trustedSignatureHex
            ?: return ArchiveTrust.Untrusted(actualSignatureHex)

        return if (actualSignatureHex == expectedSignatureHex) {
            ArchiveTrust.Trusted
        } else {
            ArchiveTrust.Mismatch(actualSignatureHex)
        }
    }

    private suspend fun importSourceEntries(
        importRequest: ImportRequest
    ): List<PatcherRuntimePluginSourceEntry> {
        val release = releaseForImport(importRequest)
        val apkAssets = release.assets.filter(::isSupportedRuntimeAsset)
        if (apkAssets.isEmpty()) {
            error("No patcher runtime APK assets found for ${importRequest.repoUrl}")
        }

        val selectedAssets = when (importRequest) {
            is ImportRequest.Asset -> apkAssets.filter {
                canonicalAssetSelector(normalizeAssetSelector(it.name)) ==
                    canonicalAssetSelector(importRequest.assetSelector)
            }
            is ImportRequest.Repository -> apkAssets
        }
        if (selectedAssets.isEmpty()) {
            error("No matching patcher runtime APK asset found for ${importRequest.repoUrl}")
        }

        val entries = readSourceEntries().toMutableList()
        val importedEntries = mutableListOf<PatcherRuntimePluginSourceEntry>()
        selectedAssets.forEach { asset ->
            val selector = canonicalAssetSelector(normalizeAssetSelector(asset.name))
            val versionKey = releaseVersionKey(release, asset)
            val existingIndex = entries.indexOfFirst {
                it.repoUrl == importRequest.repoUrl &&
                    canonicalAssetSelector(it.assetSelector) == selector
            }
            val existing = entries.getOrNull(existingIndex)
            val entry = (existing ?: PatcherRuntimePluginSourceEntry(
                id = UUID.randomUUID().toString(),
                repoUrl = importRequest.repoUrl,
                assetSelector = selector
            )).copy(
                repoUrl = importRequest.repoUrl,
                assetSelector = selector,
                prerelease = release.prerelease,
                versionKey = versionKey
            )
            try {
                downloadAssetToEntry(entry, asset)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (importRequest is ImportRequest.Repository && t.isMissingRuntimeMetadata()) {
                    if (existing == null) managedSourceDirectory(entry.id).deleteRecursively()
                    return@forEach
                }
                throw t
            }

            if (existingIndex >= 0) entries[existingIndex] = entry else entries += entry
            importedEntries += entry
        }

        if (importedEntries.isEmpty()) {
            error("No valid patcher runtime APK assets found for ${importRequest.repoUrl}")
        }

        writeSourceEntries(entries)
        return importedEntries
    }

    private suspend fun updateEntry(
        entry: PatcherRuntimePluginSourceEntry,
        force: Boolean
    ): PatcherRuntimePluginSourceEntry {
        val release = latestReleaseFor(
            repoUrl = entry.repoUrl,
            prerelease = if (entry.latest) null else entry.prerelease,
            assetSelector = entry.assetSelector
        )
        val asset = release.assets
            .filter(::isSupportedRuntimeAsset)
            .firstOrNull { it.matchesAssetSelector(entry.assetSelector) }
            ?: throw Exception("No matching patcher runtime APK asset found for ${entry.repoUrl}")

        val versionKey = releaseVersionKey(release, asset)
        if (!force && versionKey == entry.versionKey && sourceFile(entry).exists()) {
            return entry
        }

        downloadAssetToEntry(entry, asset)
        return entry.copy(versionKey = versionKey)
    }

    private suspend fun shouldAutoUpdate(
        entry: PatcherRuntimePluginSourceEntry,
        currentState: PatcherRuntimePluginSourceState.State?
    ): SourceUpdateMode {
        if (!entry.autoUpdate || entry.trustedSignatureHex == null) return SourceUpdateMode.SKIP
        if (currentState is PatcherRuntimePluginSourceState.State.Missing) return SourceUpdateMode.FORCE
        if (currentState is PatcherRuntimePluginSourceState.State.Failed) return SourceUpdateMode.CHECK
        val file = sourceFile(entry)
        if (!file.exists()) return SourceUpdateMode.FORCE

        val packageInfo = runCatching { readArchivePackageInfo(file) }
            .getOrElse {
                Log.e(tag, "Failed to inspect patcher runtime source ${entry.repoUrl}", it)
                return SourceUpdateMode.FORCE
            }
        return when (val trust = verifyArchiveTrust(entry, packageInfo)) {
            ArchiveTrust.Trusted -> SourceUpdateMode.CHECK
            is ArchiveTrust.Untrusted, is ArchiveTrust.Mismatch -> SourceUpdateMode.SKIP
            is ArchiveTrust.Unreadable -> {
                Log.e(tag, "Failed to verify patcher runtime source ${entry.repoUrl}", trust.throwable)
                SourceUpdateMode.FORCE
            }
        }
    }

    private suspend fun releaseForImport(importRequest: ImportRequest): GitHubRelease = when (importRequest) {
        is ImportRequest.Repository -> latestImportReleaseFor(importRequest.repoUrl)
        is ImportRequest.Asset -> releaseForTag(importRequest.repoUrl, importRequest.releaseTag)
    }

    private suspend fun latestImportReleaseFor(repoUrl: String): GitHubRelease =
        findLatestReleaseFor(repoUrl, prerelease = false, assetSelector = null)
            ?: findLatestReleaseFor(repoUrl, prerelease = true, assetSelector = null)
            ?: throw Exception("No patcher runtime APK assets found for $repoUrl")

    private suspend fun releaseForTag(repoUrl: String, releaseTag: String): GitHubRelease =
        releaseHistoryFor(repoUrl, prerelease = null, limit = 100)
            .firstOrNull { it.tagName == releaseTag }
            ?: throw Exception("Release $releaseTag not found for $repoUrl")

    private suspend fun releaseHistoryFor(
        repoUrl: String,
        prerelease: Boolean?,
        limit: Int
    ): List<GitHubRelease> =
        api.getRepositoryReleaseHistory(
            repoUrl = repoUrl,
            prerelease = prerelease,
            limit = limit
        ).successOrThrow("patcher runtime releases for $repoUrl")

    private suspend fun findLatestReleaseFor(
        repoUrl: String,
        prerelease: Boolean?,
        assetSelector: String?
    ): GitHubRelease? =
        releaseHistoryFor(repoUrl = repoUrl, prerelease = prerelease, limit = 100)
            .firstOrNull { release ->
                release.assets
                    .filter(::isSupportedRuntimeAsset)
                    .any { assetSelector == null || it.matchesAssetSelector(assetSelector) }
            }

    private suspend fun latestReleaseFor(
        repoUrl: String,
        prerelease: Boolean?,
        assetSelector: String
    ): GitHubRelease =
        findLatestReleaseFor(
            repoUrl = repoUrl,
            prerelease = prerelease,
            assetSelector = assetSelector
        )
            ?: throw Exception("No releases found for $repoUrl")

    private suspend fun downloadAssetToEntry(
        entry: PatcherRuntimePluginSourceEntry,
        asset: GitHubAsset
    ) {
        val directory = managedSourceDirectory(entry.id)
        directory.mkdirs()
        val target = sourceFile(entry)
        val tempFile = directory.resolve("patcher-runtime.tmp")
        if (tempFile.exists()) tempFile.delete()
        val progressNotification = downloadProgressNotifier.begin(asset.name)

        runCatching {
            httpService.downloadToFile(
                saveLocation = tempFile,
                builder = { url(asset.downloadUrl) },
                onProgress = progressNotification::update
            )
            val packageInfo = readArchivePackageInfo(tempFile)
            resolvePlugin(packageInfo, sourceId = entry.id)
            if (entry.trustedSignatureHex != null) {
                when (val trust = verifyArchiveTrust(entry, packageInfo)) {
                    ArchiveTrust.Trusted -> Unit
                    is ArchiveTrust.Mismatch -> throw SecurityException(
                        "Signer mismatch for ${packageInfo.packageName}"
                    )
                    is ArchiveTrust.Unreadable -> throw SecurityException(
                        "Failed to read signer for ${packageInfo.packageName}",
                        trust.throwable
                    )
                    is ArchiveTrust.Untrusted -> throw SecurityException(
                        "Expected trusted patcher runtime source for ${packageInfo.packageName}"
                    )
                }
            }
            if (target.exists()) target.delete()
            tempFile.copyTo(target, overwrite = true)
            ensureManagedSourceIsReadOnly(target)
        }.getOrElse { error ->
            if (error is CancellationException) {
                progressNotification.cancel()
            } else {
                progressNotification.fail()
            }
            tempFile.delete()
            throw error
        }

        tempFile.delete()
        progressNotification.complete()
    }

    private fun parseImportUrl(rawUrl: String): ImportRequest {
        val normalizedUrl = extractImportUrl(rawUrl)
        require(normalizedUrl.isNotBlank()) { "Enter a GitHub repository or release asset URL." }
        val uri = try {
            URI(normalizedUrl)
        } catch (_: Exception) {
            throw IllegalArgumentException("Unsupported patcher runtime source URL: $normalizedUrl")
        }
        val host = uri.host?.lowercase()
            ?: throw IllegalArgumentException("Unsupported patcher runtime source URL: $normalizedUrl")
        val parts = uri.path.trim('/').split('/').filter(String::isNotBlank)
        return when (host) {
            "github.com" -> {
                require(parts.size >= 2) { "Unsupported patcher runtime source URL: $normalizedUrl" }
                val repoUrl = githubRepoUrl(parts[0], parts[1])
                if (parts.size >= 5 && parts[2] == "releases" && parts[3] == "download") {
                    ImportRequest.Asset(
                        repoUrl = repoUrl,
                        assetSelector = canonicalAssetSelector(normalizeAssetSelector(parts.last())),
                        releaseTag = parts[4]
                    )
                } else {
                    ImportRequest.Repository(repoUrl)
                }
            }
            "api.github.com" -> {
                val reposIndex = parts.indexOf("repos")
                require(reposIndex != -1 && parts.size >= reposIndex + 3) {
                    "Unsupported patcher runtime source URL: $normalizedUrl"
                }
                ImportRequest.Repository(githubRepoUrl(parts[reposIndex + 1], parts[reposIndex + 2]))
            }
            else -> throw IllegalArgumentException(
                "Only GitHub repository or release asset URLs are supported."
            )
        }
    }

    private fun extractImportUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return trimmed
        val matched = GITHUB_URL_REGEX.find(trimmed)?.value ?: trimmed
        return matched
            .trim()
            .trim('<', '>', '"', '\'')
            .trimStart('(', '[', '{')
            .trimEnd(')', ']', '}', '.', ',', ';')
    }

    private fun managedSourceDirectory(id: String) = managedSourceRoot.resolve(id)
    private fun sourceFile(entry: PatcherRuntimePluginSourceEntry) =
        managedSourceDirectory(entry.id).resolve("patcher-runtime.apk")

    private fun ensureManagedSourceIsReadOnly(file: File) {
        if (!file.exists() || !file.canWrite()) return
        check(file.setReadOnly() || !file.canWrite()) {
            "Managed patcher runtime source ${file.name} must be read-only before loading."
        }
    }

    private fun isSupportedRuntimeAsset(asset: GitHubAsset): Boolean =
        asset.name.endsWith(".apk", ignoreCase = true) &&
            !asset.name.contains("universal-revanced-manager", ignoreCase = true)

    private fun GitHubAsset.matchesAssetSelector(assetSelector: String): Boolean =
        canonicalAssetSelector(normalizeAssetSelector(name)) ==
            canonicalAssetSelector(assetSelector)

    private fun Throwable.isMissingRuntimeMetadata(): Boolean =
        this is IllegalArgumentException &&
            message?.contains(METADATA_RUNTIME_KIND, ignoreCase = true) == true

    private fun releaseVersionKey(release: GitHubRelease, asset: GitHubAsset) =
        "${release.tagName}:${asset.name}"

    private fun normalizeAssetSelector(assetName: String): String {
        val baseName = assetName.substringBeforeLast('.', assetName)
        val match = VERSION_SUFFIX_REGEX.matchEntire(baseName)
        return (match?.groupValues?.getOrNull(1) ?: baseName).lowercase()
    }

    private fun canonicalAssetSelector(selector: String): String {
        val normalized = selector.lowercase().removePrefix("universal-revanced-manager-")
        return when (normalized) {
            "revanced-runtime-v21", "revanced-runtime-v21-release",
            "revanced.v21-runtime-plugin", "revanced.v21-runtime-plugin-release" ->
                "revanced.v21-plugin"
            else -> normalized
        }
    }

    private fun String.toReadableSourceName(): String =
        split(Regex("[-_]+"))
            .filter(String::isNotBlank)
            .joinToString(" ") { part -> part.replaceFirstChar { ch -> ch.uppercase() } }

    private sealed interface ImportRequest {
        val repoUrl: String
        data class Repository(override val repoUrl: String) : ImportRequest
        data class Asset(
            override val repoUrl: String,
            val assetSelector: String,
            val releaseTag: String
        ) : ImportRequest
    }

    private sealed interface ArchiveTrust {
        data object Trusted : ArchiveTrust
        data class Mismatch(val signatureHex: String) : ArchiveTrust
        data class Untrusted(val signatureHex: String) : ArchiveTrust
        data class Unreadable(val throwable: Throwable) : ArchiveTrust
    }

    private enum class SourceUpdateMode {
        SKIP,
        CHECK,
        FORCE
    }

    private companion object {
        private val json = Json { ignoreUnknownKeys = true }
        const val RUNTIME_PLUGIN_FEATURE = "app.urv.manager.patcher.runtime"
        const val METADATA_RUNTIME_KIND = "app.urv.manager.patcher.runtime.kind"
        val signingPackageInfoFlags: Int
            get() {
                val signingFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    PackageManager.GET_SIGNING_CERTIFICATES
                } else {
                    @Suppress("DEPRECATION")
                    PackageManager.GET_SIGNATURES
                }
                return PackageManager.GET_META_DATA or signingFlag
            }
        val VERSION_SUFFIX_REGEX = Regex(
            pattern = "^(.*?)-v?\\d+(?:\\.\\d+)+(?:[-._][A-Za-z0-9]+)*$",
            option = RegexOption.IGNORE_CASE
        )
        val GITHUB_URL_REGEX = Regex(
            pattern = "https?://(?:github\\.com|api\\.github\\.com)\\S+",
            option = RegexOption.IGNORE_CASE
        )
        fun githubRepoUrl(owner: String, repo: String) = "https://github.com/$owner/$repo"
    }
}
