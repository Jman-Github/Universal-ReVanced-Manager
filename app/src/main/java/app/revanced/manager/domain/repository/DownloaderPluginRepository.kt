package app.revanced.manager.domain.repository

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Parcelable
import android.util.Log
import app.revanced.manager.data.platform.NetworkInfo
import app.revanced.manager.data.room.AppDatabase
import app.revanced.manager.data.room.plugins.TrustedDownloaderPlugin
import app.revanced.manager.domain.manager.PreferencesManager
import app.revanced.manager.network.api.ReVancedAPI
import app.revanced.manager.network.api.successOrThrow
import app.revanced.manager.network.downloader.DownloaderPluginSourceEntry
import app.revanced.manager.network.downloader.DownloaderPluginSourceState
import app.revanced.manager.network.downloader.DownloaderPluginState
import app.revanced.manager.network.downloader.LoadedDownloaderPlugin
import app.revanced.manager.network.downloader.ParceledDownloaderData
import app.revanced.manager.network.dto.GitHubAsset
import app.revanced.manager.network.dto.GitHubRelease
import app.revanced.manager.network.service.HttpService
import app.revanced.manager.plugin.downloader.DownloaderBuilder
import app.revanced.manager.plugin.downloader.GetScope as LegacyGetScope
import app.revanced.manager.plugin.downloader.OutputDownloadScope as LegacyOutputDownloadScope
import app.revanced.manager.plugin.downloader.PluginHostApi
import app.revanced.manager.plugin.downloader.Scope as LegacyScope
import app.revanced.manager.downloader.DownloaderBuilder as ModernDownloaderBuilder
import app.revanced.manager.downloader.DownloaderHostApi as ModernDownloaderHostApi
import app.revanced.manager.downloader.Scope as ModernScope
import app.revanced.manager.util.PM
import app.revanced.manager.util.tag
import dalvik.system.PathClassLoader
import io.ktor.client.request.url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.OutputStream
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.URI
import java.util.UUID

