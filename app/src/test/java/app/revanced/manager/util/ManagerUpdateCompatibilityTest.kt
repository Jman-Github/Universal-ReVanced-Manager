package app.revanced.manager.util

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManagerUpdateCompatibilityTest {
    private fun accepts(code: Long = 10801023, schema: Int? = 20, pkg: String = "manager") =
        isCompatibleManagerUpdate("manager", 10801000, 20, pkg, code, schema)

    @Test fun `accepts a normal release with the same database`() {
        assertTrue(accepts())
        assertTrue(accepts(code = 10801000))
    }

    @Test fun `accepts a forward database migration`() {
        assertTrue(accepts(schema = 21))
    }

    @Test fun `rejects old and unknown database layouts`() {
        assertFalse(accepts(schema = 13))
        assertFalse(accepts(schema = null))
    }

    @Test fun `rejects APK downgrade even with compatible data`() {
        assertFalse(accepts(code = 10080100))
    }

    @Test fun `rejects a different app identity`() {
        assertFalse(accepts(pkg = "manager.pr"))
    }

    @Test fun `PR only offers releases published after its build`() {
        val built = java.time.Instant.parse("2026-09-07T12:00:00Z").toEpochMilli()
        assertFalse(isManagerReleaseAfter("2026-09-06T12:00:00Z", built))
        assertFalse(isManagerReleaseAfter("2026-09-07T12:00:00Z", built))
        assertTrue(isManagerReleaseAfter("2026-09-08T12:00:00Z", built))
    }

    @Test fun `PR rejects missing or malformed release dates`() {
        assertFalse(isManagerReleaseAfter(null, 1L))
        assertFalse(isManagerReleaseAfter("invalid", 1L))
    }

    @Test fun `normal builds retain their existing release selection`() {
        assertTrue(isManagerReleaseAfter(null, null))
    }
}
