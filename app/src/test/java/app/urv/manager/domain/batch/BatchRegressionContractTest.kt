package app.urv.manager.domain.batch

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BatchRegressionContractTest {
    @Test
    fun `interactive downloader activities are limited to user batches`() {
        assertTrue(allowsInteractiveBatchActivity(scheduled = false))
        assertFalse(allowsInteractiveBatchActivity(scheduled = true))
    }

    @Test
    fun `active batch only reopens its matching request`() {
        val requested = batchPlanRequestKey(listOf("one", "two"))
        val different = batchPlanRequestKey(listOf("three", "four"))

        assertTrue(
            canOpenBatchPlan(
                currentPhase = BatchPhase.RUNNING,
                currentRequestKey = requested,
                currentPackageNames = requested.packageNames,
                currentScheduled = false,
                requestedKey = requested
            )
        )
        assertFalse(
            canOpenBatchPlan(
                currentPhase = BatchPhase.RUNNING,
                currentRequestKey = requested,
                currentPackageNames = requested.packageNames,
                currentScheduled = false,
                requestedKey = different
            )
        )
        assertTrue(
            canOpenBatchPlan(
                currentPhase = BatchPhase.FINISHED,
                currentRequestKey = requested,
                currentPackageNames = requested.packageNames,
                currentScheduled = false,
                requestedKey = different
            )
        )
    }
}
