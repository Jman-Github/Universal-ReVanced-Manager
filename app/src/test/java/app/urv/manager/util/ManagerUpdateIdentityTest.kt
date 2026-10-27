package app.urv.manager.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManagerUpdateIdentityTest {
    private val urv = "app.universal.revanced.manager"
    private val upmUrl = "https://example.com/releases/download/v2.0.0/universal-patch-manager-v2.0.0-arm64-v8a.apk"

    @Test fun bothBrandsKeepAllSupportedAbiAndLegacyProfileNames() {
        for (brand in listOf("revanced", "patch")) {
            for (abi in listOf("arm64-v8a", "arm64_v8", "armeabi-v7a", "armeabi_v7a", "x86", "x86_64", "all", "universal")) {
                for (profile in listOf("", "FULL-", "LITE-")) {
                    val parsed = assertNotNull(parseManagerReleaseAssetName(
                        "${profile}universal-$brand-manager-v2.0.0-dev.2-$abi.apk"
                    ))
                    assertEquals(abi, parsed.abiSuffix)
                    assertEquals(profile.removeSuffix("-").ifEmpty { null }, parsed.legacyBuildProfile)
                    assertEquals(brand == "patch", parsed.isUpm)
                }
            }
        }
    }

    @Test fun plainAndUppercaseReleaseNamesStillWork() {
        assertNull(assertNotNull(parseManagerReleaseAssetName("universal-revanced-manager-2.0.0.apk")).abiSuffix)
        val parsed = assertNotNull(parseManagerReleaseAssetName("full-UNIVERSAL-PATCH-MANAGER-V2.0.0-ARM64-V8A.APK"))
        assertEquals("FULL", parsed.legacyBuildProfile)
        assertEquals("arm64-v8a", parsed.abiSuffix)
        assertTrue(parsed.isUpm)
    }

    @Test fun unrelatedAndMalformedAssetsAreRejected() {
        for (name in listOf("reseam-plugin.apk", "universal-patch-manager-plugin-2.0.0.apk",
            "universal-patch-manager-.apk", "universal-patch-manager-v2.0.apk",
            "universal-patch-manager-v2.0.0.apk.sig", "other-universal-patch-manager-v2.0.0.apk")) {
            assertNull(parseManagerReleaseAssetName(name), name)
        }
    }

    @Test fun migrationDetectionUsesOnlyTheDownloadedFilename() {
        assertTrue(managerReleaseRequiresMigration(urv, "$upmUrl?token=a#download"))
        assertTrue(managerReleaseRequiresMigration("$urv.pr", upmUrl))
        assertFalse(managerReleaseRequiresMigration(UPM_PACKAGE_NAME, upmUrl))
        assertFalse(managerReleaseRequiresMigration(urv,
            "https://example.com/universal-patch-manager-v2.0.0/other.apk"))
        assertFalse(managerReleaseRequiresMigration(urv,
            "https://example.com/universal-revanced-manager-v2.0.0.apk?file=universal-patch-manager-v2.0.0.apk"))
        assertFalse(managerReleaseRequiresMigration(urv, "not a URL"))
    }

    @Test fun packageAllowlistPreservesCurrentIdentityAndAllowsOnlyTheUpmMigration() {
        assertTrue(isSupportedManagerUpdatePackage(urv, urv))
        assertTrue(isSupportedManagerUpdatePackage("$urv.pr", "$urv.pr"))
        assertTrue(isSupportedManagerUpdatePackage(urv, UPM_PACKAGE_NAME))
        assertTrue(isSupportedManagerUpdatePackage("$urv.pr", UPM_PACKAGE_NAME))
        assertTrue(isSupportedManagerUpdatePackage(UPM_PACKAGE_NAME, UPM_PACKAGE_NAME))
        assertFalse(isSupportedManagerUpdatePackage(urv, "$urv.pr"))
        assertFalse(isSupportedManagerUpdatePackage(urv, "other.package"))
        assertFalse(isSupportedManagerUpdatePackage("other.package", UPM_PACKAGE_NAME))
        assertFalse(isSupportedManagerUpdatePackage(UPM_PACKAGE_NAME, urv))
    }

    @Test fun partialDownloadsAreNotSharedBetweenDifferentAssetsWithTheSameVersion() {
        val legacyUrl = upmUrl.replace("universal-patch-manager", "universal-revanced-manager")
        assertNotEquals(managerUpdateDownloadIdentity("2.0.0", upmUrl),
            managerUpdateDownloadIdentity("2.0.0", legacyUrl))
        assertEquals(managerUpdateDownloadIdentity("2.0.0", upmUrl),
            managerUpdateDownloadIdentity("2.0.0", upmUrl))
    }

    @Test fun noticeCannotBeAcceptedOrClosedBeforeTenSeconds() {
        var now = 500L
        val notice = ManagerRebrandConfirmation { now }
        assertTrue(notice.request("release"))
        assertEquals(10, notice.secondsRemaining)
        assertFalse(notice.acknowledge())
        assertFalse(notice.cancel())
        now += 9_999L
        assertEquals(1, notice.secondsRemaining)
        assertFalse(notice.acknowledge())
        assertFalse(notice.cancel())
        now += 1L
        assertEquals(0, notice.secondsRemaining)
        assertTrue(notice.acknowledge())
        assertFalse(notice.request("release"))
    }

    @Test fun recompositionOrRepeatedRequestsDoNotRestartTheCountdown() {
        var now = 0L
        val notice = ManagerRebrandConfirmation { now }
        notice.request("release")
        now = 5_000L
        assertTrue(notice.request("release"))
        assertEquals(5, notice.secondsRemaining)
        now = 50_000L
        assertEquals(0, notice.secondsRemaining)
        assertTrue(notice.acknowledge())
    }

    @Test fun cancelDoesNotApproveDownloadingAndAnotherAssetNeedsItsOwnNotice() {
        var now = 0L
        val notice = ManagerRebrandConfirmation { now }
        notice.request("old")
        now = 10_000L
        assertTrue(notice.cancel())
        assertTrue(notice.request("old"))
        assertEquals(10, notice.secondsRemaining)
        now = 20_000L
        assertTrue(notice.acknowledge())
        assertTrue(notice.request("new"))
        assertEquals(10, notice.secondsRemaining)
        assertFalse(notice.acknowledge())
    }
}
