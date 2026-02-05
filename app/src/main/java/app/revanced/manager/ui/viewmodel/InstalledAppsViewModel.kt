package app.revanced.manager.ui.viewmodel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.ContextCompat
import app.revanced.manager.data.platform.Filesystem
import app.revanced.manager.data.room.apps.installed.InstallType
import app.revanced.manager.data.room.apps.installed.InstalledApp
import app.revanced.manager.data.room.profile.PatchProfilePayload
import app.revanced.manager.domain.manager.PreferencesManager
import app.revanced.manager.domain.bundles.PatchBundleSource
import app.revanced.manager.domain.installer.RootInstaller
import app.revanced.manager.domain.installer.RootServiceException
import app.revanced.manager.domain.repository.InstalledAppRepository
import app.revanced.manager.domain.repository.PatchBundleRepository
import app.revanced.manager.domain.repository.remapAndExtractSelection
import app.revanced.manager.domain.repository.toSignatureMap
import app.revanced.manager.util.FilenameUtils
import app.revanced.manager.util.PM
import app.revanced.manager.util.PatchSelection
import app.revanced.manager.util.mutableStateSetOf
import app.revanced.manager.util.PatchedAppExportData
import app.revanced.manager.util.ExportNameFormatter
import app.revanced.manager.patcher.patch.PatchBundleInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale

