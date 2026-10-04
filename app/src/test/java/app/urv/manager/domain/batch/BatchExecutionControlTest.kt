package app.urv.manager.domain.batch

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BatchExecutionControlTest {
    @Test
    fun `only one coordinator owns batch execution at a time`() {
        val gate = BatchExecutionGate()
        val first = Any()
        val second = Any()

        assertTrue(gate.tryAcquire(first))
        assertTrue(gate.tryAcquire(first))
        assertFalse(gate.tryAcquire(second))
        assertFalse(gate.release(second))
        assertTrue(gate.release(first))
        assertTrue(gate.tryAcquire(second))
    }

    @Test
    fun `only a live scheduled worker keeps batch execution after finishing`() {
        assertTrue(
            shouldRetainBatchExecutionAfterFinish(
                scheduled = true,
                liveScheduledExecution = true
            )
        )
        assertFalse(
            shouldRetainBatchExecutionAfterFinish(
                scheduled = true,
                liveScheduledExecution = false
            )
        )
        assertFalse(
            shouldRetainBatchExecutionAfterFinish(
                scheduled = false,
                liveScheduledExecution = true
            )
        )
    }

    @Test
    fun `request timeout includes delivery to an unavailable host`() = runBlocking {
        val requests = Channel<Unit>()
        val completion = CompletableDeferred<String>()

        val result = awaitBatchRequest(
            timeoutMs = 100L,
            completion = completion,
            timeoutResult = "cancelled"
        ) {
            requests.send(Unit)
        }

        assertEquals("cancelled", result)
        assertEquals("cancelled", completion.await())
        requests.close()
        Unit
    }
}
