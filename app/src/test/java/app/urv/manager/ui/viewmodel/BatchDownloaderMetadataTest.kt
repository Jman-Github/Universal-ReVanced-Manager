package app.urv.manager.ui.viewmodel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BatchDownloaderMetadataTest {
    @Test
    fun `downloader metadata keeps version name and version code`() {
        val metadata = resolveBatchDownloaderMetadata(
            packageVersion = "19.16.39",
            downloadUrl = null,
            reportedVersion = "1234567",
            requestedVersion = null
        )

        assertEquals("19.16.39", metadata.versionName)
        assertEquals(1_234_567L, metadata.versionCode)
    }

    @Test
    fun `downloader metadata extracts version from encoded url`() {
        val metadata = resolveBatchDownloaderMetadata(
            packageVersion = null,
            downloadUrl = "https%3A%2F%2Fexample.test%2Fapp_2.3.4.apk",
            reportedVersion = null,
            requestedVersion = null
        )

        assertEquals("2.3.4", metadata.versionName)
    }

    @Test
    fun `numeric downloader version is shown as version code`() {
        val metadata = resolveBatchDownloaderMetadata(
            packageVersion = "1234567",
            downloadUrl = null,
            reportedVersion = null,
            requestedVersion = null
        )

        assertNull(metadata.versionName)
        assertEquals(1_234_567L, metadata.versionCode)
    }

    @Test
    fun `simple version names remain visible`() {
        val metadata = resolveBatchDownloaderMetadata(
            packageVersion = "10",
            downloadUrl = null,
            reportedVersion = null,
            requestedVersion = null
        )

        assertEquals("10", metadata.versionName)
    }

    @Test
    fun `rejected batch plan resets its pending request state`() {
        var rejected = false

        assertFalse(
            handleBatchPlanRequestResult(accepted = false) {
                rejected = true
            }
        )
        assertTrue(rejected)
    }

    @Test
    fun `completed downloader only clears its own active action`() {
        val current = Any()
        val replacement = Any()

        assertTrue(isCurrentBatchPluginAction(current, current))
        assertFalse(isCurrentBatchPluginAction(replacement, current))
    }
}
