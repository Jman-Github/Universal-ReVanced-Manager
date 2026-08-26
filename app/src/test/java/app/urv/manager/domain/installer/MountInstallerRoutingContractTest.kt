package app.urv.manager.domain.installer

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MountInstallerRoutingContractTest {
    @Test
    fun `installer tokens must match the patch mode`() {
        assertTrue(installerTokenMatchesPatchMode(InstallerManager.Token.AutoSaved, true))
        assertTrue(installerTokenMatchesPatchMode(InstallerManager.Token.RootPlayStore, true))
        assertFalse(installerTokenMatchesPatchMode(InstallerManager.Token.Internal, true))
        assertFalse(installerTokenMatchesPatchMode(InstallerManager.Token.Shizuku, true))
        assertFalse(installerTokenMatchesPatchMode(InstallerManager.Token.AutoSaved, false))
        assertFalse(installerTokenMatchesPatchMode(InstallerManager.Token.RootPlayStore, false))
        assertTrue(installerTokenMatchesPatchMode(InstallerManager.Token.Internal, false))
        assertTrue(installerTokenMatchesPatchMode(InstallerManager.Token.Shizuku, false))
    }

    @Test
    fun `patched output mount compatibility rejects unsafe post patch choices`() {
        fun supports(
            patchedPackageName: String = "com.example.app",
            patchedIsCompleteSingleApk: Boolean = true,
            patchedHasSigningCertificate: Boolean = true,
            installedHasSplitApks: Boolean = false,
            installedHasSharedUserId: Boolean = false,
            hasUsableStockIdentity: Boolean = true,
            patchedVersionMatchesSource: Boolean = true
        ) = patchedOutputSupportsRootMount(
            patchedPackageName = patchedPackageName,
            originalPackageName = "com.example.app",
            patchedIsCompleteSingleApk = patchedIsCompleteSingleApk,
            patchedHasSigningCertificate = patchedHasSigningCertificate,
            installedHasSplitApks = installedHasSplitApks,
            installedHasSharedUserId = installedHasSharedUserId,
            hasUsableStockIdentity = hasUsableStockIdentity,
            patchedVersionMatchesSource = patchedVersionMatchesSource
        )

        assertTrue(supports())
        assertFalse(supports(patchedPackageName = "com.example.renamed"))
        assertFalse(supports(patchedIsCompleteSingleApk = false))
        assertFalse(supports(patchedHasSigningCertificate = false))
        assertFalse(supports(installedHasSplitApks = true))
        assertFalse(supports(installedHasSharedUserId = true))
        assertFalse(supports(hasUsableStockIdentity = false))
        assertFalse(supports(patchedVersionMatchesSource = false))
    }

    @Test
    fun `stock identity does not ignore a contradictory verified source`() {
        assertTrue(
            rootMountStockIdentityUsable(
                installedMatchesSourceVersion = true,
                installedHasSigningCertificate = true,
                hasStandaloneStockSource = false,
                standaloneStockIdentityCompatible = false
            )
        )
        assertTrue(
            rootMountStockIdentityUsable(
                installedMatchesSourceVersion = true,
                installedHasSigningCertificate = true,
                hasStandaloneStockSource = true,
                standaloneStockIdentityCompatible = true
            )
        )
        assertFalse(
            rootMountStockIdentityUsable(
                installedMatchesSourceVersion = true,
                installedHasSigningCertificate = true,
                hasStandaloneStockSource = true,
                standaloneStockIdentityCompatible = false
            )
        )
        assertTrue(
            rootMountStockIdentityUsable(
                installedMatchesSourceVersion = false,
                installedHasSigningCertificate = false,
                hasStandaloneStockSource = true,
                standaloneStockIdentityCompatible = true
            )
        )
        assertFalse(
            rootMountStockIdentityUsable(
                installedMatchesSourceVersion = false,
                installedHasSigningCertificate = false,
                hasStandaloneStockSource = false,
                standaloneStockIdentityCompatible = false
            )
        )
    }

    @Test
    fun `compatible normal patch outputs can explicitly choose mount`() {
        assertTrue(
            installerTokenSelectableForPatchedOutput(
                InstallerManager.Token.AutoSaved,
                useMount = false,
                supportsRootMount = true
            )
        )
        assertFalse(
            installerTokenSelectableForPatchedOutput(
                InstallerManager.Token.AutoSaved,
                useMount = false,
                supportsRootMount = false
            )
        )
        assertTrue(
            installerTokenSelectableForPatchedOutput(
                InstallerManager.Token.Internal,
                useMount = false,
                supportsRootMount = true
            )
        )
        assertFalse(
            installerTokenSelectableForPatchedOutput(
                InstallerManager.Token.Internal,
                useMount = true,
                supportsRootMount = true
            )
        )
    }

    @Test
    fun `renamed packages cannot resolve to mount`() {
        val manager = source("domain/installer/InstallerManager.kt")
        assertTrue(manager.contains("buildSequence(target, sourceFile, allowMount)"))
        assertTrue(
            manager.contains(
                "if (!allowMount && baseInstallerToken(token) == Token.AutoSaved)"
            )
        )
        assertTrue(manager.contains("allowMount = false"))
    }

    @Test
    fun `matching stock identity avoids replacement`() {
        assertFalse(
            rootMountStockReplacementRequired(
                installedMatchesSourceVersion = true
            )
        )
        assertTrue(
            rootMountStockReplacementRequired(
                installedMatchesSourceVersion = false
            )
        )
    }

    @Test
    fun `renamed package uses configured fallback before system installer`() {
        val manager = source("domain/installer/InstallerManager.kt")
        val sequence = manager.substringAfter("private fun buildSequence(")
            .substringBefore("private fun availabilityFor(")
            .replace(Regex("\\s+"), " ")
        val rejectedPrimary = sequence.indexOf(
            "val rejectedPrimaryMount = " +
                "!allowMount && baseInstallerToken(primary) == Token.AutoSaved"
        )
        val earlyFallback = sequence.indexOf(
            "if (rejectedPrimaryMount && fallback != primary) add(fallback)"
        )
        val internalFallback = sequence.indexOf("if (Token.Internal !in tokens) add(Token.Internal)")

        assertTrue(rejectedPrimary >= 0)
        assertTrue(earlyFallback > rejectedPrimary)
        assertTrue(internalFallback > earlyFallback)
    }

    @Test
    fun `patcher does not treat a mounted payload as stock proof`() {
        val patcher = source("ui/viewmodel/PatcherViewModel.kt")
        val rootMount = patcher.substringAfter("private suspend fun performRootMount(")
            .substringBefore("fun confirmRootDowngrade()")

        assertTrue(rootMount.contains("val appMounted ="))
        assertTrue(rootMount.contains("rootMountStockReplacementRequired("))
        assertTrue(rootMount.contains("appMounted ->"))
        assertTrue(rootMount.contains("emptyList()"))
        assertTrue(rootMount.contains("applicationInfo.sourceDir resolves through the active bind mount"))
        assertTrue(rootMount.contains("withContext(Dispatchers.IO)"))
    }

    @Test
    fun `split input uses worker resolved stock identity before mount`() {
        val patcher = source("ui/viewmodel/PatcherViewModel.kt")
        val worker = source("patcher/worker/PatcherWorker.kt")
        val rootMount = patcher.substringAfter("private suspend fun performRootMount(")
            .substringBefore("fun confirmRootDowngrade()")

        assertFalse(patcher.contains("rootMountInputSupported"))
        assertTrue(worker.contains("INPUT_VERSION_NAME_KEY"))
        assertTrue(worker.contains("INPUT_VERSION_CODE_KEY"))
        assertTrue(rootMount.contains("val originalInputIsSplit"))
        assertTrue(rootMount.contains("val sourceVersionName = patchedSourceVersionName"))
        assertTrue(rootMount.contains("val targetVersionCode = patchedSourceVersionCode"))
        assertTrue(rootMount.contains("Patched APK source version code is unavailable"))
        assertTrue(rootMount.contains("input.selectedApp.version?.takeIf(String::isNotBlank)"))
        assertTrue(rootMount.contains("input.selectedApp.versionCode"))
        assertTrue(rootMount.contains("val installedMatchesSourceVersion ="))
        assertTrue(rootMount.contains("installedBaseInfo.versionName == sourceVersionName"))
        assertTrue(rootMount.contains("?.takeUnless { originalInputIsSplit }"))
        assertFalse(rootMount.contains("throw IllegalArgumentException(app.getString(R.string.mount_split_not_supported))"))
    }

    @Test
    fun `batch mount never guesses stock identity from patched output`() {
        val batch = source("domain/batch/BatchPatchCoordinator.kt")
        val rootMount = batch.substringAfter("private suspend fun installWithRootMount(")
            .substringBefore("private suspend fun executeRootMount(")

        assertTrue(rootMount.contains("item.version?.takeIf(String::isNotBlank)"))
        assertTrue(rootMount.contains("Patched APK source version name is unavailable"))
        assertTrue(rootMount.contains("val stockVersionCode = item.versionCode"))
        assertTrue(rootMount.contains("Patched APK source version code is unavailable"))
        assertFalse(rootMount.contains("item.version ?: patchedInfo.versionName.orEmpty()"))
        assertFalse(rootMount.contains("item.versionCode ?: patchedVersionCode"))
    }

    @Test
    fun `saved root app only rebuilds from an unambiguous saved payload`() {
        val saved = source("ui/viewmodel/InstalledAppInfoViewModel.kt")
        val mountSavedPayload = saved.substringAfter("private suspend fun mountSavedPayload(")
            .substringBefore("private suspend fun stockApksForRootSwitch(")
        val committedMount = mountSavedPayload.indexOf(
            "app?.installType == InstallType.MOUNT && !rootInstaller.isAppMounted(packageName)"
        )
        val fallbackLookup = mountSavedPayload.indexOf("val fallbackPayload = savedApkFile(app)")

        assertTrue(committedMount >= 0)
        assertTrue(fallbackLookup > committedMount)
        assertTrue(mountSavedPayload.contains("operation = RootMountOperation.MOUNT_ONLY"))
        assertTrue(mountSavedPayload.contains("patchedApk = fallbackPayload?.first"))
        assertTrue(mountSavedPayload.contains("pm.getVersionCode(savedPackage) == installedVersionCode"))
        assertFalse(mountSavedPayload.contains("retainedSourceMatchesInstalled"))
        assertFalse(mountSavedPayload.contains("filesystem.findOriginalAppFile("))
        assertFalse(mountSavedPayload.contains("expectedStockVersionCode"))
        assertTrue(mountSavedPayload.contains("stockApksForRootSwitch(packageName)"))
    }

    @Test
    fun `patcher and saved installs pass mount eligibility`() {
        val patcher = source("ui/viewmodel/PatcherViewModel.kt")
        val saved = source("ui/viewmodel/InstalledAppInfoViewModel.kt")

        assertTrue(
            Regex(
                """allowMount\s*=\s*usingMountInstall\s*&&\s*""" +
                    """(expectedPackage|currentPackageInfo\.packageName)\s*==\s*packageName"""
            )
                .findAll(patcher)
                .count() >= 5
        )
        assertTrue(saved.contains("val allowMount = app.supportsRootMount(targetPackage)"))
        assertTrue(saved.contains("allowMount = allowMount"))
    }

    @Test
    fun `root recovery states never offer the fallback installer`() {
        val patcher = source("ui/viewmodel/PatcherViewModel.kt")
        val patcherScreen = source("ui/screen/PatcherScreen.kt")
        val failureHandler = patcher.substringAfter("private fun showInstallFailure(")
            .substringBefore("private fun showSignatureMismatchPrompt(")
        val rootMount = patcher.substringAfter("private suspend fun performRootMount(")
            .substringBefore("fun confirmRootDowngrade()")

        assertTrue(failureHandler.contains("if (allowFallback) buildFallbackPrompt(adjusted) else null"))
        assertTrue(rootMount.contains("is RootMountResult.Busy ->"))
        assertTrue(rootMount.contains("R.string.root_mount_recovery_in_progress"))
        assertTrue(rootMount.contains("result.reason ?: \"Persisted phase: \""))
        assertTrue(rootMount.contains("showRootMountRecovery("))
        assertTrue(rootMount.contains("allowFallback = false"))
        assertTrue(patcherScreen.contains("viewModel.rootMountRecoveryMessage?.let"))
        assertTrue(patcherScreen.contains("R.string.root_mount_recovered_title"))
    }

    @Test
    fun `post patch installer pickers allow only compatible root mount mode overrides`() {
        val patcher = source("ui/viewmodel/PatcherViewModel.kt")
        val patcherScreen = source("ui/screen/PatcherScreen.kt")
        val batchActions = source("ui/screen/BatchResultActions.kt")
        val batchViewModel = source("ui/viewmodel/BatchPatcherViewModel.kt")
        val batchCoordinator = source("domain/batch/BatchPatchCoordinator.kt")
        val batchModels = source("domain/batch/BatchPatchModels.kt")
        val installerManager = source("domain/installer/InstallerManager.kt")
        val rootInstaller = source("domain/installer/RootInstaller.kt")
        val rootPlayStoreInstall = source("domain/installer/root/RootPlayStoreInstall.kt")
        val rootExternalInstallRecovery = source(
            "domain/installer/root/RootExternalInstallRecovery.kt"
        )
        val installerPicker = source("ui/component/patcher/InstallPickerDialog.kt")
        val installerConfiguration = source(
            "ui/component/patcher/ShizukuConfigurationDialog.kt"
        )
        val patchAvailability = source("patcher/patch/PatchAvailability.kt")
        val filesystem = source("data/platform/Filesystem.kt")
        val appInfoScreen = source("ui/screen/InstalledAppInfoScreen.kt")
        val dashboard = source("ui/screen/DashboardScreen.kt")
        val patcherSignerCheck = patcher
            .substringAfter("private fun installedSignerMatchesStockSource(")
            .substringBefore("private suspend fun canSelectRootMountForPatchedOutput(")
        val batchSignerCheck = batchCoordinator
            .substringAfter("private fun installedSignerMatchesStockSource(")
            .substringBefore("private suspend fun patchSelectionSupportsRootMountModeOverride(")
        val selectedInstall = patcher
            .substringAfter("fun installWithSelectedToken(")
            .substringBefore("private fun installWithTokenInternal(")
        val batchPersistence = batchCoordinator
            .substringAfter("private suspend fun persistResult(")
            .substringBefore("private suspend fun prunePersistedBatchOutputs(")

        assertTrue(
            patcherSignerCheck.indexOf("val stockArchive") <
                patcherSignerCheck.indexOf("if (installedInfo == null) return true")
        )
        assertTrue(
            batchSignerCheck.indexOf("val stockArchive") <
                batchSignerCheck.indexOf("if (installedInfo == null) return true")
        )
        assertTrue(patcherScreen.contains("val supportsRootMount = viewModel.supportsRootMount"))
        assertTrue(patcherScreen.contains("val mountModeSupportsRootMount = viewModel.usingMountInstall && supportsRootMount"))
        assertTrue(patcherScreen.contains("LaunchedEffect(mountModeSupportsRootMount)"))
        assertFalse(patcherScreen.contains("patchAvailabilityEnabled"))
        assertTrue(patcherScreen.contains("viewModel.isInstallerTokenSelectable(entry.token)"))
        assertTrue(patcherScreen.contains("entry.token == InstallerManager.Token.AutoSaved && !supportsRootMount"))
        assertTrue(patcherScreen.contains("onConfirm = viewModel::installWithSelectedToken"))
        assertTrue(patcher.contains("private suspend fun patchSelectionSupportsRootMountModeOverride("))
        assertTrue(patcher.contains("if (completedPatchHadFailures) return false"))
        assertTrue(patcher.contains("appliedSelection.isCompatibleWithInstallerRules("))
        assertTrue(patcher.contains("completedPatchHadFailures = failedPatchIndexes.isNotEmpty()"))
        assertTrue(patchAvailability.contains("fun PatchSelection.isCompatibleWithInstallerRules("))
        assertTrue(patchAvailability.contains("return adjusted == current"))
        assertTrue(patcher.contains("private fun verifiedStandaloneStockCandidates("))
        assertTrue(patcher.contains("fs.findOriginalAppFiles("))
        assertTrue(filesystem.contains("fun findOriginalAppFiles("))
        assertTrue(filesystem.contains("findOriginalAppFiles(packageName, version, versionCode).firstOrNull()"))
        assertTrue(patcher.contains("pm.getPackageInfo(candidate, includeSigning = true)"))
        assertTrue(patcher.contains("pm.getSignature(info) != null"))
        assertTrue(patcher.contains("private suspend fun canSelectRootMountForPatchedOutput("))
        assertTrue(patcher.contains("pm.getPackageInfo(outputFile, includeSigning = true)"))
        assertTrue(patcher.contains("fs.isManagedPatchedAppFile(candidate)"))
        assertTrue(patcher.contains("info.sharedUserId == null"))
        assertTrue(patcher.contains("packageInfoIsCompleteSingleApk(info)"))
        assertTrue(patcher.contains("private fun installedSignerMatchesStockSource("))
        assertTrue(patcher.contains("pm.getSignature(installedInfo.packageName).toByteArray()"))
        assertTrue(patcher.contains("val stockSource = stockCandidates.firstOrNull"))
        assertTrue(patcher.contains("installedSignerMatchesStockSource(installedInfo, it)"))
        assertTrue(patcher.contains("private fun installedPackageMatchesSourceVersion("))
        assertTrue(patcher.contains("pm.getVersionCode(installed) == sourceVersionCode"))
        assertTrue(patcher.contains("rootMountStockIdentityUsable("))
        assertTrue(patcher.contains("hasStandaloneStockSource = stockCandidates.isNotEmpty()"))
        assertTrue(patcher.contains("standaloneStockIdentityCompatible = stockSource != null"))
        assertTrue(patcher.contains("patchedIsCompleteSingleApk = packageInfoIsCompleteSingleApk(patched)"))
        assertTrue(patcher.contains("patchedHasSigningCertificate = pm.getSignature(patched) != null"))
        assertTrue(patcher.contains("installedHasSharedUserId = installedInfo?.sharedUserId != null"))
        assertTrue(patcher.contains("hasUsableStockIdentity = hasUsableStockIdentity"))
        assertTrue(patcher.contains("patchedVersionMatchesSource = patched.versionName == sourceVersionName"))
        assertTrue(patcher.contains("supportsRootMount = patchedPackageInfo?.packageName == packageName"))
        assertTrue(patcher.contains("stockNeedsReplacement -> {"))
        assertTrue(patcher.contains("val stock = verifiedStandaloneStockCandidates("))
        assertTrue(patcher.contains("installedSignerMatchesStockSource(installedBaseInfo, candidate)"))
        assertTrue(patcher.contains("private fun refreshRootMountModeOverrideAsync()"))
        assertTrue(patcher.contains("rootMountModeOverrideRefreshJob?.cancel()"))
        assertTrue(patcher.contains("rootMountModeOverrideRefreshJob = viewModelScope.launch(Dispatchers.IO)"))
        assertTrue(patcher.contains("withContext(Dispatchers.Main)"))
        assertTrue(patcher.contains("refreshRootMountModeOverrideAsync()"))
        assertTrue(patcher.contains("allowModeOverride = false"))
        assertTrue(patcher.contains("fun installWithSelectedToken(token: InstallerManager.Token)"))
        assertTrue(selectedInstall.contains("if (!crossModeMountRequested)"))
        assertFalse(selectedInstall.contains("getBlocking()"))
        assertTrue(selectedInstall.contains("withContext(Dispatchers.IO)"))
        assertTrue(selectedInstall.contains("canSelectRootMountForPatchedOutput()"))
        assertTrue(patcher.contains("allowModeOverride = true"))
        assertTrue(
            patcher.contains(
                "installerManager.baseInstallerToken(token) == InstallerManager.Token.AutoSaved"
            )
        )
        assertTrue(patcher.contains("allowMount = requestedMount && expectedPackage == packageName"))
        assertTrue(batchActions.contains("installerTokenSelectableForPatchedOutput("))
        assertTrue(batchActions.contains("withContext(Dispatchers.IO)"))
        assertFalse(batchActions.contains("patchAvailabilityEnabled"))
        assertTrue(batchActions.contains("val requestedPackageName = requestedItem.packageName"))
        assertTrue(batchActions.contains("val requestedItemKey = Triple("))
        assertTrue(batchActions.contains("if (currentInstallerPickerItemKey != requestedItemKey) return false"))
        assertTrue(batchActions.contains("installerPickerSupportsRootMount = supportsRootMount"))
        assertTrue(batchActions.contains("installerPickerSupportsRootMountPackage = supportsRootMountPackage"))
        assertTrue(batchActions.contains("installerPickerEligibilityItemKey = requestedItemKey"))
        assertTrue(batchActions.contains("LaunchedEffect(showInstallerPicker, currentInstallerPickerItemKey)"))
        assertTrue(batchActions.contains("refreshInstallerPickerEligibility(currentItem)"))
        assertTrue(batchActions.contains("!requestedItem.useMount &&"))
        assertTrue(batchActions.contains("supportsRootMount = installerPickerSupportsRootMount"))
        assertTrue(batchActions.contains("!installerPickerSupportsRootMountPackage"))
        assertTrue(batchViewModel.contains("suspend fun supportsRootMount(packageName: String)"))
        assertTrue(batchViewModel.contains("coordinator.supportsRootMountModeOverride(packageName)"))
        assertTrue(batchViewModel.contains("fun supportsRootMountPackage(packageName: String)"))
        assertTrue(batchCoordinator.contains("private suspend fun patchSelectionSupportsRootMountModeOverride("))
        assertTrue(batchCoordinator.contains("if (item.hadPatchFailures) return false"))
        assertTrue(batchCoordinator.contains("item.selection.isCompatibleWithInstallerRules("))
        assertTrue(batchCoordinator.contains("hadPatchFailures = patchResult.hadPatchFailures"))
        assertTrue(batchCoordinator.contains("hadPatchFailures = info.outputData"))
        assertTrue(batchPersistence.contains("bundles = item.bundles,"))
        assertTrue(
            batchPersistence.indexOf("bundles = item.bundles,") <
                batchPersistence.indexOf("bundle.copy(patchNames = emptySet())")
        )
        assertTrue(batchPersistence.contains("item.copy(logLines = emptyList())"))
        assertTrue(batchModels.contains("val hadPatchFailures: Boolean = false"))
        assertTrue(batchModels.contains("val hadPatchFailures: Boolean = true"))
        assertTrue(batchCoordinator.contains("private fun verifiedStandaloneStockCandidates("))
        assertTrue(batchCoordinator.contains("fs.findOriginalAppFiles("))
        assertTrue(batchCoordinator.contains("val localSource = (item.input as? SelectedApp.Local)?.file"))
        assertTrue(batchCoordinator.contains("pm.getPackageInfo(candidate, includeSigning = true)"))
        assertTrue(batchCoordinator.contains("pm.getSignature(info) != null"))
        assertTrue(batchCoordinator.contains("pm.getPackageInfo(patchedFile, includeSigning = true)"))
        assertTrue(batchCoordinator.contains("fs.isManagedPatchedAppFile(candidate)"))
        assertTrue(batchCoordinator.contains("info.sharedUserId == null"))
        assertTrue(batchCoordinator.contains("packageInfoIsCompleteSingleApk(info)"))
        assertTrue(batchCoordinator.contains("private fun installedSignerMatchesStockSource("))
        assertTrue(batchCoordinator.contains("pm.getSignature(installedInfo.packageName).toByteArray()"))
        assertTrue(batchCoordinator.contains("val stockSource = stockCandidates.firstOrNull"))
        assertTrue(batchCoordinator.contains("installedSignerMatchesStockSource(installedInfo, it)"))
        assertTrue(batchCoordinator.contains("private fun installedPackageMatchesSourceVersion("))
        assertTrue(batchCoordinator.contains("pm.getVersionCode(installed) == sourceVersionCode"))
        assertTrue(batchCoordinator.contains("rootMountStockIdentityUsable("))
        assertTrue(batchCoordinator.contains("hasStandaloneStockSource = stockCandidates.isNotEmpty()"))
        assertTrue(batchCoordinator.contains("standaloneStockIdentityCompatible = stockSource != null"))
        assertTrue(batchCoordinator.contains("suspend fun supportsRootMountModeOverride(packageName: String)"))
        assertTrue(batchCoordinator.contains("patchedIsCompleteSingleApk = packageInfoIsCompleteSingleApk(patched)"))
        assertTrue(batchCoordinator.contains("patchedHasSigningCertificate = pm.getSignature(patched) != null"))
        assertTrue(batchCoordinator.contains("installedHasSharedUserId = installedInfo?.sharedUserId != null"))
        assertTrue(batchCoordinator.contains("hasUsableStockIdentity = hasUsableStockIdentity"))
        assertTrue(installerManager.contains("com.android.vending.splits.required"))
        assertTrue(installerManager.contains("packageInfo.splitNames.isNullOrEmpty()"))
        assertTrue(installerManager.contains("packageInfo.applicationInfo?.splitSourceDirs.isNullOrEmpty()"))
        assertTrue(installerManager.contains("InstallPlan.Mount(target, installAsPlayStore = true)"))
        assertFalse(rootInstaller.contains("pm set-installer"))
        assertTrue(rootInstaller.contains("stageInstalledBaseApk"))
        assertTrue(rootInstaller.contains("installAsPlayStore"))
        assertTrue(rootPlayStoreInstall.contains("reinstallMountedStockAsPlayStore"))
        assertTrue(rootPlayStoreInstall.contains("restoreMountAfterPackageChange = true"))
        assertTrue(rootExternalInstallRecovery.contains("record.restoreMountAfterPackageChange"))
        assertTrue(installerPicker.contains("PlayStoreSourceConfigurationDialog("))
        assertFalse(installerPicker.contains("val showPlayStoreToggle"))
        assertTrue(installerConfiguration.contains("fun PlayStoreSourceConfigurationDialog("))
        assertTrue(batchCoordinator.contains("R.string.mount_split_not_supported"))
        assertTrue(batchCoordinator.contains("R.string.root_mount_incompatible_output"))
        assertTrue(batchCoordinator.contains("val crossModeMountRequested = installerToken != null && !item.useMount && requestedMount"))
        assertFalse(batchCoordinator.contains("val crossModeMountAllowed ="))
        assertTrue(batchCoordinator.contains("supportsRootMountModeOverride = crossModeMountRequested &&"))
        assertTrue(batchCoordinator.contains("if (crossModeMountRequested && !supportsRootMountModeOverride)"))
        assertTrue(batchCoordinator.contains("verifiedStandaloneStockCandidates(item, targetPackage)"))
        assertTrue(batchCoordinator.contains("installedSignerMatchesStockSource(installedInfo, candidate)"))
        assertTrue(batchCoordinator.contains("allowMount = requestedMount && supportsRootMount"))
        assertTrue(appInfoScreen.contains("!viewModel.supportsRootMount"))
        assertTrue(dashboard.contains("!quickActionViewModel.supportsRootMount"))
    }

    @Test
    fun `saved rooted mount deletion removes the root module first`() {
        val list = source("ui/viewmodel/InstalledAppsViewModel.kt")
        val detail = source("ui/viewmodel/InstalledAppInfoViewModel.kt")
        val screen = source("ui/screen/InstalledAppInfoScreen.kt")

        assertTrue(list.contains("removeModuleAfterUnmount = true"))
        assertTrue(detail.contains("removeModuleAfterUnmount = true"))
        assertTrue(list.indexOf("removeRootMountModule(app)") < list.indexOf("installedAppsRepository.delete(app)"))
        assertTrue(list.contains("if (clearSavedData(installedApp, deleteRecord = true))"))
        assertTrue(list.contains("toDelete.forEach { app ->"))
        assertTrue(list.contains("if (deleteAppEntry(app))"))
        val detailDelete = detail.substringAfter("private suspend fun clearSavedData(")
            .substringBefore("private suspend fun removeRootMountModule(")
        assertTrue(
            detailDelete.indexOf("removeRootMountModule(app)") <
                detailDelete.indexOf("installedAppRepository.delete(app)")
        )
        assertTrue(detail.contains("val deletingSavedRootApp = app.installType == InstallType.MOUNT"))
        assertTrue(detail.contains("isDeletingSavedRootApp = true"))
        assertTrue(screen.contains("delete_root_mount_saved_app_description"))
        assertTrue(screen.contains("if (viewModel.isDeletingSavedRootApp)"))
        assertTrue(screen.contains("delete_root_mount_saved_app_progress"))
    }

    private fun source(relativePath: String): String = sequenceOf(
        File("app/src/main/java/app/urv/manager/$relativePath"),
        File("src/main/java/app/urv/manager/$relativePath")
    ).first { it.isFile }.readText()
}
