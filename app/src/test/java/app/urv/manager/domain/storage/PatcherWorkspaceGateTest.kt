package app.urv.manager.domain.storage

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PatcherWorkspaceGateTest {
    @Test
    fun replacementWaitsForCancelledOwnersCleanup() = runBlocking {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val cleaning = CompletableDeferred<Unit>()
            val allowCleanup = CompletableDeferred<Unit>()
            val order = mutableListOf<String>()
            val first = launch {
                PatcherWorkspaceGate.withWorkspace {
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            cleaning.complete(Unit)
                            allowCleanup.await()
                            order += "cleanup"
                        }
                    }
                }
            }
            entered.await()
            first.cancel()
            cleaning.await()
            val replacement = async(start = CoroutineStart.UNDISPATCHED) {
                PatcherWorkspaceGate.withWorkspace { order += "replacement" }
            }
            try {
                assertFalse(replacement.isCompleted)
                assertTrue(CacheCleanupGuard.isCacheInUse)
            } finally {
                allowCleanup.complete(Unit)
            }
            first.join()
            replacement.await()
            assertEquals(listOf("cleanup", "replacement"), order)
            assertFalse(CacheCleanupGuard.isCacheInUse)
        }
    }

    @Test
    fun cancelledWaitingPatchNeverUsesTheWorkspace() = runBlocking {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val first = launch {
                PatcherWorkspaceGate.withWorkspace {
                    entered.complete(Unit)
                    release.await()
                }
            }
            entered.await()
            var ran = false
            val waiting = launch(start = CoroutineStart.UNDISPATCHED) {
                PatcherWorkspaceGate.withWorkspace { ran = true }
            }
            try {
                waiting.cancelAndJoin()
                assertFalse(ran)
            } finally {
                release.complete(Unit)
            }
            first.join()
            assertFalse(CacheCleanupGuard.isCacheInUse)
        }
    }

    @Test
    fun failedOwnerReleasesWorkspaceAndCacheGuard() = runBlocking {
        runCatching { PatcherWorkspaceGate.withWorkspace { error("failed patch") } }
        assertFalse(CacheCleanupGuard.isCacheInUse)
        assertEquals(123, PatcherWorkspaceGate.withWorkspace { 123 })
        assertFalse(CacheCleanupGuard.isCacheInUse)
    }
}
