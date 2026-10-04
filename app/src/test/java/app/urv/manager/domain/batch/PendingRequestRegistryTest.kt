package app.urv.manager.domain.batch

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PendingRequestRegistryTest {
    @Test
    fun `results complete only the matching request`() {
        val registry = PendingRequestRegistry<String>()
        val first = CompletableDeferred<String>()
        val second = CompletableDeferred<String>()

        assertTrue(registry.register("first", first))
        assertTrue(registry.register("second", second))
        assertTrue(registry.complete("second", "second-result"))

        assertEquals("second-result", runBlocking { second.await() })
        assertFalse(first.isCompleted)
    }

    @Test
    fun `cancel completes and removes the matching request`() {
        val registry = PendingRequestRegistry<String>()
        val completion = CompletableDeferred<String>()

        registry.register("request", completion)

        assertTrue(registry.cancel("request", "cancelled"))
        assertEquals("cancelled", runBlocking { completion.await() })
        assertFalse(registry.complete("request", "late-result"))
    }

    @Test
    fun `late result is ignored after request removal`() {
        val registry = PendingRequestRegistry<String>()
        val completion = CompletableDeferred<String>()

        registry.register("request", completion)
        registry.remove("request", completion)

        assertFalse(registry.complete("request", "late-result"))
        assertFalse(completion.isCompleted)
    }
}
