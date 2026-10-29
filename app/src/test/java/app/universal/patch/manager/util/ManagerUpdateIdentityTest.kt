package app.universal.patch.manager.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManagerUpdateIdentityTest {
    private val legacyPackage = "app.universal.revanced.manager"
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
        assertTrue(managerReleaseRequiresMigration(legacyPackage, "$upmUrl?token=a#download"))
        assertTrue(managerReleaseRequiresMigration("$legacyPackage.pr", upmUrl))
        assertFalse(managerReleaseRequiresMigration(UPM_PACKAGE_NAME, upmUrl))
        assertFalse(managerReleaseRequiresMigration(legacyPackage,
            "https://example.com/universal-patch-manager-v2.0.0/other.apk"))
        assertFalse(managerReleaseRequiresMigration(legacyPackage,
            "https://example.com/universal-revanced-manager-v2.0.0.apk?file=universal-patch-manager-v2.0.0.apk"))
        assertFalse(managerReleaseRequiresMigration(legacyPackage, "not a URL"))
    }

    @Test fun packageAllowlistPreservesCurrentIdentityAndAllowsOnlyTheUpmMigration() {
        assertTrue(isSupportedManagerUpdatePackage(legacyPackage, legacyPackage))
        assertTrue(isSupportedManagerUpdatePackage("$legacyPackage.pr", "$legacyPackage.pr"))
        assertTrue(isSupportedManagerUpdatePackage(legacyPackage, UPM_PACKAGE_NAME))
        assertTrue(isSupportedManagerUpdatePackage("$legacyPackage.pr", UPM_PACKAGE_NAME))
        assertTrue(isSupportedManagerUpdatePackage(UPM_PACKAGE_NAME, UPM_PACKAGE_NAME))
        assertFalse(isSupportedManagerUpdatePackage(legacyPackage, "$legacyPackage.pr"))
        assertFalse(isSupportedManagerUpdatePackage(legacyPackage, "other.package"))
        assertFalse(isSupportedManagerUpdatePackage("other.package", UPM_PACKAGE_NAME))
        assertFalse(isSupportedManagerUpdatePackage(UPM_PACKAGE_NAME, legacyPackage))
    }

    @Test fun mixedReleasesPreferUpmRegardlessOfUploadOrder() {
        val legacy = "universal-revanced-manager-v2.0.0-arm64-v8a.apk"
        val current = "universal-patch-manager-v2.0.0-arm64-v8a.apk"
        for (names in listOf(listOf(legacy, current), listOf(current, legacy))) {
            assertEquals(current, pickManagerReleaseAssetName(names, listOf("arm64-v8a")))
        }
    }

    @Test fun newBrandUniversalFallbackBeatsAnOldBrandNativeApk() {
        val legacy = "universal-revanced-manager-v2.0.0-arm64-v8a.apk"
        val current = "universal-patch-manager-v2.0.0-universal.apk"
        assertEquals(current, pickManagerReleaseAssetName(listOf(legacy, current), listOf("arm64-v8a")))
        assertNull(pickManagerReleaseAssetName(
            listOf(legacy, "universal-patch-manager-v2.0.0-x86.apk"), listOf("arm64-v8a")))
    }

    @Test fun legacyReleasesAndAbiAliasesRemainUsable() {
        val legacy = "FULL-universal-revanced-manager-v2.0.0-arm64_v8.apk"
        assertEquals(legacy, pickManagerReleaseAssetName(listOf(legacy), listOf("arm64-v8a")))
    }

    @Test fun profilePreferenceRemainsPlainThenFullThenLite() {
        val plain = "universal-patch-manager-v2.0.0-universal.apk"
        val full = "FULL-universal-patch-manager-v2.0.0-arm64-v8a.apk"
        val lite = "LITE-universal-patch-manager-v2.0.0-arm64-v8a.apk"
        assertEquals(plain, pickManagerReleaseAssetName(listOf(lite, full, plain), listOf("arm64-v8a")))
        assertEquals(full, pickManagerReleaseAssetName(listOf(lite, full), listOf("arm64-v8a")))
        assertEquals(lite, pickManagerReleaseAssetName(listOf(lite), listOf("arm64-v8a")))
    }

    @Test fun deviceAbiOrderIsPreservedAndUnrelatedFilesAreIgnored() {
        val arm64 = "universal-patch-manager-v2.0.0-arm64-v8a.apk"
        val arm32 = "universal-patch-manager-v2.0.0-armeabi-v7a.apk"
        assertEquals(arm32, pickManagerReleaseAssetName(
            listOf("reseam-plugin.apk", arm64, arm32), listOf("armeabi-v7a", "arm64-v8a")))
        assertNull(pickManagerReleaseAssetName(listOf("reseam-plugin.apk"), listOf("arm64-v8a")))
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
