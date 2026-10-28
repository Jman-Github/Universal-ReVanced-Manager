package app.universal.patch.manager.domain.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RuntimePluginRefreshTest {
    @Test fun replacementCanRecoverAfterBothEarlyBroadcastsSeeMissingPackageInfo() = runBlocking {
        val waits = mutableListOf<Long>()
        var attempts = 0
        val recovered = refreshRuntimePluginAfterPackageChange(pause = { waits += it }) {
            ++attempts >= 3
        }
        assertTrue(recovered)
        assertEquals(3, attempts)
        assertEquals(listOf(250L, 500L, 1_000L), waits)
    }

    @Test fun newlyInstalledRuntimeRecoversWithoutAnotherActivityResume() = runBlocking {
        var attempts = 0
        assertTrue(refreshRuntimePluginAfterPackageChange(pause = {}) { ++attempts == 2 })
        assertEquals(2, attempts)
    }

    @Test fun settledPackageDoesNotKeepReloadingBundles() = runBlocking {
        var attempts = 0
        assertTrue(refreshRuntimePluginAfterPackageChange(pause = {}) { ++attempts; true })
        assertEquals(1, attempts)
    }

    @Test fun missingOrUnrelatedPackageHasABoundedRetryWindow() = runBlocking {
        val waits = mutableListOf<Long>()
        var attempts = 0
        assertFalse(refreshRuntimePluginAfterPackageChange(pause = { waits += it }) {
            ++attempts
            false
        })
        assertEquals(5, attempts)
        assertEquals(7_750L, waits.sum())
    }

    @Test fun cancellationStopsPendingReplacementRecovery() {
        var attempts = 0
        assertThrows(CancellationException::class.java) {
            runBlocking {
                refreshRuntimePluginAfterPackageChange(pause = { throw CancellationException() }) {
                    ++attempts
                    true
                }
            }
        }
        assertEquals(0, attempts)
    }
}
