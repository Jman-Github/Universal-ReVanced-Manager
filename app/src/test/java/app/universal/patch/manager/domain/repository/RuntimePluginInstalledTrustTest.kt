package app.universal.patch.manager.domain.repository

import org.junit.Assert.*
import org.junit.Test

class RuntimePluginInstalledTrustTest {
    private val original = RuntimePluginInstalledTrust("AA", "fixture.runtime", 12345, 1000L)

    private fun RuntimePluginInstalledTrust.accepts(
        signer: String,
        packageName: String = "fixture.runtime",
        uid: Int = 12345,
        firstInstallTime: Long = 1000L
    ) = trusts(packageName, uid, firstInstallTime, RuntimePluginSigners(listOf(signer)))

    @Test fun updatedApkInTheApprovedInstallationInheritsTrust() {
        assertTrue(original.accepts("BB"))
        assertTrue(RuntimePluginInstalledTrust.decode(original.encode())!!.accepts("BB"))
    }

    @Test fun unrelatedSignerCannotInheritTrustAfterAReinstall() {
        assertFalse(original.accepts("BB", firstInstallTime = 2000L))
        assertFalse(original.accepts("BB", uid = 54321))
    }

    @Test fun anotherPackageCannotInheritTheInstallationApproval() {
        assertFalse(original.accepts("AA", packageName = "fixture.other"))
        assertFalse(original.accepts("BB", packageName = "fixture.other"))
    }

    @Test fun reinstallWithTheApprovedSignerStillPasses() {
        assertTrue(original.accepts("AA", uid = 54321, firstInstallTime = 2000L))
    }

    @Test fun legacyApprovalMustMatchTheSignerBeforeMigration() {
        val legacy = RuntimePluginInstalledTrust.decode("AA")!!
        assertTrue(legacy.accepts("AA"))
        assertFalse(legacy.accepts("BB"))
        assertEquals("AA", legacy.encode())
    }

    @Test fun refreshedApprovalRetainsTheUpdatedSignerForLaterReinstalls() {
        val updated = original.copy(signer = "BB")
        assertTrue(updated.accepts("BB", firstInstallTime = 2000L))
        assertFalse(updated.accepts("CC", firstInstallTime = 2000L))
    }

    @Test fun missingOrInvalidInstallIdentityCannotAuthorizeADifferentSigner() {
        for (approval in listOf(
            original.copy(uid = 0),
            original.copy(uid = null),
            original.copy(firstInstallTime = 0),
            original.copy(firstInstallTime = null)
        )) {
            assertFalse(approval.accepts("BB"))
            assertEquals("AA", approval.encode())
        }
    }

    @Test fun revocationLeavesNoApprovalToReuse() {
        assertNull(RuntimePluginInstalledTrust.decode(null))
        assertNull(RuntimePluginInstalledTrust.decode(""))
    }

    @Test fun storedInstallationApprovalRoundTripsWithoutChangingTheSigner() {
        assertEquals(original, RuntimePluginInstalledTrust.decode(original.encode()))
    }

    @Test fun malformedInstallationRecordsAreRejected() {
        for (value in listOf("v1|", "v1||12345|1000|AA",
            "v1|fixture.runtime|invalid|1000|AA",
            "v1|fixture.runtime|12345|invalid|AA",
            "v1|fixture.runtime|0|1000|AA",
            "v1|fixture.runtime|12345|0|AA",
            "v1|fixture.runtime|12345|1000|")) {
            assertNull(value, RuntimePluginInstalledTrust.decode(value))
        }
    }

    @Test fun missingSigningInfoNeverAuthorizesTheInstallation() {
        assertFalse(original.trusts("fixture.runtime", 12345, 1000L, RuntimePluginSigners(emptyList())))
    }
}
