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
}
