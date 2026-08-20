package app.urv.manager.domain.batch

import app.urv.manager.domain.installer.InstallerManager
import app.urv.manager.domain.installer.root.RootMountPhase
import app.urv.manager.domain.installer.root.RootMountResult
import app.urv.manager.domain.installer.root.RootRecoveryState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BatchInstallerRoutingContractTest {
    @Test
    fun `scheduled Shizuku patching resolves Standard availability`() {
        assertEquals(false, batchForcedUseMount(scheduled = true, autoInstallWithShizuku = true))
        assertEquals(null, batchForcedUseMount(scheduled = true, autoInstallWithShizuku = false))
        assertEquals(null, batchForcedUseMount(scheduled = false, autoInstallWithShizuku = true))
    }

    @Test
    fun `unsafe root mount outcomes stop installer fallback`() {
        assertFalse(rootMountAllowsBatchFallback(RootMountResult.Busy(null)))
        assertFalse(
            rootMountAllowsBatchFallback(
                RootMountResult.Failure(
                    phase = RootMountPhase.ROLLING_BACK,
                    recoveryState = RootRecoveryState.NONE,
                    diagnosticId = "diagnostic",
                    message = "failed"
                )
            )
        )
        assertFalse(
            rootMountAllowsBatchFallback(
                RootMountResult.RecoveredToPreviousMount("tx", "diagnostic")
            )
        )
    }

    @Test
    fun `interactive default install can offer the configured fallback`() {
        assertTrue(
            shouldOfferBatchFallback(
                scheduled = false,
                chooseInstallerPerInstall = false,
                explicitInstaller = false,
                attemptedToken = InstallerManager.Token.Shizuku,
                primaryToken = InstallerManager.Token.Shizuku,
                fallbackToken = InstallerManager.Token.Internal
            )
        )
    }

    @Test
    fun `automatic and explicit installs never offer the global fallback`() {
        assertFalse(
            shouldOfferBatchFallback(
                scheduled = true,
                chooseInstallerPerInstall = false,
                explicitInstaller = false,
                attemptedToken = InstallerManager.Token.Shizuku,
                primaryToken = InstallerManager.Token.Shizuku,
                fallbackToken = InstallerManager.Token.Internal
            )
        )
        assertFalse(
            shouldOfferBatchFallback(
                scheduled = false,
                chooseInstallerPerInstall = false,
                explicitInstaller = true,
                attemptedToken = InstallerManager.Token.Shizuku,
                primaryToken = InstallerManager.Token.Shizuku,
                fallbackToken = InstallerManager.Token.Internal
            )
        )
        assertFalse(
            shouldOfferBatchFallback(
                scheduled = false,
                chooseInstallerPerInstall = true,
                explicitInstaller = false,
                attemptedToken = InstallerManager.Token.Shizuku,
                primaryToken = InstallerManager.Token.Shizuku,
                fallbackToken = InstallerManager.Token.Internal
            )
        )
    }

    @Test
    fun `verified stock recovery permits installer fallback`() {
        assertTrue(
            rootMountAllowsBatchFallback(
                RootMountResult.RecoveredToStock("tx", "diagnostic")
            )
        )
        assertTrue(
            rootMountAllowsBatchFallback(
                RootMountResult.Failure(
                    phase = RootMountPhase.ROLLING_BACK,
                    recoveryState = RootRecoveryState.STOCK,
                    diagnosticId = "diagnostic",
                    message = "failed"
                )
            )
        )
        assertTrue(
            rootMountAllowsBatchFallback(
                RootMountResult.RequiresRepatch("Repatch required")
            )
        )
    }
}
