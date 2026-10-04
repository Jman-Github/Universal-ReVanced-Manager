package app.urv.manager.util

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import app.universal.revanced.manager.BuildConfig
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BuildProfileContextTest {
    @Test
    fun applicationExposesPlatformBaseButRetainsProfileStorage() {
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as app.urv.manager.ManagerApplication
        val base = requireNotNull(application.baseContext)
        // ActivityThread performs this platform-type check before creating any manifest receiver.
        assertEquals("android.app.ContextImpl", base.javaClass.name)
        val selected = BuildProfileContext.wrap(base)
        assertEquals(selected.filesDir, application.filesDir)
        assertEquals(selected.noBackupFilesDir, application.noBackupFilesDir)
        assertEquals(selected.getDatabasePath("manager"), application.getDatabasePath("manager"))
        assertSame(selected.getSharedPreferences("receiver_test", Context.MODE_PRIVATE),
            application.getSharedPreferences("receiver_test", Context.MODE_PRIVATE))
    }

    @Test
    fun packageContextWithoutApplicationUsesSelectedStorage() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val packageContext = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context? = null
        }
        val selected = packageContext.managerStorageContext
        val expected = if (BuildConfig.IS_PR_TEST_BUILD) {
            File(base.codeCacheDir, "$PR_PROFILE_DIRECTORY/$PR_SCHEMA_DIRECTORY")
        } else {
            base.codeCacheDir
        }
        assertEquals(expected, selected.codeCacheDir)
        assertSame(selected, selected.managerStorageContext)
        assertSame(selected, BuildProfileContext.wrap(selected))
    }

    @Test
    fun existingApplicationContextKeepsItsStorageProfile() {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val component = object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = application
            override fun getCodeCacheDir(): File = error("Component storage must not be used")
        }
        assertSame(application, component.managerStorageContext)
    }
}
