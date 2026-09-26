package app.urv.manager.ui.viewmodel

import app.urv.manager.domain.manager.AutoClearCacheInterval
import app.urv.manager.domain.manager.BundleUpdateDeliveryMode
import app.urv.manager.domain.manager.SearchForUpdatesBackgroundInterval
import app.urv.manager.domain.manager.normalizedImportedAutoPatchInterval
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImportExportPermissionTest {
    @Test
    fun `automatic patching alone requires notification permission`() {
        assertTrue(
            importedBackgroundWorkNeedsNotifications(
                bundleInterval = SearchForUpdatesBackgroundInterval.NEVER,
                managerInterval = SearchForUpdatesBackgroundInterval.NEVER,
                announcementInterval = SearchForUpdatesBackgroundInterval.NEVER,
                autoClearCacheInterval = AutoClearCacheInterval.NEVER,
                deliveryMode = BundleUpdateDeliveryMode.AUTO,
                autoPatchEnabled = true
            )
        )
    }

    @Test
    fun `pending notification or Shizuku permission defers automatic patch work`() {
        assertTrue(
            importedPermissionsDeferAutoPatchWork(
                needsNotificationPermission = true,
                needsShizukuPermission = false
            )
        )
        assertTrue(
            importedPermissionsDeferAutoPatchWork(
                needsNotificationPermission = false,
                needsShizukuPermission = true
            )
        )
        assertFalse(
            importedPermissionsDeferAutoPatchWork(
                needsNotificationPermission = false,
                needsShizukuPermission = false
            )
        )
    }

    @Test
    fun `disabled background work does not require notification permission`() {
        assertFalse(
            importedBackgroundWorkNeedsNotifications(
                bundleInterval = SearchForUpdatesBackgroundInterval.NEVER,
                managerInterval = SearchForUpdatesBackgroundInterval.NEVER,
                announcementInterval = SearchForUpdatesBackgroundInterval.NEVER,
                autoClearCacheInterval = AutoClearCacheInterval.NEVER,
                deliveryMode = BundleUpdateDeliveryMode.AUTO,
                autoPatchEnabled = false
            )
        )
    }

    @Test
    fun `automatic patching cannot import a never interval`() {
        assertEquals(
            SearchForUpdatesBackgroundInterval.DAY,
            normalizedImportedAutoPatchInterval(
                SearchForUpdatesBackgroundInterval.NEVER
            )
        )
        assertEquals(
            SearchForUpdatesBackgroundInterval.HOUR,
            normalizedImportedAutoPatchInterval(
                SearchForUpdatesBackgroundInterval.HOUR
            )
        )
    }
}
