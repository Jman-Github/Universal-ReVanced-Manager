package app.urv.manager.util

import org.junit.Assert.assertEquals
import org.junit.Test

class PackageLabelTest {
    @Test
    fun numericAndBrandedDottedNamesSurvive() {
        assertEquals("1.1.1.1", cleanPackageLabel(" 1.1.1.1 ", "com.cloudflare.app"))
        assertEquals("N.O.V.A.", cleanPackageLabel("N.O.V.A.", "com.gameloft.nova"))
        assertEquals("example.com", cleanPackageLabel("example.com", "com.example.app"))
        assertEquals("My Application", cleanPackageLabel("My Application", "com.example.app"))
    }

    @Test
    fun packageAndLauncherIdentifiersAreStillShortened() {
        assertEquals("Example", cleanPackageLabel("com.example.ExampleApplication", "com.example"))
        assertEquals("Example", cleanPackageLabel("org.sample.ExampleApplication", "com.example"))
        assertEquals("Application", cleanPackageLabel("com.example.Application", "com.example"))
        assertEquals("", cleanPackageLabel("  ", "com.example"))
    }
}