class InstalledAppsViewModel(
    private val installedAppsRepository: InstalledAppRepository,
    private val patchBundleRepository: PatchBundleRepository,
    private val pm: PM,
    private val rootInstaller: RootInstaller,
    private val filesystem: Filesystem,
    private val prefs: PreferencesManager
) : ViewModel() {
    val apps = combine(
        installedAppsRepository.getAll(),
        prefs.enableSavedApps.flow
    ) { installedApps, savedAppsEnabled ->
        if (savedAppsEnabled) installedApps
        else installedApps.filter { it.installType != InstallType.SAVED }
    }.flowOn(Dispatchers.IO)

    val packageInfoMap = mutableStateMapOf<String, PackageInfo?>()
    val installedOnDeviceMap = mutableStateMapOf<String, Boolean>()
    val mountedOnDeviceMap = mutableStateMapOf<String, Boolean>()
    val savedCopyMap = mutableStateMapOf<String, Boolean>()
    val selectedApps = mutableStateSetOf<String>()
    val missingPackages = mutableStateSetOf<String>()
    val bundleSummaries = mutableStateMapOf<String, List<AppBundleSummary>>()
    val bundleSummaryLoaded = mutableStateSetOf<String>()

    init {
        viewModelScope.launch {
            apps.collect { installedApps ->
                val seenPackages = mutableSetOf<String>()
                val newMissing = mutableSetOf<String>()

                installedApps.forEach { installedApp ->
                    val packageName = installedApp.currentPackageName
                    seenPackages += packageName

                    val packageInfo = resolvePackageInfo(installedApp)
                    packageInfoMap[packageName] = packageInfo

                    if (installedApp.installType != InstallType.SAVED && packageInfo == null) {
                        newMissing += packageName
                    }
                }

                val stalePackages = packageInfoMap.keys.toSet() - seenPackages
                stalePackages.forEach { packageName ->
                    packageInfoMap.remove(packageName)
                    installedOnDeviceMap.remove(packageName)
                    mountedOnDeviceMap.remove(packageName)
                    savedCopyMap.remove(packageName)
                    missingPackages.remove(packageName)
                    selectedApps.remove(packageName)
                }

                val missingToRemove = missingPackages.filterNot { it in newMissing }.toSet()
                missingPackages.removeAll(missingToRemove)
                val missingToAdd = newMissing.filterNot { it in missingPackages }.toSet()
                missingPackages.addAll(missingToAdd)

                val selectablePackages = installedApps.mapNotNull { app ->
                    when {
                        app.installType == InstallType.SAVED -> app.currentPackageName
                        app.currentPackageName in newMissing -> app.currentPackageName
                        else -> null
                    }
                }.toSet()
                selectedApps.retainAll(selectablePackages)
            }
        }

        viewModelScope.launch {
            combine(
                apps,
                patchBundleRepository.allBundlesInfoFlow,
                patchBundleRepository.sources
            ) { installedApps, bundleInfo, sources ->
                Triple(installedApps, bundleInfo, sources)
            }.collect { (installedApps, bundleInfo, sources) ->
                val sourceMap = sources.associateBy { it.uid }
                val packageNames = installedApps.map { it.currentPackageName }.toSet()

                installedApps.forEach { app ->
                    val selection = loadAppliedPatches(app.currentPackageName)
                    val summaries = buildBundleSummaries(app, selection, bundleInfo, sourceMap)
                    if (summaries.isEmpty()) {
                        bundleSummaries.remove(app.currentPackageName)
                    } else {
                        bundleSummaries[app.currentPackageName] = summaries
                    }
                    bundleSummaryLoaded.add(app.currentPackageName)
                }

                val stale = bundleSummaries.keys - packageNames
                stale.forEach { bundleSummaries.remove(it) }
                val staleLoaded = bundleSummaryLoaded - packageNames
                bundleSummaryLoaded.removeAll(staleLoaded)
            }
        }
    }

    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == Intent.ACTION_PACKAGE_REMOVED &&
                intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            ) {
                return
            }

            val packageName = intent.data?.schemeSpecificPart ?: return
            if (packageName !in packageInfoMap && packageName !in installedOnDeviceMap) return

            viewModelScope.launch {
                val installedInfo = withContext(Dispatchers.IO) {
                    pm.getPackageInfo(packageName)
                }
                installedOnDeviceMap[packageName] = installedInfo != null
                if (installedInfo != null) {
                    packageInfoMap[packageName] = installedInfo
                }
            }
        }
    }.also {
        ContextCompat.registerReceiver(
            pm.application,
            it,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    data class AppBundleSummary(
        val title: String,
        val version: String?
    )

    data class SavedAppsExportResult(
        val exported: Int,
        val failed: Int,
        val total: Int
    )

    fun removeSavedApp(app: InstalledApp) = viewModelScope.launch {
        if (app.installType != InstallType.SAVED) return@launch
        clearSavedData(app, deleteRecord = true)
        savedCopyMap[app.currentPackageName] = false
    }

    fun deleteSavedEntry(app: InstalledApp) = viewModelScope.launch {
        clearSavedData(app, deleteRecord = true)
        savedCopyMap[app.currentPackageName] = false
    }

    suspend fun getRepatchSelection(app: InstalledApp): PatchSelection? = withContext(Dispatchers.IO) {
        val selection = loadAppliedPatches(app.currentPackageName)
        if (selection.isNotEmpty()) return@withContext selection

        val payload = app.selectionPayload ?: return@withContext null
        val sources = patchBundleRepository.sources.first()
        val sourceIds = sources.map { it.uid }.toSet()
        val signatures = patchBundleRepository.allBundlesInfoFlow.first().toSignatureMap()
        val (remappedPayload, remappedSelection) = payload.remapAndExtractSelection(sources, signatures)
        val persistableSelection = remappedSelection.filterKeys { it in sourceIds }
        if (persistableSelection.isNotEmpty()) {
            installedAppsRepository.addOrUpdate(
                app.currentPackageName,
                app.originalPackageName,
                app.version,
                app.installType,
                persistableSelection,
                remappedPayload
            )
        }
        if (remappedSelection.isNotEmpty()) return@withContext remappedSelection

        val fallback = payload.bundles.associate { bundle ->
            bundle.bundleUid to bundle.patches.filter { it.isNotBlank() }.toSet()
        }.filterValues { it.isNotEmpty() }
        fallback.takeIf { it.isNotEmpty() }
    }

    override fun onCleared() {
        super.onCleared()
        pm.application.unregisterReceiver(packageChangeReceiver)
    }

    fun toggleSelection(installedApp: InstalledApp) = viewModelScope.launch {
        val packageName = installedApp.currentPackageName
        val shouldSelect = packageName !in selectedApps
        setSelectionInternal(installedApp, shouldSelect)
    }

    fun setSelection(installedApp: InstalledApp, shouldSelect: Boolean) =
        viewModelScope.launch { setSelectionInternal(installedApp, shouldSelect) }

    fun clearSelection() {
        selectedApps.clear()
    }

    fun reorderApps(orderedPackageNames: List<String>) = viewModelScope.launch(Dispatchers.IO) {
        installedAppsRepository.reorderApps(orderedPackageNames)
    }

    fun deleteSelectedApps() = viewModelScope.launch {
        if (selectedApps.isEmpty()) return@launch

        val snapshot = apps.first()
        val toDelete = snapshot.filter { it.currentPackageName in selectedApps }
        if (toDelete.isEmpty()) {
            selectedApps.clear()
            return@launch
        }

        toDelete.forEach { installedAppsRepository.delete(it) }
        withContext(Dispatchers.IO) {
            toDelete.filter { it.installType == InstallType.SAVED }.forEach { app ->
                val file = filesystem.getPatchedAppFile(app.currentPackageName, app.version)
                if (file.exists()) {
                    file.delete()
                }
            }
        }

        val removedPackages = toDelete.map { it.currentPackageName }.toSet()
        selectedApps.removeAll(removedPackages)
        removedPackages.forEach { packageName ->
            packageInfoMap.remove(packageName)
            missingPackages.remove(packageName)
        }
    }

    fun exportSelectedSavedAppsToDirectory(
        context: Context,
        directory: Path,
        exportTemplate: String?,
        onResult: (SavedAppsExportResult) -> Unit = {}
    ) = viewModelScope.launch {
        val snapshot = apps.first()
        val selected = snapshot.filter {
            it.currentPackageName in selectedApps && it.installType == InstallType.SAVED
        }
        if (selected.isEmpty()) {
            onResult(SavedAppsExportResult(0, 0, 0))
            return@launch
        }

        val result = withContext(Dispatchers.IO) {
            exportSelectedSavedAppsInternal(selected, directory, exportTemplate)
        }
        onResult(result)
    }

    private suspend fun setSelectionInternal(installedApp: InstalledApp, shouldSelect: Boolean) {
        val packageName = installedApp.currentPackageName
        if (shouldSelect && !isSelectable(installedApp)) return

        if (shouldSelect) {
            selectedApps.add(packageName)
        } else {
            selectedApps.remove(packageName)
        }
    }

    private suspend fun isSelectable(installedApp: InstalledApp): Boolean {
        if (installedApp.installType == InstallType.SAVED) return true

        val packageName = installedApp.currentPackageName
        if (packageName in missingPackages) return true

        val info = withContext(Dispatchers.IO) { pm.getPackageInfo(packageName) }
        packageInfoMap[packageName] = info

        val isMissing = info == null
        if (isMissing) {
            missingPackages.add(packageName)
        } else {
            missingPackages.remove(packageName)
        }
        return isMissing
    }

    private suspend fun resolvePackageInfo(installedApp: InstalledApp): PackageInfo? =
        withContext(Dispatchers.IO) {
            val packageName = installedApp.currentPackageName
            val hasSavedCopy = savedApkFile(installedApp) != null
            savedCopyMap[packageName] = hasSavedCopy
            try {
                if (
                    installedApp.installType == InstallType.MOUNT &&
                    !rootInstaller.isAppInstalled(packageName)
                ) {
                    installedAppsRepository.delete(installedApp)
                    return@withContext null
                }
            } catch (_: RootServiceException) {
                // Ignore root service availability issues for mounted apps and fall back to package info lookup.
            }

            if (installedApp.installType == InstallType.MOUNT) {
                val mounted = runCatching { rootInstaller.isAppMounted(packageName) }
                    .getOrDefault(false)
                mountedOnDeviceMap[packageName] = mounted
            } else {
                mountedOnDeviceMap.remove(packageName)
            }

            when (installedApp.installType) {
                InstallType.SAVED -> {
                    val installedInfo = pm.getPackageInfo(packageName)
                    installedOnDeviceMap[packageName] = installedInfo != null
                    if (installedInfo != null) {
                        return@withContext installedInfo
                    }
                    val savedFile = filesystem.getPatchedAppFile(packageName, installedApp.version)
                    val resolvedFile = if (savedFile.exists()) {
                        savedFile
                    } else {
                        filesystem.findPatchedAppFile(packageName)
                    }
                    if (resolvedFile == null) {
                        return@withContext null
                    }
                    if (resolvedFile != savedFile) {
                        val safePackage = FilenameUtils.sanitize(packageName)
                        val recoveredVersion = resolvedFile.name
                            .removePrefix("${safePackage}_")
                            .removeSuffix(".apk")
                            .ifBlank { installedApp.version }
                        val selection = installedAppsRepository.getAppliedPatches(packageName)
                        installedAppsRepository.addOrUpdate(
                            currentPackageName = installedApp.currentPackageName,
                            originalPackageName = installedApp.originalPackageName,
                            version = recoveredVersion,
                            installType = installedApp.installType,
                            patchSelection = selection,
                            selectionPayload = installedApp.selectionPayload
                        )
                    }
                    pm.getPackageInfo(resolvedFile)
                }

                else -> {
                    val installedInfo = pm.getPackageInfo(packageName)
                    installedOnDeviceMap[packageName] = installedInfo != null
                    installedInfo ?: run {
                        val savedFile = filesystem.getPatchedAppFile(packageName, installedApp.version)
                        if (savedFile.exists()) pm.getPackageInfo(savedFile) else null
                    }
                }
            }
        }

    private fun exportSelectedSavedAppsInternal(
        selected: List<InstalledApp>,
        directory: Path,
        exportTemplate: String?
    ): SavedAppsExportResult {
        Files.createDirectories(directory)

        var exported = 0
        var failed = 0
        selected.forEach { app ->
            val source = savedApkFile(app)
            if (source == null || !source.exists()) {
                failed++
                return@forEach
            }

            val exportData = buildExportMetadata(app, source)
            val fileName = ExportNameFormatter.format(exportTemplate, exportData)
            val target = resolveUniqueTarget(directory, fileName)
            val success = runCatching {
                Files.copy(source.toPath(), target, StandardCopyOption.REPLACE_EXISTING)
            }.isSuccess
            if (success) exported++ else failed++
        }

        return SavedAppsExportResult(exported = exported, failed = failed, total = selected.size)
    }

    private fun buildExportMetadata(app: InstalledApp, source: File): PatchedAppExportData {
        val packageInfo = pm.getPackageInfo(source)
        val label = packageInfo?.applicationInfo
            ?.loadLabel(pm.application.packageManager)
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: app.currentPackageName
        val summaries = bundleSummaries[app.currentPackageName].orEmpty()
        val bundleVersions = summaries.mapNotNull { it.version?.takeIf(String::isNotBlank) }
        val bundleNames = summaries.map { it.title }.filter(String::isNotBlank)
        return PatchedAppExportData(
            appName = label,
            packageName = app.currentPackageName,
            appVersion = app.version,
            patchBundleVersions = bundleVersions,
            patchBundleNames = bundleNames
        )
    }

    private fun resolveUniqueTarget(directory: Path, fileName: String): Path {
        val lower = fileName.lowercase(Locale.ROOT)
        val ext = if (lower.endsWith(".apk")) ".apk" else ""
        val base = if (ext.isNotEmpty()) fileName.dropLast(ext.length) else fileName
        var candidate = directory.resolve(fileName)
        if (!Files.exists(candidate)) return candidate

        var counter = 2
        while (true) {
            candidate = directory.resolve("${base}_$counter$ext")
            if (!Files.exists(candidate)) return candidate
            counter++
        }
    }

    private fun savedApkFile(app: InstalledApp): File? {
        val candidates = listOf(
            filesystem.getPatchedAppFile(app.currentPackageName, app.version),
            filesystem.getPatchedAppFile(app.originalPackageName, app.version)
        ).distinct()
        candidates.firstOrNull { it.exists() }?.let { return it }
        return filesystem.findPatchedAppFile(app.currentPackageName)
            ?: filesystem.findPatchedAppFile(app.originalPackageName)
    }

    private suspend fun clearSavedData(app: InstalledApp, deleteRecord: Boolean) {
        if (deleteRecord) {
            installedAppsRepository.delete(app)
        }
        withContext(Dispatchers.IO) {
            savedApkFile(app)?.delete()
        }
    }

    private suspend fun loadAppliedPatches(packageName: String): PatchSelection =
        withContext(Dispatchers.IO) { installedAppsRepository.getAppliedPatches(packageName) }

    private fun buildBundleSummaries(
        app: InstalledApp,
        selection: PatchSelection,
        bundleInfo: Map<Int, PatchBundleInfo.Global>,
        sourceMap: Map<Int, PatchBundleSource>
    ): List<AppBundleSummary> {
        val payloadBundles = app.selectionPayload?.bundles.orEmpty()
        val summaries = mutableListOf<AppBundleSummary>()
        val processed = mutableSetOf<Int>()

        selection.keys.forEach { uid ->
            processed += uid
            buildSummaryEntry(uid, payloadBundles, bundleInfo, sourceMap)?.let(summaries::add)
        }

        payloadBundles.forEach { bundle ->
            if (bundle.bundleUid in processed) return@forEach
            buildSummaryEntry(bundle.bundleUid, payloadBundles, bundleInfo, sourceMap)?.let(summaries::add)
        }

        return summaries
    }

    private fun buildSummaryEntry(
        uid: Int,
        payloadBundles: List<PatchProfilePayload.Bundle>,
        bundleInfo: Map<Int, PatchBundleInfo.Global>,
        sourceMap: Map<Int, PatchBundleSource>
    ): AppBundleSummary? {
        val info = bundleInfo[uid]
        val source = sourceMap[uid]
        val payloadBundle = payloadBundles.firstOrNull { it.bundleUid == uid }

        val title = source?.displayTitle
            ?: payloadBundle?.displayName
            ?: payloadBundle?.sourceName
            ?: info?.name
            ?: return null

        val version = payloadBundle?.version?.takeUnless { it.isBlank() } ?: info?.version
        return AppBundleSummary(title = title, version = version)
    }
}
