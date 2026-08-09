package app.urv.manager.domain.installer

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MountInstallerRoutingContractTest {
    @Test
    fun `installer tokens must match the patch mode`() {
        assertTrue(installerTokenMatchesPatchMode(InstallerManager.Token.AutoSaved, true))
        assertFalse(installerTokenMatchesPatchMode(InstallerManager.Token.Internal, true))
        assertFalse(installerTokenMatchesPatchMode(InstallerManager.Token.Shizuku, true))
        assertFalse(installerTokenMatchesPatchMode(InstallerManager.Token.AutoSaved, false))
        assertTrue(installerTokenMatchesPatchMode(InstallerManager.Token.Internal, false))
        assertTrue(installerTokenMatchesPatchMode(InstallerManager.Token.Shizuku, false))
    }

    @Test
    fun `renamed packages cannot resolve to mount`() {
        val manager = source("domain/installer/InstallerManager.kt")
        assertTrue(manager.contains("buildSequence(target, sourceFile, allowMount)"))
        assertTrue(manager.contains("if (!allowMount && token == Token.AutoSaved)"))
        assertTrue(manager.contains("allowMount = false"))
    }

    @Test
    fun `renamed package uses configured fallback before system installer`() {
        val manager = source("domain/installer/InstallerManager.kt")
        val sequence = manager.substringAfter("private fun buildSequence(")
            .substringBefore("private fun availabilityFor(")
        val rejectedPrimary = sequence.indexOf(
            "val rejectedPrimaryMount = !allowMount && primary == Token.AutoSaved"
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

        assertTrue(rootMount.contains("else if (rootInstaller.isAppMounted(packageName))"))
        assertTrue(rootMount.contains("emptyList()"))
        assertTrue(rootMount.contains("applicationInfo.sourceDir resolves through the active bind mount"))
    }

    @Test
    fun `merged split input reaches root preflight with installed stock proof`() {
        val patcher = source("ui/viewmodel/PatcherViewModel.kt")
        val rootMount = patcher.substringAfter("private suspend fun performRootMount(")
            .substringBefore("fun confirmRootDowngrade()")

        assertFalse(patcher.contains("rootMountInputSupported"))
        assertTrue(rootMount.contains("val originalInputIsSplit"))
        assertTrue(rootMount.contains("if (originalInputIsSplit) installedStock"))
        assertFalse(rootMount.contains("throw IllegalArgumentException(app.getString(R.string.mount_split_not_supported))"))
    }

    @Test
    fun `saved root app can rebuild a missing committed payload after unmount`() {
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
        assertTrue(mountSavedPayload.contains("stockApksForRootSwitch(packageName)"))
    }

    @Test
    fun `patcher and saved installs pass package compatibility`() {
        val patcher = source("ui/viewmodel/PatcherViewModel.kt")
        val saved = source("ui/viewmodel/InstalledAppInfoViewModel.kt")

        assertTrue(
            Regex("allowMount = (expectedPackage|currentPackageInfo\\.packageName) == packageName")
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
    fun `renamed apps hide mount installer choices`() {
        val patcherScreen = source("ui/screen/PatcherScreen.kt")
        val appInfoScreen = source("ui/screen/InstalledAppInfoScreen.kt")
        val dashboard = source("ui/screen/DashboardScreen.kt")

        assertTrue(patcherScreen.contains("entry.token == InstallerManager.Token.AutoSaved && !supportsRootMount"))
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