@OptIn(PluginHostApi::class, ModernDownloaderHostApi::class)
class DownloaderPluginRepository(
    private val pm: PM,
    private val prefs: PreferencesManager,
    private val app: Application,
    private val api: ReVancedAPI,
    private val httpService: HttpService,
    private val networkInfo: NetworkInfo,
    db: AppDatabase
) {
    private val trustDao = db.trustedDownloaderPluginDao()
    private val managedSourceRoot = app.getDir("managed_downloader_plugins", Context.MODE_PRIVATE)
    private val _pluginStates = MutableStateFlow(emptyMap<String, DownloaderPluginState>())
    private val _sourceStates = MutableStateFlow(emptyMap<String, DownloaderPluginSourceState>())
    val pluginStates = _pluginStates.asStateFlow()
    val sourceStates = _sourceStates.asStateFlow()
    val loadedPluginsFlow = combine(pluginStates, sourceStates) { installed, sources ->
        installed.values
            .filterIsInstance<DownloaderPluginState.Loaded>()
            .flatMap { it.plugins } +
            sources.values
                .mapNotNull { state ->
                    (state.state as? DownloaderPluginSourceState.State.Loaded)?.plugins
                }
                .flatten()
    }

    private val acknowledgedDownloaderPlugins = prefs.acknowledgedDownloaderPlugins
    private val installedPluginPackageNames = MutableStateFlow(emptySet<String>())
    val newPluginPackageNames = combine(
        installedPluginPackageNames,
        acknowledgedDownloaderPlugins.flow
    ) { installed, acknowledged ->
        installed subtract acknowledged
    }

    suspend fun reload() {
        val installedPlugins =
            withContext(Dispatchers.IO) {
                pm.getPackagesWithFeatures(setOf(LEGACY_PLUGIN_FEATURE, MODERN_PLUGIN_FEATURE))
                    .associate { it.packageName to loadInstalledPlugin(it.packageName) }
            }
        val managedSources =
            withContext(Dispatchers.IO) {
                buildMap {
                    readSourceEntries().forEach { entry ->
                        put(entry.id, loadManagedSource(entry))
                    }
                }
            }

        _pluginStates.value = installedPlugins
        _sourceStates.value = managedSources
        installedPluginPackageNames.value = installedPlugins.keys

        val acknowledgedPlugins = acknowledgedDownloaderPlugins.get()
        val uninstalledPlugins = acknowledgedPlugins subtract installedPluginPackageNames.value
        if (uninstalledPlugins.isNotEmpty()) {
            Log.d(tag, "Uninstalled plugins: ${uninstalledPlugins.joinToString(", ")}")
            acknowledgedDownloaderPlugins.update(acknowledgedPlugins subtract uninstalledPlugins)
            trustDao.removeAll(uninstalledPlugins)
        }
    }

    suspend fun importSourcesFromUrl(rawUrl: String): Int = withContext(Dispatchers.IO) {
        val importRequest = parseImportUrl(rawUrl)
        val importedEntries = importSourceEntries(importRequest)
        reload()
        importedEntries.size
    }

    suspend fun ensureDefaultSourcesImported() = withContext(Dispatchers.IO) {
        if (prefs.defaultDownloaderSourcesSeeded.get()) return@withContext
        if (!networkInfo.isConnected()) return@withContext

        val existingEntries = readSourceEntries()
        if (existingEntries.any { it.repoUrl == DEFAULT_REMOTE_DOWNLOADER_REPO_URL }) {
            prefs.defaultDownloaderSourcesSeeded.update(true)
            return@withContext
        }

        val importedEntries = importSourceEntries(
            importRequest = ImportRequest.Repository(DEFAULT_REMOTE_DOWNLOADER_REPO_URL)
        )
        check(importedEntries.isNotEmpty()) {
            "Expected at least one default downloader source to be imported."
        }

        if (existingEntries.isEmpty()) {
            val importedEntryIds = importedEntries.mapTo(mutableSetOf(), DownloaderPluginSourceEntry::id)
            val seededEntries = readSourceEntries().map { entry ->
                if (entry.id in importedEntryIds) {
                    val packageInfo = readArchivePackageInfo(sourceFile(entry))
                    entry.copy(trustedSignatureHex = archiveSignatureHex(packageInfo))
                } else {
                    entry
                }
            }
            writeSourceEntries(seededEntries)
        }

        prefs.defaultDownloaderSourcesSeeded.update(true)
        reload()
    }

    suspend fun updateSource(id: String) = withContext(Dispatchers.IO) {
        val entries = readSourceEntries().toMutableList()
        val index = entries.indexOfFirst { it.id == id }
        if (index == -1) return@withContext

        val updatedEntry = updateEntry(entries[index], force = true)
        entries[index] = updatedEntry
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
        val signatureHex = archiveSignatureHex(packageInfo)
        entries[index] = entry.copy(trustedSignatureHex = signatureHex)
        writeSourceEntries(entries)
        reload()
    }

    suspend fun revokeTrustForSource(id: String) = withContext(Dispatchers.IO) {
        val entries = readSourceEntries().toMutableList()
        val index = entries.indexOfFirst { it.id == id }
        if (index == -1) return@withContext

        val entry = entries[index]
        if (entry.trustedSignatureHex == null) return@withContext

        entries[index] = entry.copy(trustedSignatureHex = null)
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

    suspend fun setSourcePrerelease(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val entries = readSourceEntries().map { entry ->
            if (entry.id == id) entry.copy(prerelease = enabled) else entry
        }
        writeSourceEntries(entries)
        reload()
    }

    suspend fun updateCheck() {
        if (!networkInfo.isConnected()) return
        if (!prefs.allowMeteredUpdates.get() && !networkInfo.isUnmetered()) return

        val entries = withContext(Dispatchers.IO) { readSourceEntries() }
        if (entries.none { it.autoUpdate && it.trustedSignatureHex != null }) return

        val updatedEntries = buildList {
            entries.forEach { entry ->
                if (!shouldAutoUpdate(entry)) {
                    add(entry)
                    return@forEach
                }

                val updated = runCatching { updateEntry(entry, force = false) }
                    .onFailure { Log.e(tag, "Failed to update downloader source ${entry.repoUrl}", it) }
                    .getOrElse { entry }
                add(updated)
            }
        }

        withContext(Dispatchers.IO) {
            writeSourceEntries(updatedEntries)
        }
        reload()
    }

    fun unwrapParceledData(data: ParceledDownloaderData): Pair<LoadedDownloaderPlugin, Parcelable> {
        val allLoadedPlugins = _pluginStates.value.values
            .filterIsInstance<DownloaderPluginState.Loaded>()
            .flatMap { it.plugins } +
            _sourceStates.value.values
                .mapNotNull { state ->
                    (state.state as? DownloaderPluginSourceState.State.Loaded)?.plugins
                }
                .flatten()

        val plugin = data.pluginId
            ?.let { pluginId -> allLoadedPlugins.firstOrNull { it.id == pluginId } }
            ?: run {
                val fallbackPlugins = (_pluginStates.value[data.pluginPackageName] as? DownloaderPluginState.Loaded)
                    ?.plugins
                    ?: allLoadedPlugins.filter { it.packageName == data.pluginPackageName }
                if (fallbackPlugins.isEmpty()) {
                    throw Exception("Downloader plugin with name ${data.pluginPackageName} is not available")
                }

                data.pluginClassName
                    ?.let { className -> fallbackPlugins.firstOrNull { it.className == className } }
                    ?: fallbackPlugins.firstOrNull()
            }
            ?: throw Exception("No downloader implementation is available for ${data.pluginPackageName}")

        return plugin to data.unwrapWith(plugin)
    }

    suspend fun trustPackage(packageName: String) {
        trustDao.upsertTrust(
            TrustedDownloaderPlugin(
                packageName,
                pm.getSignature(packageName).toByteArray()
            )
        )

        reload()
        prefs.edit {
            acknowledgedDownloaderPlugins += packageName
        }
    }

    suspend fun revokeTrustForPackage(packageName: String) =
        trustDao.remove(packageName).also { reload() }

    suspend fun acknowledgeAllNewPlugins() =
        acknowledgedDownloaderPlugins.update(installedPluginPackageNames.value)

    suspend fun removePlugin(packageName: String) {
        trustDao.remove(packageName)
        acknowledgedDownloaderPlugins.update(acknowledgedDownloaderPlugins.get() - packageName)
        _pluginStates.value = _pluginStates.value - packageName
        installedPluginPackageNames.value = installedPluginPackageNames.value - packageName
    }

    private suspend fun loadInstalledPlugin(packageName: String): DownloaderPluginState {
        try {
            if (!verify(packageName)) return DownloaderPluginState.Untrusted
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(tag, "Got exception while verifying plugin $packageName", e)
            return DownloaderPluginState.Failed(e)
        }

        return try {
            val packageInfo = pm.getPackageInfo(packageName, flags = PackageManager.GET_META_DATA)!!
            val pluginContext = app.createPackageContext(packageName, 0)
            val resolved = loadResolvedPluginPackage(
                packageInfo = packageInfo,
                pluginContext = pluginContext,
                sourceId = null
            )

            DownloaderPluginState.Loaded(
                plugins = resolved.plugins,
                classLoader = resolved.classLoader,
                name = resolved.displayName
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(tag, "Failed to load plugin $packageName", t)
            DownloaderPluginState.Failed(t)
        }
    }

    private suspend fun loadManagedSource(entry: DownloaderPluginSourceEntry): DownloaderPluginSourceState {
        val file = sourceFile(entry)
        val fallbackName = entry.assetSelector.toReadableSourceName()
        if (!file.exists()) {
            return DownloaderPluginSourceState(
                entry = entry,
                name = fallbackName,
                version = null,
                repoUrl = entry.repoUrl,
                state = DownloaderPluginSourceState.State.Missing
            )
        }

        return try {
            ensureManagedSourceIsReadOnly(file)
            val packageInfo = readArchivePackageInfo(file)
            when (val trust = verifyArchiveTrust(entry, packageInfo)) {
                ArchiveTrust.Trusted -> Unit
                is ArchiveTrust.Untrusted -> {
                    return DownloaderPluginSourceState(
                        entry = entry,
                        name = fallbackName,
                        version = packageInfo.versionName,
                        repoUrl = entry.repoUrl,
                        state = DownloaderPluginSourceState.State.Untrusted(
                            packageName = packageInfo.packageName,
                            signature = trust.signatureHex
                        )
                    )
                }
                is ArchiveTrust.Mismatch -> {
                    return DownloaderPluginSourceState(
                        entry = entry,
                        name = fallbackName,
                        version = packageInfo.versionName,
                        repoUrl = entry.repoUrl,
                        state = DownloaderPluginSourceState.State.Untrusted(
                            packageName = packageInfo.packageName,
                            signature = trust.signatureHex
                        )
                    )
                }
            }
            val pluginContext = createArchiveContext(packageInfo)
            val resolved = loadResolvedPluginPackage(
                packageInfo = packageInfo,
                pluginContext = pluginContext,
                sourceId = entry.id
            )
            DownloaderPluginSourceState(
                entry = entry,
                name = resolved.displayName,
                version = resolved.version,
                repoUrl = entry.repoUrl,
                state = DownloaderPluginSourceState.State.Loaded(resolved.plugins)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(tag, "Failed to load managed downloader source ${entry.repoUrl}", t)
            DownloaderPluginSourceState(
                entry = entry,
                name = fallbackName,
                version = null,
                repoUrl = entry.repoUrl,
                state = DownloaderPluginSourceState.State.Failed(t)
            )
        }
    }

    private data class ResolvedPluginPackage(
        val plugins: List<LoadedDownloaderPlugin>,
        val classLoader: ClassLoader,
        val displayName: String,
        val version: String
    )

    private fun loadResolvedPluginPackage(
        packageInfo: PackageInfo,
        pluginContext: Context,
        sourceId: String?
    ): ResolvedPluginPackage {
        val classNames = resolveClassNames(packageInfo, pluginContext)
        val classLoader = PathClassLoader(packageInfo.applicationInfo!!.sourceDir, app.classLoader)
        val packageLabel = with(pm) { packageInfo.label() }

        val scopeImpl = object : LegacyScope, ModernScope {
            override val hostPackageName = app.packageName
            override val pluginPackageName = pluginContext.packageName
            override val downloaderPackageName = pluginContext.packageName
        }

        val loadedPlugins = classNames.map { className ->
            val downloader = classLoader
                .loadClass(className)
                .getDownloaderBuilder()
                .buildDownloader(scopeImpl, pluginContext)
            val fallbackName = if (classNames.size > 1) {
                className.substringAfterLast('.')
            } else {
                packageLabel
            }

            LoadedDownloaderPlugin(
                packageName = packageInfo.packageName,
                className = className,
                name = downloader.resolveName(pluginContext, fallbackName),
                version = packageInfo.versionName ?: "0",
                sourceId = sourceId,
                getImpl = downloader.resolveGet(),
                downloadImpl = downloader.resolveDownload(),
                classLoader = classLoader
            )
        }

        return ResolvedPluginPackage(
            plugins = loadedPlugins,
            classLoader = classLoader,
            displayName = if (loadedPlugins.size == 1) loadedPlugins.first().name else packageLabel,
            version = packageInfo.versionName ?: "0"
        )
    }

    private suspend fun verify(packageName: String): Boolean {
        val expectedSignature = trustDao.getTrustedSignature(packageName) ?: return false
        return pm.hasSignature(packageName, expectedSignature)
    }

    private suspend fun verify(packageInfo: PackageInfo): Boolean {
        val expectedSignature = trustDao.getTrustedSignature(packageInfo.packageName) ?: return false
        val actualSignature = pm.getSignature(packageInfo)?.toByteArray() ?: return false
        return actualSignature.contentEquals(expectedSignature)
    }

    private fun readArchivePackageInfo(file: File): PackageInfo {
        val packageInfo = pm.getPackageInfo(file)
            ?: throw Exception("Failed to read downloader archive ${file.name}")
        return packageInfo
    }

    private fun verifyArchiveTrust(
        entry: DownloaderPluginSourceEntry,
        packageInfo: PackageInfo
    ): ArchiveTrust {
        val actualSignatureHex = archiveSignatureHex(packageInfo)
        val expectedSignatureHex = entry.trustedSignatureHex
            ?: return ArchiveTrust.Untrusted(actualSignatureHex)

        return if (actualSignatureHex == expectedSignatureHex) {
            ArchiveTrust.Trusted
        } else {
            ArchiveTrust.Mismatch(actualSignatureHex)
        }
    }

    private fun archiveSignatureHex(packageInfo: PackageInfo): String {
        val signature = pm.getSignature(packageInfo)?.toByteArray()
            ?: throw SecurityException("Failed to read signer for ${packageInfo.packageName}")
        return signature.joinToString(separator = "") { byte -> "%02X".format(byte) }
    }

    private suspend fun readSourceEntries(): List<DownloaderPluginSourceEntry> {
        val raw = prefs.downloaderPluginSourcesJson.get()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<DownloaderPluginSourceEntry>>(raw)
        }.getOrElse {
            Log.e(tag, "Failed to decode downloader source entries", it)
            emptyList()
        }
    }

    private suspend fun writeSourceEntries(entries: List<DownloaderPluginSourceEntry>) {
        prefs.downloaderPluginSourcesJson.update(
            json.encodeToString(entries.sortedBy { "${it.repoUrl}|${it.assetSelector}|${it.id}" })
        )
    }

    private suspend fun importSourceEntries(
        importRequest: ImportRequest
    ): List<DownloaderPluginSourceEntry> {
        val release = releaseForImport(importRequest)
        val apkAssets = release.assets.filter(::isSupportedDownloaderAsset)
        if (apkAssets.isEmpty()) {
            error("No downloader APK assets found for ${importRequest.repoUrl}")
        }

        val selectedAssets = when (importRequest) {
            is ImportRequest.Asset -> {
                apkAssets.filter { normalizeAssetSelector(it.name) == importRequest.assetSelector }
            }

            is ImportRequest.Repository -> apkAssets
        }
        if (selectedAssets.isEmpty()) {
            error("No matching downloader APK asset found for ${importRequest.repoUrl}")
        }

        val entries = readSourceEntries().toMutableList()
        val importedEntries = mutableListOf<DownloaderPluginSourceEntry>()
        selectedAssets.forEach { asset ->
            val selector = normalizeAssetSelector(asset.name)
            val versionKey = releaseVersionKey(release, asset)
            val existingIndex = entries.indexOfFirst {
                it.repoUrl == importRequest.repoUrl && it.assetSelector == selector
            }
            val existing = entries.getOrNull(existingIndex)
            val entry = (existing ?: DownloaderPluginSourceEntry(
                id = UUID.randomUUID().toString(),
                repoUrl = importRequest.repoUrl,
                assetSelector = selector
            )).copy(
                repoUrl = importRequest.repoUrl,
                assetSelector = selector,
                prerelease = release.prerelease,
                versionKey = versionKey
            )
            downloadAssetToEntry(entry, asset)

            if (existingIndex >= 0) {
                entries[existingIndex] = entry
            } else {
                entries += entry
            }
            importedEntries += entry
        }

        writeSourceEntries(entries)
        return importedEntries
    }

    private suspend fun updateEntry(
        entry: DownloaderPluginSourceEntry,
        force: Boolean
    ): DownloaderPluginSourceEntry {
        val release = latestReleaseFor(entry.repoUrl, prerelease = entry.prerelease)
        val asset = release.assets
            .filter(::isSupportedDownloaderAsset)
            .firstOrNull { normalizeAssetSelector(it.name) == entry.assetSelector }
            ?: throw Exception("No matching downloader APK asset found for ${entry.repoUrl}")

        val versionKey = releaseVersionKey(release, asset)
        if (!force && versionKey == entry.versionKey && sourceFile(entry).exists()) {
            return entry
        }

        downloadAssetToEntry(entry, asset)
        return entry.copy(versionKey = versionKey)
    }

    private suspend fun shouldAutoUpdate(entry: DownloaderPluginSourceEntry): Boolean {
        if (!entry.autoUpdate || entry.trustedSignatureHex == null) return false

        val file = sourceFile(entry)
        if (!file.exists()) return true

        val packageInfo = runCatching { readArchivePackageInfo(file) }
            .getOrElse {
                Log.e(tag, "Failed to inspect downloader source ${entry.repoUrl} before auto-update", it)
                return false
            }

        return verifyArchiveTrust(entry, packageInfo) == ArchiveTrust.Trusted
    }

    private suspend fun releaseForImport(importRequest: ImportRequest): GitHubRelease = when (importRequest) {
        is ImportRequest.Repository -> latestImportReleaseFor(importRequest.repoUrl)
        is ImportRequest.Asset -> releaseForTag(importRequest.repoUrl, importRequest.releaseTag)
    }

    private suspend fun latestImportReleaseFor(repoUrl: String): GitHubRelease {
        return findLatestReleaseFor(repoUrl, prerelease = false)
            ?: findLatestReleaseFor(repoUrl, prerelease = true)
            ?: throw Exception("No releases found for $repoUrl")
    }

    private suspend fun releaseForTag(repoUrl: String, releaseTag: String): GitHubRelease {
        return releaseHistoryFor(repoUrl, prerelease = null, limit = 100)
            .firstOrNull { it.tagName == releaseTag }
            ?: throw Exception("Release $releaseTag not found for $repoUrl")
    }

    private suspend fun releaseHistoryFor(
        repoUrl: String,
        prerelease: Boolean?,
        limit: Int
    ): List<GitHubRelease> {
        return api.getRepositoryReleaseHistory(
            repoUrl = repoUrl,
            prerelease = prerelease,
            limit = limit
        ).successOrThrow("downloader releases for $repoUrl")
    }

    private suspend fun findLatestReleaseFor(repoUrl: String, prerelease: Boolean?): GitHubRelease? {
        return releaseHistoryFor(
            repoUrl = repoUrl,
            prerelease = prerelease,
            limit = 1
        ).firstOrNull()
    }

    private suspend fun latestReleaseFor(repoUrl: String, prerelease: Boolean): GitHubRelease {
        return findLatestReleaseFor(
            repoUrl = repoUrl,
            prerelease = prerelease
        )
            ?: throw Exception("No releases found for $repoUrl")
    }

    private suspend fun downloadAssetToEntry(
        entry: DownloaderPluginSourceEntry,
        asset: GitHubAsset
    ) {
        val directory = managedSourceDirectory(entry.id)
        directory.mkdirs()
        val target = sourceFile(entry)
        val tempFile = directory.resolve("downloader.tmp")
        if (tempFile.exists()) tempFile.delete()

        runCatching {
            httpService.downloadToFile(
                saveLocation = tempFile,
                builder = {
                    url(asset.downloadUrl)
                }
            )
            val packageInfo = readArchivePackageInfo(tempFile)
            when (verifyArchiveTrust(entry, packageInfo)) {
                ArchiveTrust.Trusted, is ArchiveTrust.Untrusted, is ArchiveTrust.Mismatch -> Unit
            }
            if (target.exists()) target.delete()
            tempFile.copyTo(target, overwrite = true)
            ensureManagedSourceIsReadOnly(target)
        }.getOrElse { error ->
            tempFile.delete()
            throw error
        }

        tempFile.delete()
    }

    private sealed interface ImportRequest {
        val repoUrl: String

        data class Repository(
            override val repoUrl: String
        ) : ImportRequest

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
    }

    private fun parseImportUrl(rawUrl: String): ImportRequest {
        val normalizedUrl = extractImportUrl(rawUrl)
        require(normalizedUrl.isNotBlank()) { "Enter a GitHub repository or release asset URL." }

        val uri = try {
            URI(normalizedUrl)
        } catch (_: Exception) {
            throw IllegalArgumentException("Unsupported downloader source URL: $normalizedUrl")
        }
        val host = uri.host?.lowercase()
            ?: throw IllegalArgumentException("Unsupported downloader source URL: $normalizedUrl")
        val parts = uri.path.trim('/').split('/').filter(String::isNotBlank)

        return when (host) {
            "github.com" -> {
                require(parts.size >= 2) { "Unsupported downloader source URL: $normalizedUrl" }
                val repoUrl = githubRepoUrl(parts[0], parts[1])
                if (parts.size >= 5 && parts[2] == "releases" && parts[3] == "download") {
                    ImportRequest.Asset(
                        repoUrl = repoUrl,
                        assetSelector = normalizeAssetSelector(parts.last()),
                        releaseTag = parts[4]
                    )
                } else {
                    ImportRequest.Repository(repoUrl)
                }
            }

            "api.github.com" -> {
                val reposIndex = parts.indexOf("repos")
                require(reposIndex != -1 && parts.size >= reposIndex + 3) {
                    "Unsupported downloader source URL: $normalizedUrl"
                }
                ImportRequest.Repository(
                    githubRepoUrl(parts[reposIndex + 1], parts[reposIndex + 2])
                )
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

    private fun sourceFile(entry: DownloaderPluginSourceEntry) =
        managedSourceDirectory(entry.id).resolve("downloader.apk")

    private fun ensureManagedSourceIsReadOnly(file: File) {
        if (!file.exists() || !file.canWrite()) return

        check(file.setReadOnly() || !file.canWrite()) {
            "Managed downloader source ${file.name} must be read-only before loading."
        }
    }

    private fun createArchiveContext(packageInfo: PackageInfo): Context {
        val applicationInfo = packageInfo.applicationInfo
            ?: throw IllegalStateException("Missing ApplicationInfo for ${packageInfo.packageName}")
        val resources = createArchiveResources(applicationInfo)
        val classLoader = PathClassLoader(applicationInfo.sourceDir, app.classLoader)
        return ArchivePluginContext(
            base = app,
            applicationInfo = applicationInfo,
            resources = resources,
            classLoader = classLoader
        )
    }

    private fun createArchiveResources(applicationInfo: ApplicationInfo): Resources {
        val sourcePath = applicationInfo.publicSourceDir ?: applicationInfo.sourceDir
        require(!sourcePath.isNullOrBlank()) {
            "Missing source path for ${applicationInfo.packageName}"
        }

        val assetManager = AssetManager::class.java
            .getDeclaredConstructor()
            .newInstance()
        val cookie = addAssetPath.invoke(assetManager, sourcePath) as? Int ?: 0
        check(cookie != 0) {
            "Failed to load downloader resources from $sourcePath"
        }

        return Resources(
            assetManager,
            app.resources.displayMetrics,
            app.resources.configuration
        )
    }

    private fun resolveClassNames(
        packageInfo: PackageInfo,
        pluginContext: Context
    ): List<String> {
        val names = linkedSetOf<String>()
        val metaData = packageInfo.applicationInfo?.metaData

        fun addClassName(value: String?) {
            val normalized = value?.trim().orEmpty()
            if (normalized.isNotEmpty()) names += normalized
        }

        fun addClassArray(resourceId: Int) {
            if (resourceId == 0) return
            runCatching { pluginContext.resources.getStringArray(resourceId) }
                .getOrNull()
                ?.forEach(::addClassName)
        }

        addClassName(metaData?.getString(LEGACY_METADATA_PLUGIN_CLASS))
        addClassName(metaData?.getString(MODERN_METADATA_PLUGIN_CLASS))

        addClassArray(metaData?.getInt(LEGACY_METADATA_CLASSES_ARRAY, 0) ?: 0)
        addClassArray(metaData?.getInt(MODERN_METADATA_CLASSES_ARRAY, 0) ?: 0)

        addClassArray(findStringArrayResource(pluginContext, LEGACY_CLASSES_RESOURCE_NAME))
        addClassArray(findStringArrayResource(pluginContext, MODERN_CLASSES_RESOURCE_NAME))

        if (names.isEmpty()) {
            throw Exception(
                "Missing downloader class metadata. Expected one of " +
                    "$LEGACY_METADATA_PLUGIN_CLASS, $MODERN_METADATA_PLUGIN_CLASS, " +
                    "$LEGACY_CLASSES_RESOURCE_NAME, or $MODERN_CLASSES_RESOURCE_NAME"
            )
        }

        return names.toList()
    }

    private fun findStringArrayResource(context: Context, resourceName: String): Int =
        runCatching {
            @Suppress("DiscouragedApi")
            context.resources.getIdentifier(resourceName, "array", context.packageName)
        }.getOrDefault(0)

    private fun isSupportedDownloaderAsset(asset: GitHubAsset): Boolean =
        asset.name.endsWith(".apk", ignoreCase = true)

    private fun releaseVersionKey(release: GitHubRelease, asset: GitHubAsset) =
        "${release.tagName}:${asset.name}"

    private fun normalizeAssetSelector(assetName: String): String {
        val baseName = assetName.substringBeforeLast('.', assetName)
        val match = VERSION_SUFFIX_REGEX.matchEntire(baseName)
        return (match?.groupValues?.getOrNull(1) ?: baseName).lowercase()
    }

    private fun String.toReadableSourceName(): String =
        split(Regex("[-_]+"))
            .filter(String::isNotBlank)
            .joinToString(" ") { part ->
                part.replaceFirstChar { ch -> ch.uppercase() }
            }

    private fun githubRepoUrl(owner: String, repo: String) = "https://github.com/$owner/$repo"

    private companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private const val DEFAULT_REMOTE_DOWNLOADER_REPO_URL =
            "https://github.com/brosssh/revanced-manager-downloaders"

        const val LEGACY_PLUGIN_FEATURE = "app.revanced.manager.plugin.downloader"
        const val MODERN_PLUGIN_FEATURE = "app.revanced.manager.downloader"

        const val LEGACY_METADATA_PLUGIN_CLASS = "app.revanced.manager.plugin.downloader.class"
        const val MODERN_METADATA_PLUGIN_CLASS = "app.revanced.manager.downloader.class"
        const val LEGACY_METADATA_CLASSES_ARRAY = "app.revanced.manager.plugin.downloader.classes"
        const val MODERN_METADATA_CLASSES_ARRAY = "app.revanced.manager.downloader.classes"
        const val LEGACY_CLASSES_RESOURCE_NAME = "app.revanced.manager.plugin.downloader.classes"
        const val MODERN_CLASSES_RESOURCE_NAME = "app.revanced.manager.downloader.classes"

        val VERSION_SUFFIX_REGEX = Regex(
            pattern = "^(.*?)-v?\\d+(?:\\.\\d+)+(?:[-._][A-Za-z0-9]+)*$",
            option = RegexOption.IGNORE_CASE
        )

        val GITHUB_URL_REGEX = Regex(
            pattern = "https?://(?:github\\.com|api\\.github\\.com)\\S+",
            option = RegexOption.IGNORE_CASE
        )

        val addAssetPath: Method by lazy {
            AssetManager::class.java.getMethod("addAssetPath", String::class.java)
        }

        const val PUBLIC_STATIC = Modifier.PUBLIC or Modifier.STATIC
        val Int.isPublicStatic get() = (this and PUBLIC_STATIC) == PUBLIC_STATIC

        val Class<*>.isDownloaderBuilder get() =
            DownloaderBuilder::class.java.isAssignableFrom(this) ||
                ModernDownloaderBuilder::class.java.isAssignableFrom(this)

        @Suppress("UNCHECKED_CAST")
        fun Class<*>.getDownloaderBuilder(): Any =
            declaredMethods
                .firstOrNull {
                    it.modifiers.isPublicStatic &&
                        it.returnType.isDownloaderBuilder &&
                        it.parameterTypes.isEmpty()
                }
                ?.invoke(null)
                ?: throw Exception("Could not find a valid downloader implementation in class $canonicalName")

        fun Any.buildDownloader(scopeImpl: Any, context: Context): Any {
            val buildMethod = this::class.java.methods.firstOrNull {
                it.name == "build" && it.parameterTypes.size == 2
            } ?: throw Exception("Could not find build(scope, context) on ${this::class.java.canonicalName}")
            return buildMethod.invoke(this, scopeImpl, context)
                ?: throw Exception("Downloader build returned null for ${this::class.java.canonicalName}")
        }

        @Suppress("UNCHECKED_CAST")
        fun Any.resolveGet() =
            this::class.java.methods
                .firstOrNull { it.name == "getGet" && it.parameterCount == 0 }
                ?.invoke(this) as? (suspend LegacyGetScope.(String, String?) -> Pair<Parcelable, String?>?)
                ?: throw Exception("Downloader ${this::class.java.canonicalName} has no valid get function")

        @Suppress("UNCHECKED_CAST")
        fun Any.resolveDownload() =
            this::class.java.methods
                .firstOrNull { it.name == "getDownload" && it.parameterCount == 0 }
                ?.invoke(this) as? (suspend LegacyOutputDownloadScope.(Parcelable, OutputStream) -> Unit)
                ?: throw Exception("Downloader ${this::class.java.canonicalName} has no valid download function")

        fun Any.resolveName(context: Context, fallback: String): String {
            val resId = this::class.java.methods
                .firstOrNull { it.name == "getName" && it.parameterCount == 0 }
                ?.invoke(this) as? Int
            if (resId == null || resId == 0) return fallback

            return runCatching { context.getString(resId) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: fallback
        }
    }
}

private class ArchivePluginContext(
    base: Context,
    private val applicationInfo: ApplicationInfo,
    private val resources: Resources,
    private val classLoader: ClassLoader
) : ContextWrapper(base) {
    override fun getApplicationInfo(): ApplicationInfo = applicationInfo

    override fun getPackageName(): String = applicationInfo.packageName

    override fun getResources(): Resources = resources

    override fun getAssets(): AssetManager = resources.assets

    override fun getClassLoader(): ClassLoader = classLoader
}
