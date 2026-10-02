package app.urv.manager.domain.installer.root

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RootPackageStatePollingTest {
    private val expected = RootPackageState(
        packageName = "com.example.app", userId = 0, installed = true,
        basePath = "/data/app/example/base.apk", baseSha256 = "stock"
    )

    @Test
    fun `requires consecutive identical verified package reads`() = runBlocking {
        val states = ArrayDeque(listOf(expected, expected.copy(baseSha256 = "other"), expected, expected))
        assertEquals(expected, awaitStableRootPackageState(expected, 2, timeoutMs = 2_000, pollIntervalMs = 1) { states.removeFirst() })
        assertTrue(states.isEmpty())
    }

    @Test
    fun `launcher visibility changes do not reset verified stock stability`() = runBlocking {
        val states = ArrayDeque(listOf(expected.copy(launcherResolvable = true), expected))
        assertEquals(expected, awaitStableRootPackageState(expected, 2, timeoutMs = 2_000, pollIntervalMs = 1) {
            states.removeFirst()
        })
        assertTrue(states.isEmpty())
    }

    @Test
    fun `a stalled query is bounded by the overall deadline`() = runBlocking {
        assertFailsWith<IllegalStateException> {
            awaitStableRootPackageState(expected, 2, timeoutMs = 100, pollIntervalMs = 1) { awaitCancellation() }
        }
        Unit
    }

    @Test
    fun `caller cancellation is not converted into a timeout`() = runBlocking {
        assertFailsWith<CancellationException> {
            awaitStableRootPackageState(expected, 2) { throw CancellationException("cancelled") }
        }
        Unit
    }

    @Test
    fun `a changed split cannot satisfy stable verification`() = runBlocking {
        val path = "/data/app/example/split_config.en.apk"
        val split = expected.copy(splitPaths = listOf(path), splitSha256 = mapOf(path to "a".repeat(64)))
        assertFailsWith<IllegalStateException> {
            awaitStableRootPackageState(split, 2, timeoutMs = 100, pollIntervalMs = 1) {
                split.copy(splitSha256 = mapOf(path to "b".repeat(64)))
            }
        }
        Unit
    }
}
