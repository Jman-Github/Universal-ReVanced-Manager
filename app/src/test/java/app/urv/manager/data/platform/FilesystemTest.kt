package app.urv.manager.data.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilesystemTest {
    @Test
    fun restoredLeaseExpiresAtMaximumAge() {
        assertFalse(
            isPatchOptionInputLeaseExpired(
                leasedAtMillis = 1_000L,
                nowMillis = 1_999L,
                maxAgeMillis = 1_000L
            )
        )
        assertTrue(
            isPatchOptionInputLeaseExpired(
                leasedAtMillis = 1_000L,
                nowMillis = 2_000L,
                maxAgeMillis = 1_000L
            )
        )
    }

    @Test
    fun restoredLeaseDoesNotExpireWhenClockMovesBackwards() {
        assertFalse(
            isPatchOptionInputLeaseExpired(
                leasedAtMillis = 2_000L,
                nowMillis = 1_000L,
                maxAgeMillis = 1_000L
            )
        )
    }

    @Test
    fun managedInputExtensionPreservesValidLongExtensions() {
        assertEquals(
            "mobileconfig",
            normalizeManagedPatchOptionInputExtension(".MobileConfig")
        )
    }

    @Test
    fun managedInputExtensionRejectsUnsafeNames() {
        assertEquals("dat", normalizeManagedPatchOptionInputExtension("../config"))
        assertEquals("dat", normalizeManagedPatchOptionInputExtension("bad\u0000name"))
    }

    @Test
    fun patchedAppLookupDoesNotCrossSavedVariantBoundaries() {
        assertTrue(
            patchedAppFileNameMatchesPackage(
                "com.example.app_1.0.apk",
                "com.example.app"
            )
        )
        assertFalse(
            patchedAppFileNameMatchesPackage(
                "com.example.app__bundle_abc123_1.0.apk",
                "com.example.app"
            )
        )
        assertTrue(
            patchedAppFileNameMatchesPackage(
                "com.example.app__bundle_abc123_1.0.apk",
                "com.example.app__bundle_abc123"
            )
        )
    }

    @Test
    fun retainedOriginalIdentityIncludesVersionCode() {
        assertEquals("1.0_100_original", retainedOriginalFileStem("1.0", 100L))
        assertEquals("1.0_101_original", retainedOriginalFileStem("1.0", 101L))
    }

    @Test
    fun exactRetainedOriginalMatchRejectsDifferentVersionCode() {
        assertTrue(retainedOriginalFileMatches("1.0_100_original.apk", "1.0", 100L))
        assertFalse(retainedOriginalFileMatches("1.0_101_original.apk", "1.0", 100L))
        assertTrue(retainedOriginalFileMatches("1.0_original.apk", "1.0", 100L))
    }

    @Test
    fun versionOnlyRetainedOriginalMatchAcceptsCodeSpecificFiles() {
        assertTrue(retainedOriginalFileMatches("1.0_100_original.apk", "1.0", null))
        assertTrue(retainedOriginalFileMatches("1.0_101_original.apks", "1.0", null))
        assertFalse(retainedOriginalFileMatches("2.0_100_original.apk", "1.0", null))
    }
}
