package app.urv.manager.util

import org.junit.Assert.assertEquals
import org.junit.Test

class SplitExportNameTest {
    private val metadata = PatchedAppExportData(
        appName = "Example", packageName = "com.example", appVersion = "1",
        managerVersion = "test", fileExtension = "apks"
    )

    @Test fun splitExportReplacesApkTemplateExtension() {
        assertEquals("Example.apks", ExportNameFormatter.format("{app name}.apk", metadata))
        assertEquals("Example.apks", ExportNameFormatter.format("{app name}", metadata))
        assertEquals("Example.apks", ExportNameFormatter.format("{app name}.apks", metadata))
    }

    @Test fun singleApkExportRetainsExistingExtensionBehavior() {
        val single = metadata.copy(fileExtension = "apk")
        assertEquals("Example.APK", ExportNameFormatter.format("{app name}.APK", single))
        assertEquals("Example.apk", ExportNameFormatter.format("{app name}", single))
    }
}
