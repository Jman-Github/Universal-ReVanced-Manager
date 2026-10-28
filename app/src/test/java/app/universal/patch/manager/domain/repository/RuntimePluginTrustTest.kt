package app.universal.patch.manager.domain.repository

import org.junit.Assert.*
import org.junit.Test

class RuntimePluginTrustTest {
    @Test fun sameSignerRemainsTrustedAfterAnUpdate() {
        assertTrue(RuntimePluginSigners(listOf("AA")).trusts("AA"))
    }

    @Test fun persistedHexCaseDoesNotInvalidateTrust() {
        assertTrue(RuntimePluginSigners(listOf("AABB")).trusts("aabb"))
    }

    @Test fun verifiedCertificateRotationPreservesTheOriginalTrustAnchor() {
        val updated = RuntimePluginSigners(listOf("BB"), listOf("AA", "BB"))
        assertTrue(updated.trusts("AA"))
        assertTrue(updated.trusts("BB"))
        assertEquals("BB", updated.identity)
    }

    @Test fun unrelatedSignerStillRequiresExplicitTrust() {
        assertFalse(RuntimePluginSigners(listOf("BB")).trusts("AA"))
        assertFalse(RuntimePluginSigners(listOf("BB"), listOf("CC", "BB")).trusts("AA"))
    }

    @Test fun noStoredTrustNeverAuthorizesAnInstalledOrRemoteRuntime() {
        val signers = RuntimePluginSigners(listOf("AA"))
        assertFalse(signers.trusts(null))
        assertFalse(signers.trusts(""))
    }

    @Test fun allSignersMustMatchForAMultiSignerRuntime() {
        val signers = RuntimePluginSigners(listOf("BB", "AA"))
        assertEquals("AA,BB", signers.identity)
        assertTrue(signers.trusts("BB,AA"))
        assertFalse(signers.trusts("AA"))
        assertFalse(signers.trusts("AA,CC"))
    }

    @Test fun unverifiedHistoryCannotSubstituteForAMultiSignerIdentity() {
        val signers = RuntimePluginSigners(listOf("BB", "CC"), listOf("AA"))
        assertFalse(signers.trusts("AA"))
        assertFalse(signers.trusts("AA,BB"))
        assertFalse(RuntimePluginSigners(listOf("AA"), listOf("AA", "BB")).trusts("AA,BB"))
    }

    @Test fun unreadableSigningInformationNeverPassesTrust() {
        assertFalse(RuntimePluginSigners(emptyList()).trusts("AA"))
    }
}
