package app.urv.manager.domain.installer.root

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RootMountFeedbackTest {
    @Test
    fun `boot recovery handoffs do not announce failed mounts`() {
        assertNull(bootRootMountFeedbackSuccess("INCOMPLETE_TRANSACTION"))
        assertNull(bootRootMountFeedbackSuccess("DEFERRED"))
        assertNull(bootRootMountFeedbackSuccess("WAITING_FOR_LOCK"))
        assertNull(bootRootMountFeedbackSuccess(null))
    }

    @Test
    fun `verified and terminal boot results still report their outcome`() {
        assertEquals(true, bootRootMountFeedbackSuccess("VERIFIED"))
        for (status in listOf("REPAIR_REQUIRED", "REPATCH_REQUIRED", "VERIFY_FAILED")) {
            assertEquals(false, bootRootMountFeedbackSuccess(status))
        }
    }

    @Test
    fun `failed operation with a verified previous mount reports a mounted outcome`() {
        val recovered = RootMountResult.Failure(
            RootMountPhase.MOUNTING, RootRecoveryState.PREVIOUS_MOUNT, "diagnostic", "Interrupted operation"
        )
        assertEquals(true, automaticRootMountFeedbackSuccess(recovered))
        assertEquals(true, automaticRootMountFeedbackSuccess(
            RootMountResult.RecoveredToPreviousMount("transaction", "diagnostic")
        ))
        assertEquals(false, automaticRootMountFeedbackSuccess(recovered.copy(recoveryState = RootRecoveryState.STOCK)))
        assertEquals(false, automaticRootMountFeedbackSuccess(recovered.copy(recoveryState = RootRecoveryState.NONE)))
    }

    @Test
    fun `healthy checks record success and unfinished work stays silent`() {
        assertEquals(true, automaticRootMountFeedbackSuccess(RootMountResult.Success("transaction")))
        assertNull(automaticRootMountFeedbackSuccess(RootMountResult.Busy(RootMountPhase.MOUNTING)))
        assertEquals(false, automaticRootMountFeedbackSuccess(
            RootMountResult.RecoveredToStock("transaction", "diagnostic")
        ))
        assertEquals(false, automaticRootMountFeedbackSuccess(RootMountResult.RequiresRepatch("Version changed")))
    }

    @Test
    fun `silent healthy checks do not discard an unreported boot success`() {
        assertFalse(isOutdatedRootMountFeedback(
            "3:true", "3:true", 90_000, 16_000, 120_000, previousReportedAt = -1
        ))
    }

    @Test
    fun `quiet checks still reject stale failures and older quiet checks`() {
        assertTrue(isOutdatedRootMountFeedback(
            "3:true", "3:false", 90_000, 16_000, 120_000, previousReportedAt = -1
        ))
        assertTrue(isOutdatedRootMountFeedback(
            "3:true", "3:true", 90_000, 16_000, 120_000, previousReportedAt = -1, notify = false
        ))
    }

    @Test
    fun `delayed boot failures cannot override a newer successful repair`() {
        assertTrue(isOutdatedRootMountFeedback("3:true", "3:false", 90_000, 16_000, 120_000))
    }

    @Test
    fun `delayed boot success cannot override a newer failure`() {
        assertTrue(isOutdatedRootMountFeedback("3:false", "3:true", 90_000, 16_000, 120_000))
    }

    @Test
    fun `current results and results from a new boot remain eligible`() {
        assertFalse(isOutdatedRootMountFeedback("3:false", "3:true", 90_000, 91_000, 120_000))
        assertFalse(isOutdatedRootMountFeedback("3:true", "4:false", 90_000, 16_000, 120_000))
        assertFalse(isOutdatedRootMountFeedback(null, "3:true", -1, 16_000, 120_000))
    }

    @Test
    fun `missing or reset completion clocks do not discard current results`() {
        assertFalse(isOutdatedRootMountFeedback("3:true", "3:false", -1, 16_000, 120_000))
        assertFalse(isOutdatedRootMountFeedback("3:true", "3:false", 200_000, 16_000, 120_000))
    }

    @Test
    fun `a healthy check cannot resurrect success from before a reported failure`() {
        assertTrue(isOutdatedRootMountFeedback(
            "3:true", "3:true", 90_000, 16_000, 120_000, previousReportedAt = 20_000
        ))
        assertFalse(isOutdatedRootMountFeedback(
            "3:true", "3:true", 90_000, 30_000, 120_000, previousReportedAt = 20_000
        ))
    }

    @Test
    fun `reported boot success remains suppressed after quiet checks`() {
        assertTrue(isOutdatedRootMountFeedback(
            "3:true", "3:true", 90_000, 16_000, 120_000, previousReportedAt = 16_000
        ))
    }

    @Test
    fun `blocked failure feedback is not treated as displayed`() {
        assertTrue(shouldShowRootMountFeedback("3:false", "3:false", false, -1, 90_000, completedAt = 100))
        assertFalse(shouldShowRootMountFeedback("3:false", "3:false", false, 20_000, 90_000, completedAt = 100))
    }

    @Test
    fun `silent healthy checks preserve undelivered success feedback`() {
        assertEquals(16_000L, pendingRootMountFeedback("3:true", "3:true", 16_000, 90_000, false))
        assertEquals(-1L, pendingRootMountFeedback("3:true", "3:true", -1, 90_000, false))
    }

    @Test
    fun `a changed outcome or boot discards stale pending feedback`() {
        assertEquals(-1L, pendingRootMountFeedback("3:false", "3:true", 16_000, 90_000, false))
        assertEquals(-1L, pendingRootMountFeedback("3:true", "4:true", 16_000, 90_000, false))
    }

    @Test
    fun `new terminal feedback replaces the undelivered result`() {
        assertEquals(90_000L, pendingRootMountFeedback("3:false", "3:true", 16_000, 90_000, true))
        assertEquals(90_000L, pendingRootMountFeedback("3:true", "3:false", 16_000, 90_000, true))
    }

    @Test
    fun `duplicate success reports are suppressed within a boot`() {
        assertFalse(shouldShowRootMountFeedback("3:true", "3:true", true, 100, 59_999, completedAt = 100))
    }

    @Test
    fun `app restarts cannot repeat the same successful boot outcome`() {
        assertFalse(shouldShowRootMountFeedback("3:true", "3:true", true, 100, 60_100, completedAt = 100))
        assertFalse(shouldShowRootMountFeedback("3:true", "3:true", true, 100, 3_600_100, completedAt = 100))
    }

    @Test
    fun `a genuinely new remount can report success without a timer delay`() {
        assertTrue(shouldShowRootMountFeedback("3:true", "3:true", true, 100, 110, completedAt = 105))
        assertFalse(shouldShowRootMountFeedback("3:true", "3:true", true, 110, 3_600_100, completedAt = 105))
    }

    @Test
    fun `unchanged failures do not repeat on every background check`() {
        assertFalse(shouldShowRootMountFeedback("3:false", "3:false", false, 100, 3_600_100, completedAt = 100))
    }

    @Test
    fun `a new boot or changed outcome is reported`() {
        assertTrue(shouldShowRootMountFeedback("3:false", "4:false", false, 100, 100, completedAt = 100))
        assertTrue(shouldShowRootMountFeedback("3:false", "3:true", true, 100, 101, completedAt = 100))
        assertTrue(shouldShowRootMountFeedback("3:true", "3:false", false, 100, 101, completedAt = 100))
    }

    @Test
    fun `missing prior timestamps and reset clocks allow success feedback`() {
        assertTrue(shouldShowRootMountFeedback("3:true", "3:true", true, -1, 100, completedAt = 100))
        assertTrue(shouldShowRootMountFeedback("3:true", "3:true", true, 200, 100, completedAt = 100))
    }
}
