package app.urv.manager.reseam.runtime

import app.reseam.sdk.Problem
import org.junit.Assert.assertTrue
import org.junit.Test

class ReseamBundleCompatibilityTest {
    @Test fun newerBundleExplainsWhichRuntimeToUpdate() {
        val message = reseamBundleProblemMessage(Problem.EngineTooOld("madkarma-patches", "0.20.1", "0.19.0"))
        assertTrue(message.contains("madkarma-patches"))
        assertTrue(message.contains("requires engine 0.20.1"))
        assertTrue(message.contains("runtime uses 0.19.0"))
        assertTrue(message.contains("Update the Reseam runtime plugin"))
    }

    @Test fun olderBundleExplainsWhichBundleToUpdate() {
        val message = reseamBundleProblemMessage(Problem.BundleTooOld("reseam-patches", "0.19.0", "0.20.1"))
        assertTrue(message.contains("reseam-patches"))
        assertTrue(message.contains("built for engine 0.19.0"))
        assertTrue(message.contains("runtime uses 0.20.1"))
        assertTrue(message.contains("Update the patch bundle"))
    }
}
