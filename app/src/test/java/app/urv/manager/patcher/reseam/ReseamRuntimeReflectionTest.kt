package app.urv.manager.patcher.reseam

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class ReseamRuntimeReflectionTest {
    @Test fun metadataFailuresKeepTheirOriginalMessageAndCause() {
        val failure = IllegalArgumentException("Update the Reseam runtime plugin")
        val method = Entry::class.java.getMethod("fail", Throwable::class.java)
        assertSame(failure, assertThrows(IllegalArgumentException::class.java) {
            method.invokeReseamRuntime(failure)
        })
    }

    @Test fun cancellationKeepsItsType() {
        val failure = CancellationException("cancelled")
        val method = Entry::class.java.getMethod("fail", Throwable::class.java)
        assertSame(failure, assertThrows(CancellationException::class.java) {
            method.invokeReseamRuntime(failure)
        })
    }

    @Test fun successfulCallsKeepTheirArgumentsAndResult() {
        assertEquals("0.20.1", Entry::class.java.getMethod("version").invokeReseamRuntime())
    }

    object Entry {
        @JvmStatic fun fail(error: Throwable): Nothing = throw error
        @JvmStatic fun version(): String = "0.20.1"
    }
}
