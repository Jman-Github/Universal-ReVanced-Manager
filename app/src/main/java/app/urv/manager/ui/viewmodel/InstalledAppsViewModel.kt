package app.urv.manager.ui.viewmodel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import app.universal.revanced.manager.R
import app.urv.manager.data.platform.Filesystem
import app.urv.manager.data.room.apps.installed.InstallType
import app.urv.manager.data.room.apps.installed.InstalledApp
import app.urv.manager.data.room.profile.PatchProfilePayload
import app.urv.manager.domain.manager.PreferencesManager
import app.urv.manager.domain.bundles.PatchBundleSource
import app.urv.manager.domain.bundles.PatchBundleSource.Extensions.asRemoteOrNull
import app.urv.manager.domain.installer.RootInstaller
import app.urv.manager.domain.installer.RootServiceException
import app.urv.manager.domain.installer.root.RootMountOperation
import app.urv.manager.domain.installer.root.RootMountRequest
import app.urv.manager.domain.installer.root.RootMountTransactionCoordinator
import app.urv.manager.domain.installer.root.requireSuccess
import app.urv.manager.domain.repository.InstalledAppRepository
import app.urv.manager.domain.repository.PatchBundleRepository
import app.urv.manager.domain.repository.remapAndExtractSelection
import app.urv.manager.domain.repository.toSignatureMap
import app.urv.manager.util.PM
import app.urv.manager.util.PatchSelection
import app.urv.manager.util.mutableStateSetOf
import app.urv.manager.util.PatchBundleExportData
import app.urv.manager.util.PatchedAppExportData
import app.urv.manager.util.ExportNameFormatter
import app.urv.manager.util.buildSavedAppVariantIdentity
import app.urv.manager.util.mergeWith
import app.urv.manager.util.savedAppBasePackage
import app.urv.manager.util.savedApkAbiLabel
import app.urv.manager.util.simpleMessage
import app.urv.manager.util.supportsRootMount
import app.urv.manager.util.toast
import app.urv.manager.patcher.patch.PatchBundleInfo
import kotlinx.coroutines.CancellationException
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
    private val rootMountCoordinator: RootMountTransactionCoordinator,
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
    val savedApkAbiLabelMap = mutableStateMapOf<String, String>()
    val appLabelMap = mutableStateMapOf<String, String>()
    val customInstallerLabelMap = mutableStateMapOf<String, String>()
    private val devicePackageLookupMap = mutableStateMapOf<String, String>()
    private var normalizingSavedEntries = false
    val selectedApps = mutableStateSetOf<String>()
    val missingPackages = mutableStateSetOf<String>()
    val bundleSummaries = mutableStateMapOf<String, List<AppBundleSummary>>()
    val bundleSummaryLoaded = mutableStateSetOf<String>()

    init {
        viewModelScope.launch {
            apps.collect { installedApps ->
                if (normalizeDuplicateSavedEntries(installedApps)) {
                    return@collect
                }
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
                    savedApkAbiLabelMap.remove(packageName)
                    appLabelMap.remove(packageName)
                    customInstallerLabelMap.remove(packageName)
                    devicePackageLookupMap.remove(packageName)
                    missingPackages.remove(packageName)
                    selectedApps.remove(packageName)
                }

                val missingToRemove = missingPackages.filterNot { it in newMissing }.toSet()
                missingPackages.removeAll(missingToRemove)
                val missingToAdd = newMissing.filterNot { it in missingPackages }.toSet()
                missingPackages.addAll(missingToAdd)

                val selectablePackages = installedApps
                    .map(InstalledApp::currentPackageName)
                    .toSet()
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

    fun refreshDeviceAndMountState() = viewModelScope.launch {
        val installedApps = apps.first()
        val newMissing = mutableSetOf<String>()

        installedApps.forEach { installedApp ->
            val packageName = installedApp.currentPackageName
            val packageInfo = resolvePackageInfo(installedApp)
            packageInfoMap[packageName] = packageInfo

            if (installedApp.installType != InstallType.SAVED && packageInfo == null) {
                newMissing += packageName
            }
        }

        val missingToRemove = missingPackages.filterNot { it in newMissing }.toSet()
        missingPackages.removeAll(missingToRemove)
        val missingToAdd = newMissing.filterNot { it in missingPackages }.toSet()
        missingPackages.addAll(missingToAdd)
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
            val matchingEntries = devicePackageLookupMap
                .filterValues { it == packageName }
                .keys
                .toSet()
            val targetEntries = if (matchingEntries.isNotEmpty()) {
                matchingEntries
            } else if (packageName in packageInfoMap || packageName in installedOnDeviceMap) {
                setOf(packageName)
            } else {
                emptySet()
            }
            if (targetEntries.isEmpty()) return

            viewModelScope.launch {
                val installedAppsByPackage = apps.first().associateBy(InstalledApp::currentPackageName)
                val installedInfoAndInstallerLabel = withContext(Dispatchers.IO) {
                    val installedInfo = pm.getPackageInfo(packageName)
                    val needsInstallerLabel = targetEntries.any { key ->
                        installedAppsByPackage[key]?.installType == InstallType.CUSTOM
                    }
                    installedInfo to if (installedInfo != null && needsInstallerLabel) {
                        pm.getInstallerLabel(packageName)
                    } else {
                        null
                    }
                }
                val (installedInfo, installerLabel) = installedInfoAndInstallerLabel
                targetEntries.forEach { key ->
                    installedOnDeviceMap[key] = installedInfo != null
                    if (installedInfo != null) {
                        packageInfoMap[key] = installedInfo
                    }
                    if (
                        installedAppsByPackage[key]?.installType == InstallType.CUSTOM &&
                        !installerLabel.isNullOrBlank()
                    ) {
                        customInstallerLabelMap[key] = installerLabel
                    } else {
                        customInstallerLabelMap.remove(key)
                    }
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
        val version: String?,
        val hasUpdate: Boolean
    )

    data class SavedAppsExportResult(
        val exported: Int,
        val failed: Int,
        val total: Int
    )

    fun removeSavedApp(app: InstalledApp) = viewModelScope.launch {
        if (app.installType != InstallType.SAVED) return@launch
        if (!clearSavedData(app, deleteRecord = true)) return@launch
        savedCopyMap[app.currentPackageName] = false
    }

    fun deleteSavedEntry(app: InstalledApp) = viewModelScope.launch {
        if (!clearSavedData(app, deleteRecord = true)) return@launch
        savedCopyMap[app.currentPackageName] = false
    }

    suspend fun getRepatchSelection(app: InstalledApp): PatchSelection? = withContext(Dispatchers.IO) {
        val storedSelection = loadAppliedPatches(app.currentPackageName)
        val payload = app.selectionPayload ?: return@withContext storedSelection.takeIf { it.isNotEmpty() }
        val sources = patchBundleRepository.sources.first()
        val sourceIds = sources.map { it.uid }.toSet()
        val signatures = patchBundleRepository.allBundlesInfoFlow.first().toSignatureMap()
        val (remappedPayload, remappedSelection) = payload.remapAndExtractSelection(sources, signatures)
        val mergedSelection = storedSelection.mergeWith(remappedSelection)
        val persistableSelection = mergedSelection.filterKeys { it in sourceIds }
        if (persistableSelection.isNotEmpty() &&
            (persistableSelection != storedSelection || remappedPayload != payload)
        ) {
            installedAppsRepository.addOrUpdate(
                app.currentPackageName,
                app.originalPackageName,
                app.version,
                app.installType,
                persistableSelection,
                remappedPayload
            )
        }
        mergedSelection.takeIf { it.isNotEmpty() }
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

        val removedPackages = mutableSetOf<String>()
        toDelete.forEach { app ->
            if (deleteAppEntry(app)) {
                removedPackages += app.currentPackageName
            }
        }

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

    fun exportSelectedSavedAppsToTreeUri(
        context: Context,
        treeUri: Uri,
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

        val root = DocumentFile.fromTreeUri(context, treeUri)
        if (root == null || !root.isDirectory) {
            onResult(SavedAppsExportResult(0, selected.size, selected.size))
            return@launch
        }

        val result = withContext(Dispatchers.IO) {
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
                val targetName = resolveUniqueDocumentName(root, fileName)
                val target = root.createFile("application/vnd.android.package-archive", targetName)
                if (target == null) {
                    failed++
                    return@forEach
                }

                val success = runCatching {
                    context.contentResolver.openOutputStream(target.uri)?.use { output ->
                        source.inputStream().use { input -> input.copyTo(output) }
                    } ?: error("Could not open output stream")
                }.isSuccess

                if (success) exported++ else failed++
            }

            SavedAppsExportResult(exported = exported, failed = failed, total = selected.size)
        }
        onResult(result)
    }

    private suspend fun setSelectionInternal(installedApp: InstalledApp, shouldSelect: Boolean) {
        val packageName = installedApp.currentPackageName

        if (shouldSelect) {
            selectedApps.add(packageName)
        } else {
            selectedApps.remove(packageName)
        }
    }

    private suspend fun resolvePackageInfo(installedApp: InstalledApp): PackageInfo? =
        withContext(Dispatchers.IO) {
            val packageName = installedApp.currentPackageName
            val savedCopy = savedApkFile(installedApp)
            val savedPackageInfo = savedCopy?.let(pm::getPackageInfo)
            val savedArchiveLabel = savedCopy?.let { pm.getArchiveLabel(it, savedPackageInfo) }
            val supportsRootMount = installedApp.supportsRootMount(savedPackageInfo?.packageName)
            val hasSavedCopy = savedCopy != null
            savedCopyMap[packageName] = hasSavedCopy
            val savedAbiLabel = savedCopy?.savedApkAbiLabel(pm.application)
            if (savedAbiLabel.isNullOrBlank()) {
                savedApkAbiLabelMap.remove(packageName)
            } else {
                savedApkAbiLabelMap[packageName] = savedAbiLabel
            }
            try {
                if (
                    installedApp.installType == InstallType.MOUNT &&
                    supportsRootMount &&
                    !rootInstaller.isAppInstalled(packageName)
                ) {
                    if (clearSavedData(installedApp, deleteRecord = true)) {
                        return@withContext null
                    }
                }
            } catch (_: RootServiceException) {
                // Ignore root service availability issues for mounted apps and fall back to package info lookup.
            }

            val mounted = if (installedApp.installType == InstallType.MOUNT && supportsRootMount) {
                runCatching { rootInstaller.isAppMounted(packageName) }.getOrDefault(false).also {
                    mountedOnDeviceMap[packageName] = it
                }
            } else {
                mountedOnDeviceMap.remove(packageName)
                false
            }

            var resolvedLabel = savedArchiveLabel
            val resolvedPackageInfo = when {
                installedApp.installType == InstallType.SAVED ||
                    (installedApp.installType == InstallType.MOUNT && !supportsRootMount) -> {
                    if (savedCopy == null) return@withContext null
                    val archivePackageInfo = savedPackageInfo
                    val devicePackageName = archivePackageInfo?.packageName
                        ?.takeIf { it.isNotBlank() }
                        ?: installedApp.originalPackageName.takeIf { it.isNotBlank() }
                        ?: savedAppBasePackage(packageName)
                    devicePackageLookupMap[packageName] = devicePackageName

                    val installedInfo = pm.getPackageInfo(devicePackageName)
                    installedOnDeviceMap[packageName] = installedInfo != null
                    if (resolvedLabel.isNullOrBlank()) {
                        resolvedLabel = installedInfo?.let { info ->
                            runCatching { with(pm) { info.label() } }.getOrNull()
                        }
                    }
                    val archiveVersion = archivePackageInfo?.versionName?.takeIf { it.isNotBlank() }
                    val resolvedInstallType = if (
                        installedApp.installType == InstallType.MOUNT && !supportsRootMount
                    ) {
                        InstallType.SAVED
                    } else {
                        installedApp.installType
                    }
                    if (
                        resolvedInstallType != installedApp.installType ||
                        (archiveVersion != null && archiveVersion != installedApp.version)
                    ) {
                        val selection = installedAppsRepository.getAppliedPatches(packageName)
                        installedAppsRepository.addOrUpdate(
                            currentPackageName = installedApp.currentPackageName,
                            originalPackageName = installedApp.originalPackageName,
                            version = archiveVersion ?: installedApp.version,
                            installType = resolvedInstallType,
                            patchSelection = selection,
                            selectionPayload = installedApp.selectionPayload,
                            createdAtOverride = installedApp.createdAt
                        )
                    }
                    archivePackageInfo ?: installedInfo
                }

                else -> {
                    devicePackageLookupMap[packageName] = packageName
                    val installedInfo = pm.getPackageInfo(packageName)
                    installedOnDeviceMap[packageName] = installedInfo != null
                    if (resolvedLabel.isNullOrBlank() && !mounted) {
                        resolvedLabel = installedInfo?.let { info ->
                            runCatching { with(pm) { info.label() } }.getOrNull()
                        }
                    }
                    installedInfo ?: run {
                        val savedFile = filesystem.getPatchedAppFile(packageName, installedApp.version)
                        if (savedFile.exists()) pm.getPackageInfo(savedFile) else null
                    }
                }
            }

            val fallbackLabel = if (
                installedApp.installType == InstallType.SAVED ||
                (installedApp.installType == InstallType.MOUNT && !supportsRootMount)
            ) {
                installedApp.originalPackageName.takeIf { it.isNotBlank() }
                    ?: savedAppBasePackage(packageName)
            } else {
                packageName
            }
            appLabelMap[packageName] = resolvedLabel
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: fallbackLabel
            val customInstallerLabel = if (
                installedApp.installType == InstallType.CUSTOM &&
                installedOnDeviceMap[packageName] == true
            ) {
                pm.getInstallerLabel(resolvedPackageInfo?.packageName ?: packageName)
            } else {
                null
            }
            if (customInstallerLabel.isNullOrBlank()) {
                customInstallerLabelMap.remove(packageName)
            } else {
                customInstallerLabelMap[packageName] = customInstallerLabel
            }
            resolvedPackageInfo
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
        val displayPackageName = if (app.installType == InstallType.SAVED) {
            app.originalPackageName.takeIf { it.isNotBlank() }
                ?: savedAppBasePackage(app.currentPackageName)
        } else {
            app.currentPackageName
        }
        val label = appLabelMap[app.currentPackageName]
            ?: pm.getArchiveLabel(source, packageInfo)
            ?: displayPackageName
        val patchBundles = bundleSummaries[app.currentPackageName]
            .orEmpty()
            .mapNotNull { summary ->
                val name = summary.title.takeIf(String::isNotBlank)
                val version = summary.version?.takeIf(String::isNotBlank)
                if (name == null && version == null) {
                    null
                } else {
                    PatchBundleExportData(name = name, version = version)
                }
            }
        return PatchedAppExportData(
            appName = label,
            packageName = packageInfo?.packageName ?: displayPackageName,
            appVersion = app.version,
            patchBundles = patchBundles
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

    private fun resolveUniqueDocumentName(directory: DocumentFile, fileName: String): String {
        val lower = fileName.lowercase(Locale.ROOT)
        val ext = if (lower.endsWith(".apk")) ".apk" else ""
        val base = if (ext.isNotEmpty()) fileName.dropLast(ext.length) else fileName
        if (directory.findFile(fileName) == null) return fileName

        var counter = 2
        while (true) {
            val candidate = "${base}_$counter$ext"
            if (directory.findFile(candidate) == null) return candidate
            counter++
        }
    }

    private fun savedApkFile(app: InstalledApp): File? =
        filesystem.getPatchedAppFile(
            app.currentPackageName,
            app.version
        ).takeIf(File::isFile)

    private suspend fun clearSavedData(app: InstalledApp, deleteRecord: Boolean): Boolean {
        if (deleteRecord) return deleteAppEntry(app)

        return try {
            withContext(Dispatchers.IO) {
                savedApkFile(app)?.delete()
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            reportSavedAppDeleteFailure(error)
            false
        }
    }

    private suspend fun deleteAppEntry(app: InstalledApp): Boolean {
        return try {
            if (app.installType == InstallType.MOUNT) {
                removeRootMountModule(app)
            }
            installedAppsRepository.delete(app)
            if (app.installType == InstallType.SAVED || app.installType == InstallType.MOUNT) {
                withContext(Dispatchers.IO) {
                    savedApkFile(app)?.delete()
                }
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            reportSavedAppDeleteFailure(error)
            false
        }
    }

    private suspend fun removeRootMountModule(app: InstalledApp) {
        rootMountCoordinator.execute(
            RootMountRequest(
                packageName = app.currentPackageName,
                userId = android.os.Process.myUid() / 100_000,
                operation = RootMountOperation.UNMOUNT,
                removeModuleAfterUnmount = true
            )
        ).requireSuccess()
    }

    private fun reportSavedAppDeleteFailure(error: Exception) {
        Log.e("InstalledAppsViewModel", "Failed to delete saved app", error)
        pm.application.toast(
            pm.application.getString(R.string.saved_app_delete_failed, error.simpleMessage())
        )
    }

    private suspend fun loadAppliedPatches(packageName: String): PatchSelection =
        withContext(Dispatchers.IO) { installedAppsRepository.getAppliedPatches(packageName) }

    private suspend fun normalizeDuplicateSavedEntries(installedApps: List<InstalledApp>): Boolean {
        // When overwrite protection is active, duplicate saved entries for the
        // currently installed variant are intentional and should remain visible.
        if (prefs.enableSavedApps.get() && prefs.disableSavedAppOverwrite.get()) return false
        if (normalizingSavedEntries || installedApps.isEmpty()) return false
        val duplicateSavedEntries = withContext(Dispatchers.IO) {
            val duplicates = mutableListOf<InstalledApp>()
            installedApps
                .groupBy(::appsBasePackage)
                .values
                .forEach { entries ->
                    val installedEntries = entries.filter { it.installType != InstallType.SAVED }
                    if (installedEntries.isEmpty()) return@forEach
                    val installedIdentities = installedEntries.mapTo(mutableSetOf()) { app ->
                        savedEntryIdentity(app)
                    }
                    entries
                        .filter { it.installType == InstallType.SAVED }
                        .forEach { savedEntry ->
                            if (savedEntryIdentity(savedEntry) in installedIdentities) {
                                duplicates += savedEntry
                            }
                        }
                }
            duplicates
        }
        if (duplicateSavedEntries.isEmpty()) return false

        normalizingSavedEntries = true
        try {
            withContext(Dispatchers.IO) {
                duplicateSavedEntries.forEach { savedEntry ->
                    installedAppsRepository.delete(savedEntry)
                    filesystem.getPatchedAppFile(
                        savedEntry.currentPackageName,
                        savedEntry.version
                    ).takeIf { it.exists() }?.delete()
                }
            }
        } finally {
            normalizingSavedEntries = false
        }
        return true
    }

    private suspend fun savedEntryIdentity(app: InstalledApp): String =
        buildSavedAppVariantIdentity(
            appVersion = app.version,
            selectionPayload = app.selectionPayload,
            patchSelection = loadAppliedPatches(app.currentPackageName)
        )

    private fun appsBasePackage(app: InstalledApp): String =
        if (app.installType == InstallType.SAVED) {
            app.originalPackageName.takeIf { it.isNotBlank() }
                ?: savedAppBasePackage(app.currentPackageName)
        } else {
            app.currentPackageName
        }

    private fun buildBundleSummaries(
        app: InstalledApp,
        selection: PatchSelection,
        bundleInfo: Map<Int, PatchBundleInfo.Global>,
        sourceMap: Map<Int, PatchBundleSource>
    ): List<AppBundleSummary> {
        val payloadBundles = app.selectionPayload?.bundles.orEmpty()
        val sourceByEndpoint = sourceMap.values.mapNotNull { source ->
            source.asRemoteOrNull?.endpoint
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.let { it to source }
        }.toMap()
        val summaries = mutableListOf<AppBundleSummary>()
        val processed = mutableSetOf<Int>()

        selection.keys.forEach { uid ->
            processed += uid
            buildSummaryEntry(uid, payloadBundles, bundleInfo, sourceMap, sourceByEndpoint)
                ?.let(summaries::add)
        }

        payloadBundles.forEach { bundle ->
            if (bundle.bundleUid in processed) return@forEach
            buildSummaryEntry(bundle.bundleUid, payloadBundles, bundleInfo, sourceMap, sourceByEndpoint)
                ?.let(summaries::add)
        }

        return summaries
    }

    private fun buildSummaryEntry(
        uid: Int,
        payloadBundles: List<PatchProfilePayload.Bundle>,
        bundleInfo: Map<Int, PatchBundleInfo.Global>,
        sourceMap: Map<Int, PatchBundleSource>,
        sourceByEndpoint: Map<String, PatchBundleSource>
    ): AppBundleSummary? {
        val payloadBundle = payloadBundles.firstOrNull { it.bundleUid == uid }
        val payloadEndpoint = payloadBundle?.sourceEndpoint
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val source = payloadEndpoint?.let(sourceByEndpoint::get) ?: sourceMap[uid]
        val info = bundleInfo[source?.uid ?: uid] ?: bundleInfo[uid]

        val title = source?.displayTitle
            ?: payloadBundle?.displayName
            ?: payloadBundle?.sourceName
            ?: info?.name
            ?: return null

        val payloadVersion = payloadBundle?.version?.takeUnless { it.isBlank() }
        val currentVersion = info?.version?.takeUnless { it.isBlank() }
            ?: source?.version?.takeUnless { it.isBlank() }
        val version = payloadVersion ?: currentVersion
        val hasUpdate = payloadVersion != null &&
            currentVersion != null &&
            compareVersionStrings(currentVersion, payloadVersion) > 0

        return AppBundleSummary(
            title = title,
            version = version,
            hasUpdate = hasUpdate
        )
    }

    private fun compareVersionStrings(first: String, second: String): Int {
        val firstVersion = BundleVersion.parse(first)
        val secondVersion = BundleVersion.parse(second)
        if (firstVersion != null && secondVersion != null) {
            return firstVersion.compareTo(secondVersion)
        }

        return compareLooseVersionStrings(first, second)
    }

    private fun compareLooseVersionStrings(first: String, second: String): Int {
        val aParts = first.split(Regex("[^0-9]+"))
            .filter { it.isNotBlank() }
            .map { it.toIntOrNull() ?: 0 }
        val bParts = second.split(Regex("[^0-9]+"))
            .filter { it.isNotBlank() }
            .map { it.toIntOrNull() ?: 0 }
        val max = maxOf(aParts.size, bParts.size)
        for (index in 0 until max) {
            val a = aParts.getOrElse(index) { 0 }
            val b = bParts.getOrElse(index) { 0 }
            if (a != b) return a.compareTo(b)
        }
        return first.compareTo(second, ignoreCase = true)
    }

    private data class BundleVersion(
        val core: List<Long>,
        val prerelease: List<String>
    ) : Comparable<BundleVersion> {
        override fun compareTo(other: BundleVersion): Int {
            val max = maxOf(core.size, other.core.size)
            for (index in 0 until max) {
                val first = core.getOrElse(index) { 0 }
                val second = other.core.getOrElse(index) { 0 }
                if (first != second) return first.compareTo(second)
            }

            if (prerelease.isEmpty() && other.prerelease.isEmpty()) return 0
            if (prerelease.isEmpty()) return 1
            if (other.prerelease.isEmpty()) return -1

            val prereleaseMax = maxOf(prerelease.size, other.prerelease.size)
            for (index in 0 until prereleaseMax) {
                val first = prerelease.getOrNull(index) ?: return -1
                val second = other.prerelease.getOrNull(index) ?: return 1
                val firstNumber = first.toLongOrNull()
                val secondNumber = second.toLongOrNull()
                val comparison = when {
                    firstNumber != null && secondNumber != null -> firstNumber.compareTo(secondNumber)
                    firstNumber != null -> -1
                    secondNumber != null -> 1
                    else -> first.compareTo(second, ignoreCase = true)
                }
                if (comparison != 0) return comparison
            }

            return 0
        }

        companion object {
            private val versionPattern = Regex("^[vV]?(\\d+(?:\\.\\d+)*)(?:[-_]([^+\\s]+))?(?:\\+.*)?$")

            fun parse(value: String): BundleVersion? {
                val match = versionPattern.matchEntire(value.trim()) ?: return null
                val core = match.groupValues[1]
                    .split('.')
                    .map { it.toLongOrNull() ?: return null }
                val prerelease = match.groupValues.getOrNull(2)
                    ?.takeIf { it.isNotBlank() }
                    ?.split(Regex("[.-]"))
                    ?.filter { it.isNotBlank() }
                    .orEmpty()

                return BundleVersion(core, prerelease)
            }
        }
    }
}
